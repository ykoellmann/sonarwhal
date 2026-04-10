package com.routex.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.routex.RouteXStateService
import com.routex.model.Environment
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.DefaultListModel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

class EnvironmentSettingsPanel(private val stateService: RouteXStateService) : JPanel(BorderLayout(8, 0)) {

    // Working copy — mutated locally, committed to state only on OK
    private val envs: MutableList<Environment> = stateService.getEnvironments()
        .map { it.copy(variables = LinkedHashMap(it.variables)) }
        .toMutableList()

    private val envListModel = DefaultListModel<String>()
    private val envList = JBList(envListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private val varTableModel = object : DefaultTableModel(arrayOf("Variable", "Value"), 0) {
        override fun isCellEditable(row: Int, col: Int) = true
    }
    private val varTable = JBTable(varTableModel).apply {
        putClientProperty("terminateEditOnFocusLost", true)
        tableHeader.reorderingAllowed = false
    }

    private var selectedEnvIdx: Int = -1
    private var isInitializing = true

    init {
        envs.forEach { envListModel.addElement(it.name) }

        envList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                if (!isInitializing) saveCurrentVarsToEnv()
                selectedEnvIdx = envList.selectedIndex
                loadEnvVars()
            }
        }

        if (envs.isNotEmpty()) {
            selectedEnvIdx = 0
            envList.selectedIndex = 0
        }
        loadEnvVars()
        isInitializing = false

        add(buildEnvListPanel(), BorderLayout.WEST)
        add(buildVarTablePanel(), BorderLayout.CENTER)
    }

    private fun buildEnvListPanel(): JPanel {
        val decorator = ToolbarDecorator.createDecorator(envList)
            .setAddAction {
                val name = JOptionPane.showInputDialog(
                    envList, "Environment name:", "New Environment", JOptionPane.PLAIN_MESSAGE
                )?.trim() ?: return@setAddAction
                if (name.isEmpty()) return@setAddAction
                if (!isInitializing) saveCurrentVarsToEnv()
                val env = Environment(name = name)
                envs.add(env)
                envListModel.addElement(env.name)
                envList.selectedIndex = envs.size - 1
            }
            .setRemoveAction {
                val idx = envList.selectedIndex.takeIf { it >= 0 } ?: return@setRemoveAction
                envs.removeAt(idx)
                envListModel.removeElementAt(idx)
                selectedEnvIdx = -1
                clearVarTable()
                if (envs.isNotEmpty()) {
                    val newIdx = (idx - 1).coerceAtLeast(0)
                    envList.selectedIndex = newIdx
                }
            }
            .disableUpDownActions()

        val panel = JPanel(BorderLayout(0, 4))
        panel.border = JBUI.Borders.empty(0, 0, 0, 4)
        panel.add(sectionLabel("Environments"), BorderLayout.NORTH)
        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        panel.preferredSize = JBUI.size(180, 300)
        return panel
    }

    private fun buildVarTablePanel(): JPanel {
        val decorator = ToolbarDecorator.createDecorator(varTable)
            .setAddAction {
                varTableModel.addRow(arrayOf("", ""))
                val lastRow = varTableModel.rowCount - 1
                varTable.editCellAt(lastRow, 0)
                varTable.changeSelection(lastRow, 0, false, false)
            }
            .setRemoveAction {
                val row = varTable.selectedRow.takeIf { it >= 0 } ?: return@setRemoveAction
                if (varTable.isEditing) varTable.cellEditor?.stopCellEditing()
                varTableModel.removeRow(row)
            }
            .disableUpDownActions()

        val panel = JPanel(BorderLayout(0, 4))
        panel.add(sectionLabel("Variables  (use {{variableName}} in URLs, headers, and body)"), BorderLayout.NORTH)
        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        return panel
    }

    private fun sectionLabel(text: String) = JBLabel(text).apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(Font.PLAIN, 11f)
        border = JBUI.Borders.emptyBottom(2)
    }

    private fun loadEnvVars() {
        clearVarTable()
        val env = envs.getOrNull(selectedEnvIdx) ?: return
        env.variables.forEach { (k, v) -> varTableModel.addRow(arrayOf(k, v)) }
    }

    private fun clearVarTable() {
        if (varTable.isEditing) varTable.cellEditor?.stopCellEditing()
        varTableModel.rowCount = 0
    }

    private fun saveCurrentVarsToEnv() {
        val env = envs.getOrNull(selectedEnvIdx) ?: return
        if (varTable.isEditing) varTable.cellEditor?.stopCellEditing()
        val vars = LinkedHashMap<String, String>()
        for (row in 0 until varTableModel.rowCount) {
            val key = (varTableModel.getValueAt(row, 0) as? String)?.trim() ?: continue
            val value = (varTableModel.getValueAt(row, 1) as? String) ?: ""
            if (key.isNotEmpty()) vars[key] = value
        }
        envs[selectedEnvIdx] = env.copy(variables = vars)
    }

    /** Persist all changes to state. Call from the dialog's OK action. */
    fun commit() {
        saveCurrentVarsToEnv()

        val newIds = envs.map { it.id }.toSet()
        stateService.getEnvironments()
            .filter { it.id !in newIds }
            .forEach { stateService.removeEnvironment(it.id) }

        envs.forEach { stateService.upsertEnvironment(it) }

        val activeId = stateService.getActiveEnvironment()?.id
        if (activeId != null && activeId !in newIds) {
            stateService.setActiveEnvironment("")
        }
    }
}
