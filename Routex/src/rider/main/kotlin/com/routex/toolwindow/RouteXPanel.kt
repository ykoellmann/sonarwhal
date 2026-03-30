package com.routex.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBScrollPane
import com.routex.RouteXService
import com.routex.model.ApiEndpoint
import java.awt.BorderLayout
import javax.swing.JPanel

class RouteXPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val endpointTree = EndpointTree()
    private val detailPanel = DetailPanel()

    init {
        val toolbar = buildToolbar()

        val leftPanel = JPanel(BorderLayout())
        leftPanel.add(toolbar.component, BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(endpointTree), BorderLayout.CENTER)

        val splitter = OnePixelSplitter(false, 0.3f)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = detailPanel

        add(splitter, BorderLayout.CENTER)

        endpointTree.onEndpointSelected = { endpoint ->
            detailPanel.showEndpoint(endpoint)
        }
    }

    fun updateEndpoints(endpoints: List<ApiEndpoint>) {
        endpointTree.updateEndpoints(endpoints)
        if (endpoints.isEmpty()) {
            detailPanel.showEndpoint(null)
        }
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Re-scan all endpoints", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                RouteXService.getInstance(project).refresh()
            }
        })

        val toolbar = ActionManager.getInstance()
            .createActionToolbar("RouteX.Toolbar", group, true)
        toolbar.targetComponent = this
        return toolbar
    }
}
