package com.routex

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.intellij.openapi.wm.ToolWindowManager
import com.jetbrains.rd.framework.RdTaskResult
import com.jetbrains.rd.ide.model.RdApiEndpoint
import com.jetbrains.rd.ide.model.RdApiParameter
import com.jetbrains.rd.ide.model.RdApiSchema
import com.jetbrains.rd.ide.model.RdApiSchemaProperty
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
    private val loadingListeners = mutableListOf<(Boolean) -> Unit>()
    private val selectionListeners = mutableListOf<(String) -> Unit>()
    private val runRequestListeners = mutableListOf<(endpointId: String, requestId: String) -> Unit>()
    private var cachedEndpoints: List<ApiEndpoint> = emptyList()

    // Debounced file watcher: a single Alarm fires 500ms after the last .cs file change
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    val endpoints: List<ApiEndpoint>
        get() = cachedEndpoints

    private var modelSubscribed = false

    init {
        // One global listener for all VFS events; debounces rapid saves with a 500ms Alarm
        project.messageBus.connect(this).subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val hasCsChange = events.any { it.file?.extension == "cs" }
                    if (!hasCsChange) return
                    refreshAlarm.cancelAllRequests()
                    refreshAlarm.addRequest({ refresh() }, 500)
                }
            }
        )
    }

    fun setEndpoints(endpoints: List<ApiEndpoint>) {
        cachedEndpoints = endpoints
        notifyListeners()
    }

    fun refresh() {
        notifyLoading(true)
        val model = project.solution.routeXModel
        if (!modelSubscribed) {
            modelSubscribed = true
            model.navigateToEndpoint.advise(lifetime) { endpointId ->
                ApplicationManager.getApplication().invokeLater {
                    ToolWindowManager.getInstance(project).getToolWindow("RouteX")?.show(null)
                    selectEndpoint(endpointId)
                }
            }
        }
        model.getEndpoints.start(lifetime, Unit).result.advise(lifetime) { result ->
            ApplicationManager.getApplication().invokeLater {
                when (result) {
                    is RdTaskResult.Success -> setEndpoints(result.value.map { it.toApiEndpoint() })
                    is RdTaskResult.Cancelled -> {}
                    is RdTaskResult.Fault -> {}
                }
                notifyLoading(false)
            }
        }
    }

    /** Clears the C# backend cache then runs a full endpoint scan. */
    fun reScan() {
        notifyLoading(true)
        val model = project.solution.routeXModel
        model.clearCache.start(lifetime, Unit).result.advise(lifetime) {
            // Cache is now clear — do a full scan
            model.getEndpoints.start(lifetime, Unit).result.advise(lifetime) { result ->
                ApplicationManager.getApplication().invokeLater {
                    when (result) {
                        is RdTaskResult.Success -> setEndpoints(result.value.map { it.toApiEndpoint() })
                        is RdTaskResult.Cancelled -> {}
                        is RdTaskResult.Fault -> {}
                    }
                    notifyLoading(false)
                }
            }
        }
    }

    fun addListener(listener: (List<ApiEndpoint>) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun addLoadingListener(listener: (Boolean) -> Unit): () -> Unit {
        loadingListeners.add(listener)
        return { loadingListeners.remove(listener) }
    }

    /** Notify the UI to focus and select the given endpoint by ID. */
    fun selectEndpoint(id: String) {
        ApplicationManager.getApplication().invokeLater {
            selectionListeners.toList().forEach { it(id) }
        }
    }

    fun addSelectionListener(listener: (String) -> Unit): () -> Unit {
        selectionListeners.add(listener)
        return { selectionListeners.remove(listener) }
    }

    /** Asks the UI to select the endpoint, load the specified request, and send it immediately. */
    fun runRequest(endpointId: String, requestId: String) {
        ApplicationManager.getApplication().invokeLater {
            runRequestListeners.toList().forEach { it(endpointId, requestId) }
        }
    }

    fun addRunRequestListener(listener: (String, String) -> Unit): () -> Unit {
        runRequestListeners.add(listener)
        return { runRequestListeners.remove(listener) }
    }

    private fun notifyListeners() {
        val snapshot = cachedEndpoints
        listeners.toList().forEach { it(snapshot) }
    }

    private fun notifyLoading(loading: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            loadingListeners.toList().forEach { it(loading) }
        }
    }

    override fun dispose() {
        lifetimeDef.terminate()
        listeners.clear()
        loadingListeners.clear()
        selectionListeners.clear()
        runRequestListeners.clear()
    }

    companion object {
        fun getInstance(project: Project): RouteXService = project.service()
    }
}

private fun RdApiEndpoint.toApiEndpoint(): ApiEndpoint {
    val schema = bodySchema?.toApiSchema()
    val params = parameters.map { rdParam ->
        val p = rdParam.toApiParameter()
        if (schema != null && p.source == ParameterSource.BODY) p.copy(schema = schema) else p
    }
    return ApiEndpoint(
        id = id,
        httpMethod = httpMethod.toHttpMethod(),
        route = route,
        rawRouteSegments = route.split("/").filter { it.isNotEmpty() },
        filePath = filePath,
        lineNumber = lineNumber,
        controllerName = controllerName,
        methodName = methodName,
        language = SupportedLanguage.CSHARP,
        parameters = params,
        auth = if (authRequired) AuthInfo(required = true, type = null, policy = authPolicy) else null,
        responses = emptyList(),
        meta = EndpointMeta(
            contentHash = contentHash,
            analysisConfidence = analysisConfidence,
            analysisWarnings = analysisWarnings
        )
    )
}

private fun RdHttpMethod.toHttpMethod() = when (this) {
    RdHttpMethod.GET -> HttpMethod.GET
    RdHttpMethod.POST -> HttpMethod.POST
    RdHttpMethod.PUT -> HttpMethod.PUT
    RdHttpMethod.DELETE -> HttpMethod.DELETE
    RdHttpMethod.PATCH -> HttpMethod.PATCH
    RdHttpMethod.HEAD -> HttpMethod.HEAD
    RdHttpMethod.OPTIONS -> HttpMethod.OPTIONS
}

private fun RdApiSchema.toApiSchema(): ApiSchema = ApiSchema(
    typeName = typeName,
    properties = properties.map { it.toApiSchemaProperty() },
    isArray = isArray,
    isNullable = isNullable
)

private fun RdApiSchemaProperty.toApiSchemaProperty(): ApiSchemaProperty = ApiSchemaProperty(
    name = name,
    type = propType,
    required = required,
    validationHints = validationHints
)

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
