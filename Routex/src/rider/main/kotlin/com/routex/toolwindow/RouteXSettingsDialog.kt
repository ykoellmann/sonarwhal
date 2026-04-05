package com.routex.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.routex.RouteXStateService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class RouteXSettingsDialog(private val project: Project) : DialogWrapper(project, true) {

    private val stateService = RouteXStateService.getInstance(project)

    // Panels registered with the card layout — name → panel
    private val panels: LinkedHashMap<String, JPanel> = LinkedHashMap()
    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    private val navListModel = DefaultListModel<String>()
    private val navList = JBList(navListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        font = font.deriveFont(Font.PLAIN, 13f)
    }

    // Panels
    private val environmentPanel = EnvironmentSettingsPanel(stateService)

    init {
        title = "RouteX Settings"

        registerPanel("Environments", environmentPanel)

        navList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val name = navList.selectedValue ?: return@addListSelectionListener
                cardLayout.show(cardPanel, name)
            }
        }

        navList.selectedIndex = 0

        init()
    }

    private fun registerPanel(name: String, panel: JPanel) {
        panels[name] = panel
        navListModel.addElement(name)
        cardPanel.add(panel, name)
    }

    override fun createCenterPanel(): JComponent {
        val navPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(0, 0, 0, 8)
            add(navList, BorderLayout.CENTER)
            preferredSize = JBUI.size(160, 400)
        }

        val content = JPanel(BorderLayout(0, 0)).apply {
            border = JBUI.Borders.customLineLeft(JBColor.border())
            add(cardPanel, BorderLayout.CENTER)
            preferredSize = JBUI.size(520, 400)
        }

        val root = JPanel(BorderLayout(0, 0)).apply {
            preferredSize = JBUI.size(700, 420)
            add(navPanel, BorderLayout.WEST)
            add(content, BorderLayout.CENTER)
        }

        return root
    }

    override fun doOKAction() {
        environmentPanel.commit()
        super.doOKAction()
    }
}
