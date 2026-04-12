package com.sonarwhale.collection

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.ParameterLocation
import com.sonarwhale.model.SavedRequest

/**
 * Exports the current endpoint list and saved requests into a collection format.
 * Implement this interface to add OpenAPI, Bruno, .http, or any other format later.
 */
interface CollectionExporter {
    val formatName: String
    val fileExtension: String
    fun export(endpoints: List<ApiEndpoint>, savedRequests: Map<String, SavedRequest>): String
}

/**
 * Imports saved request data from an external collection format.
 * Returns a list of (matchKey, SavedRequest) pairs where matchKey is "METHOD /route"
 * — callers match against the endpoint list as best they can.
 */
interface CollectionImporter {
    val formatName: String
    fun import(content: String): List<Pair<String, SavedRequest>>
}

// ---------------------------------------------------------------------------
// Postman Collection v2.1
// ---------------------------------------------------------------------------

class PostmanCollectionExporter : CollectionExporter {

    override val formatName = "Postman Collection v2.1"
    override val fileExtension = "json"

    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    override fun export(endpoints: List<ApiEndpoint>, savedRequests: Map<String, SavedRequest>): String {
        val root = JsonObject()

        val info = JsonObject().apply {
            addProperty("name", "Sonarwhale Collection")
            addProperty("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json")
        }
        root.add("info", info)

        // Group into folders by tag (controller name), matching Postman convention
        val grouped = endpoints.groupBy { it.tags.firstOrNull() ?: "Endpoints" }
        val items = JsonArray()

        grouped.entries.sortedBy { it.key }.forEach { (controller, eps) ->
            val folder = JsonObject()
            folder.addProperty("name", controller)
            val folderItems = JsonArray()
            eps.sortedBy { it.path }.forEach { ep ->
                folderItems.add(buildItem(ep, savedRequests[ep.id]))
            }
            folder.add("item", folderItems)
            items.add(folder)
        }

        root.add("item", items)
        return gson.toJson(root)
    }

    private fun buildItem(ep: ApiEndpoint, saved: SavedRequest?): JsonObject {
        val item = JsonObject()
        item.addProperty("name", ep.summary ?: "${ep.method.name} ${ep.path}")

        val request = JsonObject()
        request.addProperty("method", ep.method.name)

        // Headers
        val headers = JsonArray()
        saved?.headers?.lines()?.filter { ':' in it }?.forEach { line ->
            val idx = line.indexOf(':')
            headers.add(JsonObject().apply {
                addProperty("key", line.substring(0, idx).trim())
                addProperty("value", line.substring(idx + 1).trim())
            })
        }
        request.add("header", headers)

        // URL
        request.add("url", buildUrl(ep, saved))

        // Body
        val bodyText = saved?.body
        if (!bodyText.isNullOrBlank()) {
            request.add("body", JsonObject().apply {
                addProperty("mode", "raw")
                addProperty("raw", bodyText)
                add("options", JsonObject().apply {
                    add("raw", JsonObject().apply { addProperty("language", "json") })
                })
            })
        }

        item.add("request", request)
        return item
    }

    private fun buildUrl(ep: ApiEndpoint, saved: SavedRequest?): JsonObject {
        val url = JsonObject()
        url.addProperty("raw", "{{baseUrl}}${ep.path}")

        val pathParts = JsonArray()
        ep.path.split("/").filter { it.isNotEmpty() }.forEach { pathParts.add(it) }
        url.add("path", pathParts)

        val pathVars = ep.parameters.filter { it.location == ParameterLocation.PATH }
        if (pathVars.isNotEmpty()) {
            val variables = JsonArray()
            pathVars.forEach { p ->
                variables.add(JsonObject().apply {
                    addProperty("key", p.name)
                    addProperty("value", saved?.paramValues?.get(p.name) ?: "")
                })
            }
            url.add("variable", variables)
        }

        val queryParams = ep.parameters.filter { it.location == ParameterLocation.QUERY }
        if (queryParams.isNotEmpty()) {
            val query = JsonArray()
            queryParams.forEach { p ->
                query.add(JsonObject().apply {
                    addProperty("key", p.name)
                    addProperty("value", saved?.paramValues?.get(p.name) ?: "")
                    addProperty("disabled", false)
                })
            }
            url.add("query", query)
        }

        return url
    }
}
