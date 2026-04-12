package com.sonarwhale.model

data class SonarwhaleEnvironment(
    val id: String,
    val name: String,                      // z.B. "dev", "staging", "prod"
    val source: EnvironmentSource,
    val isActive: Boolean = false
)

sealed class EnvironmentSource {

    /** Option A: Server-URL + Port, OpenAPI wird per HTTP gefetcht. */
    data class ServerUrl(
        val host: String,                  // z.B. "http://localhost"
        val port: Int,                     // z.B. 5000
        val openApiPath: String? = null    // null → Auto-Discovery
    ) : EnvironmentSource() {
        val baseUrl: String get() = "$host:$port"
        val openApiUrl: String? get() = openApiPath?.let { "$host:$port$it" }
    }

    /** Option B: Lokaler Dateipfad zur OpenAPI JSON/YAML-Datei. */
    data class FilePath(
        val path: String                   // z.B. "./bin/Debug/net8.0/swagger.json"
    ) : EnvironmentSource()

    /** Option C: Statisch importierter JSON-Inhalt (kein Auto-Refresh). */
    data class StaticImport(
        val cachedContent: String          // gespeicherter JSON-Inhalt
    ) : EnvironmentSource()
}
