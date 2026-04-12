package com.sonarwhale.openapi

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sonarwhale.model.*

/**
 * Parst OpenAPI 3.x JSON in eine Liste von ApiEndpoints.
 * Unterstützt $ref-Auflösung für inline-Schemas aus components/schemas.
 */
object OpenApiParser {

    fun parse(json: String, source: EndpointSource): List<ApiEndpoint> {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return emptyList()

        val schemas = root
            .getObject("components")
            ?.getObject("schemas")
            ?: JsonObject()

        val paths = root.getObject("paths") ?: return emptyList()
        val endpoints = mutableListOf<ApiEndpoint>()

        for ((rawPath, pathItemEl) in paths.entrySet()) {
            val pathItem = pathItemEl.asJsonObject ?: continue
            // Gemeinsame Parameter auf Path-Ebene (werden pro Operation geerbt)
            val commonParams = parseParameters(pathItem.getArray("parameters"), schemas)

            for (method in HttpMethod.entries) {
                val operationEl = pathItem.get(method.name.lowercase()) ?: continue
                val operation = operationEl.asJsonObject ?: continue

                val operationParams = parseParameters(operation.getArray("parameters"), schemas)
                // Operation-Parameter überschreiben Path-Parameter mit gleichem Namen
                val mergedParams = mergeParameters(commonParams, operationParams)

                val tags = operation.getArray("tags")?.mapNotNull { it.asStringOrNull() } ?: emptyList()
                val summary = operation.get("summary")?.asStringOrNull()
                    ?: operation.get("description")?.asStringOrNull()

                val requestBody = operation.getObject("requestBody")?.let { parseRequestBody(it, schemas) }

                val responses = parseResponses(operation.getObject("responses"), schemas)

                val auth = parseAuth(operation, root)

                val normalizedPath = rawPath.trimEnd('/')
                val id = "${method.name} $normalizedPath"

                endpoints += ApiEndpoint(
                    id = id,
                    method = method,
                    path = normalizedPath.ifEmpty { "/" },
                    summary = summary,
                    tags = tags,
                    parameters = mergedParams,
                    requestBody = requestBody,
                    responses = responses,
                    auth = auth,
                    source = source,
                    psiNavigationTarget = null
                )
            }
        }

        return endpoints
    }

    // -------------------------------------------------------------------------
    // Parameter parsing
    // -------------------------------------------------------------------------

