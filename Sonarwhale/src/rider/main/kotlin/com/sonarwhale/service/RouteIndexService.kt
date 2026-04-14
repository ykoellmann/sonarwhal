package com.sonarwhale.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.EnvironmentSource
import com.sonarwhale.openapi.OpenApiFetcher
import com.sonarwhale.openapi.OpenApiParser

/**
 * Haupt-Service: hält die Endpoint-Liste, triggert OpenAPI-Fetches, notifiziert die UI.
 * Ersetzt den alten SonarwhaleService (PSI-Basis).
 */
@Service(Service.Level.PROJECT)
class RouteIndexService(private val project: Project) : Disposable {

    enum class FetchStatus { OK, CACHED, ERROR, LOADING }

    private var cachedEndpoints: List<ApiEndpoint> = emptyList()
    private var fetchStatus: FetchStatus = FetchStatus.OK

    /** The endpoint ID currently shown in the detail view (set by selectEndpoint / runRequest). */
    @Volatile var currentEndpointId: String? = null
        private set

    private val endpointListeners   = mutableListOf<(List<ApiEndpoint>) -> Unit>()
    private val loadingListeners    = mutableListOf<(Boolean) -> Unit>()
    private val statusListeners     = mutableListOf<(FetchStatus) -> Unit>()
    private val selectionListeners  = mutableListOf<(String) -> Unit>()
    private val runRequestListeners = mutableListOf<(endpointId: String, requestId: String) -> Unit>()

    // Debounced file-save trigger: 500ms after last relevant file change
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    // 1-minute periodic refresh for ServerUrl and FilePath sources
    private val intervalAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val REFRESH_INTERVAL_MS = 60_000

    val endpoints: List<ApiEndpoint> get() = cachedEndpoints

    init {
        val bus = project.messageBus.connect(this)

        // File-save trigger: refresh on JSON/YAML changes
        bus.subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val relevant = events.any { e ->
                        val ext = e.file?.extension?.lowercase()
                        ext in listOf("json", "yaml", "yml")
                    }
                    if (!relevant) return
                    refreshAlarm.cancelAllRequests()
                    refreshAlarm.addRequest({ refresh() }, 500)
                }
            }
        )

        scheduleIntervalRefresh()
    }

    /** Returns true when the active environment warrants automatic refresh (not a static import). */
    private fun shouldAutoRefresh(): Boolean {
        val source = EnvironmentService.getInstance(project).getActive()?.source
        return source != null && source !is EnvironmentSource.StaticImport
    }

    /** Schedules a recurring 1-minute refresh for live sources. Reschedules itself after each run. */
    private fun scheduleIntervalRefresh() {
        if (!shouldAutoRefresh()) return
        intervalAlarm.addRequest({
            refresh()
            scheduleIntervalRefresh()
        }, REFRESH_INTERVAL_MS)
    }

    // -------------------------------------------------------------------------
    // Refresh — läuft vollständig im Background
    // -------------------------------------------------------------------------

    fun refresh() {
        val envService = EnvironmentService.getInstance(project)
        val env = envService.getActive() ?: run {
            setEndpoints(emptyList())
            updateStatus(FetchStatus.ERROR)
            return
        }

        notifyLoading(true)
        updateStatus(FetchStatus.LOADING)

        ApplicationManager.getApplication().executeOnPooledThread {
            val cachedJson = envService.readCache(env.id)
            val result = OpenApiFetcher.fetch(env, cachedJson)

            val (json, source, status) = when (result) {
                is OpenApiFetcher.FetchResult.Success -> Triple(result.json, result.source, FetchStatus.OK)
                is OpenApiFetcher.FetchResult.Cached  -> Triple(result.json, result.source, FetchStatus.CACHED)
                is OpenApiFetcher.FetchResult.Error   -> Triple(null, null, FetchStatus.ERROR)
            }

            if (result is OpenApiFetcher.FetchResult.Success) {
                envService.writeCache(env.id, result.json)
            }

            val newEndpoints = if (json != null && source != null)
                OpenApiParser.parse(json, source)
            else
                cachedEndpoints

            ApplicationManager.getApplication().invokeLater {
                setEndpoints(newEndpoints)
                updateStatus(status)
                notifyLoading(false)
            }
        }
    }

    /** Setzt Endpoints zurück und führt einen vollständigen Refresh durch. */
    fun reScan() {
        setEndpoints(emptyList())
        refresh()
    }

    // -------------------------------------------------------------------------
    // Endpoint-Verwaltung
    // -------------------------------------------------------------------------

    fun setEndpoints(endpoints: List<ApiEndpoint>) {
        cachedEndpoints = endpoints
        val snapshot = cachedEndpoints
        ApplicationManager.getApplication().invokeLater {
            endpointListeners.toList().forEach { it(snapshot) }
        }
    }

    // -------------------------------------------------------------------------
    // Selection & Run-Request (für Gutter Icons)
    // -------------------------------------------------------------------------

    /** Updates the currently viewed endpoint ID without triggering any listener. Called by the
     *  UI when the user selects an endpoint directly in the tree (not via gutter icon). */
    fun setCurrentEndpoint(id: String?) {
        currentEndpointId = id
    }

    fun selectEndpoint(id: String) {
        currentEndpointId = id
        ApplicationManager.getApplication().invokeLater {
            selectionListeners.toList().forEach { it(id) }
        }
    }

    fun runRequest(endpointId: String, requestId: String) {
        currentEndpointId = endpointId
        ApplicationManager.getApplication().invokeLater {
            runRequestListeners.toList().forEach { it(endpointId, requestId) }
        }
    }

    // -------------------------------------------------------------------------
    // Listener-Registrierung
    // -------------------------------------------------------------------------

    fun addListener(l: (List<ApiEndpoint>) -> Unit): () -> Unit {
        endpointListeners += l; return { endpointListeners -= l }
    }

    fun addLoadingListener(l: (Boolean) -> Unit): () -> Unit {
        loadingListeners += l; return { loadingListeners -= l }
    }

    fun addStatusListener(l: (FetchStatus) -> Unit): () -> Unit {
        statusListeners += l; return { statusListeners -= l }
    }

    fun addSelectionListener(l: (String) -> Unit): () -> Unit {
        selectionListeners += l; return { selectionListeners -= l }
    }

    fun addRunRequestListener(l: (String, String) -> Unit): () -> Unit {
        runRequestListeners += l; return { runRequestListeners -= l }
    }

    // -------------------------------------------------------------------------
    // Intern
    // -------------------------------------------------------------------------

    private fun notifyLoading(loading: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            loadingListeners.toList().forEach { it(loading) }
        }
    }

    private fun updateStatus(status: FetchStatus) {
        fetchStatus = status
        ApplicationManager.getApplication().invokeLater {
            statusListeners.toList().forEach { it(status) }
        }
    }

    override fun dispose() {
        endpointListeners.clear()
        loadingListeners.clear()
        statusListeners.clear()
        selectionListeners.clear()
        runRequestListeners.clear()
    }

    companion object {
        fun getInstance(project: Project): RouteIndexService = project.service()
    }
}
