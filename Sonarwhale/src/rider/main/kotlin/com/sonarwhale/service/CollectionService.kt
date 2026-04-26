package com.sonarwhale.service

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sonarwhale.model.*
import java.io.File
import java.util.UUID

@Service(Service.Level.PROJECT)
class CollectionService(private val project: Project) : Disposable {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val collections = mutableListOf<ApiCollection>()
    private val listeners = mutableListOf<() -> Unit>()

    private val storageFile: File
        get() {
            val dir = File(project.basePath ?: "", ".idea/sonarwhale")
            dir.mkdirs()
            return File(dir, "collections.json")
        }

    val cacheDir: File
        get() {
            val dir = File(project.basePath ?: "", ".idea/sonarwhale/cache")
            dir.mkdirs()
            return dir
        }

    init { load() }

    // ── Query ─────────────────────────────────────────────────────────────────

    fun getAll(): List<ApiCollection> = collections.toList()

    fun getById(id: String): ApiCollection? = collections.firstOrNull { it.id == id }

    fun getActiveEnvironment(collectionId: String): CollectionEnvironment? {
        val col = getById(collectionId) ?: return null
        return col.environments.firstOrNull { it.id == col.activeEnvironmentId }
            ?: col.environments.firstOrNull()
    }

    fun getBaseUrl(collectionId: String): String? {
        val env = getActiveEnvironment(collectionId) ?: return null
        return when (val s = env.source) {
            is EnvironmentSource.ServerUrl -> "${s.host}:${s.port}"
            is EnvironmentSource.FilePath -> null
            is EnvironmentSource.StaticImport -> null
        }
    }

    fun getActiveSource(collectionId: String): EnvironmentSource? =
        getActiveEnvironment(collectionId)?.source

    // ── Mutate ────────────────────────────────────────────────────────────────

    fun add(col: ApiCollection): ApiCollection {
        collections += col
        save(); notifyListeners()
        return col
    }

    fun update(col: ApiCollection) {
        val idx = collections.indexOfFirst { it.id == col.id }
        if (idx >= 0) { collections[idx] = col; save(); notifyListeners() }
    }

    fun remove(id: String) {
        collections.removeIf { it.id == id }
        save(); notifyListeners()
    }

    fun setActiveEnvironment(collectionId: String, envId: String) {
        val idx = collections.indexOfFirst { it.id == collectionId }
        if (idx >= 0) {
            collections[idx] = collections[idx].copy(activeEnvironmentId = envId)
            save(); notifyListeners()
        }
    }

    fun updateConfig(collectionId: String, config: HierarchyConfig) {
        val idx = collections.indexOfFirst { it.id == collectionId }
        if (idx >= 0) {
            collections[idx] = collections[idx].copy(config = config)
            save(); notifyListeners()
        }
    }

    // ── Cache ─────────────────────────────────────────────────────────────────

