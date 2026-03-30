package com.routex.model

data class ApiEndpoint(
    val id: String,
    val httpMethod: HttpMethod,
    val route: String,
    val rawRouteSegments: List<String> = emptyList(),
    val filePath: String,
    val lineNumber: Int,
    val controllerName: String?,
    val methodName: String,
    val language: SupportedLanguage,
    val parameters: List<ApiParameter> = emptyList(),
    val auth: AuthInfo? = null,
    val responses: List<ApiResponse> = emptyList(),
    val meta: EndpointMeta
)

data class ApiParameter(
    val name: String,
    val type: String,
    val source: ParameterSource,
    val required: Boolean,
    val defaultValue: String? = null,
    val schema: ApiSchema? = null
)

data class ApiSchema(
    val typeName: String,
    val properties: List<ApiSchemaProperty> = emptyList(),
    val isArray: Boolean = false,
    val isNullable: Boolean = false
)

data class ApiSchemaProperty(
    val name: String,
    val type: String,
    val required: Boolean,
    val nestedSchema: ApiSchema? = null,
    val validationHints: List<String> = emptyList()
)

data class AuthInfo(
    val required: Boolean,
    val type: AuthType?,
    val policy: String? = null,
    val roles: List<String> = emptyList(),
    val inherited: Boolean = false
)

data class ApiResponse(
    val statusCode: Int?,
    val schema: ApiSchema? = null,
    val description: String? = null
)

data class EndpointMeta(
    val contentHash: String,
    val detectedAt: Long = System.currentTimeMillis(),
    val lastModifiedAt: Long = System.currentTimeMillis(),
    val analysisConfidence: Float = 1.0f,
    val analysisWarnings: List<String> = emptyList()
)
