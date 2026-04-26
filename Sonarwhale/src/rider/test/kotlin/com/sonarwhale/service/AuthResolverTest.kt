package com.sonarwhale.service

import com.sonarwhale.model.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AuthResolverTest {

    private fun resolve(
        requestAuth: AuthConfig = AuthConfig(),
        endpointAuth: AuthConfig = AuthConfig(),
        tagAuth: AuthConfig = AuthConfig(),
        collectionAuth: AuthConfig = AuthConfig(),
        globalAuth: AuthConfig = AuthConfig()
    ) = AuthResolverPure().resolve(requestAuth, endpointAuth, tagAuth, collectionAuth, globalAuth)

    @Test
    fun `returns NONE when all levels are INHERIT`() {
        assertEquals(AuthMode.NONE, resolve().mode)
    }

    @Test
    fun `request level overrides all`() {
        val result = resolve(
            requestAuth = AuthConfig(mode = AuthMode.BEARER, bearerToken = "req"),
            globalAuth = AuthConfig(mode = AuthMode.BASIC, basicUsername = "user")
        )
        assertEquals(AuthMode.BEARER, result.mode)
        assertEquals("req", result.bearerToken)
    }

    @Test
    fun `endpoint level used when request is INHERIT`() {
        val result = resolve(
            requestAuth = AuthConfig(mode = AuthMode.INHERIT),
            endpointAuth = AuthConfig(mode = AuthMode.API_KEY, apiKeyName = "X-Key", apiKeyValue = "val")
        )
        assertEquals(AuthMode.API_KEY, result.mode)
        assertEquals("X-Key", result.apiKeyName)
    }

    @Test
    fun `global used when all above are INHERIT`() {
        val result = resolve(globalAuth = AuthConfig(mode = AuthMode.BEARER, bearerToken = "global"))
        assertEquals(AuthMode.BEARER, result.mode)
        assertEquals("global", result.bearerToken)
    }

    @Test
    fun `NONE mode is explicit - stops inheritance`() {
        val result = resolve(
            endpointAuth = AuthConfig(mode = AuthMode.NONE),
            globalAuth = AuthConfig(mode = AuthMode.BEARER, bearerToken = "global")
        )
        assertEquals(AuthMode.NONE, result.mode)
    }
}
