package com.sonarwhale.openapi

import com.sonarwhale.model.EnvironmentSource
import com.sonarwhale.model.EndpointSource
import com.sonarwhale.model.SonarwhaleEnvironment
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration

/**
 * Fetcht OpenAPI-Daten für ein Environment asynchron.
 * Rückgabe: (json: String?, fromCache: Boolean)
 * - json == null → Fehler, kein Cache verfügbar
 * - fromCache == true → Daten kommen aus dem Fallback-Cache
 */
object OpenApiFetcher {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Führt den Fetch für das gegebene Environment durch.
     * Blockierend — muss aus einem Background-Thread aufgerufen werden.
     *
     * @param env Das Environment
     * @param cachedJson Fallback-Inhalt wenn Quelle nicht erreichbar (kann null sein)
     * @return FetchResult
     */
    fun fetch(env: SonarwhaleEnvironment, cachedJson: String?): FetchResult {
        return when (val src = env.source) {
            is EnvironmentSource.ServerUrl -> fetchFromServer(src, cachedJson)
            is EnvironmentSource.FilePath  -> fetchFromFile(src, cachedJson)
            is EnvironmentSource.StaticImport -> FetchResult.Success(src.cachedContent, EndpointSource.OPENAPI_STATIC)
        }
    }

    // -------------------------------------------------------------------------
    // Option A: Server-URL
    // -------------------------------------------------------------------------

    private fun fetchFromServer(src: EnvironmentSource.ServerUrl, cachedJson: String?): FetchResult {
        val baseUrl = src.baseUrl.trimEnd('/')

        // Manueller Pfad-Override hat Vorrang
        val overrideUrl = src.openApiUrl
        if (overrideUrl != null) {
            return tryHttpGet(overrideUrl, EndpointSource.OPENAPI_SERVER, cachedJson)
        }

        // Auto-Discovery: bekannte Pfade der Reihe nach probieren
        for (path in OpenApiDiscovery.knownPaths) {
            val result = tryHttpGet("$baseUrl$path", EndpointSource.OPENAPI_SERVER, cachedJson = null)
            if (result is FetchResult.Success) return result
        }

        return if (cachedJson != null)
            FetchResult.Cached(cachedJson, EndpointSource.OPENAPI_SERVER)
        else
            FetchResult.Error("Keine erreichbare OpenAPI-Quelle gefunden unter $baseUrl")
    }

    private fun tryHttpGet(url: String, source: EndpointSource, cachedJson: String?): FetchResult {
        return runCatching {
            val req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() in 200..299) {
                FetchResult.Success(resp.body(), source)
            } else {
                if (cachedJson != null) FetchResult.Cached(cachedJson, source)
                else FetchResult.Error("HTTP ${resp.statusCode()} von $url")
            }
        }.getOrElse { e ->
            if (cachedJson != null) FetchResult.Cached(cachedJson, source)
            else FetchResult.Error("Verbindungsfehler: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Option B: Dateipfad
    // -------------------------------------------------------------------------

    private fun fetchFromFile(src: EnvironmentSource.FilePath, cachedJson: String?): FetchResult {
        return runCatching {
            val content = Files.readString(Paths.get(src.path))
            FetchResult.Success(content, EndpointSource.OPENAPI_FILE)
        }.getOrElse { e ->
            if (cachedJson != null) FetchResult.Cached(cachedJson, EndpointSource.OPENAPI_FILE)
            else FetchResult.Error("Datei nicht lesbar: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Ergebnis-Typen
    // -------------------------------------------------------------------------

    sealed class FetchResult {
        /** Frische Daten direkt von der Quelle. */
        data class Success(val json: String, val source: EndpointSource) : FetchResult()
        /** Daten aus dem Fallback-Cache (Quelle war nicht erreichbar). */
        data class Cached(val json: String, val source: EndpointSource) : FetchResult()
        /** Fehler — auch kein Cache verfügbar. */
        data class Error(val message: String) : FetchResult()
    }
}
