package com.routex.providers.csharp

import com.intellij.psi.PsiFile
import com.routex.RouteXService
import com.routex.model.ApiEndpoint
import com.routex.model.SupportedLanguage
import com.routex.providers.EndpointProvider

/**
 * Kotlin-side endpoint provider for C# files.
 *
 * The actual PSI analysis happens in the ReSharper backend (C#) and is communicated
 * via the Rider Protocol. This provider serves cached endpoints that were pushed
 * from the backend via [RouteXService].
 *
 * For direct PSI-based providers (Python, Java, TypeScript), subclass [EndpointProvider]
 * and implement [extractEndpoints] with IntelliJ PSI directly.
 */
class CSharpEndpointProvider : EndpointProvider {

    override val language: SupportedLanguage = SupportedLanguage.CSHARP

    override fun canHandle(file: PsiFile): Boolean =
        file.name.endsWith(".cs")

    override fun extractEndpoints(file: PsiFile): List<ApiEndpoint> {
        // C# endpoints are detected by the ReSharper backend and cached in RouteXService.
        // Return the cached endpoints that belong to this file.
        val service = file.project.getService(RouteXService::class.java) ?: return emptyList()
        return service.endpoints.filter { it.filePath == file.virtualFile?.path }
    }
}
