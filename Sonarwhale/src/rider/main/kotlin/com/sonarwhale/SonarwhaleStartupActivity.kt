package com.sonarwhale

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.sonarwhale.gutter.SonarwhaleGutterService
import com.sonarwhale.script.SonarwhaleScriptService
import com.sonarwhale.service.RouteIndexService
import java.nio.file.Path

class SonarwhaleStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Eagerly initialize gutter service so it can register its editor listeners
        SonarwhaleGutterService.getInstance(project)
        RouteIndexService.getInstance(project).refresh()

        val scriptService = SonarwhaleScriptService.getInstance(project)
        scriptService.ensureSwDts()

        val scriptsRoot = scriptService.getScriptsRoot()
        // Refresh VFS so IntelliJ discovers tsconfig.json and sw.d.ts immediately
        LocalFileSystem.getInstance().refreshNioFiles(listOf(scriptsRoot), true, false, null)

        // Add .sonarwhale/scripts/ as a module content root so Rider indexes it and
        // the TypeScript Language Service discovers tsconfig.json for sw.* autocomplete
        ensureIndexed(project, scriptsRoot)
    }

    private fun ensureIndexed(project: Project, scriptsRoot: Path) {
        val scriptsUrl = VfsUtil.pathToUrl(scriptsRoot.toString())
        val module = ModuleManager.getInstance(project).modules.firstOrNull() ?: return
        val rootManager = ModuleRootManager.getInstance(module)
        if (rootManager.contentEntries.any { it.url == scriptsUrl }) return
        WriteAction.runAndWait<Exception> {
            val model = rootManager.modifiableModel
            model.addContentEntry(scriptsUrl)
            model.commit()
        }
    }
}
