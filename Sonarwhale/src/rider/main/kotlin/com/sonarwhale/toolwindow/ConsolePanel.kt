package com.sonarwhale.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.script.ConsoleEntry
import com.sonarwhale.script.LogLevel
import com.sonarwhale.script.ScriptPhase
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ConsolePanel : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().also {
        it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
        it.border = JBUI.Borders.empty(4)
    }
    private val scroll = JBScrollPane(contentPanel)
    private val clearButton = JButton("Clear").apply { font = font.deriveFont(10f) }
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS")

    init {
        val header = JPanel(BorderLayout(4, 0))
        header.border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(3, 8)
        )
        header.add(JBLabel("Console").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor.GRAY
        }, BorderLayout.WEST)
        header.add(clearButton, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        clearButton.addActionListener { showEntries(emptyList()) }
    }

    fun showEntries(entries: List<ConsoleEntry>) {
        contentPanel.removeAll()
        for (entry in entries) {
            contentPanel.add(buildRow(entry))
        }
        contentPanel.revalidate()
        contentPanel.repaint()
        SwingUtilities.invokeLater {
            val vb = scroll.verticalScrollBar
            vb.value = vb.maximum
        }
    }

    private fun buildRow(entry: ConsoleEntry): java.awt.Component = when (entry) {
        is ConsoleEntry.ScriptBoundary -> buildBoundaryRow(entry)
        is ConsoleEntry.LogEntry       -> buildLogRow(entry)
        is ConsoleEntry.ErrorEntry     -> buildErrorRow(entry)
        is ConsoleEntry.HttpEntry      -> buildHttpRow(entry)
    }

    // ── Row builders ──────────────────────────────────────────────────────────

    private fun buildBoundaryRow(entry: ConsoleEntry.ScriptBoundary): JTextArea {
        val phase = if (entry.phase == ScriptPhase.PRE) "pre" else "post"
        val name  = entry.scriptPath.substringAfterLast('/').substringAfterLast('\\')
        return textRow("▶  $name [$phase]", JBColor.GRAY, italic = true)
    }

    private fun buildLogRow(entry: ConsoleEntry.LogEntry): JTextArea {
        val color = when (entry.level) {
            LogLevel.LOG   -> JBColor.foreground()
            LogLevel.WARN  -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xBB, 0x33))
            LogLevel.ERROR -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        }
        val prefix = when (entry.level) {
            LogLevel.LOG   -> ""
            LogLevel.WARN  -> "⚠  "
            LogLevel.ERROR -> "✕  "
        }
        val time = timeFmt.format(Date(entry.timestampMs))
        return textRow("$time  $prefix${entry.message}", color)
    }

    private fun buildErrorRow(entry: ConsoleEntry.ErrorEntry): JTextArea {
        val name = entry.scriptPath.substringAfterLast('/').substringAfterLast('\\')
        return textRow("✕  $name: ${entry.message}",
            JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x55, 0x55))).also {
            it.background = JBColor(Color(0xFF, 0xEE, 0xEE), Color(0x55, 0x22, 0x22))
            it.isOpaque = true
        }
    }

    private fun buildHttpRow(entry: ConsoleEntry.HttpEntry): JPanel {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false

        val statusColor = when {
            entry.status in 200..299 -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x44, 0xCC, 0x77))
            entry.status == 0        -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
            entry.status in 400..499 -> JBColor(Color(0xCC, 0x44, 0x00), Color(0xFF, 0x77, 0x33))
            else                     -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xBB, 0x33))
        }
        val statusText = if (entry.status == 0) "ERROR" else "${entry.status}"
        val summary = textRow(
            "→  ${entry.method}  ${entry.url}  ·  $statusText  ·  ${entry.durationMs}ms",
            statusColor
        )

        val details = buildHttpDetails(entry)
        details.isVisible = false

        summary.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        summary.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                details.isVisible = !details.isVisible
                container.revalidate()
                container.repaint()
            }
        })

        container.add(summary)
        container.add(details)
        return container
    }

    private fun buildHttpDetails(entry: ConsoleEntry.HttpEntry): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.emptyLeft(16)
        panel.isOpaque = false

        fun section(title: String, content: String) {
            if (content.isBlank()) return
            panel.add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 10f)
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyTop(4)
            })
            val area = JTextArea(content).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                lineWrap = true
                wrapStyleWord = false
                border = JBUI.Borders.empty(2, 4)
                background = JBColor.background()
            }
            panel.add(area)
        }

        val reqHeadersText = entry.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        section("Request Headers", reqHeadersText)
        section("Request Body", entry.requestBody ?: "")

        if (entry.error != null) {
            section("Error", entry.error)
        } else {
            val respHeadersText = entry.responseHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            section("Response Headers", respHeadersText)
            section("Response Body", entry.responseBody.take(2000))
        }

        return panel
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun textRow(text: String, fg: java.awt.Color, italic: Boolean = false): JTextArea =
        JTextArea(text).apply {
            isEditable    = false
            isOpaque      = false
            lineWrap      = true
            wrapStyleWord = true
            foreground    = fg
            font          = if (italic)
                Font(Font.MONOSPACED, Font.ITALIC, 11)
            else
                Font(Font.MONOSPACED, Font.PLAIN, 11)
            border        = JBUI.Borders.empty(1, 4)
        }
}
