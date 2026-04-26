package com.sonarwhale.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.VariableEntry

/** Pure resolution logic — no project dependency, unit testable. */
abstract class VariableResolverPure {
    fun buildMap(
        globalVars: List<VariableEntry>,
        collectionVars: List<VariableEntry>,
        tagVars: List<VariableEntry>,
        endpointVars: List<VariableEntry>,
        requestVars: List<VariableEntry>,
        baseUrl: String?
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        // lowest to highest priority
        baseUrl?.let { result["baseUrl"] = it }
        globalVars.filter { it.enabled }.forEach { result[it.key] = it.value }
        collectionVars.filter { it.enabled }.forEach { result[it.key] = it.value }
        tagVars.filter { it.enabled }.forEach { result[it.key] = it.value }
        endpointVars.filter { it.enabled }.forEach { result[it.key] = it.value }
        requestVars.filter { it.enabled }.forEach { result[it.key] = it.value }
        return result
    }

    fun resolve(text: String, varMap: Map<String, String>): String =
        VAR_PATTERN.replace(text) { match -> varMap[match.groupValues[1]] ?: match.value }

    companion object {
        val VAR_PATTERN = Regex("\\{\\{([^{}]+?)\\}\\}")
    }
}

@Service(Service.Level.PROJECT)
class VariableResolver(private val project: Project) : VariableResolverPure() {

    fun buildMap(collectionId: String, endpointId: String, requestId: String?): Map<String, String> {
        val state = SonarwhaleStateService.getInstance(project)
        val routeService = RouteIndexService.getInstance(project)

        val endpoint = routeService.endpoints.firstOrNull { it.id == endpointId }
        val tag = endpoint?.tags?.firstOrNull()

        // TODO(Task 7): replace null stubs with CollectionService.getInstance(project)
        val collectionVars = emptyList<com.sonarwhale.model.VariableEntry>()
        val baseUrl: String? = null

        return buildMap(
            globalVars = state.getGlobalConfig().config.variables,
            collectionVars = collectionVars,
            tagVars = if (tag != null) state.getTagConfig(tag).config.variables else emptyList(),
            endpointVars = state.getEndpointConfig(endpointId).config.variables,
            requestVars = if (requestId != null)
                state.getRequest(endpointId, requestId)?.config?.variables ?: emptyList()
            else emptyList(),
            baseUrl = baseUrl
        )
    }

    fun resolve(text: String, collectionId: String, endpointId: String, requestId: String?): String =
        resolve(text, buildMap(collectionId, endpointId, requestId))

    companion object {
        fun getInstance(project: Project): VariableResolver = project.service()
    }
}
