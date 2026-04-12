package com.sonarwhale.toolwindow

import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

data class NameValueRow(
    val enabled: Boolean,
    val key: String,
    val value: String,
    val description: String
)

/**
 * Reusable table panel used for both Params and Headers tabs.
 * Columns: ☑ · Key · Value · Description
 * Auto-appends a blank row when the last row's key field is edited.
 */
class ParamsTablePanel : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel(
        arrayOf<Array<Any>>(),
        arrayOf("", "Key", "Value", "Description")
    ) {
        override fun getColumnClass(col: Int) = if (col == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, col: Int) = col != 0
    }

    val table = JBTable(tableModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        columnModel.getColumn(0).preferredWidth = 28
        columnModel.getColumn(0).maxWidth = 28
        columnModel.getColumn(0).minWidth = 28
        columnModel.getColumn(1).preferredWidth = 160
        columnModel.getColumn(2).preferredWidth = 200
        // Description column gets remaining space
        tableHeader.reorderingAllowed = false
        rowHeight = JBUI.scale(24)
        putClientProperty("terminateEditOnFocusLost", true)
    }

    private val changeListeners = mutableListOf<() -> Unit>()

    init {
        // Single-click checkbox toggle — JBTable's default editor requires a double-click,
        // so we handle the boolean column directly via mouseClicked.
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (col == 0 && row in 0 until tableModel.rowCount) {
                    val current = tableModel.getValueAt(row, 0) as? Boolean ?: true
                    tableModel.setValueAt(!current, row, 0)
                }
            }
        })
        val scrollPane = JBScrollPane(table)
        add(scrollPane, BorderLayout.CENTER)

        val toolbar = buildToolbar()
        add(toolbar, BorderLayout.SOUTH)

        appendBlankRow()

        tableModel.addTableModelListener {
            fireChangeListeners()
            // Auto-append blank row when last row's key is edited and non-empty
            val lastRow = tableModel.rowCount - 1
            if (lastRow >= 0 && (tableModel.getValueAt(lastRow, 1) as? String)?.isNotEmpty() == true) {
                appendBlankRow()
            }
        }
    }

    private fun buildToolbar(): JPanel {
        val panel = JPanel().apply { border = JBUI.Borders.empty(2, 0, 0, 0) }

        val addBtn = JButton(object : AbstractAction("+") {
            override fun actionPerformed(e: ActionEvent) {
                appendBlankRow()
                val lastRow = tableModel.rowCount - 1
                table.editCellAt(lastRow, 1)
                table.transferFocus()
            }
        }).apply { toolTipText = "Add row" }

        val removeBtn = JButton(object : AbstractAction("−") {
            override fun actionPerformed(e: ActionEvent) {
                val row = table.selectedRow
                // Don't remove the last blank row
                if (row >= 0 && tableModel.rowCount > 1) {
                    tableModel.removeRow(row)
                }
            }
        }).apply { toolTipText = "Remove selected row" }

        panel.add(addBtn)
        panel.add(removeBtn)
        return panel
    }

    private fun appendBlankRow() {
        tableModel.addRow(arrayOf(true, "", "", ""))
    }

    /** Returns only enabled rows with non-empty key. */
    fun getRows(): List<NameValueRow> {
        val rows = mutableListOf<NameValueRow>()
        for (i in 0 until tableModel.rowCount) {
            val enabled = tableModel.getValueAt(i, 0) as? Boolean ?: true
            val key = tableModel.getValueAt(i, 1) as? String ?: ""
            val value = tableModel.getValueAt(i, 2) as? String ?: ""
            val desc = tableModel.getValueAt(i, 3) as? String ?: ""
            if (key.isNotEmpty()) rows.add(NameValueRow(enabled, key, value, desc))
        }
        return rows
    }

    /** Replaces content; always ensures one trailing blank row. */
    fun setRows(rows: List<NameValueRow>) {
        tableModel.rowCount = 0
        rows.forEach { row ->
            tableModel.addRow(arrayOf(row.enabled, row.key, row.value, row.description))
        }
        appendBlankRow()
    }

    fun addChangeListener(l: () -> Unit) {
        changeListeners += l
    }

    private fun fireChangeListeners() = changeListeners.forEach { it() }
}
