package com.sonarwhale.model

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
}

enum class ParameterLocation {
    PATH, QUERY, HEADER, COOKIE
}

enum class AuthType {
    NONE, BEARER, API_KEY, BASIC, OAUTH2
}

enum class EndpointSource {
    OPENAPI_SERVER, OPENAPI_FILE, OPENAPI_STATIC
}

enum class EndpointStatus {
    ACTIVE, ADDED, MODIFIED, REMOVED
}
