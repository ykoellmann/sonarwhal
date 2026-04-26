package com.sonarwhale.service

import com.sonarwhale.model.VariableEntry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VariableResolverTest {

    private val resolver = object : VariableResolverPure() {}

    @Test
    fun `resolves simple variable`() {
        val map = mapOf("baseUrl" to "http://localhost:5000")
        assertEquals("http://localhost:5000/api", resolver.resolve("{{baseUrl}}/api", map))
    }

    @Test
    fun `unknown variable left as-is`() {
        assertEquals("{{unknown}}", resolver.resolve("{{unknown}}", emptyMap()))
    }

    @Test
    fun `narrowest scope wins - request overrides global`() {
        val map = resolver.buildMap(
            globalVars = listOf(VariableEntry("token", "global")),
            collectionVars = emptyList(),
            tagVars = emptyList(),
            endpointVars = emptyList(),
            requestVars = listOf(VariableEntry("token", "request")),
            baseUrl = null
        )
        assertEquals("request", map["token"])
    }

    @Test
    fun `endpoint overrides collection`() {
        val map = resolver.buildMap(
            globalVars = emptyList(),
            collectionVars = listOf(VariableEntry("x", "collection")),
            tagVars = emptyList(),
            endpointVars = listOf(VariableEntry("x", "endpoint")),
            requestVars = emptyList(),
            baseUrl = null
        )
        assertEquals("endpoint", map["x"])
    }

    @Test
    fun `baseUrl is lowest priority - overridden by global`() {
        val map = resolver.buildMap(
            globalVars = listOf(VariableEntry("baseUrl", "override")),
            collectionVars = emptyList(),
            tagVars = emptyList(),
            endpointVars = emptyList(),
            requestVars = emptyList(),
            baseUrl = "http://env"
        )
        assertEquals("override", map["baseUrl"])
    }

    @Test
    fun `disabled variables are ignored`() {
        val map = resolver.buildMap(
            globalVars = listOf(VariableEntry("token", "secret", enabled = false)),
            collectionVars = emptyList(),
            tagVars = emptyList(),
            endpointVars = emptyList(),
            requestVars = emptyList(),
            baseUrl = null
        )
        assertNull(map["token"])
    }

    @Test
    fun `multiple variables resolved in one string`() {
        val map = mapOf("host" to "localhost", "port" to "5000")
        assertEquals("http://localhost:5000", resolver.resolve("http://{{host}}:{{port}}", map))
    }
}
