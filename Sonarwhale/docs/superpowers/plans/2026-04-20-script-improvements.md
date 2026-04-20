# Script Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix Rhino script execution, add a Postman-style Console tab, enable IDE autocomplete for JS files, and add script management to the tree at every level.

**Architecture:** Nine sequential tasks with clear dependency order: data types first (ConsoleEntry/ConsoleOutput), then engine changes, then UI components, then tree/panel wiring. Each task compiles and tests independently.

**Tech Stack:** Kotlin, Swing/IntelliJ UI, Mozilla Rhino 1.7.15, JUnit 5

**Spec:** `docs/superpowers/specs/2026-04-20-script-improvements-design.md`

---

## File Map

| Status | File | Task |
|---|---|---|
| Create | `src/rider/main/kotlin/com/sonarwhale/script/ConsoleEntry.kt` | 1 |
| Create | `src/rider/main/kotlin/com/sonarwhale/script/ConsoleOutput.kt` | 1 |
| Create | `src/rider/test/kotlin/com/sonarwhale/script/ConsoleOutputTest.kt` | 1 |
| Modify | `src/rider/main/kotlin/com/sonarwhale/script/ScriptEngine.kt` | 2 |
| Modify | `src/rider/test/kotlin/com/sonarwhale/script/ScriptEngineTest.kt` | 2 |
| Modify | `src/rider/main/kotlin/com/sonarwhale/script/SonarwhaleScriptService.kt` | 3 |
| Create | `src/rider/main/kotlin/com/sonarwhale/toolwindow/ConsolePanel.kt` | 4 |
| Modify | `src/rider/main/kotlin/com/sonarwhale/toolwindow/ResponsePanel.kt` | 5 |
| Modify | `src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt` | 6 |
| Modify | `src/rider/main/kotlin/com/sonarwhale/toolwindow/EndpointTree.kt` | 7 |
| Create | `src/rider/main/kotlin/com/sonarwhale/toolwindow/FolderScriptsPanel.kt` | 8 |
| Modify | `src/rider/main/kotlin/com/sonarwhale/toolwindow/DetailPanel.kt` | 9 |
| Modify | `src/rider/main/kotlin/com/sonarwhale/toolwindow/SonarwhalePanel.kt` | 9 |

---

## Task 1: ConsoleEntry and ConsoleOutput

**Files:**
- Create: `src/rider/main/kotlin/com/sonarwhale/script/ConsoleEntry.kt`
- Create: `src/rider/main/kotlin/com/sonarwhale/script/ConsoleOutput.kt`
- Create: `src/rider/test/kotlin/com/sonarwhale/script/ConsoleOutputTest.kt`

- [ ] **Step 1: Create ConsoleEntry.kt**

```kotlin
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
```

- [ ] **Step 2: Create ConsoleOutput.kt**

```kotlin
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
```

- [ ] **Step 3: Write tests for ConsoleOutput**

```kotlin
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
```

- [ ] **Step 4: Run tests**

```
./gradlew test --tests "com.sonarwhale.script.ConsoleOutputTest" --info
```

Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/script/ConsoleEntry.kt \
        src/rider/main/kotlin/com/sonarwhale/script/ConsoleOutput.kt \
        src/rider/test/kotlin/com/sonarwhale/script/ConsoleOutputTest.kt
git commit -m "feat: add ConsoleEntry and ConsoleOutput for script console logging"
```

---

## Task 2: ScriptEngine — Classloader Fix, ConsoleOutput, console.log

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/script/ScriptEngine.kt`
- Modify: `src/rider/test/kotlin/com/sonarwhale/script/ScriptEngineTest.kt`

**Context:** `executeChain()` currently calls `Context.enter()` before `try {}`, which means if Rhino fails to init, `Context.exit()` is never called. Also, IntelliJ's `PathClassLoader` prevents Rhino from finding its own classes. Fix: swap the thread classloader around the entire Rhino block.

The `ConsoleOutput` parameter has a default value so existing tests compile without changes.

- [ ] **Step 1: Write failing test for console.log capture**

Add to `ScriptEngineTest.kt`:

```kotlin
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
```

Also add the import at the top of the test file:
```kotlin
import com.sonarwhale.script.ConsoleEntry
import com.sonarwhale.script.ConsoleOutput
import com.sonarwhale.script.LogLevel
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew test --tests "com.sonarwhale.script.ScriptEngineTest" --info
```

Expected: new tests FAIL with "Too many arguments" or "Unresolved reference: ConsoleOutput".

- [ ] **Step 3: Rewrite ScriptEngine.kt**

Replace the entire file content:

