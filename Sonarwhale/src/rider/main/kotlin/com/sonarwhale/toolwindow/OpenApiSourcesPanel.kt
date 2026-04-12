package com.sonarwhale.toolwindow

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.model.EnvironmentSource
import com.sonarwhale.model.SonarwhaleEnvironment
import com.sonarwhale.service.EnvironmentService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.UUID
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel

/**
 * Settings panel to manage OpenAPI source environments (SonarwhaleEnvironment).
 * Appears in the Sonarwhale Settings dialog under "Sources".
 */
class OpenApiSourcesPanel(
    private val project: Project,
    private val service: EnvironmentService
) : JPanel(BorderLayout(8, 0)) {

    // Working copy — committed to service only on dialog OK
    private val envs: MutableList<SonarwhaleEnvironment> = service.getAll().toMutableList()

    private val listModel = DefaultListModel<String>()
    private val envList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    // Detail fields
    private val nameField = JTextField()

    private val radioServer = JRadioButton("Server URL")
    private val radioFile   = JRadioButton("File path")
    private val radioStatic = JRadioButton("Static JSON")
    private val sourceGroup = ButtonGroup().also {
        it.add(radioServer); it.add(radioFile); it.add(radioStatic)
    }

    private val sourceCardLayout = CardLayout()
    private val sourceCards = JPanel(sourceCardLayout)

    // Card: Server URL
    private val hostField    = JTextField("http://localhost")
    private val portSpinner  = JSpinner(SpinnerNumberModel(5000, 1, 65535, 1))
    private val pathField    = JTextField().also { it.toolTipText = "Leave empty for auto-discovery" }

    // Card: File path
    private val filePathField = TextFieldWithBrowseButton().also { btn ->
        btn.addBrowseFolderListener(
            "Select OpenAPI file", "Choose a swagger.json or openapi.yaml file",
            project,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withFileFilter { it.extension?.lowercase() in listOf("json", "yaml", "yml") }
        )
    }

    // Card: Static JSON
    private val staticArea = JTextArea(10, 40).also {
        it.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        it.lineWrap = false
        it.toolTipText = "Paste your OpenAPI JSON here"
    }

    private var selectedIdx: Int = -1
    private var suppressListener = false

    init {
        envs.forEach { listModel.addElement(it.name) }

        buildSourceCards()

        envList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !suppressListener) {
                saveCurrentToEnv()
                selectedIdx = envList.selectedIndex
                loadEnv()
            }
        }

        if (envs.isNotEmpty()) {
            selectedIdx = 0
            envList.selectedIndex = 0
            loadEnv()
        }

        add(buildListPanel(), BorderLayout.WEST)
        add(buildDetailPanel(), BorderLayout.CENTER)
    }

    // ── List panel (left side) ────────────────────────────────────────────────

    private fun buildListPanel(): JPanel {
        val decorator = ToolbarDecorator.createDecorator(envList)
            .setAddAction {
                val name = JOptionPane.showInputDialog(
                    envList, "Environment name:", "New Source", JOptionPane.PLAIN_MESSAGE
                )?.trim() ?: return@setAddAction
                if (name.isEmpty()) return@setAddAction
                saveCurrentToEnv()
                val env = SonarwhaleEnvironment(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    source = EnvironmentSource.ServerUrl(host = "http://localhost", port = 5000)
                )
                envs.add(env)
                listModel.addElement(env.name)
                suppressListener = true
                envList.selectedIndex = envs.size - 1
                suppressListener = false
                selectedIdx = envs.size - 1
                loadEnv()
            }
            .setRemoveAction {
                val idx = envList.selectedIndex.takeIf { it >= 0 } ?: return@setRemoveAction
                envs.removeAt(idx)
                listModel.removeElementAt(idx)
                selectedIdx = -1
                clearDetail()
                if (envs.isNotEmpty()) {
                    val newIdx = (idx - 1).coerceAtLeast(0)
                    suppressListener = true
                    envList.selectedIndex = newIdx
                    suppressListener = false
                    selectedIdx = newIdx
                    loadEnv()
                }
            }
            .disableUpDownActions()

        val panel = JPanel(BorderLayout(0, 4))
        panel.border = JBUI.Borders.empty(0, 0, 0, 0)
        panel.add(sectionLabel("Sources"), BorderLayout.NORTH)
        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        panel.preferredSize = JBUI.size(160, 360)
        return panel
    }

    // ── Detail panel (right side) ─────────────────────────────────────────────

    private fun buildDetailPanel(): JPanel {
        val gbc = GridBagConstraints().also {
            it.fill = GridBagConstraints.HORIZONTAL; it.anchor = GridBagConstraints.WEST
            it.insets = Insets(3, 4, 3, 4)
        }

        val form = JPanel(GridBagLayout())
        form.border = JBUI.Borders.empty(4, 8)

        // Name row
        gbc.gridy = 0; gbc.gridx = 0; gbc.weightx = 0.0
        form.add(JBLabel("Name:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2
        form.add(nameField, gbc)
        gbc.gridwidth = 1

        // Source type radios
        gbc.gridy = 1; gbc.gridx = 0; gbc.weightx = 0.0
        form.add(JBLabel("Source:"), gbc)
        val radioPanel = JPanel().also { p ->
            p.isOpaque = false
            p.add(radioServer); p.add(radioFile); p.add(radioStatic)
        }
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2
        form.add(radioPanel, gbc)
        gbc.gridwidth = 1

        // Source-specific cards
        gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 3; gbc.weightx = 1.0
        form.add(sourceCards, gbc)

        // Radio listeners to swap cards
        radioServer.addActionListener { sourceCardLayout.show(sourceCards, "server") }
        radioFile.addActionListener   { sourceCardLayout.show(sourceCards, "file") }
        radioStatic.addActionListener { sourceCardLayout.show(sourceCards, "static") }
        radioServer.isSelected = true

        val panel = JPanel(BorderLayout(0, 0))
        panel.border = JBUI.Borders.customLineLeft(JBColor.border())
        panel.add(sectionLabel("Configuration").also {
            it.border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()),
                JBUI.Borders.empty(6, 8)
            )
        }, BorderLayout.NORTH)
        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    private fun buildSourceCards() {
        // Card: Server URL
        val serverPanel = JPanel(GridBagLayout())
        serverPanel.border = JBUI.Borders.empty(6, 0)
        val sg = GridBagConstraints().also {
            it.fill = GridBagConstraints.HORIZONTAL; it.insets = Insets(2, 4, 2, 4)
        }
        sg.gridy = 0; sg.gridx = 0; sg.weightx = 0.0; serverPanel.add(JBLabel("Host:"), sg)
        sg.gridx = 1; sg.weightx = 1.0; serverPanel.add(hostField, sg)
        sg.gridy = 1; sg.gridx = 0; sg.weightx = 0.0; serverPanel.add(JBLabel("Port:"), sg)
        sg.gridx = 1; sg.weightx = 0.3
        portSpinner.preferredSize = JBUI.size(80, portSpinner.preferredSize.height)
        serverPanel.add(portSpinner, sg)
        sg.gridy = 2; sg.gridx = 0; sg.weightx = 0.0
        serverPanel.add(JBLabel("OpenAPI path:"), sg)
        sg.gridx = 1; sg.weightx = 1.0; serverPanel.add(pathField, sg)
        sg.gridy = 3; sg.gridx = 0; sg.gridwidth = 2; sg.weightx = 1.0
        serverPanel.add(JBLabel("Leave path empty for auto-discovery (tries /swagger/v1/swagger.json etc.)").apply {
            foreground = JBColor.GRAY; font = font.deriveFont(10f)
        }, sg)

        // Card: File path
        val filePanel = JPanel(GridBagLayout())
        filePanel.border = JBUI.Borders.empty(6, 0)
        val fg = GridBagConstraints().also {
            it.fill = GridBagConstraints.HORIZONTAL; it.insets = Insets(2, 4, 2, 4)
        }
        fg.gridy = 0; fg.gridx = 0; fg.weightx = 0.0; filePanel.add(JBLabel("File:"), fg)
        fg.gridx = 1; fg.weightx = 1.0; filePanel.add(filePathField, fg)
        fg.gridy = 1; fg.gridx = 0; fg.gridwidth = 2
        filePanel.add(JBLabel("Path to a local swagger.json or openapi.yaml file.").apply {
            foreground = JBColor.GRAY; font = font.deriveFont(10f)
        }, fg)

        // Card: Static JSON
        val staticPanel = JPanel(BorderLayout(0, 4))
        staticPanel.border = JBUI.Borders.empty(6, 4)
        staticPanel.add(JBLabel("Paste OpenAPI JSON:").apply {
            foreground = JBColor.GRAY; font = font.deriveFont(10f)
        }, BorderLayout.NORTH)
        staticPanel.add(JBScrollPane(staticArea).also {
            it.preferredSize = JBUI.size(400, 160)
        }, BorderLayout.CENTER)

        sourceCards.add(serverPanel, "server")
        sourceCards.add(filePanel,   "file")
        sourceCards.add(staticPanel, "static")
    }

    // ── Load / Save helpers ───────────────────────────────────────────────────

    private fun loadEnv() {
        val env = envs.getOrNull(selectedIdx) ?: run { clearDetail(); return }
        suppressListener = true
        nameField.text = env.name
        when (val s = env.source) {
            is EnvironmentSource.ServerUrl -> {
                radioServer.isSelected = true
                sourceCardLayout.show(sourceCards, "server")
                hostField.text   = s.host
                portSpinner.value = s.port
                pathField.text   = s.openApiPath ?: ""
            }
            is EnvironmentSource.FilePath -> {
                radioFile.isSelected = true
                sourceCardLayout.show(sourceCards, "file")
                filePathField.text = s.path
            }
            is EnvironmentSource.StaticImport -> {
                radioStatic.isSelected = true
                sourceCardLayout.show(sourceCards, "static")
                staticArea.text = s.cachedContent
            }
        }
        suppressListener = false
    }

    private fun clearDetail() {
        nameField.text    = ""
        hostField.text    = "http://localhost"
        portSpinner.value = 5000
        pathField.text    = ""
        filePathField.text = ""
        staticArea.text   = ""
        radioServer.isSelected = true
        sourceCardLayout.show(sourceCards, "server")
    }

    private fun saveCurrentToEnv() {
        val env = envs.getOrNull(selectedIdx) ?: return
        val name = nameField.text.trim().ifEmpty { env.name }
        val source: EnvironmentSource = when {
            radioServer.isSelected -> EnvironmentSource.ServerUrl(
                host = hostField.text.trim().ifEmpty { "http://localhost" },
                port = (portSpinner.value as Int),
                openApiPath = pathField.text.trim().ifEmpty { null }
            )
            radioFile.isSelected -> EnvironmentSource.FilePath(
                path = filePathField.text.trim()
            )
            else -> EnvironmentSource.StaticImport(
                cachedContent = staticArea.text.trim()
            )
        }
        val updated = env.copy(name = name, source = source)
        envs[selectedIdx] = updated
        // Update list label if name changed
        if (listModel.getElementAt(selectedIdx) != name) {
            listModel.setElementAt(name, selectedIdx)
        }
    }

    /** Persist all changes to the service. Call from the dialog's OK action. */
    fun commit() {
        saveCurrentToEnv()

        val newIds = envs.map { it.id }.toSet()
        service.getAll()
            .filter { it.id !in newIds }
            .forEach { service.remove(it.id) }

        // Preserve active flag
        val activeId = service.getActive()?.id
        envs.forEach { env ->
            val existing = service.getAll().firstOrNull { it.id == env.id }
            val withActive = env.copy(isActive = env.id == activeId || (activeId == null && existing == null && env == envs.first()))
            if (existing == null) service.add(withActive)
            else service.update(withActive)
        }

        // Ensure exactly one is active
        if (service.getActive() == null && service.getAll().isNotEmpty()) {
            service.setActive(service.getAll().first().id)
        }
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private fun sectionLabel(text: String) = JBLabel(text).apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(Font.PLAIN, 11f)
        border = JBUI.Borders.emptyBottom(2)
    }
}
