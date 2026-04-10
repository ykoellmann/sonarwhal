package com.routex.providers.python

import com.intellij.psi.PsiFile
import com.jetbrains.python.psi.PyFile
import com.routex.RouteXService
import com.routex.model.ApiEndpoint
import com.routex.model.SupportedLanguage
import com.routex.providers.EndpointProvider

/**
 * Kotlin-side endpoint provider for Python files.
 *
 * The actual PSI analysis is performed by [PythonAnalyzer] and cached in [RouteXService]
 * via [PythonScanService]. This provider returns the cached slice for a given file,
 * following the same pattern as [com.routex.providers.csharp.CSharpEndpointProvider].
 */
class PythonEndpointProvider : EndpointProvider {

    override val language: SupportedLanguage = SupportedLanguage.PYTHON

    override fun canHandle(file: PsiFile): Boolean = file is PyFile

    override fun extractEndpoints(file: PsiFile): List<ApiEndpoint> {
        val service = file.project.getService(RouteXService::class.java) ?: return emptyList()
        return service.endpoints.filter {
            it.language == SupportedLanguage.PYTHON && it.filePath == file.virtualFile?.path
        }
    }
}