```kotlin
package com.sonarwhale.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Executes a list of [ScriptFile]s in order using Mozilla Rhino.
 * All scripts in a chain share the same [ScriptContext] — mutations accumulate.
 *
 * The thread classloader is swapped to the plugin classloader before entering Rhino,
 * because IntelliJ's PathClassLoader would otherwise prevent Rhino from finding
 * its own internal classes (ContextFactory, etc.).
 */
class ScriptEngine {

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    }

    fun executeChain(
        scripts: List<ScriptFile>,
        context: ScriptContext,
        console: ConsoleOutput = ConsoleOutput()
    ) {
        if (scripts.isEmpty()) return

        val prevCl = Thread.currentThread().contextClassLoader
        Thread.currentThread().contextClassLoader = ScriptEngine::class.java.classLoader
        try {
            val cx = Context.enter()
            cx.optimizationLevel = -1   // interpreted mode — no class generation issues
            cx.languageVersion = Context.VERSION_ES6
            try {
                val scope = cx.initStandardObjects()
                ScriptableObject.putProperty(scope, "sw", buildSwObject(cx, scope, context, console))
                ScriptableObject.putProperty(scope, "console", buildConsoleObject(console))

                for (script in scripts) {
                    console.scriptStart(script)
                    runCatching {
                        val code = script.path.readText()
                        cx.evaluateString(scope, code, script.path.name, 1, null)
                    }.onFailure { e ->
                        console.error(script, e)
                        context.testResults.add(
                            TestResult(
                                name = "Script error in ${script.path.name}",
                                passed = false,
                                error = e.message ?: e.javaClass.simpleName
                            )
                        )
                    }
                }
            } finally {
                Context.exit()
            }
        } finally {
            Thread.currentThread().contextClassLoader = prevCl
        }
    }

    private fun buildConsoleObject(console: ConsoleOutput): NativeObject {
        val obj = NativeObject()
        obj.put("log",   obj, rhinoFn { _, _, args ->
            console.log(LogLevel.LOG,   args.joinToString(" ") { it?.toString() ?: "null" }); null })
        obj.put("warn",  obj, rhinoFn { _, _, args ->
            console.log(LogLevel.WARN,  args.joinToString(" ") { it?.toString() ?: "null" }); null })
        obj.put("error", obj, rhinoFn { _, _, args ->
            console.log(LogLevel.ERROR, args.joinToString(" ") { it?.toString() ?: "null" }); null })
        return obj
    }

    private fun buildSwObject(cx: Context, scope: Scriptable, context: ScriptContext, console: ConsoleOutput): NativeObject {
        val sw = NativeObject()

        // ── sw.env ───────────────────────────────────────────────────────────
        val env = NativeObject()
        env.put("get", env, rhinoFn { _, _, args ->
            val key = args.getOrNull(0)?.toString() ?: return@rhinoFn null
            context.envSnapshot[key]
        })
        env.put("set", env, rhinoFn { _, _, args ->
            val key = args.getOrNull(0)?.toString() ?: return@rhinoFn null
            val value = args.getOrNull(1)?.toString() ?: ""
            context.envSnapshot[key] = value
            null
        })
        sw.put("env", sw, env)

        // ── sw.request ───────────────────────────────────────────────────────
        val req = NativeObject()
        req.put("url", req, context.request.url)
        req.put("method", req, context.request.method)
        req.put("body", req, context.request.body)
        val headersObj = NativeObject()
        context.request.headers.forEach { (k, v) -> headersObj.put(k, headersObj, v) }
        req.put("headers", req, headersObj)
        req.put("setHeader", req, rhinoFn { _, _, args ->
            val key   = args.getOrNull(0)?.toString() ?: return@rhinoFn null
            val value = args.getOrNull(1)?.toString() ?: ""
            context.request.headers[key] = value
            null
        })
        req.put("setBody", req, rhinoFn { _, _, args ->
            context.request.body = args.getOrNull(0)?.toString() ?: ""
            null
        })
        req.put("setUrl", req, rhinoFn { _, _, args ->
            context.request.url = args.getOrNull(0)?.toString() ?: ""
            null
        })
        sw.put("request", sw, req)

        // ── sw.response ──────────────────────────────────────────────────────
        context.response?.let { resp ->
            val res = NativeObject()
            res.put("status", res, resp.status)
            res.put("body", res, resp.body)
            val respHeaders = NativeObject()
            resp.headers.forEach { (k, v) -> respHeaders.put(k, respHeaders, v) }
            res.put("headers", res, respHeaders)
            res.put("json", res, rhinoFn { c, s, _ ->
                runCatching { c.evaluateString(s, "(${resp.body})", "json-parse", 1, null) }
                    .getOrDefault(null)
            })
            sw.put("response", sw, res)
        }

        // ── sw.http ──────────────────────────────────────────────────────────
        val http = NativeObject()
        http.put("get", http, rhinoFn { c, s, args ->
            val url     = args.getOrNull(0)?.toString() ?: return@rhinoFn null
            val headers = (args.getOrNull(1) as? NativeObject)?.toHeaderMap() ?: emptyMap()
            makeHttpCall(c, s, "GET", url, null, headers, console)
        })
        http.put("post", http, rhinoFn { c, s, args ->
            val url     = args.getOrNull(0)?.toString() ?: return@rhinoFn null
            val body    = args.getOrNull(1)?.toString() ?: ""
            val headers = (args.getOrNull(2) as? NativeObject)?.toHeaderMap() ?: emptyMap()
            makeHttpCall(c, s, "POST", url, body, headers, console)
        })
        http.put("request", http, rhinoFn { c, s, args ->
            val method  = args.getOrNull(0)?.toString()?.uppercase() ?: "GET"
            val url     = args.getOrNull(1)?.toString() ?: return@rhinoFn null
            val body    = args.getOrNull(2)?.toString()
            val headers = (args.getOrNull(3) as? NativeObject)?.toHeaderMap() ?: emptyMap()
            makeHttpCall(c, s, method, url, body, headers, console)
        })
        sw.put("http", sw, http)

        // ── sw.test ──────────────────────────────────────────────────────────
        sw.put("test", sw, rhinoFn { c, s, args ->
            val name = args.getOrNull(0)?.toString() ?: "unnamed"
            val fn   = args.getOrNull(1) as? Function
            val result = if (fn == null) {
                TestResult(name, false, "test() requires a function as second argument")
            } else {
                runCatching { fn.call(c, s, s, emptyArray()) }
                    .fold(
                        onSuccess = { returnVal ->
                            val passed = returnVal != java.lang.Boolean.FALSE
                            TestResult(name, passed, if (passed) null else "Test function returned false")
                        },
                        onFailure = { e -> TestResult(name, false, e.message ?: e.javaClass.simpleName) }
                    )
            }
            context.testResults.add(result)
            null
        })

        // ── sw.expect ────────────────────────────────────────────────────────
        sw.put("expect", sw, rhinoFn { _, _, args ->
            val actual = args.getOrNull(0)
            buildExpectObject(actual, context)
        })

        return sw
    }

    private fun buildExpectObject(actual: Any?, context: ScriptContext): NativeObject {
        val expect = NativeObject()
        expect.put("toBe", expect, rhinoFn { _, _, args ->
            val expected = args.getOrNull(0)
            val passed = actual == expected
            context.testResults.add(TestResult("expect.toBe", passed,
                if (passed) null else "Expected $expected but got $actual"))
            null
        })
        expect.put("toEqual", expect, rhinoFn { _, _, args ->
            val expected = args.getOrNull(0)?.toString()
            val passed = actual?.toString() == expected
            context.testResults.add(TestResult("expect.toEqual", passed,
                if (passed) null else "Expected $expected but got $actual"))
            null
        })
        expect.put("toBeTruthy", expect, rhinoFn { _, _, _ ->
            val passed = actual != null && actual != false &&
                    actual.toString() != "false" && actual.toString() != "0"
            context.testResults.add(TestResult("expect.toBeTruthy", passed,
                if (passed) null else "Expected truthy but got $actual"))
            null
        })
        expect.put("toBeFalsy", expect, rhinoFn { _, _, _ ->
            val passed = actual == null || actual == false ||
                    actual.toString() == "false" || actual.toString() == "0"
            context.testResults.add(TestResult("expect.toBeFalsy", passed,
                if (passed) null else "Expected falsy but got $actual"))
            null
        })
        expect.put("toContain", expect, rhinoFn { _, _, args ->
            val substr = args.getOrNull(0)?.toString() ?: ""
            val passed = actual?.toString()?.contains(substr) == true
            context.testResults.add(TestResult("expect.toContain", passed,
                if (passed) null else "Expected $actual to contain '$substr'"))
            null
        })
        return expect
    }

    private fun makeHttpCall(
        cx: Context,
        scope: Scriptable,
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        console: ConsoleOutput
    ): NativeObject {
        val start = System.currentTimeMillis()
        return runCatching {
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
            headers.forEach { (k, v) -> runCatching { builder.header(k, v) } }
            val publisher = if (body != null)
                HttpRequest.BodyPublishers.ofString(body)
            else
                HttpRequest.BodyPublishers.noBody()
            builder.method(method, publisher)
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val duration = System.currentTimeMillis() - start
            val respHeaders = response.headers().map().mapValues { (_, vs) -> vs.firstOrNull() ?: "" }
            console.http(method, url, response.statusCode(), duration,
                headers, body, respHeaders, response.body(), null)
            buildResponseObject(cx, scope, response.statusCode(), respHeaders, response.body())
        }.getOrElse { e ->
            val duration = System.currentTimeMillis() - start
            console.http(method, url, 0, duration, headers, body, emptyMap(), "", e.message)
            buildErrorResponseObject(cx, scope, e)
        }
    }

    private fun buildResponseObject(cx: Context, scope: Scriptable, status: Int,
                                    headers: Map<String, String>, responseBody: String): NativeObject {
        val resObj = NativeObject()
        resObj.put("status", resObj, status)
        resObj.put("body", resObj, responseBody)
        val respHeaders = NativeObject()
        headers.forEach { (k, v) -> respHeaders.put(k, respHeaders, v) }
        resObj.put("headers", resObj, respHeaders)
        resObj.put("json", resObj, rhinoFn { c, s, _ ->
            runCatching { c.evaluateString(s, "($responseBody)", "json-parse", 1, null) }
                .getOrDefault(null)
        })
        return resObj
    }

    private fun buildErrorResponseObject(cx: Context, scope: Scriptable, e: Throwable): NativeObject {
        val resObj = NativeObject()
        resObj.put("status", resObj, 0)
        resObj.put("body", resObj, "")
        resObj.put("error", resObj, e.message ?: e.javaClass.simpleName)
        resObj.put("headers", resObj, NativeObject())
        resObj.put("json", resObj, rhinoFn { _, _, _ -> null })
        return resObj
    }

    private fun rhinoFn(block: (cx: Context, scope: Scriptable, args: Array<out Any?>) -> Any?): org.mozilla.javascript.BaseFunction {
        return object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? =
                block(cx, scope, args)
        }
    }
}

private fun NativeObject.toHeaderMap(): Map<String, String> =
    ids.filterIsInstance<String>().associateWith { get(it, this)?.toString() ?: "" }
```

