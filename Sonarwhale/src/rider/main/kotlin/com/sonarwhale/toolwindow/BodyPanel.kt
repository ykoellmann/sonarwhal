package com.sonarwhale.toolwindow

import com.intellij.lang.Language
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.JRadioButton
import com.intellij.openapi.editor.EditorFactory


sealed class BodyContent {
    object None : BodyContent()
    data class FormData(val rows: List<NameValueRow>) : BodyContent()
    data class Raw(val text: String, val contentType: String) : BodyContent()
    data class Binary(val filePath: String) : BodyContent()
}

/**
 * Body tab panel with radio strip (none / form-data / raw / binary) and a
 * CardLayout that shows the appropriate editor for each mode.
 */
class BodyPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        private const val CARD_NONE = "none"
        private const val CARD_FORM = "form-data"
        private const val CARD_RAW = "raw"
        private const val CARD_BINARY = "binary"
    }

    private val changeListeners = mutableListOf<() -> Unit>()

    // --- Radio buttons ---
    private val radioNone = JRadioButton("none", true)
    private val radioForm = JRadioButton("form-data")
    private val radioRaw = JRadioButton("raw")
    private val radioBinary = JRadioButton("binary")

    // --- Content type dropdown for raw mode ---
    private val contentTypeCombo = JComboBox(arrayOf("application/json", "application/xml", "text/html", "text/plain"))

    // --- Cards ---
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    private val formPanel = ParamsTablePanel()

    private val jsonEditor = makeEditor("JSON")
    private val xmlEditor = makeEditor("XML")
    private val textEditor = makeEditor("TEXT")

    private val rawCardLayout = CardLayout()
    private val rawCard = JPanel(rawCardLayout)

    private var binaryPath = ""
    private val binaryPathLabel = JBLabel("No file selected").apply { foreground = com.intellij.ui.JBColor.GRAY }

    init {
        ButtonGroup().apply {
            add(radioNone); add(radioForm); add(radioRaw); add(radioBinary)
        }

        // Build raw sub-card
        rawCard.add(jsonEditor, "application/json")
        rawCard.add(xmlEditor, "application/xml")
        rawCard.add(xmlEditor, "text/html")   // reuse xml-ish editor
        rawCard.add(textEditor, "text/plain")

        // Build binary card
        val binaryCard = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(4, 0, 4, 0)
            val btn = JButton(object : AbstractAction("Choose file…") {
                override fun actionPerformed(e: ActionEvent) {
                    val fc = JFileChooser()
                    if (fc.showOpenDialog(this@BodyPanel) == JFileChooser.APPROVE_OPTION) {
                        binaryPath = fc.selectedFile.absolutePath
                        binaryPathLabel.text = fc.selectedFile.name
                        fireChangeListeners()
                    }
                }
            })
            add(btn, BorderLayout.WEST)
            add(binaryPathLabel, BorderLayout.CENTER)
        }

        cardPanel.add(JPanel().apply { border = JBUI.Borders.empty(4, 0, 0, 0); add(JBLabel("No body")) }, CARD_NONE)
        cardPanel.add(formPanel, CARD_FORM)
        cardPanel.add(rawCard, CARD_RAW)
        cardPanel.add(binaryCard, CARD_BINARY)

        add(buildRadioStrip(), BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)

        // Wire radio buttons
        radioNone.addActionListener   { cardLayout.show(cardPanel, CARD_NONE);   fireChangeListeners() }
        radioForm.addActionListener   { cardLayout.show(cardPanel, CARD_FORM);   fireChangeListeners() }
        radioRaw.addActionListener    { cardLayout.show(cardPanel, CARD_RAW);    fireChangeListeners() }
        radioBinary.addActionListener { cardLayout.show(cardPanel, CARD_BINARY); fireChangeListeners() }

        contentTypeCombo.addActionListener {
            rawCardLayout.show(rawCard, contentTypeCombo.selectedItem as String)
            fireChangeListeners()
        }

        formPanel.addChangeListener { fireChangeListeners() }
    }

    private fun buildRadioStrip(): JPanel {
        // FlowLayout(hgap) adds hgap as left AND right outer margin — cancel it with inset=-hgap.
        val hgap = 4
        val strip = JPanel(FlowLayout(FlowLayout.LEFT, hgap, 2))
        strip.border = JBUI.Borders.compound(
            BorderFactory.createMatteBorder(0, 0, 1, 0, com.intellij.ui.JBColor.border()),
            JBUI.Borders.empty(0, -hgap, 0, 0)   // cancel FlowLayout's left outer margin
        )
        strip.add(radioNone)
        strip.add(radioForm)
        strip.add(radioRaw)
        strip.add(contentTypeCombo)
        strip.add(radioBinary)
        return strip
    }

    private fun makeEditor(langId: String): EditorTextField {
        val fileType = Language.findLanguageByID(langId)?.associatedFileType
            ?: PlainTextFileType.INSTANCE

        val document = EditorFactory.getInstance().createDocument("")

        return EditorTextField(document, project, fileType, false, false).apply {
            setOneLineMode(false)
            addSettingsProvider { editor ->
                (editor as? EditorEx)?.apply {
                    setVerticalScrollbarVisible(true)
                    setHorizontalScrollbarVisible(true)
                }
            }
        }
    }

    private fun currentEditor(): EditorTextField = when (contentTypeCombo.selectedItem as? String) {
        "application/xml", "text/html" -> xmlEditor
        "text/plain" -> textEditor
        else -> jsonEditor
    }

    fun getContent(): BodyContent = when {
        radioNone.isSelected -> BodyContent.None
        radioForm.isSelected -> BodyContent.FormData(formPanel.getRows())
        radioBinary.isSelected -> BodyContent.Binary(binaryPath)
        else -> BodyContent.Raw(currentEditor().text, contentTypeCombo.selectedItem as String)
    }

    fun setContent(content: BodyContent) {
        when (content) {
            is BodyContent.None -> {
                radioNone.isSelected = true
                cardLayout.show(cardPanel, CARD_NONE)
            }
            is BodyContent.FormData -> {
                radioForm.isSelected = true
                formPanel.setRows(content.rows)
                cardLayout.show(cardPanel, CARD_FORM)
            }
            is BodyContent.Raw -> {
                radioRaw.isSelected = true
                contentTypeCombo.selectedItem = content.contentType
                currentEditor().text = content.text
                rawCardLayout.show(rawCard, content.contentType)
                cardLayout.show(cardPanel, CARD_RAW)
            }
            is BodyContent.Binary -> {
                radioBinary.isSelected = true
                binaryPath = content.filePath
                binaryPathLabel.text = if (content.filePath.isNotEmpty()) File(content.filePath).name else "No file selected"
                cardLayout.show(cardPanel, CARD_BINARY)
            }
        }
    }

    fun addChangeListener(l: () -> Unit) { changeListeners += l }
    private fun fireChangeListeners() = changeListeners.forEach { it() }

    /** Returns the raw text for persistence (only meaningful in Raw mode). */
    fun getRawText(): String = currentEditor().text
    fun getRawContentType(): String = contentTypeCombo.selectedItem as? String ?: "application/json"
    fun getActiveMode(): String = when {
        radioNone.isSelected -> "none"
        radioForm.isSelected -> "form-data"
        radioBinary.isSelected -> "binary"
        else -> "raw"
    }
    fun setActiveMode(mode: String) {
        when (mode) {
            "form-data" -> { radioForm.isSelected = true; cardLayout.show(cardPanel, CARD_FORM) }
            "binary"    -> { radioBinary.isSelected = true; cardLayout.show(cardPanel, CARD_BINARY) }
            "raw"       -> { radioRaw.isSelected = true; cardLayout.show(cardPanel, CARD_RAW) }
            else        -> { radioNone.isSelected = true; cardLayout.show(cardPanel, CARD_NONE) }
        }
    }
}
