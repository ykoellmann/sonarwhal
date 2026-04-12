package com.sonarwhale.gutter

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.HttpMethod
import com.sonarwhale.service.RouteIndexService
import javax.swing.Icon

/**
 * Adds play-button gutter icons to open C# editors at lines where an HTTP endpoint
 * attribute or minimal-API mapping is detected.
 *
 * Also populates [SourceLocationService] so the tool window can navigate back to source.
 *
 * Matching strategy (no PSI — pure text/regex):
 *  1. Pre-scan the file for controller-level context (Route, Area, class name).
 *  2. Scan each line for HTTP-verb attributes (HttpGet, AcceptVerbs, …) or
 *     minimal-API calls (app.MapGet(…)).
 *  3. For each attribute block, collect all route templates, resolve route tokens
 *     (controller/action/area), normalise parameters, handle tilde override.
 *  4. Match the resolved template against loaded OpenAPI endpoints.
 *  5. Place a single gutter icon on the method declaration line (deduped).
 *
 * Known limitation: app.MapGroup() prefix chaining requires multi-line variable
 * tracking that is incompatible with a line scanner — not supported.
 */
@Service(Service.Level.PROJECT)
class SonarwhaleGutterService(private val project: Project) : Disposable {

    private val SONARWHALE_MARKER = com.intellij.openapi.util.Key.create<Boolean>("sonarwhale.gutter")

    @Volatile private var currentEndpoints: List<ApiEndpoint> = emptyList()

    // ── Precompiled patterns ──────────────────────────────────────────────────

    private data class MethodPattern(
        val method: HttpMethod,
        val attrPrefix: String,   // lowercase, e.g. "httpget"
        val mapPrefix: String     // lowercase, e.g. ".mapget("
    )

    private val methodPatterns = HttpMethod.entries.map { m ->
        val name = m.name.lowercase()
        MethodPattern(method = m, attrPrefix = "http${name}", mapPrefix = ".map${name}(")
    }