- [ ] **Step 4: Run all ScriptEngine tests**

```
./gradlew test --tests "com.sonarwhale.script.ScriptEngineTest" --info
```

Expected: all 16 tests pass (12 existing + 4 new).

- [ ] **Step 5: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/script/ScriptEngine.kt \
        src/rider/test/kotlin/com/sonarwhale/script/ScriptEngineTest.kt
git commit -m "fix: ScriptEngine classloader fix, ConsoleOutput integration, console.log support"
```

---

## Task 3: SonarwhaleScriptService — Error Handling, ConsoleOutput, getOrCreateScript Overload, jsconfig.json

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/script/SonarwhaleScriptService.kt`

**Context:** `executePreScripts()` and `executePostScripts()` currently have no try/catch — exceptions propagate silently. Both methods get a `ConsoleOutput` parameter so the caller (`RequestPanel`) can share one instance across pre → HTTP → post. `getOrCreateScript()` gets a new overload that works without an endpoint/request (for GLOBAL and TAG levels). A new `getScriptPath()` read-only method returns the expected path without creating files (used by `FolderScriptsPanel` to check existence). `ensureJsConfig()` generates `jsconfig.json`.

- [ ] **Step 1: Replace SonarwhaleScriptService.kt**

Replace the entire file:

```kotlin
package com.sonarwhale.script

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.SavedRequest
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

@Service(Service.Level.PROJECT)
class SonarwhaleScriptService(private val project: Project) {

    private val resolver: ScriptChainResolver by lazy {
        ScriptChainResolver(scriptsRoot())
    }
    private val engine = ScriptEngine()

    /**
     * Executes pre-scripts and returns the modified [ScriptContext].
     * Must be called from a background thread — sw.http makes blocking network calls.
     * Errors are captured into [console] rather than thrown.
     */
    fun executePreScripts(
        endpoint: ApiEndpoint,
        request: SavedRequest,
        url: String,
        headers: Map<String, String>,
        body: String,
        console: ConsoleOutput
    ): ScriptContext {
        val stateService = SonarwhaleStateService.getInstance(project)
        val env = stateService.getActiveEnvironment()?.variables?.toMutableMap() ?: mutableMapOf()
        val ctx = ScriptContext(
            envSnapshot = env,
            request = MutableRequestContext(
                url = url,
                method = endpoint.method.name,
                headers = headers.toMutableMap(),
                body = body
            )
        )
        val tag = endpoint.tags.firstOrNull() ?: "Default"
        val chain = resolver.resolvePreChain(tag, endpoint.method.name, endpoint.path, request.name)
        runCatching { engine.executeChain(chain, ctx, console) }
            .onFailure { e ->
                console.log(LogLevel.ERROR, "Pre-script chain failed: ${e.message ?: e.javaClass.simpleName}")
            }
        flushEnvChanges(ctx.envSnapshot)
        return ctx
    }

    /**
     * Executes post-scripts and returns the collected [TestResult]s.
     * Must be called from a background thread.
     * Errors are captured into [console] rather than thrown.
     */
    fun executePostScripts(
        endpoint: ApiEndpoint,
        request: SavedRequest,
        statusCode: Int,
        responseHeaders: Map<String, String>,
        responseBody: String,
        scriptContext: ScriptContext,
        console: ConsoleOutput
    ): List<TestResult> {
        val response = ResponseContext(statusCode, responseHeaders, responseBody)
        val postCtx = ScriptContext(
            envSnapshot = scriptContext.envSnapshot,
            request = scriptContext.request,
            response = response
        )
        val tag = endpoint.tags.firstOrNull() ?: "Default"
        val chain = resolver.resolvePostChain(tag, endpoint.method.name, endpoint.path, request.name)
        runCatching { engine.executeChain(chain, postCtx, console) }
            .onFailure { e ->
                console.log(LogLevel.ERROR, "Post-script chain failed: ${e.message ?: e.javaClass.simpleName}")
            }
        flushEnvChanges(postCtx.envSnapshot)
        return postCtx.testResults
    }

    /**
     * Returns the expected filesystem path for a script without creating anything.
     * Used by [FolderScriptsPanel] to check whether a script exists.
     */
    fun getScriptPath(
        phase: ScriptPhase,
        level: ScriptLevel,
        tag: String? = null,
        endpoint: ApiEndpoint? = null,
        request: SavedRequest? = null
    ): Path {
        val fileName = if (phase == ScriptPhase.PRE) "pre.js" else "post.js"
        val root = scriptsRoot()
        return when (level) {
            ScriptLevel.GLOBAL   -> root.resolve(fileName)
            ScriptLevel.TAG      -> root.resolve(sanitize(tag)).resolve(fileName)
            ScriptLevel.ENDPOINT -> root.resolve(sanitize(tag))
                .resolve(endpointDir(endpoint)).resolve(fileName)
            ScriptLevel.REQUEST  -> root.resolve(sanitize(tag))
                .resolve(endpointDir(endpoint)).resolve(sanitize(request?.name ?: "Default")).resolve(fileName)
        }
    }

    /**
     * Creates pre.js or post.js at the appropriate level and returns the path.
     * If the file already exists, returns the existing path without overwriting.
     * For GLOBAL level, [tag], [endpoint], and [request] may all be null.
     * For TAG level, [tag] must be provided.
     * For ENDPOINT level, [tag] and [endpoint] must be provided.
     * For REQUEST level, all parameters must be provided.
     */
    fun getOrCreateScript(
        phase: ScriptPhase,
        level: ScriptLevel,
        tag: String? = null,
        endpoint: ApiEndpoint? = null,
        request: SavedRequest? = null
    ): Path {
        val scriptPath = getScriptPath(phase, level, tag, endpoint, request)
        scriptPath.parent.createDirectories()
        ensureSwDts()
        if (!scriptPath.exists()) {
            val comment = when (phase) {
                ScriptPhase.PRE  -> "// Pre-script: runs before the HTTP request\n// Available: sw.env, sw.request, sw.http\n\n"
                ScriptPhase.POST -> "// Post-script: runs after the HTTP response\n// Available: sw.env, sw.request, sw.response, sw.http, sw.test, sw.expect\n\n"
            }
            scriptPath.writeText(comment)
        }
        return scriptPath
    }

    /**
     * Convenience overload for creating a REQUEST-level script (used by Pre/Post buttons in RequestPanel).
     */
    fun getOrCreateScript(
        endpoint: ApiEndpoint,
        request: SavedRequest,
        phase: ScriptPhase,
        level: ScriptLevel = ScriptLevel.REQUEST
    ): Path = getOrCreateScript(
        phase = phase,
        level = level,
        tag = endpoint.tags.firstOrNull() ?: "Default",
        endpoint = endpoint,
        request = request
    )

    /** Writes sw.d.ts and jsconfig.json to .sonarwhale/scripts/ if they do not exist yet. */
    fun ensureSwDts() {
        val root = scriptsRoot()
        root.createDirectories()
        val dts = root.resolve("sw.d.ts")
        if (!dts.exists()) dts.writeText(SW_DTS_CONTENT)
        ensureJsConfig()
    }

    private fun ensureJsConfig() {
        val root = scriptsRoot()
        root.createDirectories()
        val jsconfig = root.resolve("jsconfig.json")
        if (!jsconfig.exists()) {
            jsconfig.writeText("""
                {
                  "compilerOptions": {
                    "checkJs": true,
                    "strict": false,
                    "target": "ES6"
                  },
                  "include": ["./**/*.js"],
                  "exclude": []
                }
            """.trimIndent())
        }
    }

    private fun scriptsRoot(): Path =
        Path.of(project.basePath ?: ".").resolve(".sonarwhale").resolve("scripts")

    private fun sanitize(name: String?): String =
        ScriptChainResolver.sanitizeName(name ?: "Default")

    private fun endpointDir(endpoint: ApiEndpoint?): String =
        if (endpoint != null) ScriptChainResolver.sanitizeEndpointDir(endpoint.method.name, endpoint.path)
        else "unknown"

    private fun flushEnvChanges(snapshot: MutableMap<String, String>) {
        val copy = LinkedHashMap(snapshot)
        ApplicationManager.getApplication().invokeLater {
            val stateService = SonarwhaleStateService.getInstance(project)
            val env = stateService.getActiveEnvironment() ?: return@invokeLater
            stateService.upsertEnvironment(env.copy(variables = copy))
        }
    }

    companion object {
        fun getInstance(project: Project): SonarwhaleScriptService = project.service()

        private val SW_DTS_CONTENT = """
// Sonarwhale Script API — auto-generated, do not edit
// Place sw.d.ts at the root of .sonarwhale/scripts/ for IDE autocomplete

interface SwResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
  error?: string;
  json<T = any>(): T;
}

interface SwExpect {
  toBe(expected: any): void;
  toEqual(expected: any): void;
  toBeTruthy(): void;
  toBeFalsy(): void;
  toContain(substr: string): void;
}

declare const sw: {
  env: {
    get(key: string): string | undefined;
    set(key: string, value: string): void;
  };
  request: {
    url: string;
    method: string;
    headers: Record<string, string>;
    body: string;
    setHeader(key: string, value: string): void;
    setBody(body: string): void;
    setUrl(url: string): void;
  };
  response: {
    status: number;
    headers: Record<string, string>;
    body: string;
    json<T = any>(): T;
  };
  http: {
    get(url: string, headers?: Record<string, string>): SwResponse;
    post(url: string, body: string, headers?: Record<string, string>): SwResponse;
    request(method: string, url: string, body?: string, headers?: Record<string, string>): SwResponse;
  };
  test(name: string, fn: () => void): void;
  expect(value: any): SwExpect;
};
        """.trimIndent()
    }
}
```

