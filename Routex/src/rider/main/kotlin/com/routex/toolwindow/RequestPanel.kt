package com.routex.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.routex.model.ApiEndpoint
import com.routex.model.AuthInfo
import com.routex.model.AuthType
import com.routex.model.ParameterSource
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.ActionEvent
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingWorker

class RequestPanel : JPanel(BorderLayout()) {

    private val urlField = JTextField("http://localhost:5000")
    private val headersArea = JTextArea(4, 40)
    private val bodyArea = JTextArea(8, 40)
    private val sendButton = JButton("▶ Send")
    private val statusLabel = JBLabel("")

    var onResponseReceived: ((Int, String, Long) -> Unit)? = null

    private var currentEndpoint: ApiEndpoint? = null

    init {
        val content = JPanel(GridBagLayout())
        content.border = JBUI.Borders.empty(8)
        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(4, 0, 4, 0)

        var row = 0

        // URL row
        gbc.gridy = row++; gbc.gridx = 0; gbc.weightx = 0.0
        content.add(JBLabel("URL"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        content.add(urlField, gbc)

        // Headers
        gbc.gridy = row++; gbc.gridx = 0; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.NORTHWEST
        content.add(JBLabel("Headers"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.weighty = 0.3; gbc.fill = GridBagConstraints.BOTH
        headersArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        headersArea.border = JBUI.Borders.customLine(JBColor.border())
        content.add(JBScrollPane(headersArea), gbc)

        // Body
        gbc.gridy = row++; gbc.gridx = 0; gbc.weightx = 0.0; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.HORIZONTAL
        content.add(JBLabel("Body"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.weighty = 0.7; gbc.fill = GridBagConstraints.BOTH
        bodyArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        bodyArea.border = JBUI.Borders.customLine(JBColor.border())
        content.add(JBScrollPane(bodyArea), gbc)

        // Send button + status
        gbc.gridy = row; gbc.gridx = 1; gbc.weightx = 0.0; gbc.weighty = 0.0; gbc.fill = GridBagConstraints.NONE
        gbc.anchor = GridBagConstraints.EAST
        val buttonRow = JPanel(BorderLayout(8, 0))
        buttonRow.add(statusLabel, BorderLayout.WEST)
        buttonRow.add(sendButton, BorderLayout.EAST)
        content.add(buttonRow, gbc)

        add(JBScrollPane(content), BorderLayout.CENTER)

        sendButton.addActionListener { sendRequest() }
    }

    fun showEndpoint(endpoint: ApiEndpoint) {
        currentEndpoint = endpoint

        // Build URL with route
        val base = urlField.text.trimEnd('/')
        urlField.text = base + endpoint.route

        // Build headers template
        val headerLines = mutableListOf<String>()
        if (endpoint.auth?.required == true) {
            headerLines.add("Authorization: Bearer <token>")
        }
        endpoint.parameters
            .filter { it.source == ParameterSource.HEADER }
            .forEach { headerLines.add("${it.name}: ") }
        headersArea.text = headerLines.joinToString("\n")

        // Build body template from schema
        bodyArea.text = buildBodyTemplate(endpoint)
    }

    private fun buildBodyTemplate(endpoint: ApiEndpoint): String {
        val bodyParam = endpoint.parameters.firstOrNull { it.source == ParameterSource.BODY }
            ?: return ""

        val schema = bodyParam.schema
        if (schema != null && schema.properties.isNotEmpty()) {
            val props = schema.properties.joinToString(",\n  ") { prop ->
                val value = when {
                    prop.type.lowercase().contains("string") -> "\"${prop.name}\": \"\""
                    prop.type.lowercase() in listOf("int", "long", "double", "float", "decimal") -> "\"${prop.name}\": 0"
                    prop.type.lowercase() == "bool" || prop.type.lowercase() == "boolean" -> "\"${prop.name}\": false"
                    else -> "\"${prop.name}\": null"
                }
                value
            }
            return "{\n  $props\n}"
        }

        // Fallback — use type name as comment
        return "// ${bodyParam.type}\n{\n  \n}"
    }

    private fun sendRequest() {
        val endpoint = currentEndpoint ?: return
        val rawUrl = urlField.text.trim()
        if (rawUrl.isEmpty()) return

        sendButton.isEnabled = false
        statusLabel.text = "Sending…"

        object : SwingWorker<Triple<Int, String, Long>, Unit>() {
            override fun doInBackground(): Triple<Int, String, Long> {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()

                val requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(rawUrl))
                    .timeout(Duration.ofSeconds(30))

                // Parse and add custom headers
                headersArea.text.lines()
                    .filter { it.contains(":") }
                    .forEach { line ->
                        val idx = line.indexOf(':')
                        val name = line.substring(0, idx).trim()
                        val value = line.substring(idx + 1).trim()
                        if (name.isNotEmpty()) runCatching { requestBuilder.header(name, value) }
                    }

                val body = bodyArea.text.trim()
                when (endpoint.httpMethod.name) {
                    "GET", "HEAD" -> requestBuilder.GET()
                    "DELETE" -> requestBuilder.DELETE()
                    "POST" -> requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body))
                    "PUT" -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body))
                    else -> requestBuilder.method(
                        endpoint.httpMethod.name,
                        if (body.isEmpty()) HttpRequest.BodyPublishers.noBody()
                        else HttpRequest.BodyPublishers.ofString(body)
                    )
                }

                val start = System.currentTimeMillis()
                val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                val duration = System.currentTimeMillis() - start

                return Triple(response.statusCode(), response.body(), duration)
            }

            override fun done() {
                sendButton.isEnabled = true
                try {
                    val (status, responseBody, duration) = get()
                    statusLabel.text = "$status — ${duration}ms"
                    onResponseReceived?.invoke(status, responseBody, duration)
                } catch (e: Exception) {
                    statusLabel.text = "Error: ${e.message}"
                    onResponseReceived?.invoke(0, "Error: ${e.message}", 0)
                }
            }
        }.execute()
    }
}
