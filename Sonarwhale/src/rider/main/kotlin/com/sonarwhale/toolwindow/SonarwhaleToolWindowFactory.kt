package com.sonarwhale.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.sonarwhale.service.RouteIndexService

class SonarwhaleToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = SonarwhalePanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        val service = RouteIndexService.getInstance(project)

        // Seed with whatever the service already has cached (e.g. from startup activity)
        panel.updateEndpoints(service.endpoints)

        // SonarwhalePanel.init already registered an endpoint listener; trigger the initial scan.
        service.refresh()
    }

    override fun isApplicable(project: Project): Boolean = true
}