- [ ] **Step 2: Run all tests to check compilation**

```
./gradlew test --info
```

Expected: all existing tests still pass (the convenience overload preserves the old call sites).

- [ ] **Step 3: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/script/SonarwhaleScriptService.kt
git commit -m "feat: SonarwhaleScriptService error handling, ConsoleOutput, getScriptPath, jsconfig.json"
```

---

## Task 4: ConsolePanel

**Files:**
- Create: `src/rider/main/kotlin/com/sonarwhale/toolwindow/ConsolePanel.kt`

**Context:** A scrollable panel that renders `ConsoleEntry` items. HTTP entries show a one-line summary and expand on click to show headers and body. No IntelliJ Platform service needed — pure Swing.

- [ ] **Step 1: Create ConsolePanel.kt**

```kotlin
package com.sonarwhale.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.script.ConsoleEntry
import com.sonarwhale.script.LogLevel
import com.sonarwhale.script.ScriptPhase
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.SwingUtilities

class ConsolePanel : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().also {
        it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
        it.border = JBUI.Borders.empty(4)
    }
    private val scroll = JBScrollPane(contentPanel)
    private val clearButton = JButton("Clear").apply { font = font.deriveFont(10f) }
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS")

    init {
        val header = JPanel(BorderLayout(4, 0))
        header.border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(3, 8)
        )
        header.add(JBLabel("Console").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor.GRAY
        }, BorderLayout.WEST)
        header.add(clearButton, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        clearButton.addActionListener { showEntries(emptyList()) }
    }

    fun showEntries(entries: List<ConsoleEntry>) {
        contentPanel.removeAll()
        for (entry in entries) {
            contentPanel.add(buildRow(entry))
        }
        contentPanel.revalidate()
        contentPanel.repaint()
        SwingUtilities.invokeLater {
            val vb = scroll.verticalScrollBar
            vb.value = vb.maximum
        }
    }

    private fun buildRow(entry: ConsoleEntry): JPanel = when (entry) {
        is ConsoleEntry.ScriptBoundary -> buildBoundaryRow(entry)
        is ConsoleEntry.LogEntry       -> buildLogRow(entry)
        is ConsoleEntry.ErrorEntry     -> buildErrorRow(entry)
        is ConsoleEntry.HttpEntry      -> buildHttpRow(entry)
    }

    private fun buildBoundaryRow(entry: ConsoleEntry.ScriptBoundary): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).also { it.isOpaque = false }
        val phase = if (entry.phase == ScriptPhase.PRE) "pre" else "post"
        val name  = entry.scriptPath.substringAfterLast('/').substringAfterLast('\\')
        row.add(JBLabel("▶ $name [$phase]").apply {
            font = font.deriveFont(Font.ITALIC, 10f)
            foreground = JBColor.GRAY
        })
        return row
    }

    private fun buildLogRow(entry: ConsoleEntry.LogEntry): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).also { it.isOpaque = false }
        val color = when (entry.level) {
            LogLevel.LOG   -> JBColor.foreground()
            LogLevel.WARN  -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xBB, 0x33))
            LogLevel.ERROR -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        }
        val prefix = when (entry.level) {
            LogLevel.LOG   -> ""
            LogLevel.WARN  -> "⚠ "
            LogLevel.ERROR -> "✕ "
        }
        row.add(JBLabel(timeFmt.format(Date(entry.timestampMs))).apply {
            font = font.deriveFont(Font.PLAIN, 10f)
            foreground = JBColor.GRAY
        })
        row.add(JBLabel("$prefix${entry.message}").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = color
        })
        return row
    }

    private fun buildErrorRow(entry: ConsoleEntry.ErrorEntry): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1))
        row.background = JBColor(Color(0xFF, 0xEE, 0xEE), Color(0x55, 0x22, 0x22))
        val name = entry.scriptPath.substringAfterLast('/').substringAfterLast('\\')
        row.add(JBLabel("✕ $name: ${entry.message}").apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x55, 0x55))
        })
        return row
    }

    private fun buildHttpRow(entry: ConsoleEntry.HttpEntry): JPanel {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false

        // One-line summary — clickable to expand
        val summary = JPanel(FlowLayout(FlowLayout.LEFT, 4, 1)).also { it.isOpaque = false }
        val statusColor = when {
            entry.status in 200..299 -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x44, 0xCC, 0x77))
            entry.status == 0        -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
            entry.status in 400..499 -> JBColor(Color(0xCC, 0x44, 0x00), Color(0xFF, 0x77, 0x33))
            else                     -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xBB, 0x33))
        }
        val statusText = if (entry.status == 0) "ERROR" else "${entry.status}"
        summary.add(JBLabel("→").apply { foreground = JBColor.GRAY })
        summary.add(JBLabel(entry.method).apply {
            font = font.deriveFont(Font.BOLD, 11f)
        })
        summary.add(JBLabel(entry.url).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
        })
        summary.add(JBLabel("·").apply { foreground = JBColor.GRAY })
        summary.add(JBLabel(statusText).apply { foreground = statusColor; font = font.deriveFont(Font.BOLD, 11f) })
        summary.add(JBLabel("·").apply { foreground = JBColor.GRAY })
        summary.add(JBLabel("${entry.durationMs}ms").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
        })

        // Expandable details (hidden by default)
        val details = buildHttpDetails(entry)
        details.isVisible = false

        summary.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        summary.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                details.isVisible = !details.isVisible
                container.revalidate()
                container.repaint()
            }
        })

        container.add(summary)
        container.add(details)
        return container
    }

    private fun buildHttpDetails(entry: ConsoleEntry.HttpEntry): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.emptyLeft(16)
        panel.isOpaque = false

        fun section(title: String, content: String) {
            if (content.isBlank()) return
            panel.add(JBLabel(title).apply {
                font = font.deriveFont(Font.BOLD, 10f)
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyTop(4)
            })
            val area = JTextArea(content).apply {
                isEditable = false
                font = Font(Font.MONOSPACED, Font.PLAIN, 11)
                lineWrap = true
                wrapStyleWord = false
                border = JBUI.Borders.empty(2, 4)
                background = JBColor.background()
            }
            panel.add(area)
        }

        val reqHeadersText = entry.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        section("Request Headers", reqHeadersText)
        section("Request Body", entry.requestBody ?: "")

        if (entry.error != null) {
            section("Error", entry.error)
        } else {
            val respHeadersText = entry.responseHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            section("Response Headers", respHeadersText)
            section("Response Body", entry.responseBody.take(2000))
        }

        return panel
    }
}
```

- [ ] **Step 2: Verify compilation**

```
./gradlew compileKotlin --info
```

Expected: compiles without errors.

- [ ] **Step 3: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/ConsolePanel.kt
git commit -m "feat: add ConsolePanel for Postman-style script output"
```

