package com.routex

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.routex.gutter.RouteXGutterService

class RouteXStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Eagerly initialize gutter services so they can register their editor listeners
        RouteXGutterService.getInstance(project)
        RouteXService.getInstance(project).refresh()
    }
}