    // Extracts string literals from a line
    private val reStringLiteral = Regex(""""([^"]*?)"""")

    // Normalises a route parameter token:
    //   {**rest}  -> {rest}   greedy catch-all
    //   {*rest}   -> {rest}   catch-all
    //   {id:guid} -> {id}     constraint
    //   {id?}     -> {id}     optional
    //   {page=1}  -> {page}   default value
    private val reRouteParam = Regex("""\{\*{0,2}(\w+)(?:[?=:][^}]*)?\}""")

    private val reClass       = Regex("""class\s+(\w+?)(Controller)?\s*[:<{]""", RegexOption.IGNORE_CASE)
    private val reRoute       = Regex("""Route\s*\(\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val reArea        = Regex("""Area\s*\(\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    // Matches last identifier before '(' on a method declaration line
    private val reMethodName  = Regex("""(?:public|private|protected|internal|static|async|override|virtual)\s[^(]*\b(\w+)\s*\(""",
                                       RegexOption.IGNORE_CASE)
    private val reAcceptVerbs = Regex("""AcceptVerbs\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
    private val reVerbLiteral = Regex(""""(\w+)"""")

    private val maxLookahead = 8

    // ── Controller context ────────────────────────────────────────────────────

    private data class ControllerContext(
        val prefix: String?,
        val controllerName: String?,
        val areaName: String?
    )

    // ── Attribute block (one method's stacked attributes) ─────────────────────

    private data class AttributeBlock(
        val methods: List<HttpMethod>,
        val templates: List<String>,     // resolved + normalised, may be empty
        val declarationLine: Int,
        val hasNonAction: Boolean,
        val tildeOverride: Boolean
    )

    // ── Scan result ───────────────────────────────────────────────────────────

    data class ScanMatch(val endpoint: ApiEndpoint, val line: Int)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    init {
        RouteIndexService.getInstance(project).addListener { endpoints ->
            currentEndpoints = endpoints
            SourceLocationService.getInstance(project).clear()
            refreshAllOpenEditors()
            scanProjectFilesInBackground()
        }

        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) markEditor(event.editor)
            }
        }, this)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun refreshAllOpenEditors() {
        ApplicationManager.getApplication().invokeLater {
            EditorFactory.getInstance().allEditors
                .filter { it.project == project }
                .forEach { markEditor(it) }
        }
    }

    // ── Core: mark one open editor ────────────────────────────────────────────

    private fun markEditor(editor: Editor) {
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        if (vFile.extension?.lowercase() != "cs") return

        val markup = editor.markupModel
        markup.allHighlighters
            .filter { it.getUserData(SONARWHALE_MARKER) == true }
            .forEach { markup.removeHighlighter(it) }

        if (currentEndpoints.isEmpty()) return

        val lines = (0 until document.lineCount).map { i ->
            val start = document.getLineStartOffset(i)
            val end   = document.getLineEndOffset(i)
            document.getText(com.intellij.openapi.util.TextRange(start, end))
        }

        val locService = SourceLocationService.getInstance(project)
        for ((endpoint, iconLine) in scanLines(lines)) {
            // Place gutter icon
            val lineStart = document.getLineStartOffset(iconLine)
            val lineEnd   = document.getLineEndOffset(iconLine)
            val h = markup.addRangeHighlighter(
                lineStart, lineEnd,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                null,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            h.putUserData(SONARWHALE_MARKER, true)
            h.gutterIconRenderer = SonarwhaleGutterRenderer(endpoint, project)

            // Register source location
            locService.register(endpoint.id, vFile, iconLine)
        }
    }

    // ── Project-wide VFS scan (populates SourceLocationService for all .cs files) ──

    private fun scanProjectFilesInBackground() {
        val endpoints = currentEndpoints
        if (endpoints.isEmpty()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            ApplicationManager.getApplication().runReadAction {
                val locService = SourceLocationService.getInstance(project)
                ProjectFileIndex.getInstance(project).iterateContent { vFile ->
                    if (vFile.extension?.lowercase() == "cs") {
                        scanVfsFile(vFile, locService)
                    }
                    true // continue iteration
                }
            }
        }
    }

    private fun scanVfsFile(vFile: VirtualFile, locService: SourceLocationService) {
        if (currentEndpoints.isEmpty()) return
        try {
            val text  = String(vFile.contentsToByteArray(), vFile.charset)
            val lines = text.lines()
            for ((endpoint, line) in scanLines(lines)) {
                locService.register(endpoint.id, vFile, line)
            }
        } catch (_: Exception) { /* skip unreadable files */ }
    }

    // ── Core scan: works on List<String> lines ────────────────────────────────

    /**
     * Scans a list of lines (from either a Document or a VirtualFile) and returns
     * all (endpoint, declarationLine) matches. Deduplicates by declaration line.
     */
    fun scanLines(lines: List<String>): List<ScanMatch> {
        if (currentEndpoints.isEmpty()) return emptyList()

        val ctx         = extractControllerContext(lines)
        val result      = mutableListOf<ScanMatch>()
        val markedLines = mutableSetOf<Int>()

        var i = 0
        while (i < lines.size) {
            val lower = lines[i].lowercase()

            val isAttr    = methodPatterns.any { lower.contains(it.attrPrefix) } ||
                            lower.contains("acceptverbs")
            val isMapCall = methodPatterns.any { lower.contains(it.mapPrefix) }

            if (!isAttr && !isMapCall) { i++; continue }

            if (isAttr) {
                val block = collectAttributeBlock(lines, i, ctx)

                if (!block.hasNonAction && block.declarationLine !in markedLines) {
                    val effectivePrefix = if (block.tildeOverride) null else ctx.prefix
                    val endpoint = findEndpointForBlock(block, effectivePrefix)
                    if (endpoint != null) {
                        result += ScanMatch(endpoint, block.declarationLine)
                        markedLines += block.declarationLine
                    }
                }
                i = block.declarationLine + 1
            } else {
                // Minimal-API call — line is the icon line
                val endpoint = findEndpointForMinimalApi(lines[i])
                if (endpoint != null && i !in markedLines) {
                    result += ScanMatch(endpoint, i)
                    markedLines += i
                }
                i++
            }
        }
        return result
    }

    // ── Attribute block extraction ────────────────────────────────────────────

    private fun collectAttributeBlock(
        lines: List<String>,
        verbLine: Int,
        ctx: ControllerContext
    ): AttributeBlock {
        val methods      = mutableListOf<HttpMethod>()
        val rawLiterals  = mutableListOf<String>()
        var hasNonAction = false
        var declLine     = verbLine
        var seenDecl     = false

        var i = verbLine
        while (i < minOf(verbLine + maxLookahead, lines.size) && !seenDecl) {
            val text  = lines[i]
            val lower = text.trim().lowercase()
            when {
                lower.isEmpty() -> { /* blank — keep scanning */ }
                lower.startsWith("[") || lower.startsWith("//") -> {
                    if (lower.contains("nonaction")) hasNonAction = true
                    for (pat in methodPatterns) {
                        if (lower.contains(pat.attrPrefix)) methods += pat.method
                    }
                    reAcceptVerbs.find(text)?.let { m ->
                        reVerbLiteral.findAll(m.groupValues[1]).forEach { v ->
                            HttpMethod.entries.firstOrNull { it.name.equals(v.groupValues[1], ignoreCase = true) }
                                ?.let { methods += it }
                        }
                    }
                    reStringLiteral.findAll(text).forEach { rawLiterals += it.groupValues[1] }
                }
                else -> {
                    declLine = i
                    seenDecl = true
                }
            }
            i++
        }

        if (!seenDecl) {
            declLine = findMethodDeclarationLine(lines, verbLine + 1)
        }

        val actionName = reMethodName.find(lines.getOrElse(declLine) { "" })
            ?.groupValues?.get(1)?.lowercase()

        val tildeOverride = rawLiterals.any { it.startsWith("~/") || it.startsWith("~") }
        val templates = rawLiterals.map { raw ->
            val stripped = when {
                raw.startsWith("~/") -> raw.removePrefix("~/")
                raw.startsWith("~")  -> raw.removePrefix("~")
                else                 -> raw
            }
            normalizeTemplate(resolveTokens(stripped, ctx, actionName).trimStart('/'))
        }.filter { it.isNotEmpty() }

        return AttributeBlock(
            methods         = methods.distinct(),
            templates       = templates,
            declarationLine = declLine,
            hasNonAction    = hasNonAction,
            tildeOverride   = tildeOverride
        )
    }

    // ── Token / template helpers ──────────────────────────────────────────────

    private fun resolveTokens(template: String, ctx: ControllerContext, actionName: String?): String =
        template
            .replace("[controller]", ctx.controllerName ?: "", ignoreCase = true)
            .replace("[action]",     actionName ?: "",          ignoreCase = true)
            .replace("[area]",       ctx.areaName ?: "",        ignoreCase = true)

    private fun normalizeTemplate(template: String): String =
        reRouteParam.replace(template) { "{${it.groupValues[1]}}" }

    private fun extractControllerContext(lines: List<String>): ControllerContext {
        var controllerName: String? = null
        var routeTemplate:  String? = null
        var areaName:       String? = null

        for (line in lines) {
            if (controllerName == null) reClass.find(line)?.let { controllerName = it.groupValues[1].lowercase() }
            if (routeTemplate  == null) reRoute.find(line)?.let { routeTemplate  = it.groupValues[1] }
            if (areaName       == null) reArea.find(line)?.let  { areaName       = it.groupValues[1].lowercase() }
            if (controllerName != null && routeTemplate != null && areaName != null) break
        }

        val resolved = routeTemplate?.let { t ->
            resolveTokens(t, ControllerContext(null, controllerName, areaName), null)
                .trimStart('/').lowercase()
        }
        return ControllerContext(prefix = resolved, controllerName = controllerName, areaName = areaName)
    }

    private fun findMethodDeclarationLine(lines: List<String>, startLine: Int): Int {
        for (i in startLine until minOf(startLine + maxLookahead, lines.size)) {
            val text  = lines[i].trim()
            val lower = text.lowercase()
            if (text.isEmpty()) continue
            if (text.startsWith("[")) continue
            if (lower.contains("public ")    || lower.contains("private ") ||
                lower.contains("protected ") || lower.contains("internal ") ||
                lower.contains("static ")    || lower.contains("async ") ||
                lower.contains("override ")  || lower.contains("virtual ")) return i
            break
        }
        return startLine
    }

    // ── Endpoint matching ─────────────────────────────────────────────────────

    private fun findEndpointForBlock(block: AttributeBlock, controllerPrefix: String?): ApiEndpoint? {
        val methodsToTry = block.methods.ifEmpty { currentEndpoints.map { it.method }.distinct() }

        for (method in methodsToTry) {
            val candidates = currentEndpoints.filter { it.method == method }
            if (candidates.isEmpty()) continue

            if (block.templates.isNotEmpty()) {
                for (template in block.templates) {
                    matchByTemplate(template, candidates, controllerPrefix)?.let { return it }
                }
            } else {
                matchByPrefix(candidates, controllerPrefix)?.let { return it }
            }
        }
        return null
    }

    private fun matchByTemplate(
        template: String,
        candidates: List<ApiEndpoint>,
        controllerPrefix: String?
    ): ApiEndpoint? {
        if (controllerPrefix != null) {
            val fullPath = normalizeTemplate("$controllerPrefix/$template").lowercase()
            candidates.firstOrNull { ep ->
                normalizeTemplate(ep.path.trimStart('/')).lowercase() == fullPath
            }?.let { return it }
            candidates.firstOrNull { ep ->
                normalizeTemplate(ep.path.trimStart('/')).lowercase().endsWith(fullPath)
            }?.let { return it }
        }
        candidates.firstOrNull { ep ->
            normalizeTemplate(ep.path.trimStart('/')).lowercase().endsWith(template)
        }?.let { return it }
        candidates.firstOrNull { ep ->
            normalizeTemplate(ep.path.trimStart('/')).lowercase().contains(template)
        }?.let { return it }
        candidates.firstOrNull { ep ->
            template.contains(normalizeTemplate(ep.path.trimStart('/')).lowercase())
        }?.let { return it }
        return null
    }

    private fun matchByPrefix(candidates: List<ApiEndpoint>, controllerPrefix: String?): ApiEndpoint? {
        val pool = if (controllerPrefix != null) {
            val prefixed = candidates.filter { ep ->
                normalizeTemplate(ep.path.trimStart('/')).lowercase().startsWith(controllerPrefix)
            }
            prefixed.ifEmpty { candidates }
        } else candidates
        val noParams = pool.filter { !it.path.contains('{') }
        return noParams.firstOrNull() ?: pool.firstOrNull()
    }

    private fun findEndpointForMinimalApi(lineText: String): ApiEndpoint? {
        val lower = lineText.lowercase()
        for (pat in methodPatterns) {
            if (!lower.contains(pat.mapPrefix)) continue
            val rawLiteral = reStringLiteral.find(lineText)?.groupValues?.get(1) ?: continue
            val template   = normalizeTemplate(rawLiteral.trimStart('/')).lowercase()
            if (template.isEmpty()) continue
            matchByTemplate(template, currentEndpoints.filter { it.method == pat.method }, null)
                ?.let { return it }
        }
        return null
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): SonarwhaleGutterService = project.service()
    }

    // ── Gutter icon renderer ──────────────────────────────────────────────────

    private inner class SonarwhaleGutterRenderer(
        private val endpoint: ApiEndpoint,
        private val project: Project
    ) : GutterIconRenderer() {

        override fun getIcon(): Icon = AllIcons.RunConfigurations.TestState.Run

        override fun getTooltipText(): String {
            val defaultReq = SonarwhaleStateService.getInstance(project).getDefaultRequest(endpoint.id)
            return if (defaultReq != null)
                "Run ${endpoint.method.name} ${endpoint.path} — ${defaultReq.name}"
            else
                "Open ${endpoint.method.name} ${endpoint.path} in Sonarwhale"
        }

        override fun isNavigateAction() = true

        override fun getClickAction() = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                ToolWindowManager.getInstance(project).getToolWindow("Sonarwhale")?.show(null)

                val stateService = SonarwhaleStateService.getInstance(project)
                val defaultReq   = stateService.getDefaultRequest(endpoint.id)

                val indexService = RouteIndexService.getInstance(project)
                if (defaultReq != null) {
                    indexService.runRequest(endpoint.id, defaultReq.id)
                } else {
                    indexService.selectEndpoint(endpoint.id)
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SonarwhaleGutterRenderer) return false
            return endpoint.id == other.endpoint.id
        }

        override fun hashCode() = endpoint.id.hashCode()

        override fun getAlignment() = Alignment.LEFT
    }
}