---

## Task 5: ResponsePanel — Add Console Tab

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/ResponsePanel.kt`

**Context:** Add a third tab "Console" alongside "Body" and "Tests". Auto-switch to Console tab when there are entries.

- [ ] **Step 1: Modify ResponsePanel.kt**

Add the import and field after the existing imports and field declarations. Then wire it in `init {}` and add the public method.

Add import:
```kotlin
import com.sonarwhale.script.ConsoleEntry
```

Add field after `private val testsScroll`:
```kotlin
private val consolePanel = ConsolePanel()
```

In `init {}`, after `tabs.addTab("Tests", testsScroll)`, add:
```kotlin
tabs.addTab("Console", consolePanel)
```

In `clear()`, after `tabs.setTitleAt(tabs.indexOfComponent(testsScroll), "Tests")`, add:
```kotlin
consolePanel.showEntries(emptyList())
tabs.setTitleAt(tabs.indexOfComponent(consolePanel), "Console")
```

Add new public method after `showTestResults()`:
```kotlin
fun showConsole(entries: List<ConsoleEntry>) {
    val consoleIdx = tabs.indexOfComponent(consolePanel)
    tabs.setTitleAt(consoleIdx, if (entries.isEmpty()) "Console" else "Console (${entries.size})")
    consolePanel.showEntries(entries)
    if (entries.isNotEmpty()) {
        tabs.selectedIndex = consoleIdx
    }
}
```

- [ ] **Step 2: Compile**

```
./gradlew compileKotlin --info
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/ResponsePanel.kt
git commit -m "feat: add Console tab to ResponsePanel"
```

---

## Task 6: RequestPanel — ConsoleOutput Wiring + Remove ⚡

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt`

