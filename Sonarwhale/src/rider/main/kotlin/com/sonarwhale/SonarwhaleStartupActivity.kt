package com.sonarwhale

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.sonarwhale.gutter.SonarwhaleGutterService
import com.sonarwhale.service.RouteIndexService

class SonarwhaleStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Eagerly initialize gutter service so it can register its editor listeners
        SonarwhaleGutterService.getInstance(project)
        RouteIndexService.getInstance(project).refresh()
    }
}
