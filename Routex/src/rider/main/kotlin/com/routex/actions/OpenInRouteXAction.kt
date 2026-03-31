package com.routex.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import com.routex.RouteXService

/**
 * Editor context menu action for C# files — finds the nearest endpoint above the
 * caret by file path + line number, then focuses the RouteX tool window and selects it.
 *
 * No PSI analysis is done here; we match against the already-cached endpoint list in
 * RouteXService to keep the action fast and index-independent.
 */
class OpenInRouteXAction : AnAction("Open in RouteX"), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor  = e.getData(CommonDataKeys.EDITOR) ?: return
        val file    = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val caretLine = editor.caretModel.logicalPosition.line + 1 // 1-based
        val filePath  = file.path

        val service = RouteXService.getInstance(project)
        val match = service.endpoints
            .filter { it.filePath == filePath && it.lineNumber <= caretLine }
            .maxByOrNull { it.lineNumber } ?: return

        // Show the tool window, then select the endpoint
        ToolWindowManager.getInstance(project).getToolWindow("RouteX")?.show(null)
        service.selectEndpoint(match.id)
    }

    override fun update(e: AnActionEvent) {
        // Only show for .cs files so the action doesn't clutter other language menus
        val isCSharp = e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?.extension?.equals("cs", ignoreCase = true) == true
        e.presentation.isVisible = isCSharp
        e.presentation.isEnabled = isCSharp && e.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
