package com.routex.toolwindow

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.routex.model.ApiEndpoint
import com.routex.model.HttpMethod
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JPanel

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

    init {
        requestPanel.onResponseReceived = { status, body, duration ->
            responsePanel.showResponse(status, body, duration)
        }
        add(headerHolder, BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        cardLayout.show(cardPanel, "empty")
    }

    fun showEndpoint(endpoint: ApiEndpoint?) {
        if (endpoint == null) {
            headerHolder.isVisible = false
            cardLayout.show(cardPanel, "empty")
        } else {
            headerHolder.removeAll()
            headerHolder.add(buildHeader(endpoint))
            headerHolder.isVisible = true
            requestPanel.showEndpoint(endpoint)
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

    private fun buildHeader(endpoint: ApiEndpoint): JPanel {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(6, 12)
        )

        val gbc = GridBagConstraints()
        gbc.gridy = 0
        gbc.anchor = GridBagConstraints.WEST
        gbc.fill = GridBagConstraints.NONE

        // Method badge — colored pill
        val methodColor = httpMethodColor(endpoint.httpMethod)
        val badge = JBLabel(endpoint.httpMethod.name).apply {
            font = Font(Font.MONOSPACED, Font.BOLD, 11)
            foreground = methodColor
            border = JBUI.Borders.empty(1, 0, 1, 10)
        }
        gbc.gridx = 0; gbc.weightx = 0.0; gbc.insets = Insets(0, 0, 0, 0)
        panel.add(badge, gbc)

        // Method name
        val nameLabel = JBLabel("${endpoint.methodName}()").apply {
            font = font.deriveFont(Font.BOLD, 13f)
        }
        gbc.gridx = 1; gbc.insets = Insets(0, 0, 0, 8)
        panel.add(nameLabel, gbc)

        // Controller
        endpoint.controllerName?.let { ctrl ->
            panel.add(separator(), gbc.also { it.gridx = 2 })
            panel.add(JBLabel(ctrl).apply { foreground = JBColor.GRAY }, gbc.also { it.gridx = 3 })
        }

        // Route
        panel.add(separator(), gbc.also { it.gridx = 4 })
        panel.add(JBLabel(endpoint.route).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            foreground = JBColor.GRAY
        }, gbc.also { it.gridx = 5 })

        // Warnings
        if (endpoint.meta.analysisWarnings.isNotEmpty()) {
            panel.add(JBLabel("  ⚠ ${endpoint.meta.analysisWarnings.first()}").apply {
                foreground = JBColor(Color(0xBB, 0x77, 0x00), Color(0xFF, 0xBB, 0x33))
                font = font.deriveFont(11f)
            }, gbc.also { it.gridx = 6 })
        }

        // Right-fill spacer
        panel.add(JPanel().also { it.isOpaque = false }, GridBagConstraints().also {
            it.gridx = 7; it.gridy = 0; it.weightx = 1.0; it.fill = GridBagConstraints.HORIZONTAL
        })

        // Go-to-source link (right-aligned)
        val sourceLink = HyperlinkLabel("→ source").apply {
            font = font.deriveFont(11f)
        }
        sourceLink.addHyperlinkListener {
            val vf = LocalFileSystem.getInstance().findFileByPath(endpoint.filePath) ?: return@addHyperlinkListener
            OpenFileDescriptor(project, vf, endpoint.lineNumber - 1, 0).navigate(true)
        }
        panel.add(sourceLink, GridBagConstraints().also {
            it.gridx = 8; it.gridy = 0; it.weightx = 0.0
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
