package com.sonarwhale.script

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScriptContextTest {

    @Test
    fun `env snapshot is mutable and readable`() {
        val ctx = ScriptContext(
            envSnapshot = mutableMapOf("key" to "value"),
            request = MutableRequestContext("http://example.com", "GET", mutableMapOf(), "")
        )
        assertEquals("value", ctx.envSnapshot["key"])
        ctx.envSnapshot["key"] = "updated"
        assertEquals("updated", ctx.envSnapshot["key"])
    }

    @Test
    fun `request fields are mutable`() {
        val req = MutableRequestContext(
            url = "http://example.com/api",
            method = "POST",
            headers = mutableMapOf("Content-Type" to "application/json"),
            body = "{}"
        )
        req.url = "http://example.com/api/v2"
        req.headers["Authorization"] = "Bearer token"
        assertEquals("http://example.com/api/v2", req.url)
        assertEquals("Bearer token", req.headers["Authorization"])
    }

    @Test
    fun `test result tracks pass and fail`() {
        val pass = TestResult("check status", passed = true, error = null)
        val fail = TestResult("check body", passed = false, error = "Expected 200 but got 404")
        assertTrue(pass.passed)
        assertFalse(fail.passed)
        assertEquals("Expected 200 but got 404", fail.error)
    }
}
