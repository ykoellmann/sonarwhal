package com.routex

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.rd.framework.RdTaskResult
import com.jetbrains.rd.ide.model.RdApiEndpoint
import com.jetbrains.rd.ide.model.RdApiParameter
import com.jetbrains.rd.ide.model.RdHttpMethod
import com.jetbrains.rd.ide.model.RdParameterSource
import com.jetbrains.rd.ide.model.routeXModel
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rider.projectView.solution
import com.routex.model.*

@Service(Service.Level.PROJECT)
class RouteXService(private val project: Project) : Disposable {

    private val lifetimeDef = LifetimeDefinition()
    private val lifetime = lifetimeDef.lifetime
    private val listeners = mutableListOf<(List<ApiEndpoint>) -> Unit>()
    private var cachedEndpoints: List<ApiEndpoint> = emptyList()

    val endpoints: List<ApiEndpoint>
        get() = cachedEndpoints

    fun setEndpoints(endpoints: List<ApiEndpoint>) {
        cachedEndpoints = endpoints
        notifyListeners()
    }

    fun refresh() {
        val model = project.solution.routeXModel
        model.getEndpoints.start(lifetime, Unit).result.advise(lifetime) { result ->
            when (result) {
                is RdTaskResult.Success -> setEndpoints(result.value.map { it.toApiEndpoint() })
                is RdTaskResult.Cancelled -> { /* no-op */ }
                is RdTaskResult.Fault -> { /* silently ignore */ }
            }
        }
    }

    fun addListener(listener: (List<ApiEndpoint>) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = cachedEndpoints
        listeners.toList().forEach { it(snapshot) }
    }

    override fun dispose() {
        lifetimeDef.terminate()
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): RouteXService = project.service()
    }
}

private fun RdApiEndpoint.toApiEndpoint() = ApiEndpoint(
    id = id,
    httpMethod = httpMethod.toHttpMethod(),
    route = route,
    rawRouteSegments = route.split("/").filter { it.isNotEmpty() },
    filePath = filePath,
    lineNumber = lineNumber,
    controllerName = controllerName,
    methodName = methodName,
    language = SupportedLanguage.CSHARP,
    parameters = parameters.map { it.toApiParameter() },
    auth = if (authRequired) AuthInfo(required = true, type = null, policy = authPolicy) else null,
    responses = emptyList(),
    meta = EndpointMeta(
        contentHash = contentHash,
        analysisConfidence = analysisConfidence,
        analysisWarnings = analysisWarnings
    )
)

private fun RdHttpMethod.toHttpMethod() = when (this) {
    RdHttpMethod.GET -> HttpMethod.GET
    RdHttpMethod.POST -> HttpMethod.POST
    RdHttpMethod.PUT -> HttpMethod.PUT
    RdHttpMethod.DELETE -> HttpMethod.DELETE
    RdHttpMethod.PATCH -> HttpMethod.PATCH
    RdHttpMethod.HEAD -> HttpMethod.HEAD
    RdHttpMethod.OPTIONS -> HttpMethod.OPTIONS
}

private fun RdApiParameter.toApiParameter() = ApiParameter(
    name = name,
    type = paramType,
    source = source.toParameterSource(),
    required = required,
    defaultValue = defaultValue
)

private fun RdParameterSource.toParameterSource() = when (this) {
    RdParameterSource.PATH -> ParameterSource.PATH
    RdParameterSource.QUERY -> ParameterSource.QUERY
    RdParameterSource.BODY -> ParameterSource.BODY
    RdParameterSource.HEADER -> ParameterSource.HEADER
    RdParameterSource.FORM -> ParameterSource.FORM
}
