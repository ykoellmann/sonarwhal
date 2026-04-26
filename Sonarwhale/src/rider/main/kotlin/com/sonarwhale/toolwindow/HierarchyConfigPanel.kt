package com.sonarwhale.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.model.HierarchyConfig
import com.sonarwhale.model.VariableEntry
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

/**
 * Tabbed config panel shown when a hierarchy node (Global, Collection, Controller,
 * Endpoint, or SavedRequest) is selected. Tabs: Variables, Auth, Scripts.
 *
 * [onSave] is called whenever the user modifies config. Caller persists the result.
 */
class HierarchyConfigPanel(
    private val project: Project,
    private var config: HierarchyConfig,
    private val onSave: (HierarchyConfig) -> Unit
) : JPanel(BorderLayout()) {

    private val tabs = CollapsibleTabPane()
    private val variablesPanel = VariablesTablePanel()

    init {
        variablesPanel.setVariables(config.variables)
        variablesPanel.onChange = { updated ->
            config = config.copy(variables = updated)
            onSave(config)
        }

        tabs.addTab("Variables", JBScrollPane(variablesPanel))
        // Auth and Scripts tabs added in Task 15
        add(tabs, BorderLayout.CENTER)
    }

    fun setConfig(newConfig: HierarchyConfig) {
        config = newConfig
        variablesPanel.setVariables(newConfig.variables)
    }
}

// ── Variables table ───────────────────────────────────────────────────────────

private class VariablesTablePanel : JPanel(BorderLayout()) {

    var onChange: ((List<VariableEntry>) -> Unit)? = null
    private val tableModel = VariablesTableModel()

    init {
        val table = com.intellij.ui.table.JBTable(tableModel).apply {
            setShowGrid(false)
            rowHeight = 22
            columnModel.getColumn(0).preferredWidth = 30   // enabled checkbox
            columnModel.getColumn(1).preferredWidth = 140  // key
            columnModel.getColumn(2).preferredWidth = 200  // value
        }

        tableModel.addTableModelListener {
            onChange?.invoke(tableModel.getVariables())
        }

        val addBtn = JButton(com.intellij.icons.AllIcons.General.Add).apply {
            isBorderPainted = false; isContentAreaFilled = false
            addActionListener {
                tableModel.addRow()
                table.editCellAt(tableModel.rowCount - 1, 1)
            }
        }
        val removeBtn = JButton(com.intellij.icons.AllIcons.General.Remove).apply {
            isBorderPainted = false; isContentAreaFilled = false
            addActionListener {
                val row = table.selectedRow
                if (row >= 0) tableModel.removeRow(row)
            }
        }

        val toolbar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0))
        toolbar.add(addBtn); toolbar.add(removeBtn)
        toolbar.border = JBUI.Borders.customLineBottom(JBColor.border())

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun setVariables(vars: List<VariableEntry>) = tableModel.setVariables(vars)
}

private class VariablesTableModel : AbstractTableModel() {
    private val rows = mutableListOf<VariableEntry>()
    private val cols = arrayOf("", "Key", "Value")

    fun setVariables(vars: List<VariableEntry>) {
        rows.clear(); rows.addAll(vars); fireTableDataChanged()
    }

    fun getVariables(): List<VariableEntry> = rows.toList()

    fun addRow() { rows.add(VariableEntry()); fireTableRowsInserted(rows.size - 1, rows.size - 1) }

    fun removeRow(idx: Int) { if (idx in rows.indices) { rows.removeAt(idx); fireTableRowsDeleted(idx, idx) } }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 3
    override fun getColumnName(col: Int) = cols[col]
    override fun isCellEditable(row: Int, col: Int) = true
    override fun getColumnClass(col: Int) = if (col == 0) Boolean::class.java else String::class.java

    override fun getValueAt(row: Int, col: Int): Any = when (col) {
        0 -> rows[row].enabled
        1 -> rows[row].key
        2 -> rows[row].value
        else -> ""
    }

    override fun setValueAt(value: Any?, row: Int, col: Int) {
        if (row !in rows.indices) return
        rows[row] = when (col) {
            0 -> rows[row].copy(enabled = value as Boolean)
            1 -> rows[row].copy(key = value as String)
            2 -> rows[row].copy(value = value as String)
            else -> rows[row]
        }
        fireTableCellUpdated(row, col)
    }
}
