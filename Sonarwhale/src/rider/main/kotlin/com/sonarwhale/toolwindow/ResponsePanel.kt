package com.sonarwhale.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.script.ConsoleEntry
import com.sonarwhale.script.TestResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingWorker

class ResponsePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val statusLabel = JBLabel("Response").apply {
        font = font.deriveFont(Font.BOLD, 11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 0, 0, 12)
    }
    private val durationLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(11f)
    }
    private val openButton = JButton("Open in Editor").apply {
        font = font.deriveFont(10f)
        isVisible = false
        toolTipText = "Open response in a new editor tab with JSON highlighting"
    }
    private val collapseIcon = JBLabel(AllIcons.General.ArrowDown).apply {
        toolTipText = "Collapse response panel"
        border = JBUI.Borders.empty(0, 4, 0, 0)
    }
    private val bodyArea = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = JBUI.Borders.empty(8)
    }
    private val bodyScroll = JBScrollPane(bodyArea)
    private val tabs = CollapsibleTabPane()
    private val testsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }
    private val testsScroll = JBScrollPane(testsPanel)
    private val consolePanel = ConsolePanel()

    var onToggle: (() -> Unit)? = null
    var isContentVisible: Boolean = true
        private set

    private fun toggleCollapse() {
        isContentVisible = !isContentVisible
        tabs.isVisible = isContentVisible
        collapseIcon.icon = if (isContentVisible) AllIcons.General.ArrowDown else AllIcons.General.ArrowUp
        collapseIcon.toolTipText = if (isContentVisible) "Collapse response panel" else "Expand response panel"
        minimumSize = if (isContentVisible) null else java.awt.Dimension(0, 36)
        revalidate(); repaint()
        onToggle?.invoke()
    }

    init {
        val header = JPanel(GridBagLayout())
        header.border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBColor.border()),
            JBUI.Borders.empty(5, 8)
        )

        val gbc = GridBagConstraints()
        gbc.gridy = 0; gbc.anchor = GridBagConstraints.WEST; gbc.insets = Insets(0, 0, 0, 0)

        gbc.gridx = 0; gbc.weightx = 0.0
        header.add(statusLabel, gbc)

        gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
        val spacer = JPanel().also { it.isOpaque = false }
        header.add(spacer, gbc)

        gbc.gridx = 2; gbc.weightx = 0.0; gbc.fill = GridBagConstraints.NONE
        gbc.insets = Insets(0, 0, 0, 8)
        header.add(durationLabel, gbc)

        gbc.gridx = 3; gbc.insets = Insets(0, 0, 0, 0)
        header.add(openButton, gbc)

        gbc.gridx = 4; gbc.insets = Insets(0, 4, 0, 0)
        header.add(collapseIcon, gbc)

        add(header, BorderLayout.NORTH)
        tabs.addTab("Body", bodyScroll)
        tabs.addTab("Tests", testsScroll)
        tabs.addTab("Console", consolePanel)
        add(tabs, BorderLayout.CENTER)

        openButton.addActionListener { openInEditor() }

        val handCursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val clickHandler = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) = toggleCollapse()
        }
        // Make the entire header area clickable — attach to header and all non-button children.
        // openButton handles its own click and is intentionally excluded.
        listOf<java.awt.Component>(header, statusLabel, durationLabel, spacer, collapseIcon)
            .forEach { it.addMouseListener(clickHandler); it.cursor = handCursor }
    }

    fun showResponse(statusCode: Int, body: String, durationMs: Long) {
        if (statusCode == 0) {
            // Not an HTTP response — a connection/network error.
            statusLabel.text = "Error"
            statusLabel.foreground = JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
            durationLabel.text = ""
            bodyArea.text = body
            bodyArea.caretPosition = 0
            openButton.isVisible = false
            return
        }

        statusLabel.text = "HTTP $statusCode"
        statusLabel.foreground = statusColor(statusCode)
        durationLabel.text = "${durationMs}ms"
        bodyArea.text = "…"
        openButton.isVisible = false

        object : SwingWorker<String, Unit>() {
            override fun doInBackground(): String = tryFormatJson(body)
            override fun done() {
                val text = runCatching { get() }.getOrDefault(body)
                bodyArea.text = text
                bodyArea.caretPosition = 0
                openButton.isVisible = text.isNotEmpty()
            }
        }.execute()
    }

    fun clear() {
        statusLabel.text = "Response"
        statusLabel.foreground = JBColor.GRAY
        durationLabel.text = ""
        bodyArea.text = ""
        openButton.isVisible = false
        testsPanel.removeAll()
        tabs.setTitleAt(tabs.indexOfComponent(testsScroll), "Tests")
        consolePanel.showEntries(emptyList())
        tabs.setTitleAt(tabs.indexOfComponent(consolePanel), "Console")
        testsPanel.revalidate()
    }

    fun showTestResults(results: List<TestResult>) {
        testsPanel.removeAll()
        val testsIdx = tabs.indexOfComponent(testsScroll)
        if (results.isEmpty()) {
            tabs.setTitleAt(testsIdx, "Tests")
            testsPanel.revalidate()
            testsPanel.repaint()
            return
        }
        val passed = results.count { it.passed }
        tabs.setTitleAt(testsIdx, "Tests ($passed/${results.size})")

        for (result in results) {
            val row = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
                isOpaque = false
            }
            val icon = JBLabel(if (result.passed) "✓" else "✗").apply {
                foreground = if (result.passed)
                    JBColor(java.awt.Color(0x00, 0xAA, 0x55), java.awt.Color(0x44, 0xCC, 0x77))
                else
                    JBColor(java.awt.Color(0xCC, 0x00, 0x00), java.awt.Color(0xFF, 0x44, 0x44))
                font = font.deriveFont(Font.BOLD, 12f)
            }
            val name = JBLabel(result.name).apply { font = font.deriveFont(12f) }
            row.add(icon)
            row.add(name)
            if (!result.passed && result.error != null) {
                row.add(JBLabel("  ${result.error}").apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(Font.ITALIC, 11f)
                })
            }
            testsPanel.add(row)
        }

        testsPanel.revalidate()
        testsPanel.repaint()

        if (results.any { !it.passed }) {
            tabs.selectedIndex = tabs.indexOfComponent(testsScroll)
        }
    }

    fun showConsole(entries: List<ConsoleEntry>) {
        val consoleIdx = tabs.indexOfComponent(consolePanel)
        tabs.setTitleAt(consoleIdx, if (entries.isEmpty()) "Console" else "Console (${entries.size})")
        consolePanel.showEntries(entries)
        if (entries.isNotEmpty()) {
            tabs.selectedIndex = consoleIdx
        }
    }

    private fun openInEditor() {
        val content = bodyArea.text.ifEmpty { return }
        // Resolve JSON language at runtime — available in Rider without a compile-time dependency
        val lang = Language.findLanguageByID("JSON") ?: PlainTextLanguage.INSTANCE
        val scratch = ScratchRootType.getInstance()
            .createScratchFile(project, "sonarwhale-response.json", lang, content)
            ?: return
        FileEditorManager.getInstance(project).openFile(scratch, true)
    }

    private fun statusColor(code: Int): Color = when {
        code in 200..299 -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x44, 0xCC, 0x77))
        code in 300..399 -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xAA, 0x33))
        code in 400..499 -> JBColor(Color(0xCC, 0x44, 0x00), Color(0xFF, 0x66, 0x33))
        code >= 500      -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        else             -> JBColor.GRAY
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
                c == '"' && (i == 0 || json[i - 1] != '\\') -> { inString = !inString; sb.append(c) }
                inString -> sb.append(c)
                c == '{' || c == '[' -> { sb.append(c); indent++; sb.append('\n').append("  ".repeat(indent)) }
                c == '}' || c == ']' -> { indent--; sb.append('\n').append("  ".repeat(indent)).append(c) }
                c == ',' -> { sb.append(c); sb.append('\n').append("  ".repeat(indent)) }
                c == ':' -> sb.append(": ")
                c == ' ' || c == '\n' || c == '\r' || c == '\t' -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
