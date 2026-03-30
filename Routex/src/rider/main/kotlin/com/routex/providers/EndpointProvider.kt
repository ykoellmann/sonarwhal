package com.routex.providers

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiFile
import com.routex.model.ApiEndpoint
import com.routex.model.SupportedLanguage

interface EndpointProvider {
    val language: SupportedLanguage
    fun canHandle(file: PsiFile): Boolean
    fun extractEndpoints(file: PsiFile): List<ApiEndpoint>

    companion object {
        val EP_NAME: ExtensionPointName<EndpointProvider> =
            ExtensionPointName.create("com.jetbrains.rider.plugins.routex.endpointProvider")
    }
}
