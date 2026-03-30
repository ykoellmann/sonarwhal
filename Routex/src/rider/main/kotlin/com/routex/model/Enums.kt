package com.routex.model

enum class SupportedLanguage {
    CSHARP, PYTHON, JAVA, TYPESCRIPT, GO
}

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
}

enum class ParameterSource {
    PATH, QUERY, BODY, HEADER, FORM
}

enum class AuthType {
    BEARER, API_KEY, BASIC, NONE
}
