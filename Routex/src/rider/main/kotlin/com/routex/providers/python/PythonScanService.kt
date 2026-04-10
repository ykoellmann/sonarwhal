package com.routex.providers.python

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiManager
import com.intellij.util.Alarm
import com.jetbrains.python.psi.PyFile
import com.routex.RouteXService
import com.routex.model.ApiEndpoint
import com.routex.model.SupportedLanguage

/**
 * Project-level service responsible for:
 *   1. Scanning all Python files in the project on startup (via [PythonStartupActivity]).
 *   2. Watching VFS events and re-scanning any changed .py file with a 500ms debounce.
 *
 * Detected endpoints are pushed into [RouteXService.setEndpointsForLanguage] so they
 * appear in the tool window alongside endpoints from all other language providers.
 *
 * Only loaded when the Python plugin (com.intellij.modules.python) is present.
 */
@Service(Service.Level.PROJECT)
class PythonScanService(private val project: Project) : Disposable {

    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    init {
        project.messageBus.connect(this).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val hasPyChange = events.any { it.file?.extension == "py" }
                    if (!hasPyChange) return
                    refreshAlarm.cancelAllRequests()
                    refreshAlarm.addRequest({ scanAndMerge() }, 500)
                }
            }
        )
    }

    /** Scans every Python source file in the project and pushes endpoints into [RouteXService]. */
    fun scanAndMerge() {
        val endpoints = ReadAction.compute<List<ApiEndpoint>, Throwable> {
            val psiManager = PsiManager.getInstance(project)
            val fileIndex = ProjectFileIndex.getInstance(project)
            val results = mutableListOf<ApiEndpoint>()

            fileIndex.iterateContent { vFile ->
                if (vFile.extension == "py" && !vFile.isDirectory) {
                    val psiFile = psiManager.findFile(vFile) as? PyFile
                    if (psiFile != null) results += PythonAnalyzer.analyze(psiFile)
                }
                true  // continue iteration
            }

            results
        }
        RouteXService.getInstance(project).setEndpointsForLanguage(SupportedLanguage.PYTHON, endpoints)
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): PythonScanService = project.service()
    }
}
