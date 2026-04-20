package com.sonarwhale.script

sealed class ConsoleEntry {
    abstract val timestampMs: Long

    data class LogEntry(
        override val timestampMs: Long,
        val level: LogLevel,
        val message: String
    ) : ConsoleEntry()

    data class HttpEntry(
        override val timestampMs: Long,
        val method: String,
        val url: String,
        val status: Int,          // 0 = network error
        val durationMs: Long,
        val requestHeaders: Map<String, String>,
        val requestBody: String?,
        val responseHeaders: Map<String, String>,
        val responseBody: String,
        val error: String?        // non-null on network failure
    ) : ConsoleEntry()

    data class ErrorEntry(
        override val timestampMs: Long,
        val scriptPath: String,
        val message: String
    ) : ConsoleEntry()

    data class ScriptBoundary(
        override val timestampMs: Long,
        val scriptPath: String,
        val phase: ScriptPhase
    ) : ConsoleEntry()
}

enum class LogLevel { LOG, WARN, ERROR }
