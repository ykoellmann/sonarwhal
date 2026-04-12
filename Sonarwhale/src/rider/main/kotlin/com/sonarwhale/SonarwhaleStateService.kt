package com.sonarwhale

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.sonarwhale.model.Environment
import com.sonarwhale.model.SavedRequest
import java.util.UUID

@Service(Service.Level.PROJECT)
@State(name = "Sonarwhale", storages = [Storage("sonarwhale.xml")])
class SonarwhaleStateService(@Suppress("UNUSED_PARAMETER") project: Project) : PersistentStateComponent<SonarwhaleStateService.State> {

    /**
     * Only primitive types and String-keyed maps here — IntelliJ's XML serializer
     * cannot handle nested generic objects.  Lists of SavedRequest are stored as
     * JSON strings and parsed on demand.
     */
    class State {
        @JvmField var baseUrl: String = "http://localhost:5000"
        // endpointId → JSON array of SavedRequest
        @JvmField var savedRequests: LinkedHashMap<String, String> = LinkedHashMap()
        // JSON array of Environment
        @JvmField var environments: String = ""
        @JvmField var activeEnvironmentId: String = ""
    }

    private val gson = Gson()
    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState.baseUrl = state.baseUrl
        myState.savedRequests.clear()
        myState.savedRequests.putAll(state.savedRequests)
        myState.environments = state.environments
        myState.activeEnvironmentId = state.activeEnvironmentId
    }

    var baseUrl: String
        get() = myState.baseUrl
        set(v) { myState.baseUrl = v }

    // ── Read ───────────────────────────────────────────────────────────────────

    fun getRequests(endpointId: String): List<SavedRequest> {
        val json = myState.savedRequests[endpointId] ?: return emptyList()
        return parseRequests(json)
    }

    /** Returns the request with isDefault=true, or the first one, or null. */
    fun getDefaultRequest(endpointId: String): SavedRequest? {
        val requests = getRequests(endpointId)
        return requests.firstOrNull { it.isDefault } ?: requests.firstOrNull()
    }

    fun getRequest(endpointId: String, requestId: String): SavedRequest? =
        getRequests(endpointId).firstOrNull { it.id == requestId }

    // ── Write ──────────────────────────────────────────────────────────────────

    /** Insert or update a request by id. If it's the first request it is auto-marked default. */
    fun upsertRequest(endpointId: String, request: SavedRequest) {
        val list = getRequests(endpointId).toMutableList()
        val idx = list.indexOfFirst { it.id == request.id }
        if (idx >= 0) list[idx] = request else list.add(request)
        // Auto-default when there's only one
        val saved = if (list.size == 1 && !list[0].isDefault) list.map { it.copy(isDefault = true) } else list
        myState.savedRequests[endpointId] = gson.toJson(saved)
    }

    fun removeRequest(endpointId: String, requestId: String) {
        val list = getRequests(endpointId).filter { it.id != requestId }.toMutableList()
        // Re-assign default if needed
        if (list.isNotEmpty() && list.none { it.isDefault }) {
            list[0] = list[0].copy(isDefault = true)
        }
        if (list.isEmpty()) myState.savedRequests.remove(endpointId)
        else myState.savedRequests[endpointId] = gson.toJson(list)
    }

    /** Mark exactly one request as default; clears isDefault on all others. */
    fun setDefault(endpointId: String, requestId: String) {
        val updated = getRequests(endpointId).map { it.copy(isDefault = it.id == requestId) }
        myState.savedRequests[endpointId] = gson.toJson(updated)
    }

    // ── Environments ───────────────────────────────────────────────────────────

    fun getEnvironments(): List<Environment> {
        val json = myState.environments
        if (json.isBlank()) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<Environment>>() {}.type
            gson.fromJson<List<Environment>>(json, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun getActiveEnvironment(): Environment? {
        val id = myState.activeEnvironmentId
        if (id.isBlank()) return null
        return getEnvironments().firstOrNull { it.id == id }
    }

    fun setActiveEnvironment(id: String) {
        myState.activeEnvironmentId = id
    }

    fun upsertEnvironment(env: Environment) {
        val list = getEnvironments().toMutableList()
        val idx = list.indexOfFirst { it.id == env.id }
        if (idx >= 0) list[idx] = env else list.add(env)
        myState.environments = gson.toJson(list)
    }

    fun removeEnvironment(id: String) {
        val list = getEnvironments().filter { it.id != id }
        myState.environments = gson.toJson(list)
        if (myState.activeEnvironmentId == id) myState.activeEnvironmentId = ""
    }

    /**
     * Replaces `{{varName}}` placeholders in [text] with values from the active environment.
     * Unknown variables are left as-is so they stay visible to the user.
     */
    fun resolveVariables(text: String): String {
        val env = getActiveEnvironment() ?: return text
        if (env.variables.isEmpty()) return text
        return VAR_PATTERN.replace(text) { match ->
            env.variables[match.groupValues[1]] ?: match.value
        }
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun parseRequests(json: String): List<SavedRequest> {
        if (json.isBlank()) return emptyList()
        return if (json.trimStart().startsWith("[")) {
            // Current format: JSON array
            runCatching {
                val type = object : TypeToken<List<SavedRequest>>() {}.type
                gson.fromJson<List<SavedRequest>>(json, type) ?: emptyList()
            }.getOrDefault(emptyList())
        } else {
            // Legacy format: single JSON object → migrate to list
            runCatching {
                val obj = JsonParser.parseString(json).asJsonObject
                listOf(SavedRequest(
                    id = UUID.randomUUID().toString(),
                    name = "Default",
                    isDefault = true,
                    headers = obj.get("headers")?.asString ?: "",
                    body = obj.get("body")?.asString ?: "",
                    bodyMode = obj.get("bodyMode")?.asString ?: "raw",
                    bodyContentType = obj.get("bodyContentType")?.asString ?: "application/json",
                    paramValues = obj.get("paramValues")?.asJsonObject
                        ?.entrySet()
                        ?.associate { it.key to it.value.asString }
                        ?: emptyMap()
                ))
            }.getOrDefault(emptyList())
        }
    }

    companion object {
        private val VAR_PATTERN = Regex("\\{\\{([^{}]+?)\\}\\}")
        fun getInstance(project: Project): SonarwhaleStateService = project.service()
    }
}
