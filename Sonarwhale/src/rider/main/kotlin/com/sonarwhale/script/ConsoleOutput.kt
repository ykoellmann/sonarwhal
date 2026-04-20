package com.sonarwhale.script

import java.util.concurrent.CopyOnWriteArrayList

class ConsoleOutput {
    private val _entries = CopyOnWriteArrayList<ConsoleEntry>()
    val entries: List<ConsoleEntry> get() = _entries.toList()

    fun log(level: LogLevel, message: String) {
        _entries += ConsoleEntry.LogEntry(now(), level, message)
    }

    fun scriptStart(script: ScriptFile) {
        _entries += ConsoleEntry.ScriptBoundary(now(), script.path.toString(), script.phase)
    }

    fun error(script: ScriptFile, e: Throwable) {
        _entries += ConsoleEntry.ErrorEntry(now(), script.path.toString(),
            e.message ?: e.javaClass.simpleName)
    }

    fun http(
        method: String, url: String, status: Int, durationMs: Long,
        requestHeaders: Map<String, String>, requestBody: String?,
        responseHeaders: Map<String, String>, responseBody: String,
        error: String?
    ) {
        _entries += ConsoleEntry.HttpEntry(now(), method, url, status, durationMs,
            requestHeaders, requestBody, responseHeaders, responseBody, error)
    }

    private fun now() = System.currentTimeMillis()
}
