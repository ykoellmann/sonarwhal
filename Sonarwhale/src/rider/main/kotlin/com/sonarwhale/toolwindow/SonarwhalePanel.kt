package com.sonarwhale.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.service.RouteIndexService
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SonarwhalePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val endpointTree = EndpointTree(project)
    private val detailPanel = DetailPanel(project)
    private val searchField = SearchTextField(false)
    private val progressBar = JProgressBar().also {
        it.isIndeterminate = true
        it.isVisible = false
    }

    private var allEndpoints: List<ApiEndpoint> = emptyList()

    init {
        val service = RouteIndexService.getInstance(project)

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
            service.setCurrentEndpoint(endpoint.id)
            detailPanel.showEndpoint(endpoint)
        }

        endpointTree.onControllerSelected = { node ->
            service.setCurrentEndpoint(null)
            detailPanel.showController(node)
        }

        endpointTree.onGlobalSelected = {
            service.setCurrentEndpoint(null)
            detailPanel.showGlobal()
        }

        endpointTree.onCollectionSelected = { collection ->
            detailPanel.showCollection(collection)
        }

        endpointTree.onRequestSelected = { endpoint, request ->
            service.setCurrentEndpoint(endpoint.id)
            detailPanel.showRequest(endpoint, request)
        }

        // After saving a request, refresh that endpoint's sub-nodes in the tree
        detailPanel.requestPanel.onRequestSaved = {
            val epId = detailPanel.requestPanel.currentEndpointId
            if (epId != null) endpointTree.refreshRequests(epId)
        }

        service.addSelectionListener { id ->
            endpointTree.selectEndpoint(id)
        }

        service.addRunRequestListener { endpointId, requestId ->
            val endpoint = service.endpoints.firstOrNull { it.id == endpointId } ?: return@addRunRequestListener
            val request = SonarwhaleStateService.getInstance(project).getRequest(endpointId, requestId)
            if (request != null) {
                endpointTree.selectRequest(endpointId, requestId)
                detailPanel.showRequest(endpoint, request)
                detailPanel.requestPanel.triggerSend()
            } else {
                endpointTree.selectEndpoint(endpointId)
                detailPanel.showEndpoint(endpoint)
            }
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
            it.path.lowercase().contains(query) ||
            it.method.name.lowercase().contains(query) ||
            it.tags.any { tag -> tag.lowercase().contains(query) } ||
            (it.summary?.lowercase()?.contains(query) == true)
        }
        endpointTree.updateEndpoints(filtered)
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Refresh", "Refresh OpenAPI from source", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                RouteIndexService.getInstance(project).refresh()
            }
        })
        group.add(object : AnAction("Re-Scan", "Clear cache and re-fetch OpenAPI", AllIcons.Actions.ForceRefresh) {
            override fun actionPerformed(e: AnActionEvent) {
                RouteIndexService.getInstance(project).reScan()
            }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("Sonarwhale.Toolbar", group, true)
        toolbar.targetComponent = this
        return toolbar
    }
}
