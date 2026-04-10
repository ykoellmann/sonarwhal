package com.routex.gutter

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.DocumentUtil
import com.routex.RouteXService
import com.routex.RouteXStateService
import com.routex.model.ApiEndpoint
import javax.swing.Icon

/**
 * Adds play-button gutter icons to open C# editors at lines where an endpoint is detected.
 * Entirely document/markup-level — no C# PSI dependency.
 */
@Service(Service.Level.PROJECT)
class RouteXGutterService(private val project: Project) : Disposable {

    // Key stored on each RangeHighlighter so we can identify ours during cleanup
    private val ROUTEX_MARKER = com.intellij.openapi.util.Key.create<Boolean>("routex.gutter")

    private var currentEndpoints: List<ApiEndpoint> = emptyList()

    init {
        // Listen to endpoint changes
        RouteXService.getInstance(project).addListener { endpoints ->
            currentEndpoints = endpoints
            refreshAllOpenEditors()
        }

        // Listen to new editors being opened
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                markEditor(event.editor)
            }
        }, this)
    }

    private fun refreshAllOpenEditors() {
        EditorFactory.getInstance().allEditors.forEach { editor ->
            if (editor.project == project) markEditor(editor)
        }
    }

    private fun markEditor(editor: Editor) {
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        if (vFile.extension?.lowercase() != "cs") return

        val filePath = vFile.path
        val markupModel: MarkupModel = editor.markupModel

        // Remove old RouteX markers
        markupModel.allHighlighters.filter { it.getUserData(ROUTEX_MARKER) == true }
            .forEach { markupModel.removeHighlighter(it) }

        // Add fresh markers for each endpoint in this file
        val fileEndpoints = currentEndpoints.filter { it.filePath == filePath }
        if (fileEndpoints.isEmpty()) return

        val lineCount = document.lineCount
        fileEndpoints.forEach { endpoint ->
            val line = endpoint.lineNumber - 1  // 0-based
            if (line < 0 || line >= lineCount) return@forEach

            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)

            val highlighter = markupModel.addRangeHighlighter(
                lineStart, lineEnd,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                null,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            highlighter.putUserData(ROUTEX_MARKER, true)
            highlighter.gutterIconRenderer = RouteXGutterRenderer(endpoint, project)
        }
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): RouteXGutterService = project.service()
    }

    private inner class RouteXGutterRenderer(
        private val endpoint: ApiEndpoint,
        private val project: Project
    ) : GutterIconRenderer() {

        override fun getIcon(): Icon = AllIcons.RunConfigurations.TestState.Run

        override fun getTooltipText(): String {
            val defaultReq = RouteXStateService.getInstance(project).getDefaultRequest(endpoint.id)
            return if (defaultReq != null)
                "Run ${endpoint.httpMethod.name} ${endpoint.route} — ${defaultReq.name}"
            else
                "Open ${endpoint.httpMethod.name} ${endpoint.route} in RouteX — no request yet, click to create one"
        }

        override fun isNavigateAction() = true

        override fun getClickAction() = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                ToolWindowManager.getInstance(project).getToolWindow("RouteX")?.show(null)

                val stateService = RouteXStateService.getInstance(project)
                val defaultReq = stateService.getDefaultRequest(endpoint.id)

                if (defaultReq != null) {
                    // Run the default (or only) saved request directly
                    RouteXService.getInstance(project).runRequest(endpoint.id, defaultReq.id)
                } else {
                    // No saved request yet — just navigate so the user can create/fill one
                    RouteXService.getInstance(project).selectEndpoint(endpoint.id)
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RouteXGutterRenderer) return false
            return endpoint.id == other.endpoint.id
        }

        override fun hashCode() = endpoint.id.hashCode()

        override fun getAlignment() = Alignment.LEFT
    }
}
