package com.routex.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JPanel
import javax.swing.JTextArea

class ResponsePanel : JPanel(BorderLayout()) {

    private val statusLabel = JBLabel("No response yet")
    private val bodyArea = JTextArea()
    private val durationLabel = JBLabel("")

    init {
        val header = JPanel(GridBagLayout())
        header.border = JBUI.Borders.empty(8, 8, 4, 8)
        val gbc = GridBagConstraints()

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.WEST
        header.add(statusLabel, gbc)

        gbc.gridx = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST
        durationLabel.foreground = JBColor.GRAY
        durationLabel.font = durationLabel.font.deriveFont(11f)
        header.add(durationLabel, gbc)

        bodyArea.isEditable = false
        bodyArea.font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        bodyArea.border = JBUI.Borders.empty(8)

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(bodyArea), BorderLayout.CENTER)
    }

    fun showResponse(statusCode: Int, body: String, durationMs: Long) {
        statusLabel.text = "HTTP $statusCode"
        statusLabel.foreground = statusColor(statusCode)
        statusLabel.font = statusLabel.font.deriveFont(Font.BOLD, 13f)

        durationLabel.text = "${durationMs}ms"

        bodyArea.text = tryFormatJson(body)
        bodyArea.caretPosition = 0
    }

    fun clear() {
        statusLabel.text = "No response yet"
        statusLabel.foreground = JBColor.GRAY
        durationLabel.text = ""
        bodyArea.text = ""
    }

    private fun statusColor(code: Int): Color = when {
        code in 200..299 -> JBColor(Color(0x00, 0x99, 0x44), Color(0x44, 0xCC, 0x77))
        code in 300..399 -> JBColor(Color(0xCC, 0x66, 0x00), Color(0xFF, 0x99, 0x33))
        code in 400..499 -> JBColor(Color(0xCC, 0x44, 0x00), Color(0xFF, 0x66, 0x33))
        code >= 500 -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        else -> JBColor.GRAY
    }

    private fun tryFormatJson(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return text
        return runCatching { formatJson(trimmed) }.getOrDefault(text)
    }

    private fun formatJson(json: String): String {
        val sb = StringBuilder()
        var indent = 0
        var inString = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            when {
                c == '"' && (i == 0 || json[i - 1] != '\\') -> {
                    inString = !inString
                    sb.append(c)
                }
                inString -> sb.append(c)
                c == '{' || c == '[' -> {
                    sb.append(c)
                    indent++
                    sb.append('\n').append("  ".repeat(indent))
                }
                c == '}' || c == ']' -> {
                    indent--
                    sb.append('\n').append("  ".repeat(indent)).append(c)
                }
                c == ',' -> {
                    sb.append(c)
                    sb.append('\n').append("  ".repeat(indent))
                }
                c == ':' -> sb.append(": ")
                c == ' ' || c == '\n' || c == '\r' || c == '\t' -> { /* skip whitespace */ }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