**Context:** Three changes: (1) remove ⚡ from button labels, (2) create a `ConsoleOutput` at start of `sendRequest()`, pass it to both pre/post script calls, pass result to `responsePanel.showConsole()`, (3) the `executePreScripts`/`executePostScripts` signatures now require a `ConsoleOutput` parameter.

- [ ] **Step 1: Remove ⚡ from button labels**

Find:
```kotlin
private val preScriptButton = JButton("⚡ Pre").apply {
```
Replace with:
```kotlin
private val preScriptButton = JButton("Pre").apply {
```

Find:
```kotlin
private val postScriptButton = JButton("⚡ Post").apply {
```
Replace with:
```kotlin
private val postScriptButton = JButton("Post").apply {
```

- [ ] **Step 2: Add ConsoleOutput import**

Add to the import block:
```kotlin
import com.sonarwhale.script.ConsoleOutput
```

- [ ] **Step 3: Add onConsoleReceived callback**

After the `onTestResultsReceived` declaration:
```kotlin
var onConsoleReceived: ((List<com.sonarwhale.script.ConsoleEntry>) -> Unit)? = null
```

- [ ] **Step 4: Update sendRequest() to thread ConsoleOutput through the chain**

In `sendRequest()`, add this line right before `sendButton.isEnabled = false`:
```kotlin
val consoleOutput = ConsoleOutput()
```

In `doInBackground()`, update the `executePreScripts` call to include `console = consoleOutput`:
```kotlin
val ctx = scriptService.executePreScripts(
    endpoint = endpoint,
    request  = savedRequest,
    url      = rawUrl,
    headers  = initialHeaders,
    body     = initialBody,
    console  = consoleOutput
)
```

Update the `executePostScripts` call to include `console = consoleOutput`:
```kotlin
testResults = scriptService.executePostScripts(
    endpoint        = endpoint,
    request         = savedRequest,
    statusCode      = statusCode,
    responseHeaders = responseHeaders,
    responseBody    = responseBody,
    scriptContext   = ctx,
    console         = consoleOutput
)
```

In `done()`, after `onTestResultsReceived?.invoke(testResults)`, add:
```kotlin
onConsoleReceived?.invoke(consoleOutput.entries)
```

- [ ] **Step 5: Compile**

```
./gradlew compileKotlin --info
```

Expected: no errors. (If the `executePostScripts` call site is missing `console`, the compiler will tell you exactly where.)

- [ ] **Step 6: Wire onConsoleReceived in DetailPanel**

Open `src/rider/main/kotlin/com/sonarwhale/toolwindow/DetailPanel.kt`.

In `init {}`, after `requestPanel.onTestResultsReceived = { results -> responsePanel.showTestResults(results) }`, add:
```kotlin
requestPanel.onConsoleReceived = { entries -> responsePanel.showConsole(entries) }
```

- [ ] **Step 7: Compile**

```
./gradlew compileKotlin --info
```

Expected: no errors.

- [ ] **Step 8: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt \
        src/rider/main/kotlin/com/sonarwhale/toolwindow/DetailPanel.kt
git commit -m "feat: wire ConsoleOutput through sendRequest, remove lightning from Pre/Post buttons"
```

---

## Task 7: EndpointTree — GlobalNode, Context Menu Script Actions

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/EndpointTree.kt`

**Context:** Add `GlobalNode` as the sole direct child of the invisible root. All `ControllerNode`s become children of `GlobalNode`. Add `onGlobalSelected` callback. Extend context menus on all node types to include "Create Pre-Script" and "Create Post-Script" actions. Script creation opens the file in the editor.

- [ ] **Step 1: Add GlobalNode class**

After the `NoResults` object declaration:
```kotlin
object GlobalNode {
    override fun toString() = "Global"
}
```

- [ ] **Step 2: Add onGlobalSelected callback and import**

Add to the imports:
```kotlin
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.SavedRequest
import com.sonarwhale.script.ScriptLevel
import com.sonarwhale.script.ScriptPhase
import com.sonarwhale.script.SonarwhaleScriptService
```

(Some of these may already be imported — only add what is missing.)

Add callback field after `onRequestSelected`:
```kotlin
var onGlobalSelected: (() -> Unit)? = null
```

- [ ] **Step 3: Update selection listener for GlobalNode**

In the `addTreeSelectionListener` block, add the GlobalNode case:
```kotlin
is GlobalNode -> onGlobalSelected?.invoke()
```

The when block should now be:
```kotlin
when (val obj = node.userObject) {
    is EndpointNode   -> onEndpointSelected?.invoke(obj.endpoint)
    is ControllerNode -> onControllerSelected?.invoke(obj)
    is RequestNode    -> onRequestSelected?.invoke(obj.endpoint, obj.request)
    is GlobalNode     -> onGlobalSelected?.invoke()
}
```

- [ ] **Step 4: Update rebuildTree() to add GlobalNode**

Replace the `rebuildTree()` function body:
```kotlin
private fun rebuildTree() {
    val root = DefaultMutableTreeNode("root")
    val globalNode = DefaultMutableTreeNode(GlobalNode)

    if (currentEndpoints.isEmpty()) {
        globalNode.add(DefaultMutableTreeNode(NoResults))
    } else {
        val grouped = currentEndpoints.groupBy { it.tags.firstOrNull() ?: "Endpoints" }
        for ((tag, eps) in grouped.entries.sortedBy { it.key }) {
            val ctrlNode = DefaultMutableTreeNode(ControllerNode(tag, eps))
            for (ep in eps.sortedWith(compareBy({ it.path }, { it.method.name }))) {
                val epNode = DefaultMutableTreeNode(EndpointNode(ep))
                stateService.getRequests(ep.id).forEach { req ->
                    epNode.add(DefaultMutableTreeNode(RequestNode(ep, req)))
                }
                ctrlNode.add(epNode)
            }
            globalNode.add(ctrlNode)
        }
    }

    root.add(globalNode)
    model = DefaultTreeModel(root)
    expandAllRows()
}
```

- [ ] **Step 5: Update showContextMenu to handle GlobalNode and ControllerNode**

In `showContextMenu()`, update the when block:
```kotlin
when (val obj = node.userObject) {
    is GlobalNode     -> buildGlobalMenu(group)
    is ControllerNode -> buildControllerMenu(group, obj)
    is EndpointNode   -> buildEndpointMenu(group, obj.endpoint)
    is RequestNode    -> buildRequestMenu(group, obj.endpoint, obj.request)
    else              -> return
}
```

- [ ] **Step 6: Add script action helper and new menu builders**

Add a private helper method for opening/creating a script file (call it from all menu builders):

