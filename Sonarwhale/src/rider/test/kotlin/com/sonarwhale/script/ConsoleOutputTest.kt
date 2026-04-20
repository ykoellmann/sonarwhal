package com.sonarwhale.script

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConsoleOutputTest {

    @Test
    fun `log adds LogEntry with correct level and message`() {
        val out = ConsoleOutput()
        out.log(LogLevel.WARN, "watch out")
        val entries = out.entries
        assertEquals(1, entries.size)
        val entry = entries[0] as ConsoleEntry.LogEntry
        assertEquals(LogLevel.WARN, entry.level)
        assertEquals("watch out", entry.message)
    }

    @Test
    fun `scriptStart adds ScriptBoundary`() {
        val out = ConsoleOutput()
        val script = ScriptFile(ScriptLevel.GLOBAL, ScriptPhase.PRE,
            java.nio.file.Path.of("/tmp/pre.js"))
        out.scriptStart(script)
        val entry = out.entries[0] as ConsoleEntry.ScriptBoundary
        assertEquals(ScriptPhase.PRE, entry.phase)
        assertTrue(entry.scriptPath.endsWith("pre.js"))
    }

    @Test
    fun `error adds ErrorEntry with message`() {
        val out = ConsoleOutput()
        val script = ScriptFile(ScriptLevel.GLOBAL, ScriptPhase.PRE,
            java.nio.file.Path.of("/tmp/pre.js"))
        out.error(script, RuntimeException("boom"))
        val entry = out.entries[0] as ConsoleEntry.ErrorEntry
        assertEquals("boom", entry.message)
    }

    @Test
    fun `http adds HttpEntry with all fields`() {
        val out = ConsoleOutput()
        out.http("POST", "http://x.com", 201, 42L,
            mapOf("X-A" to "1"), """{"a":1}""",
            mapOf("Content-Type" to "application/json"), """{"id":99}""", null)
        val entry = out.entries[0] as ConsoleEntry.HttpEntry
        assertEquals("POST", entry.method)
        assertEquals(201, entry.status)
        assertEquals(42L, entry.durationMs)
        assertNull(entry.error)
    }

    @Test
    fun `entries returns snapshot safe to iterate`() {
        val out = ConsoleOutput()
        out.log(LogLevel.LOG, "a")
        out.log(LogLevel.LOG, "b")
        val snap = out.entries
        out.log(LogLevel.LOG, "c")   // must not affect already-captured snapshot
        assertEquals(2, snap.size)
    }
}
