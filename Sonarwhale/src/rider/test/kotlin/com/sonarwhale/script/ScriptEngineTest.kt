package com.sonarwhale.script

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class ScriptEngineTest {

    @TempDir
    lateinit var tempDir: Path

    private fun engine() = ScriptEngine()

    private fun scriptFile(name: String, code: String): ScriptFile {
        val file = tempDir.resolve(name).also { it.createFile(); it.writeText(code) }
        return ScriptFile(ScriptLevel.GLOBAL, ScriptPhase.PRE, file)
    }

    private fun ctx(
        env: Map<String, String> = emptyMap(),
        url: String = "http://example.com",
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        body: String = ""
    ) = ScriptContext(
        envSnapshot = env.toMutableMap(),
        request = MutableRequestContext(url, method, headers.toMutableMap(), body)
    )

    @Test
    fun `sw env get returns value from snapshot`() {
        val ctx = ctx(env = mapOf("token" to "abc123"))
        val script = scriptFile("pre.js", """
            var t = sw.env.get("token");
            sw.env.set("result", t);
        """.trimIndent())
        engine().executeChain(listOf(script), ctx)
        assertEquals("abc123", ctx.envSnapshot["result"])
    }

    @Test
    fun `sw env set stores value in snapshot`() {
        val ctx = ctx()
        val script = scriptFile("pre.js", """sw.env.set("myKey", "myValue");""")
        engine().executeChain(listOf(script), ctx)
        assertEquals("myValue", ctx.envSnapshot["myKey"])
    }

    @Test
    fun `sw request setHeader adds header to context`() {
        val ctx = ctx()
        val script = scriptFile("pre.js", """sw.request.setHeader("Authorization", "Bearer tok");""")
        engine().executeChain(listOf(script), ctx)
        assertEquals("Bearer tok", ctx.request.headers["Authorization"])
    }

    @Test
    fun `sw request setBody updates body`() {
        val ctx = ctx(body = "{}")
        val script = scriptFile("pre.js", """sw.request.setBody('{"updated":true}');""")
        engine().executeChain(listOf(script), ctx)
        assertEquals("""{"updated":true}""", ctx.request.body)
    }

    @Test
    fun `sw request setUrl updates url`() {
        val ctx = ctx(url = "http://old.com")
        val script = scriptFile("pre.js", """sw.request.setUrl("http://new.com/api");""")
        engine().executeChain(listOf(script), ctx)
        assertEquals("http://new.com/api", ctx.request.url)
    }

    @Test
    fun `sw test passing assertion adds passed TestResult`() {
        val ctx = ctx()
        val script = scriptFile("post.js", """
            sw.test("always passes", function() { return true; });
        """.trimIndent())
        engine().executeChain(listOf(script), ctx)
        assertEquals(1, ctx.testResults.size)
        assertTrue(ctx.testResults[0].passed)
        assertEquals("always passes", ctx.testResults[0].name)
    }

    @Test
    fun `sw test failing assertion adds failed TestResult with error`() {
        val ctx = ctx()
        val script = scriptFile("post.js", """
            sw.test("always fails", function() {
                throw new Error("boom");
            });
        """.trimIndent())
        engine().executeChain(listOf(script), ctx)
        assertEquals(1, ctx.testResults.size)
        assertFalse(ctx.testResults[0].passed)
        assertNotNull(ctx.testResults[0].error)
    }

    @Test
    fun `sw response fields accessible in post context`() {
        val response = ResponseContext(200, mapOf("Content-Type" to "application/json"), """{"id":1}""")
        val ctx = ScriptContext(
            envSnapshot = mutableMapOf(),
            request = MutableRequestContext("http://example.com", "GET", mutableMapOf(), ""),
            response = response
        )
        val script = scriptFile("post.js", """
            sw.env.set("status", String(sw.response.status));
            sw.env.set("id", String(sw.response.json().id));
        """.trimIndent())
        engine().executeChain(listOf(script), ctx)
        assertEquals("200", ctx.envSnapshot["status"])
        assertEquals("1", ctx.envSnapshot["id"])
    }

    @Test
    fun `multiple scripts in chain all execute`() {
        val ctx = ctx()
        val s1 = scriptFile("s1.js", """sw.env.set("a", "1");""")
        val s2 = scriptFile("s2.js", """sw.env.set("b", "2");""")
        engine().executeChain(listOf(s1, s2), ctx)
        assertEquals("1", ctx.envSnapshot["a"])
        assertEquals("2", ctx.envSnapshot["b"])
    }

    @Test
    fun `script syntax error is caught and stored as test result`() {
        val ctx = ctx()
        val script = scriptFile("bad.js", """this is not valid JS @@###""")
        engine().executeChain(listOf(script), ctx)
        assertEquals(1, ctx.testResults.size)
        assertFalse(ctx.testResults[0].passed)
        assertTrue(ctx.testResults[0].name.contains("bad.js"))
    }

    @Test
    fun `empty chain does nothing`() {
        val ctx = ctx(env = mapOf("x" to "1"))
        engine().executeChain(emptyList(), ctx)
        assertEquals("1", ctx.envSnapshot["x"])
        assertTrue(ctx.testResults.isEmpty())
    }

    @Test
    fun `sw expect toBe passes when values equal`() {
        val ctx = ctx()
        val script = scriptFile("post.js", """sw.expect(42).toBe(42);""")
        engine().executeChain(listOf(script), ctx)
        assertEquals(1, ctx.testResults.size)
        assertTrue(ctx.testResults[0].passed)
    }

    @Test
    fun `sw expect toContain fails when string does not contain substr`() {
        val ctx = ctx()
        val script = scriptFile("post.js", """sw.expect("hello world").toContain("xyz");""")
        engine().executeChain(listOf(script), ctx)
        assertEquals(1, ctx.testResults.size)
        assertFalse(ctx.testResults[0].passed)
        assertNotNull(ctx.testResults[0].error)
    }

    @Test
    fun `sw test return false is treated as failure`() {
        val ctx = ctx()
        val script = scriptFile("post.js", """
            sw.test("should fail", function() { return false; });
        """.trimIndent())
        engine().executeChain(listOf(script), ctx)
        assertEquals(1, ctx.testResults.size)
        assertFalse(ctx.testResults[0].passed)
    }

    @Test
    fun `console log is captured in ConsoleOutput`() {
        val ctx = ctx()
        val script = scriptFile("pre.js", """console.log("hello from script");""")
        val console = ConsoleOutput()
        engine().executeChain(listOf(script), ctx, console)
        val logs = console.entries.filterIsInstance<ConsoleEntry.LogEntry>()
        assertEquals(1, logs.size)
        assertEquals("hello from script", logs[0].message)
        assertEquals(LogLevel.LOG, logs[0].level)
    }

    @Test
    fun `console warn uses WARN level`() {
        val ctx = ctx()
        val script = scriptFile("pre.js", """console.warn("attention");""")
        val console = ConsoleOutput()
        engine().executeChain(listOf(script), ctx, console)
        val logs = console.entries.filterIsInstance<ConsoleEntry.LogEntry>()
        assertEquals(1, logs.size)
        assertEquals(LogLevel.WARN, logs[0].level)
    }

    @Test
    fun `ScriptBoundary emitted per script`() {
        val ctx = ctx()
        val s1 = scriptFile("s1.js", "")
        val s2 = scriptFile("s2.js", "")
        val console = ConsoleOutput()
        engine().executeChain(listOf(s1, s2), ctx, console)
        val boundaries = console.entries.filterIsInstance<ConsoleEntry.ScriptBoundary>()
        assertEquals(2, boundaries.size)
    }

    @Test
    fun `script error adds ErrorEntry to ConsoleOutput`() {
        val ctx = ctx()
        val script = scriptFile("bad.js", """this is not valid JS @@###""")
        val console = ConsoleOutput()
        engine().executeChain(listOf(script), ctx, console)
        val errors = console.entries.filterIsInstance<ConsoleEntry.ErrorEntry>()
        assertEquals(1, errors.size)
        assertTrue(errors[0].scriptPath.endsWith("bad.js"))
    }
}
