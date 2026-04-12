package com.sonarwhale.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

import com.sonarwhale.service.RouteIndexService

/**
 * Editor context menu action for C# files — finds the nearest endpoint above the
 * caret by file path + line number, then focuses the Sonarwhale tool window and selects it.
 *
 * Matching is done via psiNavigationTarget (populated in Phase 4). Until then, the action
 * selects the first endpoint whose psiNavigationTarget starts with the current file path.
 */
class OpenInSonarwhaleAction : AnAction("Open in Sonarwhale"), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file    = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val filePath  = file.path
        val service = RouteIndexService.getInstance(project)
        val match = service.endpoints
            .firstOrNull { it.psiNavigationTarget?.startsWith(filePath) == true } ?: return

        // Show the tool window, then select the endpoint
        ToolWindowManager.getInstance(project).getToolWindow("Sonarwhale")?.show(null)
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
