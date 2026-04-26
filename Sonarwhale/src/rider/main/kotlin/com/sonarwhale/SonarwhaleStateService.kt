package com.sonarwhale

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.sonarwhale.model.EndpointConfig
import com.sonarwhale.model.Environment
import com.sonarwhale.model.GlobalConfig
import com.sonarwhale.model.SavedRequest
import com.sonarwhale.model.TagConfig
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
        // endpointId → JSON array of SavedRequest
        @JvmField var savedRequests: LinkedHashMap<String, String> = LinkedHashMap()
        // NEW: global config JSON (GlobalConfig)
        @JvmField var globalConfig: String = ""
        // NEW: tag → JSON of TagConfig
        @JvmField var tagConfigs: LinkedHashMap<String, String> = LinkedHashMap()
        // NEW: endpointId → JSON of EndpointConfig
        @JvmField var endpointConfigs: LinkedHashMap<String, String> = LinkedHashMap()
    }

    private val gson = Gson()
    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState.savedRequests.clear()
        myState.savedRequests.putAll(state.savedRequests)
        myState.globalConfig = state.globalConfig
        myState.tagConfigs.clear()
        myState.tagConfigs.putAll(state.tagConfigs)
        myState.endpointConfigs.clear()
        myState.endpointConfigs.putAll(state.endpointConfigs)
    }

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

    // ── GlobalConfig ───────────────────────────────────────────────────────────

    fun getGlobalConfig(): GlobalConfig {
        val json = myState.globalConfig
        if (json.isBlank()) return GlobalConfig()
        return runCatching {
            gson.fromJson(json, GlobalConfig::class.java)
        }.getOrDefault(GlobalConfig())
    }

    fun setGlobalConfig(config: GlobalConfig) {
        myState.globalConfig = gson.toJson(config)
    }

    // ── TagConfig ──────────────────────────────────────────────────────────────

    fun getTagConfig(tag: String): TagConfig {
        val json = myState.tagConfigs[tag] ?: return TagConfig(tag = tag)
        return runCatching { gson.fromJson(json, TagConfig::class.java) }.getOrDefault(TagConfig(tag = tag))
    }

    fun setTagConfig(config: TagConfig) {
        myState.tagConfigs[config.tag] = gson.toJson(config)
    }

    // ── EndpointConfig ─────────────────────────────────────────────────────────

    fun getEndpointConfig(endpointId: String): EndpointConfig {
        val json = myState.endpointConfigs[endpointId] ?: return EndpointConfig(endpointId = endpointId)
        return runCatching { gson.fromJson(json, EndpointConfig::class.java) }.getOrDefault(EndpointConfig(endpointId = endpointId))
    }

    fun setEndpointConfig(config: EndpointConfig) {
        myState.endpointConfigs[config.endpointId] = gson.toJson(config)
    }

    // ── Compatibility stubs (TODO: remove when Tasks 18/19/20 update callers) ─

    /** @deprecated Use EnvironmentService. Stub retained for compile compatibility. */
    fun getEnvironments(): List<Environment> = emptyList()

    /** @deprecated Use EnvironmentService. Stub retained for compile compatibility. */
    fun getActiveEnvironment(): Environment? = null

    /** @deprecated Use EnvironmentService. Stub retained for compile compatibility. */
    fun upsertEnvironment(@Suppress("UNUSED_PARAMETER") env: Environment) {}

    /** @deprecated Use EnvironmentService. Stub retained for compile compatibility. */
    fun removeEnvironment(@Suppress("UNUSED_PARAMETER") id: String) {}

    /** @deprecated Use EnvironmentService. Stub retained for compile compatibility. */
    fun setActiveEnvironment(@Suppress("UNUSED_PARAMETER") id: String) {}

    /** @deprecated Use VariableResolver. Stub retained for compile compatibility. */
    fun resolveVariables(text: String): String = text

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
        fun getInstance(project: Project): SonarwhaleStateService = project.service()
    }
}
