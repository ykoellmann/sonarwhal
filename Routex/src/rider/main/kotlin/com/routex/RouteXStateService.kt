package com.routex

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "RouteX", storages = [Storage("routex.xml")])
class RouteXStateService(@Suppress("UNUSED_PARAMETER") project: Project) : PersistentStateComponent<RouteXStateService.State> {

    data class SavedRequest(
        var headers: String = "",
        var body: String = "",
        var paramValues: Map<String, String> = emptyMap()
    )

    /**
     * Only primitive types and String-keyed/valued maps here so IntelliJ's XML
     * serializer never has to deal with nested generic objects.
     * SavedRequest objects are stored as JSON strings and parsed on demand.
     */
    class State {
        @JvmField var baseUrl: String = "http://localhost:5000"
        @JvmField var savedRequests: LinkedHashMap<String, String> = LinkedHashMap()
    }

    private val gson = Gson()
    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState.baseUrl = state.baseUrl
        myState.savedRequests.clear()
        myState.savedRequests.putAll(state.savedRequests)
    }

    var baseUrl: String
        get() = myState.baseUrl
        set(v) { myState.baseUrl = v }

    fun getSavedRequest(endpointId: String): SavedRequest? {
        val json = myState.savedRequests[endpointId] ?: return null
        return runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            val headers = obj.get("headers")?.asString ?: ""
            val body = obj.get("body")?.asString ?: ""
            val paramValues = obj.get("paramValues")?.asJsonObject
                ?.entrySet()
                ?.associate { it.key to it.value.asString }
                ?: emptyMap()
            SavedRequest(headers = headers, body = body, paramValues = paramValues)
        }.getOrNull()
    }

    fun setSavedRequest(endpointId: String, req: SavedRequest) {
        myState.savedRequests[endpointId] = gson.toJson(req)
    }

    companion object {
        fun getInstance(project: Project): RouteXStateService = project.service()
    }
}
