package com.routex.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.routex.RouteXService
import com.routex.RouteXStateService
import com.routex.model.ApiEndpoint
import com.routex.model.SavedRequest
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
        val service = RouteXService.getInstance(project)

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

        endpointTree.onGoToSource = { endpoint ->
            val vf = LocalFileSystem.getInstance().findFileByPath(endpoint.filePath)
            if (vf != null) OpenFileDescriptor(project, vf, endpoint.lineNumber - 1, 0).navigate(true)
        }

        endpointTree.onRequestSelected = { endpoint, request ->
            detailPanel.showRequest(endpoint, request)
        }

        endpointTree.onAddRequest = { endpoint ->
            val name = Messages.showInputDialog(project, "Request name:", "New Request", null)
                ?.takeIf { it.isNotBlank() }
            if (name != null) {
                val newReq = SavedRequest(name = name)
                RouteXStateService.getInstance(project).upsertRequest(endpoint.id, newReq)
                endpointTree.refreshTree()
                detailPanel.showRequest(endpoint, newReq)
            }
        }

        detailPanel.onRequestSaved = {
            endpointTree.refreshTree()
        }

        endpointTree.onRenameRequest = { endpoint, request ->
            val newName = Messages.showInputDialog(project, "Request name:", "Rename Request", null, request.name, null)
                ?.takeIf { it.isNotBlank() }
            if (newName != null) {
                RouteXStateService.getInstance(project).upsertRequest(endpoint.id, request.copy(name = newName))
                endpointTree.refreshTree()
                detailPanel.updateRequestName(endpoint.id, request.id, newName)
            }
        }

        service.addSelectionListener { id ->
            endpointTree.selectEndpoint(id)
        }

        // Gutter-triggered: select endpoint, load the request, and send immediately
        service.addRunRequestListener { endpointId, requestId ->
            endpointTree.selectEndpoint(endpointId)
            val endpoint = service.endpoints.firstOrNull { it.id == endpointId } ?: return@addRunRequestListener
            val request = RouteXStateService.getInstance(project).getRequest(endpointId, requestId) ?: return@addRunRequestListener
            detailPanel.showRequest(endpoint, request)
            detailPanel.triggerSendRequest()
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
        val stateService = RouteXStateService.getInstance(project)

        envCombo.addActionListener {
            if (suppressEnvComboListener) return@addActionListener
            val idx = envCombo.selectedIndex
            val envs = stateService.getEnvironments()
            stateService.setActiveEnvironment(if (idx <= 0) "" else envs.getOrNull(idx - 1)?.id ?: "")
            detailPanel.refreshComputedUrl()
        }

        val editButton = JButton(AllIcons.General.Settings).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            toolTipText = "Manage environments"
            addActionListener {
                RouteXSettingsDialog(project).apply {
                    if (showAndGet()) {
                        refreshEnvCombo()
                        detailPanel.refreshComputedUrl()
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
            val stateService = RouteXStateService.getInstance(project)
            val envs = stateService.getEnvironments()
            val activeId = stateService.getActiveEnvironment()?.id

            envCombo.removeAllItems()
            envCombo.addItem("No Environment")
            envs.forEach { envCombo.addItem(it.name) }

            val activeIdx = if (activeId != null) envs.indexOfFirst { it.id == activeId } + 1 else 0
            envCombo.selectedIndex = activeIdx.coerceIn(0, envCombo.itemCount - 1)
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
            it.methodName.lowercase().contains(query) ||
            it.route.lowercase().contains(query) ||
            it.httpMethod.name.lowercase().contains(query) ||
            (it.controllerName?.lowercase()?.contains(query) == true)
        }
        endpointTree.updateEndpoints(filtered)
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup()
        group.add(object : AnAction("Refresh", "Refresh changed files only (incremental)", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                RouteXService.getInstance(project).refresh()
            }
        })
        group.add(object : AnAction("Re-Scan", "Clear cache and re-analyse all files", AllIcons.Actions.ForceRefresh) {
            override fun actionPerformed(e: AnActionEvent) {
                RouteXService.getInstance(project).reScan()
            }
        })
        val toolbar = ActionManager.getInstance().createActionToolbar("RouteX.Toolbar", group, true)
        toolbar.targetComponent = this
        return toolbar
    }
}
