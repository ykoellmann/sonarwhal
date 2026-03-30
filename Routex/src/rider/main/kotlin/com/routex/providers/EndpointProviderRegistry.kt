package com.routex.providers

import com.intellij.openapi.components.Service
import com.intellij.psi.PsiFile
import com.routex.model.ApiEndpoint

@Service(Service.Level.APP)
class EndpointProviderRegistry {

    fun extractEndpoints(file: PsiFile): List<ApiEndpoint> =
        EndpointProvider.EP_NAME.extensionList
            .filter { it.canHandle(file) }
            .flatMap { it.extractEndpoints(file) }
}
