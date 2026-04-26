package com.sonarwhale.service

import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/** Pure resolution logic — no project dependency, unit testable. */
open class AuthResolverPure {
    fun resolve(
        requestAuth: AuthConfig,
        endpointAuth: AuthConfig,
        tagAuth: AuthConfig,
        collectionAuth: AuthConfig,
        globalAuth: AuthConfig
    ): AuthConfig {
        for (auth in listOf(requestAuth, endpointAuth, tagAuth, collectionAuth, globalAuth)) {
            if (auth.mode != AuthMode.INHERIT) return auth
        }
        return AuthConfig(mode = AuthMode.NONE)
    }
}

@Service(Service.Level.PROJECT)
class AuthResolver(private val project: Project) : AuthResolverPure() {

    // OAuth token cache: "tokenUrl|clientId" → (token, expiresAtMs)
    private val tokenCache = mutableMapOf<String, Pair<String, Long>>()

    fun resolve(collectionId: String, endpointId: String, requestId: String?): AuthConfig {
        val state = SonarwhaleStateService.getInstance(project)
        val collectionService = CollectionService.getInstance(project)
        val routeService = RouteIndexService.getInstance(project)

        val endpoint = routeService.endpoints.firstOrNull { it.id == endpointId }
        val tag = endpoint?.tags?.firstOrNull()

        return resolve(
            requestAuth = if (requestId != null)
                state.getRequest(endpointId, requestId)?.config?.auth ?: AuthConfig()
            else AuthConfig(),
            endpointAuth = state.getEndpointConfig(endpointId).config.auth,
            tagAuth = if (tag != null) state.getTagConfig(tag).config.auth else AuthConfig(),
            collectionAuth = collectionService.getById(collectionId)?.config?.auth ?: AuthConfig(),
            globalAuth = state.getGlobalConfig().config.auth
        )
    }

    fun applyToRequest(
        builder: HttpRequest.Builder,
        urlBuilder: StringBuilder,
        auth: AuthConfig,
        varMap: Map<String, String>,
        varResolver: VariableResolver
    ) {
        fun v(s: String) = varResolver.resolve(s, varMap)
        when (auth.mode) {
            AuthMode.NONE, AuthMode.INHERIT -> {}
            AuthMode.BEARER ->
                builder.header("Authorization", "Bearer ${v(auth.bearerToken)}")
            AuthMode.BASIC -> {
                val encoded = Base64.getEncoder()
                    .encodeToString("${v(auth.basicUsername)}:${v(auth.basicPassword)}".toByteArray())
                builder.header("Authorization", "Basic $encoded")
            }
            AuthMode.API_KEY -> when (auth.apiKeyLocation) {
                ApiKeyLocation.HEADER -> builder.header(v(auth.apiKeyName), v(auth.apiKeyValue))
                ApiKeyLocation.QUERY -> {
                    val sep = if (urlBuilder.contains('?')) '&' else '?'
                    urlBuilder.append("${sep}${v(auth.apiKeyName)}=${v(auth.apiKeyValue)}")
                }
            }
            AuthMode.OAUTH2_CLIENT_CREDENTIALS -> {
                val token = getOrFetchToken(auth, varMap, varResolver)
                if (token != null) builder.header("Authorization", "Bearer $token")
            }
        }
    }

    private fun getOrFetchToken(
        auth: AuthConfig, varMap: Map<String, String>, varResolver: VariableResolver
    ): String? {
        fun v(s: String) = varResolver.resolve(s, varMap)
        val cacheKey = "${v(auth.oauthTokenUrl)}|${v(auth.oauthClientId)}"
        val cached = tokenCache[cacheKey]
        if (cached != null && System.currentTimeMillis() < cached.second) return cached.first

        return runCatching {
            val body = buildString {
                append("grant_type=client_credentials")
                append("&client_id=${v(auth.oauthClientId)}")
                append("&client_secret=${v(auth.oauthClientSecret)}")
                if (auth.oauthScope.isNotBlank()) append("&scope=${v(auth.oauthScope)}")
            }
            val req = HttpRequest.newBuilder()
                .uri(java.net.URI.create(v(auth.oauthTokenUrl)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
            val json = JsonParser.parseString(resp.body()).asJsonObject
            val token = json.get("access_token")?.asString ?: return@runCatching null
            val expiresIn = json.get("expires_in")?.asLong ?: 3600L
            tokenCache[cacheKey] = Pair(token, System.currentTimeMillis() + (expiresIn - 30) * 1000)
            token
        }.getOrNull()
    }

    fun clearTokenCache() = tokenCache.clear()

    companion object {
        fun getInstance(project: Project): AuthResolver = project.service()
    }
}
