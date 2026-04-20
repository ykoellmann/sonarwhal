package com.sonarwhale.collection

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.ApiParameter
import com.sonarwhale.model.EndpointSource
import com.sonarwhale.model.HttpMethod
import com.sonarwhale.model.ParameterLocation
import com.sonarwhale.model.SavedRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CollectionFormatTest {

    private val exporter = PostmanCollectionExporter()

    private fun endpoint(
        method: HttpMethod,
        path: String,
        tag: String = "Default",
        vararg params: ApiParameter
    ) = ApiEndpoint(
        id = "${method.name} $path",
        method = method,
        path = path,
        summary = null,
        tags = listOf(tag),
        parameters = params.toList(),
        requestBody = null,
        responses = emptyMap(),
        auth = null,
        source = EndpointSource.OPENAPI_SERVER,
        psiNavigationTarget = null
    )

    private fun parseRoot(json: String): JsonObject =
        JsonParser.parseString(json).asJsonObject

    // --- Schema / structure ---

    @Test
    fun `export produces valid JSON with Postman schema URL`() {
        val json = exporter.export(emptyList(), emptyMap())
        val root = parseRoot(json)
        val schema = root.getAsJsonObject("info").get("schema").asString
        assertEquals("https://schema.getpostman.com/json/collection/v2.1.0/collection.json", schema)
    }

    @Test
    fun `empty endpoint list produces empty item array`() {
        val json = exporter.export(emptyList(), emptyMap())
        val root = parseRoot(json)
        val items = root.getAsJsonArray("item")
        assertNotNull(items)
        assertEquals(0, items.size())
    }

    // --- Grouping ---

    @Test
    fun `endpoints grouped by tag into Postman folders`() {
        val ep1 = endpoint(HttpMethod.GET, "/api/users", "Users")
        val ep2 = endpoint(HttpMethod.POST, "/api/users", "Users")
        val ep3 = endpoint(HttpMethod.GET, "/api/orders", "Orders")
        val json = exporter.export(listOf(ep1, ep2, ep3), emptyMap())
        val root = parseRoot(json)
        val items = root.getAsJsonArray("item")
        // Should be 2 folders: Orders and Users (sorted alphabetically)
        assertEquals(2, items.size())
        val folderNames = (0 until items.size()).map {
            items[it].asJsonObject.get("name").asString
        }
        assertTrue(folderNames.contains("Users"))
        assertTrue(folderNames.contains("Orders"))
    }

    @Test
    fun `endpoint with no tag goes into Endpoints folder`() {
        val ep = ApiEndpoint(
            id = "GET /api/misc",
            method = HttpMethod.GET,
            path = "/api/misc",
            summary = null,
            tags = emptyList(),
            parameters = emptyList(),
            requestBody = null,
            responses = emptyMap(),
            auth = null,
            source = EndpointSource.OPENAPI_SERVER,
            psiNavigationTarget = null
        )
        val json = exporter.export(listOf(ep), emptyMap())
        val root = parseRoot(json)
        val items = root.getAsJsonArray("item")
        assertEquals(1, items.size())
        assertEquals("Endpoints", items[0].asJsonObject.get("name").asString)
    }

    // --- Request method ---

    @Test
    fun `request method matches endpoint method`() {
        val ep = endpoint(HttpMethod.POST, "/api/users", "Users")
        val json = exporter.export(listOf(ep), emptyMap())
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val method = item.getAsJsonObject("request").get("method").asString
        assertEquals("POST", method)
    }

    // --- URL ---

    @Test
    fun `url raw contains baseUrl variable prefix and endpoint path`() {
        val ep = endpoint(HttpMethod.GET, "/api/users", "Users")
        val json = exporter.export(listOf(ep), emptyMap())
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val raw = item.getAsJsonObject("request").getAsJsonObject("url").get("raw").asString
        assertEquals("{{baseUrl}}/api/users", raw)
    }

    @Test
    fun `url path array contains segments split by slash`() {
        val ep = endpoint(HttpMethod.GET, "/api/users/profile", "Users")
        val json = exporter.export(listOf(ep), emptyMap())
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val pathArray = item.getAsJsonObject("request").getAsJsonObject("url").getAsJsonArray("path")
        assertEquals(3, pathArray.size())
        assertEquals("api", pathArray[0].asString)
        assertEquals("users", pathArray[1].asString)
        assertEquals("profile", pathArray[2].asString)
    }

    // --- Parameters ---

    @Test
    fun `path parameters appear in url variable`() {
        val ep = endpoint(
            HttpMethod.GET, "/api/users/{id}", "Users",
            ApiParameter("id", ParameterLocation.PATH, required = true, schema = null)
        )
        val json = exporter.export(listOf(ep), emptyMap())
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val urlObj = item.getAsJsonObject("request").getAsJsonObject("url")
        val variables = urlObj.getAsJsonArray("variable")
        assertNotNull(variables)
        assertEquals(1, variables.size())
        assertEquals("id", variables[0].asJsonObject.get("key").asString)
    }

    @Test
    fun `query parameters appear in url query`() {
        val ep = endpoint(
            HttpMethod.GET, "/api/users", "Users",
            ApiParameter("search", ParameterLocation.QUERY, required = false, schema = null)
        )
        val json = exporter.export(listOf(ep), emptyMap())
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val urlObj = item.getAsJsonObject("request").getAsJsonObject("url")
        val query = urlObj.getAsJsonArray("query")
        assertNotNull(query)
        assertEquals(1, query.size())
        assertEquals("search", query[0].asJsonObject.get("key").asString)
    }

    @Test
    fun `no path parameters means no variable array in url`() {
        val ep = endpoint(HttpMethod.GET, "/api/users", "Users")
        val json = exporter.export(listOf(ep), emptyMap())
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val urlObj = item.getAsJsonObject("request").getAsJsonObject("url")
        assertFalse(urlObj.has("variable"))
    }

    // --- Headers ---

    @Test
    fun `headers from saved request colon-separated lines parsed into Postman header objects`() {
        val ep = endpoint(HttpMethod.GET, "/api/users", "Users")
        val saved = SavedRequest(
            id = ep.id,
            name = "Default",
            isDefault = true,
            headers = "Authorization: Bearer token123\nContent-Type: application/json"
        )
        val json = exporter.export(listOf(ep), mapOf(ep.id to saved))
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val headers = item.getAsJsonObject("request").getAsJsonArray("header")
        assertEquals(2, headers.size())
        val first = headers[0].asJsonObject
        assertEquals("Authorization", first.get("key").asString)
        assertEquals("Bearer token123", first.get("value").asString)
        val second = headers[1].asJsonObject
        assertEquals("Content-Type", second.get("key").asString)
        assertEquals("application/json", second.get("value").asString)
    }

    @Test
    fun `no headers in saved request means empty header array`() {
        val ep = endpoint(HttpMethod.GET, "/api/users", "Users")
        val saved = SavedRequest(id = ep.id, name = "Default", isDefault = true, headers = "")
        val json = exporter.export(listOf(ep), mapOf(ep.id to saved))
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val headers = item.getAsJsonObject("request").getAsJsonArray("header")
        assertEquals(0, headers.size())
    }

    // --- Body ---

    @Test
    fun `body from saved request included as raw body with language json`() {
        val ep = endpoint(HttpMethod.POST, "/api/users", "Users")
        val bodyText = """{"name": "Alice"}"""
        val saved = SavedRequest(
            id = ep.id,
            name = "Default",
            isDefault = true,
            body = bodyText
        )
        val json = exporter.export(listOf(ep), mapOf(ep.id to saved))
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val body = item.getAsJsonObject("request").getAsJsonObject("body")
        assertNotNull(body)
        assertEquals("raw", body.get("mode").asString)
        assertEquals(bodyText, body.get("raw").asString)
        val language = body.getAsJsonObject("options").getAsJsonObject("raw").get("language").asString
        assertEquals("json", language)
    }

    @Test
    fun `blank body in saved request means no body field in output`() {
        val ep = endpoint(HttpMethod.POST, "/api/users", "Users")
        val saved = SavedRequest(id = ep.id, name = "Default", isDefault = true, body = "")
        val json = exporter.export(listOf(ep), mapOf(ep.id to saved))
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val request = item.getAsJsonObject("request")
        assertFalse(request.has("body"))
    }

    // --- Param values from saved request ---

    @Test
    fun `path param value from saved request appears in variable value`() {
        val ep = endpoint(
            HttpMethod.GET, "/api/users/{id}", "Users",
            ApiParameter("id", ParameterLocation.PATH, required = true, schema = null)
        )
        val saved = SavedRequest(
            id = ep.id,
            name = "Default",
            isDefault = true,
            paramValues = mapOf("id" to "99")
        )
        val json = exporter.export(listOf(ep), mapOf(ep.id to saved))
        val root = parseRoot(json)
        val folder = root.getAsJsonArray("item")[0].asJsonObject
        val item = folder.getAsJsonArray("item")[0].asJsonObject
        val urlObj = item.getAsJsonObject("request").getAsJsonObject("url")
        val variables = urlObj.getAsJsonArray("variable")
        assertEquals("99", variables[0].asJsonObject.get("value").asString)
    }
}
