package com.sonarwhale.toolwindow

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.SavedRequest
import com.sonarwhale.script.ScriptLevel
import com.sonarwhale.script.ScriptPhase
import com.sonarwhale.script.SonarwhaleScriptService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * Detail panel shown when the Global node or a Tag/Controller node is selected.
 * Displays scripts for that level with Create/Edit/Delete actions.
 * Auth and Variables sections are placeholders for future features.
 */
class FolderScriptsPanel(
    private val project: Project,
    private val level: ScriptLevel,
    private val tag: String? = null,
    private val endpoint: ApiEndpoint? = null,
    private val request: SavedRequest? = null,
    private val onRefresh: () -> Unit = {}   // called after delete to rebuild the panel
) : JPanel(BorderLayout()) {

    private val scriptService = SonarwhaleScriptService.getInstance(project)

    init {
        border = JBUI.Borders.empty(16)
        add(buildContent(), BorderLayout.NORTH)
    }

    private fun buildContent(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0; fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0; anchor = GridBagConstraints.NORTHWEST
            insets = Insets(0, 0, 4, 0)
        }

        // Title
        val titleText = when {
            level == ScriptLevel.GLOBAL -> "Global"
            tag != null                 -> tag
            else                        -> "Scripts"
        }
        panel.add(JBLabel(titleText).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }, gbc.also { it.gridy = 0; it.insets = Insets(0, 0, 12, 0) })

        // Scripts section header
        panel.add(sectionHeader("Scripts"), gbc.also { it.gridy = 1 })

        // Pre-script row
        panel.add(scriptRow(ScriptPhase.PRE), gbc.also { it.gridy = 2; it.insets = Insets(4, 8, 4, 0) })

        // Post-script row
        panel.add(scriptRow(ScriptPhase.POST), gbc.also { it.gridy = 3; it.insets = Insets(4, 8, 12, 0) })

        // Auth section (placeholder)
        panel.add(JSeparator(), gbc.also { it.gridy = 4; it.insets = Insets(0, 0, 8, 0) })
        panel.add(sectionHeader("Auth"), gbc.also { it.gridy = 5 })
        panel.add(JBLabel("Authentication settings — coming soon").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC, 11f)
            border = JBUI.Borders.emptyLeft(8)
        }, gbc.also { it.gridy = 6; it.insets = Insets(4, 8, 12, 0) })

        // Variables section (placeholder)
        panel.add(JSeparator(), gbc.also { it.gridy = 7; it.insets = Insets(0, 0, 8, 0) })
        panel.add(sectionHeader("Variables"), gbc.also { it.gridy = 8 })
        panel.add(JBLabel("Environment variables scoped to this level — coming soon").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC, 11f)
            border = JBUI.Borders.emptyLeft(8)
        }, gbc.also { it.gridy = 9; it.insets = Insets(4, 8, 0, 0) })

        return panel
    }

    private fun sectionHeader(text: String): JBLabel =
        JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor.GRAY
        }

    private fun scriptRow(phase: ScriptPhase): JPanel {
        val phaseName = if (phase == ScriptPhase.PRE) "Pre" else "Post"
        val scriptPath = scriptService.getScriptPath(phase, level, tag, endpoint, request)
        val exists = Files.exists(scriptPath)

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).also { it.isOpaque = false }
        row.add(JBLabel("$phaseName   ").apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        })

        if (exists) {
            // Show truncated path and Edit / Delete buttons
            val displayPath = scriptPath.toString().let {
                val max = 50
                if (it.length > max) "…" + it.takeLast(max) else it
            }
            row.add(JBLabel(displayPath).apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor.GRAY
                toolTipText = scriptPath.toString()
            })
            val editBtn = JButton("Edit").apply { font = font.deriveFont(10f) }
            val deleteBtn = JButton("Delete").apply {
                font = font.deriveFont(10f)
                foreground = JBColor(Color(0xCC, 0x33, 0x00), Color(0xFF, 0x66, 0x44))
            }
            editBtn.addActionListener { openFile(scriptPath) }
            deleteBtn.addActionListener {
                val answer = Messages.showYesNoDialog(
                    project,
                    "Delete ${scriptPath.fileName}?",
                    "Delete Script",
                    null
                )
                if (answer == Messages.YES) {
                    Files.deleteIfExists(scriptPath)
                    onRefresh()   // DetailPanel recreates FolderScriptsPanel, re-reading filesystem
                }
            }
            row.add(editBtn)
            row.add(deleteBtn)
        } else {
            row.add(JBLabel("(no script)").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC, 11f)
            })
            val createBtn = JButton("Create").apply { font = font.deriveFont(10f) }
            createBtn.addActionListener {
                ProgressManager.getInstance().run(
                    object : Task.Backgroundable(project, "Creating script…", false) {
                        override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                            val path = scriptService.getOrCreateScript(phase, level, tag, endpoint, request)
                            openFile(path)
                        }
                    }
                )
            }
            row.add(createBtn)
        }

        return row
    }

    private fun openFile(path: java.nio.file.Path) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }
}
