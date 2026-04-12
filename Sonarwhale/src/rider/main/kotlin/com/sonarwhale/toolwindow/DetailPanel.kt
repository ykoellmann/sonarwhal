package com.sonarwhale.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.sonarwhale.gutter.SourceLocationService
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.AuthType
import com.sonarwhale.model.HttpMethod
import com.sonarwhale.model.SavedRequest
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JPanel

class DetailPanel(private val project: Project) : JPanel(BorderLayout()) {

    val requestPanel  = RequestPanel(project)
    private val responsePanel = ResponsePanel(project)

    private val emptyLabel = JBLabel("Select an endpoint").apply {
        horizontalAlignment = JBLabel.CENTER
        foreground = JBColor.GRAY
    }

    private val splitter = OnePixelSplitter(true, 0.58f).also {
        it.firstComponent  = requestPanel
        it.secondComponent = responsePanel
    }

    private val cardLayout      = CardLayout()
    private val controllerPanel = JPanel(BorderLayout())
    private val cardPanel       = JPanel(cardLayout).also {
        it.add(emptyLabel,       "empty")
        it.add(splitter,         "content")
        it.add(controllerPanel,  "controller")
    }

    private val headerHolder = JPanel(BorderLayout()).also { it.isVisible = false }

    init {
        requestPanel.onResponseReceived = { status, body, duration ->
            responsePanel.showResponse(status, body, duration)
        }
        add(headerHolder, BorderLayout.NORTH)
        add(cardPanel,    BorderLayout.CENTER)
        cardLayout.show(cardPanel, "empty")
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called when an endpoint node is selected in the tree (no specific request). */
    fun showEndpoint(endpoint: ApiEndpoint?) {
        if (endpoint == null) {
            headerHolder.isVisible = false
            cardLayout.show(cardPanel, "empty")
        } else {
            showHeader(endpoint)
            requestPanel.showEndpoint(endpoint)
            responsePanel.clear()
            cardLayout.show(cardPanel, "content")
        }
        revalidate(); repaint()
    }

    /** Called when a request sub-node is selected in the tree. */
    fun showRequest(endpoint: ApiEndpoint, request: SavedRequest) {
        showHeader(endpoint)
        requestPanel.showRequest(endpoint, request)
        responsePanel.clear()
        cardLayout.show(cardPanel, "content")
        revalidate(); repaint()
    }

    fun showController(node: ControllerNode) {
        headerHolder.isVisible = false
        controllerPanel.removeAll()
        controllerPanel.border = JBUI.Borders.empty(24)
        val title = JBLabel(node.name).apply { font = font.deriveFont(Font.BOLD, 14f) }
        val count = JBLabel("${node.endpoints.size} endpoint${if (node.endpoints.size == 1) "" else "s"}").apply {
            foreground = JBColor.GRAY; font = font.deriveFont(12f)
        }
        val textPanel = JPanel(BorderLayout(0, 4)).also {
            it.isOpaque = false
            it.add(title, BorderLayout.NORTH)
            it.add(count, BorderLayout.CENTER)
        }
        controllerPanel.add(textPanel, BorderLayout.NORTH)
        cardLayout.show(cardPanel, "controller")
        revalidate(); repaint()
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private fun showHeader(endpoint: ApiEndpoint) {
        headerHolder.removeAll()
        headerHolder.add(buildHeader(endpoint))
        headerHolder.isVisible = true
    }

    private fun buildHeader(endpoint: ApiEndpoint): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(6, 12)
        )
        val gbc = GridBagConstraints()
        gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST; gbc.fill = GridBagConstraints.NONE

        val badge = JBLabel(endpoint.method.name).apply {
            font = Font(Font.MONOSPACED, Font.BOLD, 11)
            foreground = httpMethodColor(endpoint.method)
            border = JBUI.Borders.empty(1, 0, 1, 10)
        }
        gbc.gridx = 0; gbc.weightx = 0.0; gbc.insets = Insets(0, 0, 0, 0)
        panel.add(badge, gbc)

        gbc.gridx = 1; gbc.insets = Insets(0, 0, 0, 8)
        panel.add(JBLabel(endpoint.path).apply { font = Font(Font.MONOSPACED, Font.BOLD, 13) }, gbc)

        if (endpoint.tags.isNotEmpty()) {
            panel.add(separator(), gbc.also { it.gridx = 2 })
            panel.add(JBLabel(endpoint.tags.joinToString(", ")).apply {
                foreground = JBColor.GRAY; font = font.deriveFont(11f)
            }, gbc.also { it.gridx = 3 })
        }

        endpoint.summary?.takeIf { it.isNotBlank() }?.let { s ->
            panel.add(separator(), gbc.also { it.gridx = 4 })
            val short = if (s.length > 60) s.take(60) + "…" else s
            panel.add(JBLabel(short).apply {
                foreground = JBColor.GRAY; font = font.deriveFont(Font.ITALIC, 11f)
            }, gbc.also { it.gridx = 5 })
        }

        panel.add(JPanel().also { it.isOpaque = false }, GridBagConstraints().also {
            it.gridx = 6; it.gridy = 0; it.weightx = 1.0; it.fill = GridBagConstraints.HORIZONTAL
        })

        endpoint.auth?.let { auth ->
            if (auth.type != AuthType.NONE) {
                panel.add(JBLabel("🔒 ${auth.type.name}").apply {
                    font = font.deriveFont(10f)
                    foreground = JBColor(Color(0x88, 0x55, 0x00), Color(0xCC, 0xAA, 0x44))
                }, GridBagConstraints().also {
                    it.gridx = 7; it.gridy = 0; it.weightx = 0.0
                    it.anchor = GridBagConstraints.EAST; it.insets = Insets(0, 8, 0, 0)
                })
            }
        }

        // "Jump to source" button — only visible when a source location is known
        val navBtn = JButton(AllIcons.Actions.EditSource).apply {
            isBorderPainted    = false
            isContentAreaFilled = false
            toolTipText        = "Jump to source"
            isVisible          = SourceLocationService.getInstance(project).canNavigate(endpoint.id)
            addActionListener {
                SourceLocationService.getInstance(project).navigate(endpoint.id)
            }
        }
        panel.add(navBtn, GridBagConstraints().also {
            it.gridx = 8; it.gridy = 0; it.weightx = 0.0
            it.anchor = GridBagConstraints.EAST; it.insets = Insets(0, 4, 0, 0)
        })

        return panel
    }

    private fun separator() = JBLabel("·").apply {
        foreground = JBColor.GRAY; border = JBUI.Borders.empty(0, 4)
    }

    private fun httpMethodColor(method: HttpMethod): Color = when (method) {
        HttpMethod.GET     -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x4C, 0xC4, 0x7F))
        HttpMethod.POST    -> JBColor(Color(0x00, 0x77, 0xDD), Color(0x44, 0x99, 0xFF))
        HttpMethod.PUT     -> JBColor(Color(0xCC, 0x66, 0x00), Color(0xFF, 0x99, 0x33))
        HttpMethod.DELETE  -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        HttpMethod.PATCH   -> JBColor(Color(0x88, 0x00, 0xCC), Color(0xBB, 0x44, 0xFF))
        HttpMethod.HEAD    -> JBColor(Color(0x44, 0x44, 0x88), Color(0x88, 0x88, 0xCC))
        HttpMethod.OPTIONS -> JBColor(Color(0x55, 0x55, 0x55), Color(0x88, 0x88, 0x88))
    }
}
