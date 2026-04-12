package com.sonarwhale.model

data class ApiEndpoint(
    val id: String,                        // stabile ID: "METHOD /normalized/path"
    val method: HttpMethod,
    val path: String,                      // "/api/users/{id}"
    val summary: String?,
    val tags: List<String>,                // OpenAPI-Tags (oft Controller-Name)
    val parameters: List<ApiParameter>,
    val requestBody: ApiSchema?,
    val responses: Map<Int, ApiSchema>,
    val auth: AuthInfo?,
    val source: EndpointSource,
    val psiNavigationTarget: String?,      // für Jump-to-Definition (Phase 4)
    val status: EndpointStatus = EndpointStatus.ACTIVE
)

data class ApiParameter(
    val name: String,
    val location: ParameterLocation,       // PATH, QUERY, HEADER, COOKIE
    val required: Boolean,
    val schema: ApiSchema?
)

data class ApiSchema(
    val type: String,                      // "object", "string", "integer", "array", "boolean", "number"
    val properties: Map<String, ApiSchema>? = null,
    val items: ApiSchema? = null,          // für Array-Typen
    val example: Any? = null,
    val description: String? = null
)

data class AuthInfo(
    val type: AuthType,
    val scheme: String? = null             // z.B. "bearer" für HTTP Bearer
)

fun ApiSchema.toJsonTemplate(depth: Int = 0): String {
    if (depth > 4) return "\"...\""
    return when (type) {
        "object" -> {
            val props = properties
            if (props.isNullOrEmpty()) "{}"
            else "{\n" + props.entries.joinToString(",\n") { (k, v) ->
                "  ".repeat(depth + 1) + "\"$k\": ${v.toJsonTemplate(depth + 1)}"
            } + "\n" + "  ".repeat(depth) + "}"
        }
        "array"  -> "[${items?.toJsonTemplate(depth + 1) ?: "null"}]"
        "string" -> if (example != null) "\"$example\"" else "\"\""
        "integer", "number" -> example?.toString() ?: "0"
        "boolean" -> example?.toString() ?: "false"
        else     -> if (example != null) "\"$example\"" else "null"
    }
}