    fun readCache(envId: String): String? = runCatching {
        File(cacheDir, "$envId.json").readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun writeCache(envId: String, json: String) = runCatching {
        File(cacheDir, "$envId.json").writeText(json)
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    fun addListener(l: () -> Unit): () -> Unit { listeners += l; return { listeners -= l } }
    private fun notifyListeners() = listeners.toList().forEach { it() }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun save() = runCatching {
        val arr = JsonArray()
        for (col in collections) arr.add(collectionToJson(col))
        storageFile.writeText(gson.toJson(arr))
    }

    private fun load() {
        collections.clear()

        // Try loading new format first
        val loaded = runCatching {
            val arr = JsonParser.parseString(storageFile.readText()).asJsonArray
            arr.mapNotNull { jsonToCollection(it.asJsonObject) }
        }.getOrNull()

        if (!loaded.isNullOrEmpty()) {
            collections += loaded
            return
        }

        // Attempt migration from old environments.json
        val oldFile = File(project.basePath ?: "", ".idea/sonarwhale/environments.json")
        if (oldFile.exists()) {
            val migrated = migrateFromOldEnvironments(oldFile)
            if (migrated.isNotEmpty()) {
                collections += migrated
                save()
                oldFile.renameTo(File(oldFile.parent, "environments.json.migrated"))
                return
            }
        }

        // Fresh default
        val devEnv = CollectionEnvironment(
            name = "dev",
            source = EnvironmentSource.ServerUrl(host = "http://localhost", port = 5000)
        )
        collections += ApiCollection(name = "My API", environments = listOf(devEnv),
            activeEnvironmentId = devEnv.id)
        save()
    }

    private fun migrateFromOldEnvironments(file: File): List<ApiCollection> = runCatching {
        // Old format: array of { id, name, isActive, source: { type, host, port, ... } }
        val arr = JsonParser.parseString(file.readText()).asJsonArray
        val envs = arr.mapNotNull { jsonToOldEnv(it.asJsonObject) }
        if (envs.isEmpty()) return@runCatching emptyList()

        // Each old environment becomes a CollectionEnvironment under one "My API" collection
        val activeId = envs.firstOrNull { it.second }?.first?.id ?: envs.first().first.id
        val collection = ApiCollection(
            name = "My API",
            environments = envs.map { it.first },
            activeEnvironmentId = activeId
        )
        listOf(collection)
    }.getOrDefault(emptyList())

    // Returns Pair<CollectionEnvironment, isActive>
    private fun jsonToOldEnv(obj: JsonObject): Pair<CollectionEnvironment, Boolean>? = runCatching {
        val src = obj.getAsJsonObject("source")
        val source: EnvironmentSource = when (src.get("type").asString) {
            "serverUrl" -> EnvironmentSource.ServerUrl(
                host = src.get("host").asString,
                port = src.get("port").asInt,
                openApiPath = src.get("openApiPath")?.asString
            )
            "filePath" -> EnvironmentSource.FilePath(path = src.get("path").asString)
            "staticImport" -> EnvironmentSource.StaticImport(cachedContent = src.get("cachedContent").asString)
            else -> return@runCatching null
        }
        Pair(
            CollectionEnvironment(
                id = obj.get("id").asString,
                name = obj.get("name").asString,
                source = source
            ),
            obj.get("isActive")?.asBoolean ?: false
        )
    }.getOrNull()

    private fun collectionToJson(col: ApiCollection): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", col.id)
        obj.addProperty("name", col.name)
        obj.addProperty("activeEnvironmentId", col.activeEnvironmentId)
        val envArr = JsonArray()
        for (env in col.environments) envArr.add(envToJson(env))
        obj.add("environments", envArr)
        obj.add("config", gson.toJsonTree(col.config))
        return obj
    }

    private fun jsonToCollection(obj: JsonObject): ApiCollection? = runCatching {
        val envArr = obj.getAsJsonArray("environments") ?: JsonArray()
        val envs = envArr.mapNotNull { jsonToEnv(it.asJsonObject) }
        val config = runCatching {
            gson.fromJson(obj.getAsJsonObject("config"), HierarchyConfig::class.java)
        }.getOrDefault(HierarchyConfig())
        ApiCollection(
            id = obj.get("id").asString,
            name = obj.get("name").asString,
            environments = envs,
            activeEnvironmentId = obj.get("activeEnvironmentId")?.asString,
            config = config
        )
    }.getOrNull()

    private fun envToJson(env: CollectionEnvironment): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", env.id)
        obj.addProperty("name", env.name)
        val src = JsonObject()
        when (val s = env.source) {
            is EnvironmentSource.ServerUrl -> {
                src.addProperty("type", "serverUrl")
                src.addProperty("host", s.host)
                src.addProperty("port", s.port)
                s.openApiPath?.let { src.addProperty("openApiPath", it) }
            }
            is EnvironmentSource.FilePath -> {
                src.addProperty("type", "filePath")
                src.addProperty("path", s.path)
            }
            is EnvironmentSource.StaticImport -> {
                src.addProperty("type", "staticImport")
                src.addProperty("cachedContent", s.cachedContent)
            }
        }
        obj.add("source", src)
        return obj
    }

    private fun jsonToEnv(obj: JsonObject): CollectionEnvironment? = runCatching {
        val src = obj.getAsJsonObject("source")
        val source: EnvironmentSource = when (src.get("type").asString) {
            "serverUrl" -> EnvironmentSource.ServerUrl(
                host = src.get("host").asString,
                port = src.get("port").asInt,
                openApiPath = src.get("openApiPath")?.asString
            )
            "filePath" -> EnvironmentSource.FilePath(path = src.get("path").asString)
            "staticImport" -> EnvironmentSource.StaticImport(cachedContent = src.get("cachedContent").asString)
            else -> return@runCatching null
        }
        CollectionEnvironment(
            id = obj.get("id").asString,
            name = obj.get("name").asString,
            source = source
        )
    }.getOrNull()

    override fun dispose() { listeners.clear() }

    companion object {
        fun getInstance(project: Project): CollectionService = project.service()
    }
}
