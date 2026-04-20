package com.sonarwhale.script

data class MutableRequestContext(
    var url: String,
    var method: String,
    var headers: MutableMap<String, String>,
    var body: String
)

data class ResponseContext(
    val status: Int,
    val headers: Map<String, String>,
    val body: String
)

data class TestResult(
    val name: String,
    val passed: Boolean,
    val error: String?
)

class ScriptContext(
    val envSnapshot: MutableMap<String, String>,
    val request: MutableRequestContext,
    val response: ResponseContext? = null
) {
    val testResults: MutableList<TestResult> = mutableListOf()
}
