package com.routex.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.routex.RouteXService

class RouteXToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = RouteXPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        val service = RouteXService.getInstance(project)

        // Populate tree with whatever is already cached (e.g. from a previous refresh)
        panel.updateEndpoints(service.endpoints)

        // Update tree whenever the service receives new endpoints
        service.addListener { endpoints ->
            ApplicationManager.getApplication().invokeLater {
                panel.updateEndpoints(endpoints)
            }
        }

        // Trigger initial scan from C# backend (no-op until rdgen runs)
        service.refresh()
    }

    override fun isApplicable(project: Project): Boolean = true
}