```kotlin
private fun openOrCreateScriptInBackground(
    phase: ScriptPhase,
    level: ScriptLevel,
    tag: String? = null,
    endpoint: ApiEndpoint? = null,
    request: SavedRequest? = null
) {
    val scriptService = SonarwhaleScriptService.getInstance(project)
    ProgressManager.getInstance().run(
        object : Task.Backgroundable(project, "Creating script…", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val path = scriptService.getOrCreateScript(phase, level, tag, endpoint, request)
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
        }
    )
}
```

Add `buildGlobalMenu()`:
```kotlin
private fun buildGlobalMenu(group: DefaultActionGroup) {
    group.add(object : AnAction("Create Pre-Script (Global)",
        "Create global pre.js that runs before every request", AllIcons.Actions.Edit) {
        override fun actionPerformed(e: AnActionEvent) {
            openOrCreateScriptInBackground(ScriptPhase.PRE, ScriptLevel.GLOBAL)
        }
    })
    group.add(object : AnAction("Create Post-Script (Global)",
        "Create global post.js that runs after every request", AllIcons.Actions.Edit) {
        override fun actionPerformed(e: AnActionEvent) {
            openOrCreateScriptInBackground(ScriptPhase.POST, ScriptLevel.GLOBAL)
        }
    })
}
```

Add `buildControllerMenu()`:
```kotlin
private fun buildControllerMenu(group: DefaultActionGroup, node: ControllerNode) {
    group.add(object : AnAction("Create Pre-Script (${node.name})",
        "Create pre.js for this tag/controller", AllIcons.Actions.Edit) {
        override fun actionPerformed(e: AnActionEvent) {
            openOrCreateScriptInBackground(ScriptPhase.PRE, ScriptLevel.TAG, tag = node.name)
        }
    })
    group.add(object : AnAction("Create Post-Script (${node.name})",
        "Create post.js for this tag/controller", AllIcons.Actions.Edit) {
        override fun actionPerformed(e: AnActionEvent) {
            openOrCreateScriptInBackground(ScriptPhase.POST, ScriptLevel.TAG, tag = node.name)
        }
    })
}
```

Add script actions to `buildEndpointMenu()`. After the "Copy Path" item, add:
```kotlin
group.add(Separator.getInstance())
group.add(object : AnAction("Create Pre-Script (Endpoint)",
    "Create pre.js for this endpoint", AllIcons.Actions.Edit) {
    override fun actionPerformed(e: AnActionEvent) {
        openOrCreateScriptInBackground(ScriptPhase.PRE, ScriptLevel.ENDPOINT,
            tag = endpoint.tags.firstOrNull() ?: "Default", endpoint = endpoint)
    }
})
group.add(object : AnAction("Create Post-Script (Endpoint)",
    "Create post.js for this endpoint", AllIcons.Actions.Edit) {
    override fun actionPerformed(e: AnActionEvent) {
        openOrCreateScriptInBackground(ScriptPhase.POST, ScriptLevel.ENDPOINT,
            tag = endpoint.tags.firstOrNull() ?: "Default", endpoint = endpoint)
    }
})
```

Add script actions to `buildRequestMenu()`. After the "Copy Path" item, add:
```kotlin
group.add(Separator.getInstance())
group.add(object : AnAction("Create Pre-Script (Request)",
    "Create pre.js for this specific request", AllIcons.Actions.Edit) {
    override fun actionPerformed(e: AnActionEvent) {
        openOrCreateScriptInBackground(ScriptPhase.PRE, ScriptLevel.REQUEST,
            tag = endpoint.tags.firstOrNull() ?: "Default",
            endpoint = endpoint, request = request)
    }
})
group.add(object : AnAction("Create Post-Script (Request)",
    "Create post.js for this specific request", AllIcons.Actions.Edit) {
    override fun actionPerformed(e: AnActionEvent) {
        openOrCreateScriptInBackground(ScriptPhase.POST, ScriptLevel.REQUEST,
            tag = endpoint.tags.firstOrNull() ?: "Default",
            endpoint = endpoint, request = request)
    }
})
```

- [ ] **Step 7: Update cell renderer for GlobalNode**

In `EndpointTreeCellRenderer.customizeCellRenderer()`, add the `GlobalNode` case before `is ControllerNode`:

```kotlin
is GlobalNode -> {
    append("Global", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    icon = AllIcons.Nodes.Package
}
```

- [ ] **Step 8: Compile**

```
./gradlew compileKotlin --info
```

Expected: no errors.

- [ ] **Step 9: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/EndpointTree.kt
git commit -m "feat: add GlobalNode to tree, script creation context menus at all levels"
```

---

## Task 8: FolderScriptsPanel

**Files:**
- Create: `src/rider/main/kotlin/com/sonarwhale/toolwindow/FolderScriptsPanel.kt`

**Context:** Shown when Global or Tag/Controller is selected. Shows Pre-Script and Post-Script rows with Create/Edit/Delete buttons. Auth and Variables sections are greyed-out placeholders. Takes `SonarwhaleScriptService` to check path existence and create scripts.

- [ ] **Step 1: Create FolderScriptsPanel.kt**

```kotlin
package com.sonarwhale.toolwindow

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.SavedRequest
import com.sonarwhale.script.ScriptLevel
import com.sonarwhale.script.ScriptPhase
import com.sonarwhale.script.SonarwhaleScriptService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSeparator

/**
 * Detail panel shown when the Global node or a Tag/Controller node is selected.
 * Displays scripts for that level with Create/Edit/Delete actions.
 * Auth and Variables sections are placeholders for future features.
 */
