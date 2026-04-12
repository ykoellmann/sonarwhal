package com.routex.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.routex.model.ApiEndpoint
import com.routex.RouteXStateService
import com.routex.service.EnvironmentService
import com.routex.service.RouteIndexService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
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

    private val envCombo = JComboBox<String>()
    private var suppressEnvComboListener = false

    private var allEndpoints: List<ApiEndpoint> = emptyList()

    init {
        val service = RouteIndexService.getInstance(project)

        val toolbar = buildToolbar()

        val topBar = JPanel(BorderLayout(4, 0))
        topBar.add(toolbar.component, BorderLayout.WEST)
        topBar.add(searchField, BorderLayout.CENTER)
        topBar.add(buildEnvPanel(), BorderLayout.EAST)

        refreshEnvCombo()

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

        endpointTree.onRequestSelected = { endpoint, request ->
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
            val request = RouteXStateService.getInstance(project).getRequest(endpointId, requestId)
            if (request != null) {
                endpointTree.selectRequest(endpointId, requestId)
                detailPanel.showRequest(endpoint, request)
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

    private fun buildEnvPanel(): JPanel {
        val envService = EnvironmentService.getInstance(project)

        envCombo.addActionListener {
            if (suppressEnvComboListener) return@addActionListener
            val idx = envCombo.selectedIndex
            val envs = envService.getAll()
            val selected = envs.getOrNull(idx)
            if (selected != null) {
                envService.setActive(selected.id)
                RouteIndexService.getInstance(project).refresh()
            }
        }

        val editButton = JButton(AllIcons.General.Settings).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            toolTipText = "Manage sources & environments"
            addActionListener {
                RouteXSettingsDialog(project).apply {
                    if (showAndGet()) {
                        refreshEnvCombo()
                        RouteIndexService.getInstance(project).refresh()
                    }
                }
            }
        }

        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        panel.add(JBLabel("Env:").apply { foreground = JBColor.GRAY; font = font.deriveFont(10f) })
        panel.add(envCombo)
        panel.add(editButton)
        return panel
    }

    private fun refreshEnvCombo() {
        suppressEnvComboListener = true
        try {
            val envService = EnvironmentService.getInstance(project)
            val envs = envService.getAll()
            val activeId = envService.getActive()?.id

            envCombo.removeAllItems()
            envs.forEach { envCombo.addItem(it.name) }
            if (envs.isEmpty()) envCombo.addItem("No source configured")

            val activeIdx = if (activeId != null) envs.indexOfFirst { it.id == activeId } else 0
            envCombo.selectedIndex = activeIdx.coerceIn(0, (envCombo.itemCount - 1).coerceAtLeast(0))
        } finally {
            suppressEnvComboListener = false
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
        val toolbar = ActionManager.getInstance().createActionToolbar("RouteX.Toolbar", group, true)
        toolbar.targetComponent = this
        return toolbar
    }
}
