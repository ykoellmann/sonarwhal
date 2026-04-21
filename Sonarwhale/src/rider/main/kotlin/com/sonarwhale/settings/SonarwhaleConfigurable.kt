package com.sonarwhale.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

class SonarwhaleConfigurable(private val project: Project) : Configurable {

    override fun getDisplayName() = "Sonarwhale"

    override fun createComponent(): JComponent = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(JBLabel("Configure Sonarwhale in the sections below.").apply {
            foreground = JBColor.GRAY
        }, BorderLayout.NORTH)
    }

    override fun isModified() = false
    override fun apply() {}
    override fun reset() {}
}
