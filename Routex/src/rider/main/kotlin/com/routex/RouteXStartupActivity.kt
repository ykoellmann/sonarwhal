package com.routex

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class RouteXStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        RouteXService.getInstance(project).refresh()
    }
}
