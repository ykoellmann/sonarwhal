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
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.service.RouteIndexService
import javax.swing.Icon

/**
 * Places gutter play-icons in open source editors for any language handled by a registered
 * [LanguageScanner], and populates [SourceLocationService] for "jump to source" navigation.
 *
 * To add support for a new language, implement [LanguageScanner] and add it to [scanners].
 */
@Service(Service.Level.PROJECT)
class SonarwhaleGutterService(private val project: Project) : Disposable {

    private val SONARWHALE_MARKER = Key.create<Boolean>("sonarwhale.gutter")

    @Volatile private var currentEndpoints: List<ApiEndpoint> = emptyList()

    // ── Language scanners ─────────────────────────────────────────────────────

    private val scanners: List<LanguageScanner> = listOf(
        CSharpScanner()
        // Future: PythonScanner(), JavaSpringScanner()
    )

    private val supportedExtensions: Set<String> =
        scanners.flatMap { it.fileExtensions }.toSet()

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

    /** Returns true when [extension] (lowercase, no dot) is handled by any registered scanner. */
    fun isSupported(extension: String): Boolean = extension in supportedExtensions

    /**
     * Dispatches [lines] to the scanner registered for [extension] and returns all matches.
     * Returns an empty list if no scanner handles [extension].
     */
    fun scanLines(lines: List<String>, extension: String): List<ScanMatch> {
        val scanner = scanners.firstOrNull { extension in it.fileExtensions } ?: return emptyList()
        return scanner.scanLines(lines, currentEndpoints)
    }

    // ── Editor marking ────────────────────────────────────────────────────────

    private fun markEditor(editor: Editor) {
        val document = editor.document
        val vFile    = FileDocumentManager.getInstance().getFile(document) ?: return
        val ext      = vFile.extension?.lowercase() ?: return
        if (!isSupported(ext)) return

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
        for ((endpoint, iconLine) in scanLines(lines, ext)) {
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

            locService.register(endpoint.id, vFile, iconLine)
        }
    }

    // ── Project-wide background scan ──────────────────────────────────────────

    private fun scanProjectFilesInBackground() {
        if (currentEndpoints.isEmpty()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            ApplicationManager.getApplication().runReadAction {
                val locService = SourceLocationService.getInstance(project)
                ProjectFileIndex.getInstance(project).iterateContent { vFile ->
                    val ext = vFile.extension?.lowercase()
                    if (ext != null && isSupported(ext)) scanVfsFile(vFile, ext, locService)
                    true
                }
            }
        }
    }

    private fun scanVfsFile(vFile: VirtualFile, extension: String, locService: SourceLocationService) {
        if (currentEndpoints.isEmpty()) return
        try {
            val lines = String(vFile.contentsToByteArray(), vFile.charset).lines()
            for ((endpoint, line) in scanLines(lines, extension)) {
                locService.register(endpoint.id, vFile, line)
            }
        } catch (_: Exception) { /* skip unreadable files */ }
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
