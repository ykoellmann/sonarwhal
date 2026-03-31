package com.routex.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.routex.RouteXStateService
import com.routex.model.ApiEndpoint
import com.routex.model.ApiParameter
import com.routex.model.ParameterSource
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingWorker
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class RequestPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val stateService = RouteXStateService.getInstance(project)

    // URL bar
    private val baseUrlField = JTextField(stateService.baseUrl).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        toolTipText = "Base URL — saved per project"
    }
    private val computedUrlField = JTextField().apply {
        isEditable = false
        foreground = JBColor.GRAY
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        toolTipText = "Full URL computed from base + route + parameters"
    }
    private val sendButton = JButton("Send").apply { font = font.deriveFont(Font.BOLD) }
    private val saveButton = JButton("Save").apply {
        font = font.deriveFont(11f)
        toolTipText = "Save current headers, body & param values for this endpoint"
    }
    private val statusLabel = JBLabel("").apply { font = font.deriveFont(11f) }

    // Tab content — created once, reused
    private val paramsPanel = JPanel(GridBagLayout())
    private val headersArea = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        tabSize = 2
    }
    private val bodyArea = JTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        tabSize = 2
    }

    private val paramsScrollPane = JBScrollPane(paramsPanel).also { it.border = JBUI.Borders.empty() }
    private val headersTab = buildHeadersTab()
    private val bodyTab = buildBodyTab()
    private val tabs = JBTabbedPane()

    var onResponseReceived: ((Int, String, Long) -> Unit)? = null

    private var currentEndpoint: ApiEndpoint? = null
    private val paramFields = LinkedHashMap<String, JTextField>()

    private val recomputeListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = updateComputedUrl()
        override fun removeUpdate(e: DocumentEvent) = updateComputedUrl()
        override fun changedUpdate(e: DocumentEvent) = updateComputedUrl()
    }

    init {
        add(buildUrlBar(), BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        sendButton.addActionListener { sendRequest() }
        saveButton.addActionListener { saveRequest() }
        baseUrlField.document.addDocumentListener(recomputeListener)
    }

    private fun buildUrlBar(): JPanel {
        val bar = JPanel(GridBagLayout())
        bar.border = JBUI.Borders.empty(8, 8, 4, 8)

        val gbc = GridBagConstraints()
        gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.WEST

        gbc.gridx = 0; gbc.weightx = 0.0; gbc.insets = Insets(0, 0, 0, 4)
        bar.add(JBLabel("Base URL").also { it.foreground = JBColor.GRAY; it.font = it.font.deriveFont(10f) }, gbc)

        gbc.gridx = 1; gbc.weightx = 0.26; gbc.insets = Insets(0, 0, 0, 4)
        bar.add(baseUrlField, gbc)

        gbc.gridx = 2; gbc.weightx = 0.74; gbc.insets = Insets(0, 0, 0, 6)
        bar.add(computedUrlField, gbc)

        gbc.gridx = 3; gbc.weightx = 0.0; gbc.insets = Insets(0, 0, 0, 4)
        bar.add(sendButton, gbc)

        gbc.gridx = 4; gbc.insets = Insets(0, 0, 0, 8)
        bar.add(saveButton, gbc)

        gbc.gridx = 5; gbc.insets = Insets(0, 0, 0, 0)
        bar.add(statusLabel, gbc)

        return bar
    }

    private fun buildHeadersTab(): JPanel {
        val panel = JPanel(BorderLayout(0, 4))
        panel.border = JBUI.Borders.empty(8)
        panel.add(JBLabel("One header per line:  Name: Value").also {
            it.foreground = JBColor.GRAY; it.font = it.font.deriveFont(10f)
        }, BorderLayout.NORTH)
        panel.add(JBScrollPane(headersArea), BorderLayout.CENTER)
        return panel
    }

    private fun buildBodyTab(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(8)
        panel.add(JBScrollPane(bodyArea), BorderLayout.CENTER)
        return panel
    }

    fun showEndpoint(endpoint: ApiEndpoint) {
        currentEndpoint = endpoint

        val hasParams = endpoint.parameters.any { it.source == ParameterSource.PATH || it.source == ParameterSource.QUERY }
        val hasBody   = endpoint.parameters.any { it.source == ParameterSource.BODY }

        // Build param fields first so we can restore saved values before URL computation
        rebuildParamsTab(endpoint)

        // Restore saved param values
        val savedReq = stateService.getSavedRequest(endpoint.id)
        savedReq?.paramValues?.forEach { (name, value) -> paramFields[name]?.text = value }

        // Dynamic tab visibility
        val prevTab = tabs.selectedComponent
        tabs.removeAll()
        if (hasParams) tabs.addTab("Params", paramsScrollPane)
        tabs.addTab("Headers", headersTab)
        if (hasBody) tabs.addTab("Body", bodyTab)

        (0 until tabs.tabCount).firstOrNull { tabs.getComponentAt(it) == prevTab }
            ?.let { tabs.selectedIndex = it }

        updateComputedUrl()

        // Restore headers — saved value or template
        headersArea.text = savedReq?.headers?.takeIf { it.isNotEmpty() } ?: buildHeadersTemplate(endpoint)
        headersArea.caretPosition = 0

        if (hasBody) {
            bodyArea.text = savedReq?.body?.takeIf { it.isNotEmpty() } ?: buildBodyTemplate(endpoint)
            bodyArea.caretPosition = 0
        } else {
            bodyArea.text = ""
        }

        saveButton.isEnabled = true
    }

    private fun rebuildParamsTab(endpoint: ApiEndpoint) {
        paramsPanel.removeAll()
        paramFields.clear()

        val pathParams  = endpoint.parameters.filter { it.source == ParameterSource.PATH }
        val queryParams = endpoint.parameters.filter { it.source == ParameterSource.QUERY }

        if (pathParams.isEmpty() && queryParams.isEmpty()) {
            paramsPanel.revalidate(); paramsPanel.repaint(); return
        }

        var row = 0

        fun sectionLabel(title: String) {
            paramsPanel.add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 10f); foreground = JBColor.GRAY
                border = JBUI.Borders.empty(8, 8, 2, 0)
            }, GridBagConstraints().also {
                it.gridx = 0; it.gridy = row; it.gridwidth = 3
                it.weightx = 1.0; it.fill = GridBagConstraints.HORIZONTAL
            })
            row++
        }

        fun paramRow(param: ApiParameter) {
            paramsPanel.add(JBLabel(if (param.required) "●" else "○").apply {
                foreground = if (param.required)
                    JBColor(Color(0xCC, 0x22, 0x22), Color(0xFF, 0x55, 0x55)) else JBColor.GRAY
                font = font.deriveFont(9f)
                toolTipText = if (param.required) "Required" else "Optional"
                border = JBUI.Borders.empty(0, 8, 0, 4)
            }, GridBagConstraints().also {
                it.gridx = 0; it.gridy = row; it.weightx = 0.0
                it.anchor = GridBagConstraints.WEST; it.insets = Insets(2, 0, 2, 0)
            })

            paramsPanel.add(JBLabel(param.name).apply {
                font = font.deriveFont(Font.PLAIN, 12f)
                border = JBUI.Borders.empty(0, 0, 0, 12)
            }, GridBagConstraints().also {
                it.gridx = 1; it.gridy = row; it.weightx = 0.0
                it.anchor = GridBagConstraints.WEST; it.insets = Insets(2, 0, 2, 0)
            })

            val field = JTextField(param.defaultValue ?: "").also {
                it.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
                it.document.addDocumentListener(recomputeListener)
            }
            paramFields[param.name] = field
            paramsPanel.add(field, GridBagConstraints().also {
                it.gridx = 2; it.gridy = row; it.weightx = 1.0
                it.fill = GridBagConstraints.HORIZONTAL; it.insets = Insets(2, 0, 2, 8)
            })
            row++
        }

        if (pathParams.isNotEmpty())  { sectionLabel("PATH");  pathParams.forEach  { paramRow(it) } }
        if (queryParams.isNotEmpty()) { sectionLabel("QUERY"); queryParams.forEach { paramRow(it) } }

        paramsPanel.add(JPanel().also { it.isOpaque = false }, GridBagConstraints().also {
            it.gridx = 0; it.gridy = row; it.weighty = 1.0; it.gridwidth = 3
            it.fill = GridBagConstraints.VERTICAL
        })

        paramsPanel.revalidate()
        paramsPanel.repaint()
    }

    private fun updateComputedUrl() {
        // Persist base URL on every change — state is saved on project close
        stateService.baseUrl = baseUrlField.text

        val endpoint = currentEndpoint ?: run { computedUrlField.text = ""; return }
        val base = baseUrlField.text.trimEnd('/')
        var route = endpoint.route

        endpoint.parameters.filter { it.source == ParameterSource.PATH }.forEach { param ->
            val v = paramFields[param.name]?.text ?: ""
            val pattern = Regex("\\{${Regex.escape(param.name)}(?::[^}]*)??\\??\\}")
            route = pattern.replace(route) { if (v.isEmpty()) it.value else v }
        }

        val query = endpoint.parameters
            .filter { it.source == ParameterSource.QUERY }
            .mapNotNull { param ->
                val v = paramFields[param.name]?.text ?: ""
                if (v.isEmpty()) null
                else "${URLEncoder.encode(param.name, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }

        computedUrlField.text = base + route + if (query.isEmpty()) "" else "?" + query.joinToString("&")
    }

    private fun buildHeadersTemplate(endpoint: ApiEndpoint): String {
        val lines = mutableListOf<String>()
        if (endpoint.auth?.required == true) lines.add("Authorization: Bearer <token>")
        endpoint.parameters.filter { it.source == ParameterSource.HEADER }.forEach { lines.add("${it.name}: ") }
        return lines.joinToString("\n")
    }

    private fun buildBodyTemplate(endpoint: ApiEndpoint): String {
        val bodyParam = endpoint.parameters.firstOrNull { it.source == ParameterSource.BODY } ?: return ""
        val schema = bodyParam.schema
        if (schema != null && schema.properties.isNotEmpty()) {
            val fields = schema.properties.joinToString(",\n  ") { prop ->
                when {
                    prop.type.lowercase().contains("string") -> "\"${prop.name}\": \"\""
                    prop.type.lowercase() in listOf("int", "long", "double", "float", "decimal") -> "\"${prop.name}\": 0"
                    prop.type.lowercase() == "bool" || prop.type.lowercase() == "boolean" -> "\"${prop.name}\": false"
                    else -> "\"${prop.name}\": null"
                }
            }
            return "{\n  $fields\n}"
        }
        return "// ${bodyParam.type}\n{\n  \n}"
    }

    private fun saveRequest() {
        val endpoint = currentEndpoint ?: return
        val paramValues = paramFields.entries
            .filter { it.value.text.isNotEmpty() }
            .associate { it.key to it.value.text }

        val req = RouteXStateService.SavedRequest().also {
            it.headers = headersArea.text
            it.body = bodyArea.text
            it.paramValues = paramValues
        }
        stateService.setSavedRequest(endpoint.id, req)

        statusLabel.foreground = JBColor.GRAY
        statusLabel.text = "Saved"
    }

    private fun sendRequest() {
        val endpoint = currentEndpoint ?: return
        val rawUrl = computedUrlField.text.trim()
        if (rawUrl.isEmpty()) return

        saveRequest() // auto-save on send

        sendButton.isEnabled = false
        statusLabel.foreground = JBColor.GRAY
        statusLabel.text = "Sending…"

        val headersSnapshot = headersArea.text
        val bodySnapshot = bodyArea.text.trim()

        object : SwingWorker<Triple<Int, String, Long>, Unit>() {
            override fun doInBackground(): Triple<Int, String, Long> {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                val builder = HttpRequest.newBuilder()
                    .uri(URI.create(rawUrl))
                    .timeout(Duration.ofSeconds(30))

                headersSnapshot.lines().filter { it.contains(":") }.forEach { line ->
                    val idx = line.indexOf(':')
                    val name = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    if (name.isNotEmpty()) runCatching { builder.header(name, value) }
                }

                when (endpoint.httpMethod.name) {
                    "GET", "HEAD" -> builder.GET()
                    "DELETE"      -> builder.DELETE()
                    "POST"        -> builder.POST(HttpRequest.BodyPublishers.ofString(bodySnapshot))
                    "PUT"         -> builder.PUT(HttpRequest.BodyPublishers.ofString(bodySnapshot))
                    else          -> builder.method(
                        endpoint.httpMethod.name,
                        if (bodySnapshot.isEmpty()) HttpRequest.BodyPublishers.noBody()
                        else HttpRequest.BodyPublishers.ofString(bodySnapshot)
                    )
                }

                val start = System.currentTimeMillis()
                val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                return Triple(response.statusCode(), response.body(), System.currentTimeMillis() - start)
            }

            override fun done() {
                sendButton.isEnabled = true
                runCatching {
                    val (status, body, duration) = get()
                    statusLabel.foreground = statusColor(status)
                    statusLabel.text = "$status — ${duration}ms"
                    onResponseReceived?.invoke(status, body, duration)
                }.onFailure { e ->
                    statusLabel.foreground = JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
                    statusLabel.text = "Error: ${e.message}"
                    onResponseReceived?.invoke(0, "Error: ${e.message}", 0)
                }
            }
        }.execute()
    }

    private fun statusColor(code: Int): Color = when {
        code in 200..299 -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x44, 0xCC, 0x77))
        code in 300..399 -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xAA, 0x33))
        code in 400..499 -> JBColor(Color(0xCC, 0x44, 0x00), Color(0xFF, 0x66, 0x33))
        else             -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
    }
}
