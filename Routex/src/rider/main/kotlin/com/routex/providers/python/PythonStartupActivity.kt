package com.routex.providers.python

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Triggers the initial Python endpoint scan after the project is fully loaded.
 * Registered in routex-python.xml — only active when the Python plugin is present.
 */
class PythonStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        PythonScanService.getInstance(project).scanAndMerge()
    }
}
