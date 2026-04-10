package com.routex.toolwindow

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.routex.RouteXStateService
import com.routex.model.ApiEndpoint
import com.routex.model.HttpMethod
import com.routex.model.SavedRequest
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class DetailPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val requestPanel = RequestPanel(project)
    private val responsePanel = ResponsePanel(project)

    private val emptyLabel = JBLabel("Select an endpoint").apply {
        horizontalAlignment = JBLabel.CENTER
        foreground = JBColor.GRAY
    }

    private val splitter = OnePixelSplitter(true, 0.58f).also {
        it.firstComponent = requestPanel
        it.secondComponent = responsePanel
    }

    // CardLayout keeps splitter permanently in the hierarchy so its divider never resets.
    private val cardLayout = CardLayout()

    // Reusable controller panel — content rebuilt on each selection.
    private val controllerPanel = JPanel(BorderLayout())

    private val cardPanel = JPanel(cardLayout).also {
        it.add(emptyLabel, "empty")
        it.add(splitter, "content")
        it.add(controllerPanel, "controller")
    }

    // Header slot — hidden when no endpoint is selected.
    private val headerHolder = JPanel(BorderLayout()).also { it.isVisible = false }

    var onRequestSaved: (() -> Unit)? = null

    private var currentShownEndpointId: String? = null
    private var currentShownRequestId: String? = null
    private var currentNameField: JTextField? = null

    /** Called when the request is renamed from the tree context menu. */
    fun updateRequestName(endpointId: String, requestId: String, newName: String) {
        if (currentShownEndpointId == endpointId && currentShownRequestId == requestId) {
            currentNameField?.text = newName
            requestPanel.setRequestName(newName)
        }
    }

    init {
        requestPanel.onResponseReceived = { status, body, duration ->
            responsePanel.showResponse(status, body, duration)
        }
        requestPanel.onRequestSaved = { onRequestSaved?.invoke() }
        add(headerHolder, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        cardLayout.show(cardPanel, "empty")
    }

    /** Show a specific named request for an endpoint. */
    fun showRequest(endpoint: ApiEndpoint, request: SavedRequest) {
        currentShownEndpointId = endpoint.id
        currentShownRequestId = request.id
        headerHolder.removeAll()
        headerHolder.add(buildHeader(endpoint, request))
        headerHolder.isVisible = true
        requestPanel.showRequest(endpoint, request)
        responsePanel.clear()
        cardLayout.show(cardPanel, "content")
        revalidate()
        repaint()
    }

    /** Trigger send on the currently displayed request (used by gutter icon). */
    fun triggerSendRequest() = requestPanel.triggerSend()

    /** Re-run URL resolution with the current active environment (called when env selection changes). */
    fun refreshComputedUrl() = requestPanel.refreshEnvironment()

    fun showEndpoint(endpoint: ApiEndpoint?) {
        if (endpoint == null) {
            currentShownEndpointId = null
            currentShownRequestId = null
            headerHolder.isVisible = false
            cardLayout.show(cardPanel, "empty")
        } else {
            currentShownEndpointId = endpoint.id
            val req = RouteXStateService.getInstance(project).getDefaultRequest(endpoint.id)
            currentShownRequestId = req?.id
            headerHolder.removeAll()
            headerHolder.add(buildHeader(endpoint, req))
            headerHolder.isVisible = true
            if (req != null) requestPanel.showRequest(endpoint, req)
            else requestPanel.showEndpoint(endpoint)
            responsePanel.clear()
            cardLayout.show(cardPanel, "content")
        }
        revalidate()
        repaint()
    }

    fun showController(node: ControllerNode) {
        headerHolder.isVisible = false

        controllerPanel.removeAll()
        controllerPanel.border = JBUI.Borders.empty(24)

        val title = JBLabel(node.name).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }
        val count = JBLabel("${node.endpoints.size} endpoint${if (node.endpoints.size == 1) "" else "s"}").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(12f)
        }
        val textPanel = JPanel(BorderLayout(0, 4)).also {
            it.isOpaque = false
            it.add(title, BorderLayout.NORTH)
            it.add(count, BorderLayout.CENTER)
        }
        controllerPanel.add(textPanel, BorderLayout.NORTH)

        cardLayout.show(cardPanel, "controller")
        revalidate()
        repaint()
    }

    private fun buildHeader(endpoint: ApiEndpoint, request: SavedRequest?): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(4, 4)
        )

        val gbc = GridBagConstraints()
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.NONE
        var col = 0

        // ── Request name (saved requests only) ────────────────────────────
        if (request != null) {
            val nameField = object : JTextField(request.name) {
                override fun getPreferredSize(): java.awt.Dimension {
                    val fm = getFontMetrics(font)
                    val ins = insets
                    val w = maxOf(fm.stringWidth(text) + (ins?.left ?: 0) + (ins?.right ?: 0) + 16, 60)
                    return java.awt.Dimension(w, super.getPreferredSize().height)
                }
            }.apply {
                font = font.deriveFont(Font.BOLD, 13f)
                border = JBUI.Borders.empty(1, 2)
                isOpaque = false
                toolTipText = "Click to rename"
            }
            currentNameField = nameField
            nameField.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent)  { nameField.revalidate(); requestPanel.setRequestName(nameField.text) }
                override fun removeUpdate(e: DocumentEvent)  { nameField.revalidate(); requestPanel.setRequestName(nameField.text) }
                override fun changedUpdate(e: DocumentEvent) { nameField.revalidate(); requestPanel.setRequestName(nameField.text) }
            })
            gbc.gridx = col++; gbc.weightx = 0.0; gbc.insets = Insets(0, 0, 0, 0)
            panel.add(nameField, gbc)

            panel.add(separator(), gbc.also { it.gridx = col++ })
        }

        // ── HTTP method badge ──────────────────────────────────────────────
        val badge = JBLabel(endpoint.httpMethod.name).apply {
            font = Font(Font.MONOSPACED, Font.BOLD, 11)
            foreground = httpMethodColor(endpoint.httpMethod)
            border = JBUI.Borders.empty(1, 0, 1, 6)
        }
        gbc.gridx = col++; gbc.weightx = 0.0; gbc.insets = Insets(0, 0, 0, 0)
        panel.add(badge, gbc)

        // Method name
        gbc.gridx = col++; gbc.insets = Insets(0, 0, 0, 8)
        val methodDisplayName = if (endpoint.controllerName != null) "${endpoint.methodName}()" else endpoint.methodName
        panel.add(JBLabel(methodDisplayName).apply { font = font.deriveFont(Font.BOLD, 13f) }, gbc)

        // Controller
        endpoint.controllerName?.let { ctrl ->
            panel.add(separator(), gbc.also { it.gridx = col++ })
            panel.add(JBLabel(ctrl).apply { foreground = JBColor.GRAY }, gbc.also { it.gridx = col++ })
        }

        // Route
        panel.add(separator(), gbc.also { it.gridx = col++ })
        panel.add(JBLabel(endpoint.route).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12); foreground = JBColor.GRAY
        }, gbc.also { it.gridx = col++ })

        // Warnings
        if (endpoint.meta.analysisWarnings.isNotEmpty()) {
            panel.add(JBLabel("  ⚠ ${endpoint.meta.analysisWarnings.first()}").apply {
                foreground = JBColor(Color(0xBB, 0x77, 0x00), Color(0xFF, 0xBB, 0x33))
                font = font.deriveFont(11f)
            }, gbc.also { it.gridx = col++ })
        }

        // Right-fill spacer
        panel.add(JPanel().also { it.isOpaque = false }, GridBagConstraints().also {
            it.gridx = col++; it.gridy = 0; it.weightx = 1.0; it.fill = GridBagConstraints.HORIZONTAL
        })

        // Go-to-source link
        val sourceLink = HyperlinkLabel("→ source").apply { font = font.deriveFont(11f) }
        sourceLink.addHyperlinkListener {
            val vf = LocalFileSystem.getInstance().findFileByPath(endpoint.filePath) ?: return@addHyperlinkListener
            OpenFileDescriptor(project, vf, endpoint.lineNumber - 1, 0).navigate(true)
        }
        panel.add(sourceLink, GridBagConstraints().also {
            it.gridx = col; it.gridy = 0; it.weightx = 0.0
            it.anchor = GridBagConstraints.EAST; it.insets = Insets(0, 8, 0, 0)
        })

        return panel
    }

    private fun separator() = JBLabel("·").apply {
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 4)
    }

    private fun httpMethodColor(method: HttpMethod): Color = when (method) {
        HttpMethod.GET    -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x4C, 0xC4, 0x7F))
        HttpMethod.POST   -> JBColor(Color(0x00, 0x77, 0xDD), Color(0x44, 0x99, 0xFF))
        HttpMethod.PUT    -> JBColor(Color(0xCC, 0x66, 0x00), Color(0xFF, 0x99, 0x33))
        HttpMethod.DELETE -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        HttpMethod.PATCH  -> JBColor(Color(0x88, 0x00, 0xCC), Color(0xBB, 0x44, 0xFF))
        HttpMethod.HEAD   -> JBColor(Color(0x44, 0x44, 0x88), Color(0x88, 0x88, 0xCC))
        HttpMethod.OPTIONS-> JBColor(Color(0x55, 0x55, 0x55), Color(0x88, 0x88, 0x88))
    }
}
