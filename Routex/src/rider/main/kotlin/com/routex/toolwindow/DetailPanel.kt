package com.routex.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.routex.model.ApiEndpoint
import com.routex.model.AuthInfo
import com.routex.model.HttpMethod
import com.routex.model.ParameterSource
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JTextArea

class DetailPanel : JPanel(BorderLayout()) {

    private val tabs = JBTabbedPane()
    private val requestPanel = RequestPanel()
    private val responsePanel = ResponsePanel()
    private val emptyLabel = JBLabel("Select an endpoint to view details")

    init {
        emptyLabel.horizontalAlignment = JBLabel.CENTER
        emptyLabel.foreground = JBColor.GRAY

        tabs.addTab("Request", requestPanel)
        tabs.addTab("Response", responsePanel)

        // Wire request result into the response tab
        requestPanel.onResponseReceived = { status, body, duration ->
            responsePanel.showResponse(status, body, duration)
            tabs.selectedIndex = 1
        }

        add(emptyLabel, BorderLayout.CENTER)
    }

    fun showEndpoint(endpoint: ApiEndpoint?) {
        removeAll()

        if (endpoint == null) {
            add(emptyLabel, BorderLayout.CENTER)
        } else {
            val header = buildHeader(endpoint)
            add(header, BorderLayout.NORTH)

            requestPanel.showEndpoint(endpoint)
            responsePanel.clear()
            add(tabs, BorderLayout.CENTER)
        }

        revalidate()
        repaint()
    }

    private fun buildHeader(endpoint: ApiEndpoint): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8, 12, 8, 12)

        val methodColor = httpMethodColor(endpoint.httpMethod)
        val methodLabel = JBLabel(endpoint.httpMethod.name)
        methodLabel.font = methodLabel.font.deriveFont(Font.BOLD, 13f)
        methodLabel.foreground = methodColor

        val routeLabel = JBLabel("  ${endpoint.route}")
        routeLabel.font = routeLabel.font.deriveFont(13f)

        val headerRow = JPanel(BorderLayout())
        headerRow.add(methodLabel, BorderLayout.WEST)
        headerRow.add(routeLabel, BorderLayout.CENTER)

        val meta = JBLabel(buildMetaText(endpoint))
        meta.foreground = JBColor.GRAY
        meta.font = meta.font.deriveFont(11f)

        panel.add(headerRow, BorderLayout.NORTH)
        panel.add(meta, BorderLayout.SOUTH)

        return panel
    }

    private fun buildMetaText(endpoint: ApiEndpoint): String {
        val parts = mutableListOf<String>()
        endpoint.controllerName?.let { parts.add(it) }
        parts.add(endpoint.methodName + "()")
        if (endpoint.meta.analysisWarnings.isNotEmpty())
            parts.add("⚠ ${endpoint.meta.analysisWarnings.first()}")
        return parts.joinToString("  •  ")
    }

    private fun httpMethodColor(method: HttpMethod): Color = when (method) {
        HttpMethod.GET -> JBColor(Color(0x00, 0x99, 0x44), Color(0x44, 0xCC, 0x77))
        HttpMethod.POST -> JBColor(Color(0x00, 0x66, 0xCC), Color(0x44, 0x99, 0xFF))
        HttpMethod.PUT -> JBColor(Color(0xCC, 0x66, 0x00), Color(0xFF, 0x99, 0x33))
        HttpMethod.DELETE -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        HttpMethod.PATCH -> JBColor(Color(0x88, 0x00, 0xCC), Color(0xBB, 0x44, 0xFF))
        HttpMethod.HEAD -> JBColor(Color(0x44, 0x44, 0x88), Color(0x88, 0x88, 0xCC))
        HttpMethod.OPTIONS -> JBColor(Color(0x44, 0x44, 0x44), Color(0x88, 0x88, 0x88))
    }
}
