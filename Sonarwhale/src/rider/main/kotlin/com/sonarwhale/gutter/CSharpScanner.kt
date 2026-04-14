package com.sonarwhale.gutter

import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.HttpMethod

/**
 * [LanguageScanner] for ASP.NET Core C# files.
 *
 * Matching strategy (pure text/regex, no PSI):
 *  1. Pre-scan the file for controller-level context (Route, Area, class name).
 *  2. Scan each line for HTTP-verb attributes ([HttpGet], [AcceptVerbs], …) or
 *     Minimal-API calls (app.MapGet(…)).
 *  3. For each attribute block, collect route templates, resolve [controller]/[action]/[area]
 *     tokens, normalise route parameters, handle tilde overrides.
 *  4. Match the resolved template against the OpenAPI endpoint list.
 *  5. Return one [ScanMatch] per method declaration line (deduped).
 *
 * Known limitation: app.MapGroup() prefix chaining requires multi-line variable tracking
 * that is incompatible with a line scanner — not supported.
 */
class CSharpScanner : LanguageScanner {

    override val fileExtensions: Set<String> = setOf("cs")

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

    private val reClass      = Regex("""class\s+(\w+?)(Controller)?\s*[:<{]""", RegexOption.IGNORE_CASE)
    private val reRoute      = Regex("""Route\s*\(\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val reArea       = Regex("""Area\s*\(\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    // Matches the last identifier before '(' on a method declaration line
    private val reMethodName = Regex(
        """(?:public|private|protected|internal|static|async|override|virtual)\s[^(]*\b(\w+)\s*\(""",
        RegexOption.IGNORE_CASE
    )
    private val reAcceptVerbs = Regex("""AcceptVerbs\s*\(([^)]+)\)""", RegexOption.IGNORE_CASE)
    private val reVerbLiteral = Regex(""""(\w+)"""")

    private val maxLookahead = 8

    // ── Internal data types ───────────────────────────────────────────────────

    private data class ControllerContext(
        val prefix: String?,
        val controllerName: String?,
        val areaName: String?
    )

    private data class AttributeBlock(
        val methods: List<HttpMethod>,
        val templates: List<String>,   // resolved + normalised, may be empty
        val declarationLine: Int,
        val hasNonAction: Boolean,
        val tildeOverride: Boolean
    )

    // ── LanguageScanner ───────────────────────────────────────────────────────

    override fun scanLines(lines: List<String>, endpoints: List<ApiEndpoint>): List<ScanMatch> {
        if (endpoints.isEmpty()) return emptyList()

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
                    val endpoint = findEndpointForBlock(block, effectivePrefix, endpoints)
                    if (endpoint != null) {
                        result += ScanMatch(endpoint, block.declarationLine)
                        markedLines += block.declarationLine
                    }
                }
                i = block.declarationLine + 1
            } else {
                val endpoint = findEndpointForMinimalApi(lines[i], endpoints)
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
                            HttpMethod.entries
                                .firstOrNull { it.name.equals(v.groupValues[1], ignoreCase = true) }
                                ?.let { methods += it }
                        }
                    }
                    reStringLiteral.findAll(text).forEach { m ->
                        // Skip named parameter values (e.g. Name = "routeName") — only positional
                        // string args are route templates.
                        val before = text.substring(0, m.range.first).trimEnd()
                        if (!before.endsWith("=")) rawLiterals += m.groupValues[1]
                    }
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

    private fun findEndpointForBlock(
        block: AttributeBlock,
        controllerPrefix: String?,
        endpoints: List<ApiEndpoint>
    ): ApiEndpoint? {
        val methodsToTry = block.methods.ifEmpty { endpoints.map { it.method }.distinct() }

        for (method in methodsToTry) {
            val candidates = endpoints.filter { it.method == method }
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

    private fun findEndpointForMinimalApi(lineText: String, endpoints: List<ApiEndpoint>): ApiEndpoint? {
        val lower = lineText.lowercase()
        for (pat in methodPatterns) {
            if (!lower.contains(pat.mapPrefix)) continue
            val rawLiteral = reStringLiteral.find(lineText)?.groupValues?.get(1) ?: continue
            val template   = normalizeTemplate(rawLiteral.trimStart('/')).lowercase()
            if (template.isEmpty()) continue
            matchByTemplate(template, endpoints.filter { it.method == pat.method }, null)
                ?.let { return it }
        }
        return null
    }
}
