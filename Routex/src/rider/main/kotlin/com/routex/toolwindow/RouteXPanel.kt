package com.routex.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.routex.RouteXService
import com.routex.model.ApiEndpoint
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class RouteXPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val endpointTree = EndpointTree(project)
    private val detailPanel = DetailPanel(project)
    private val searchField = SearchTextField(false)
    private val progressBar = JProgressBar().also {
        it.isIndeterminate = true
        it.isVisible = false
    }

    private var allEndpoints: List<ApiEndpoint> = emptyList()

    init {
        val service = RouteXService.getInstance(project)

        val toolbar = buildToolbar()

        val topBar = JPanel(BorderLayout(4, 0))
        topBar.add(toolbar.component, BorderLayout.WEST)
        topBar.add(searchField, BorderLayout.CENTER)

        val leftPanel = JPanel(BorderLayout())
        leftPanel.add(topBar, BorderLayout.NORTH)
        leftPanel.add(progressBar, BorderLayout.SOUTH)
        leftPanel.add(JBScrollPane(endpointTree), BorderLayout.CENTER)

        val splitter = OnePixelSplitter(false, 0.27f)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = detailPanel
        add(splitter, BorderLayout.CENTER)

        endpointTree.onEndpointSelected = { endpoint ->
            detailPanel.showEndpoint(endpoint)
        }

        endpointTree.onControllerSelected = { node ->
            detailPanel.showController(node)
        }

        endpointTree.onGoToSource = { endpoint ->
            val vf = LocalFileSystem.getInstance().findFileByPath(endpoint.filePath)
            if (vf != null) OpenFileDescriptor(project, vf, endpoint.lineNumber - 1, 0).navigate(true)
        }

        service.addSelectionListener { id ->
            endpointTree.selectEndpoint(id)
        }

        searchField.getTextEditor().document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()
        })

        service.addLoadingListener { loading ->
            progressBar.isVisible = loading
        }

        service.addListener { endpoints ->
            allEndpoints = endpoints
            applyFilter()
        }
    }

    fun updateEndpoints(endpoints: List<ApiEndpoint>) {
        allEndpoints = endpoints
        applyFilter()
    }

    private fun applyFilter() {
        val query = searchField.text.trim().lowercase()
        val filtered = if (query.isEmpty()) allEndpoints
        else allEndpoints.filter {
            it.methodName.lowercase().contains(query) ||
            it.route.lowercase().contains(query) ||
            it.httpMethod.name.lowercase().contains(query) ||
            (it.controllerName?.lowercase()?.contains(query) == true)
        }
        endpointTree.updateEndpoints(filtered)
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Refresh", "Re-scan all endpoints", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                RouteXService.getInstance(project).refresh()
            }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("RouteX.Toolbar", group, true)
        toolbar.targetComponent = this
        return toolbar
    }
}