    private fun parseParameters(arr: com.google.gson.JsonArray?, schemas: JsonObject): List<ApiParameter> {
        arr ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el.asJsonObject ?: return@mapNotNull null
            // $ref auflösen
            val param = resolveRef(obj, schemas) ?: obj
            val name = param.get("name")?.asStringOrNull() ?: return@mapNotNull null
            val location = when (param.get("in")?.asStringOrNull()) {
                "path"   -> ParameterLocation.PATH
                "query"  -> ParameterLocation.QUERY
                "header" -> ParameterLocation.HEADER
                "cookie" -> ParameterLocation.COOKIE
                else     -> return@mapNotNull null
            }
            val required = param.get("required")?.asBooleanOrNull() ?: (location == ParameterLocation.PATH)
            val schema = param.getObject("schema")?.let { parseSchema(it, schemas) }
            ApiParameter(name = name, location = location, required = required, schema = schema)
        }
    }

    private fun mergeParameters(base: List<ApiParameter>, override: List<ApiParameter>): List<ApiParameter> {
        val result = base.toMutableList()
        for (param in override) {
            val idx = result.indexOfFirst { it.name == param.name && it.location == param.location }
            if (idx >= 0) result[idx] = param else result += param
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Request Body
    // -------------------------------------------------------------------------

    private fun parseRequestBody(obj: JsonObject, schemas: JsonObject): ApiSchema? {
        val content = obj.getObject("content") ?: return null
        // Bevorzuge application/json, dann das erste verfügbare
        val mediaType = content.getObject("application/json")
            ?: content.entrySet().firstOrNull()?.value?.asJsonObject
            ?: return null
        return mediaType.getObject("schema")?.let { parseSchema(it, schemas) }
    }

    // -------------------------------------------------------------------------
    // Responses
    // -------------------------------------------------------------------------

    private fun parseResponses(obj: JsonObject?, schemas: JsonObject): Map<Int, ApiSchema> {
        obj ?: return emptyMap()
        val result = mutableMapOf<Int, ApiSchema>()
        for ((code, el) in obj.entrySet()) {
            val statusCode = code.toIntOrNull() ?: continue
            val responseObj = el.asJsonObject ?: continue
            val content = responseObj.getObject("content") ?: continue
            val mediaType = content.getObject("application/json")
                ?: content.entrySet().firstOrNull()?.value?.asJsonObject
                ?: continue
            val schema = mediaType.getObject("schema")?.let { parseSchema(it, schemas) } ?: continue
            result[statusCode] = schema
        }
        return result
    }

    // -------------------------------------------------------------------------
    // Schema parsing (mit $ref-Auflösung, max. Tiefe 5)
    // -------------------------------------------------------------------------

    private fun parseSchema(obj: JsonObject, schemas: JsonObject, depth: Int = 0): ApiSchema {
        if (depth > 5) return ApiSchema(type = "object")

        val resolved = resolveRef(obj, schemas) ?: obj
        val type = resolved.get("type")?.asStringOrNull()

        return when {
            type == "array" -> {
                val items = resolved.getObject("items")?.let { parseSchema(it, schemas, depth + 1) }
                ApiSchema(type = "array", items = items, description = resolved.get("description")?.asStringOrNull())
            }
            type == "object" || resolved.has("properties") -> {
                val props = resolved.getObject("properties")?.entrySet()?.associate { (name, el) ->
                    name to parseSchema(el.asJsonObject ?: JsonObject(), schemas, depth + 1)
                }
                val example = resolved.get("example")
                ApiSchema(
                    type = "object",
                    properties = props,
                    example = example,
                    description = resolved.get("description")?.asStringOrNull()
                )
            }
            else -> ApiSchema(
                type = type ?: "string",
                example = resolved.get("example"),
                description = resolved.get("description")?.asStringOrNull()
            )
        }
    }

    // -------------------------------------------------------------------------
    // Auth (vereinfacht: Security Requirements + global SecuritySchemes)
    // -------------------------------------------------------------------------

    private fun parseAuth(operation: JsonObject, root: JsonObject): AuthInfo? {
        val securityReqs = operation.getArray("security")
            ?: root.getArray("security")
            ?: return null

        if (securityReqs.size() == 0) return null

        val schemes = root.getObject("components")?.getObject("securitySchemes") ?: JsonObject()
        val firstScheme = securityReqs.firstOrNull()?.asJsonObject?.keySet()?.firstOrNull() ?: return null
        val schemeDef = schemes.getObject(firstScheme)

        return when (schemeDef?.get("type")?.asStringOrNull()) {
            "http" -> {
                val scheme = schemeDef.get("scheme")?.asStringOrNull()
                if (scheme?.equals("bearer", ignoreCase = true) == true)
                    AuthInfo(type = AuthType.BEARER, scheme = "bearer")
                else
                    AuthInfo(type = AuthType.BASIC, scheme = scheme)
            }
            "apiKey" -> AuthInfo(type = AuthType.API_KEY, scheme = schemeDef.get("name")?.asStringOrNull())
            "oauth2" -> AuthInfo(type = AuthType.OAUTH2)
            else -> AuthInfo(type = AuthType.BEARER)
        }
    }

    // -------------------------------------------------------------------------
    // $ref-Auflösung (nur lokale #/components/schemas/... Refs)
    // -------------------------------------------------------------------------

    private fun resolveRef(obj: JsonObject, schemas: JsonObject): JsonObject? {
        val ref = obj.get("\$ref")?.asStringOrNull() ?: return null
        val schemaName = ref.removePrefix("#/components/schemas/")
        return schemas.getObject(schemaName)
    }

    // -------------------------------------------------------------------------
    // Gson-Hilfsfunktionen
    // -------------------------------------------------------------------------

    private fun JsonObject.getObject(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.getArray(key: String): com.google.gson.JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private val JsonElement.asJsonObject: JsonObject?
        get() = takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonElement.asStringOrNull(): String? =
        runCatching { asString }.getOrNull()

    private fun JsonElement.asBooleanOrNull(): Boolean? =
        runCatching { asBoolean }.getOrNull()
}
