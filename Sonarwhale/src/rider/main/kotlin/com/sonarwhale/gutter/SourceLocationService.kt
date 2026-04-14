package com.sonarwhale.gutter

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task.Modal
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores the source file + line for each endpoint discovered by [SonarwhaleGutterService].
 * Provides navigation from the tool window back to the C# controller method.
 *
 * Population happens in two ways:
 *  1. [SonarwhaleGutterService] registers locations as it places gutter icons in open editors.
 *  2. A background project scan via [SonarwhaleGutterService.scanProjectFilesInBackground] covers
 *     files that have never been opened.
 *  3. [navigate] triggers an on-demand blocking scan as a last resort if the location is
 *     not yet cached.
 *
 * Supported file extensions are determined by [SonarwhaleGutterService.isSupported], which
 * delegates to the registered [LanguageScanner] implementations.
 */
@Service(Service.Level.PROJECT)
class SourceLocationService(private val project: Project) {

    data class SourceLocation(val file: VirtualFile, val line: Int)

    private val locations = ConcurrentHashMap<String, SourceLocation>()

    fun register(endpointId: String, file: VirtualFile, line: Int) {
        locations[endpointId] = SourceLocation(file, line)
    }

    fun get(endpointId: String): SourceLocation? = locations[endpointId]

    fun canNavigate(endpointId: String): Boolean = locations.containsKey(endpointId)

    /**
     * Opens the source file at the endpoint's line. If no cached location exists, runs a
     * blocking modal scan of all project .cs files (with cancellable progress) before giving up.
     * Must be called on the EDT.
     */
    fun navigate(endpointId: String): Boolean {
        val cached = locations[endpointId]
        if (cached != null) {
            OpenFileDescriptor(project, cached.file, cached.line, 0).navigate(true)
            return true
        }

        // Location not cached yet — run a blocking modal scan
        ProgressManager.getInstance().run(object : Modal(project, "Searching for endpoint source…", true) {
            override fun run(indicator: ProgressIndicator) {
                ApplicationManager.getApplication().runReadAction {
                    val gutterService = SonarwhaleGutterService.getInstance(project)
                    ProjectFileIndex.getInstance(project).iterateContent { vFile ->
                        if (indicator.isCanceled) return@iterateContent false
                        val ext = vFile.extension?.lowercase()
                        if (ext != null && gutterService.isSupported(ext)) {
                            try {
                                val text  = String(vFile.contentsToByteArray(), vFile.charset)
                                val lines = text.lines()
                                for ((ep, line) in gutterService.scanLines(lines, ext)) {
                                    register(ep.id, vFile, line)
                                }
                            } catch (_: Exception) {}
                        }
                        !locations.containsKey(endpointId) // false = stop early once found
                    }
                }
            }
        })

        val loc = locations[endpointId] ?: return false
        OpenFileDescriptor(project, loc.file, loc.line, 0).navigate(true)
        return true
    }

    /** Removes all registered locations (e.g. when the endpoint list changes). */
    fun clear() = locations.clear()

    companion object {
        fun getInstance(project: Project): SourceLocationService = project.service()
    }
}