class FolderScriptsPanel(
    private val project: Project,
    private val level: ScriptLevel,
    private val tag: String? = null,
    private val endpoint: ApiEndpoint? = null,
    private val request: SavedRequest? = null,
    private val onRefresh: () -> Unit = {}   // called after delete to rebuild the panel
) : JPanel(BorderLayout()) {

    private val scriptService = SonarwhaleScriptService.getInstance(project)

    init {
        border = JBUI.Borders.empty(16)
        add(buildContent(), BorderLayout.NORTH)
    }

    private fun buildContent(): JPanel {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0; fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0; anchor = GridBagConstraints.NORTHWEST
            insets = Insets(0, 0, 4, 0)
        }

        // Title
        val titleText = when {
            level == ScriptLevel.GLOBAL -> "Global"
            tag != null                 -> tag
            else                        -> "Scripts"
        }
        panel.add(JBLabel(titleText).apply {
            font = font.deriveFont(Font.BOLD, 14f)
        }, gbc.also { it.gridy = 0; it.insets = Insets(0, 0, 12, 0) })

        // Scripts section header
        panel.add(sectionHeader("Scripts"), gbc.also { it.gridy = 1 })

        // Pre-script row
        panel.add(scriptRow(ScriptPhase.PRE), gbc.also { it.gridy = 2; it.insets = Insets(4, 8, 4, 0) })

        // Post-script row
        panel.add(scriptRow(ScriptPhase.POST), gbc.also { it.gridy = 3; it.insets = Insets(4, 8, 12, 0) })

        // Auth section (placeholder)
        panel.add(JSeparator(), gbc.also { it.gridy = 4; it.insets = Insets(0, 0, 8, 0) })
        panel.add(sectionHeader("Auth"), gbc.also { it.gridy = 5 })
        panel.add(JBLabel("Authentication settings — coming soon").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC, 11f)
            border = JBUI.Borders.emptyLeft(8)
        }, gbc.also { it.gridy = 6; it.insets = Insets(4, 8, 12, 0) })

        // Variables section (placeholder)
        panel.add(JSeparator(), gbc.also { it.gridy = 7; it.insets = Insets(0, 0, 8, 0) })
        panel.add(sectionHeader("Variables"), gbc.also { it.gridy = 8 })
        panel.add(JBLabel("Environment variables scoped to this level — coming soon").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC, 11f)
            border = JBUI.Borders.emptyLeft(8)
        }, gbc.also { it.gridy = 9; it.insets = Insets(4, 8, 0, 0) })

        return panel
    }

    private fun sectionHeader(text: String): JBLabel =
        JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor.GRAY
        }

    private fun scriptRow(phase: ScriptPhase): JPanel {
        val phaseName = if (phase == ScriptPhase.PRE) "Pre" else "Post"
        val scriptPath = scriptService.getScriptPath(phase, level, tag, endpoint, request)
        val exists = Files.exists(scriptPath)

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).also { it.isOpaque = false }
        row.add(JBLabel("$phaseName   ").apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        })

        if (exists) {
            // Show truncated path and Edit / Delete buttons
            val displayPath = scriptPath.toString().let {
                val max = 50
                if (it.length > max) "…" + it.takeLast(max) else it
            }
            row.add(JBLabel(displayPath).apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor.GRAY
                toolTipText = scriptPath.toString()
            })
            val editBtn = JButton("Edit").apply { font = font.deriveFont(10f) }
            val deleteBtn = JButton("Delete").apply {
                font = font.deriveFont(10f)
                foreground = JBColor(Color(0xCC, 0x33, 0x00), Color(0xFF, 0x66, 0x44))
            }
            editBtn.addActionListener { openFile(scriptPath) }
            deleteBtn.addActionListener {
                val answer = Messages.showYesNoDialog(
                    project,
                    "Delete ${scriptPath.fileName}?",
                    "Delete Script",
                    null
                )
                if (answer == Messages.YES) {
                    Files.deleteIfExists(scriptPath)
                    onRefresh()   // DetailPanel recreates FolderScriptsPanel, re-reading filesystem
                }
            }
            row.add(editBtn)
            row.add(deleteBtn)
        } else {
            row.add(JBLabel("(no script)").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC, 11f)
            })
            val createBtn = JButton("Create").apply { font = font.deriveFont(10f) }
            createBtn.addActionListener {
                ProgressManager.getInstance().run(
                    object : Task.Backgroundable(project, "Creating script…", false) {
                        override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                            val path = scriptService.getOrCreateScript(phase, level, tag, endpoint, request)
                            openFile(path)
                        }
                    }
                )
            }
            row.add(createBtn)
        }

        return row
    }

    private fun openFile(path: java.nio.file.Path) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }
}
```

- [ ] **Step 2: Compile**

```
./gradlew compileKotlin --info
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/FolderScriptsPanel.kt
git commit -m "feat: add FolderScriptsPanel for Global and Tag level script management"
```

---

## Task 9: DetailPanel and SonarwhalePanel — Wire GlobalNode and FolderScriptsPanel

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/DetailPanel.kt`
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/SonarwhalePanel.kt`

**Context:** `DetailPanel` gains a `"folder"` card slot and two new public methods: `showGlobal()` and `showFolderPanel()`. `showController()` is updated to use `showFolderPanel()` instead of the old minimal layout. `SonarwhalePanel` wires `onGlobalSelected`.

- [ ] **Step 1: Update DetailPanel.kt**

Add import:
```kotlin
import com.sonarwhale.script.ScriptLevel
```

In `init {}` of `DetailPanel`, add a `folderCardHolder` field and a fourth card. Replace the `cardPanel` construction:

After the `private val controllerPanel` declaration, add:
```kotlin
private val folderCardHolder = JPanel(BorderLayout())
```

In the `cardPanel` `also {}` block, after `it.add(controllerPanel, "controller")`, add:
```kotlin
it.add(folderCardHolder, "folder")
```

Replace the entire `showController()` function:
```kotlin
fun showController(node: ControllerNode) {
    showFolderPanel(ScriptLevel.TAG, tag = node.name)
}
```

Add `showGlobal()`:
```kotlin
fun showGlobal() {
    showFolderPanel(ScriptLevel.GLOBAL)
}
```

Add `showFolderPanel()` (private helper):
```kotlin
private fun showFolderPanel(level: ScriptLevel, tag: String? = null) {
    headerHolder.isVisible = false
    folderCardHolder.removeAll()
    folderCardHolder.add(FolderScriptsPanel(project, level, tag,
        onRefresh = { showFolderPanel(level, tag) }), BorderLayout.CENTER)
    folderCardHolder.revalidate()
    folderCardHolder.repaint()
    cardLayout.show(cardPanel, "folder")
    revalidate(); repaint()
}
```

- [ ] **Step 2: Wire onGlobalSelected in SonarwhalePanel.kt**

After the `endpointTree.onControllerSelected` block in `SonarwhalePanel.init {}`, add:
```kotlin
endpointTree.onGlobalSelected = {
    service.setCurrentEndpoint(null)
    detailPanel.showGlobal()
}
```

- [ ] **Step 3: Compile**

```
./gradlew compileKotlin --info
```

Expected: no errors.

- [ ] **Step 4: Run all tests**

```
./gradlew test --info
```

Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/DetailPanel.kt \
        src/rider/main/kotlin/com/sonarwhale/toolwindow/SonarwhalePanel.kt
git commit -m "feat: wire GlobalNode and FolderScriptsPanel into detail view"
```

---

## Done

All nine tasks complete. Manual verification steps:
1. Build the plugin: `./gradlew buildPlugin`
2. Run in sandbox: `./gradlew runIde` (or run via IDE run config)
3. Open a project with an OpenAPI source configured
4. Verify: tree shows "Global" root, expanding shows controllers
5. Right-click Global → "Create Pre-Script (Global)" → `.sonarwhale/scripts/pre.js` opens in editor
6. Right-click a controller → "Create Pre-Script (tag)" → opens tag-level script
7. Click Global in tree → detail panel shows Scripts / Auth (placeholder) / Variables (placeholder)
8. Send a request with a script → Console tab appears with script output
9. Verify `sw.d.ts` and `jsconfig.json` created in `.sonarwhale/scripts/`
10. Open a script file → verify IDE autocomplete suggests `sw.env.get(...)` etc.
