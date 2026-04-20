# Pre/Post Scripts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add hierarchical JavaScript pre/post scripts to Sonarwhale so users can run auth flows, mutate requests, and assert on responses using `.js` files in `.sonarwhale/scripts/`.

**Architecture:** Scripts live on the filesystem at `.sonarwhale/scripts/` in a hierarchy matching global → tag → endpoint → request. `ScriptChainResolver` walks the tree and collects the ordered chain; `ScriptEngine` executes the chain via Mozilla Rhino, exposing a `sw.*` API. `SonarwhaleScriptService` coordinates both and is called from `RequestPanel.sendRequest()` before and after the HTTP call.

**Tech Stack:** Kotlin, Mozilla Rhino 1.7.15 (JVM JS engine), JUnit 5, IntelliJ Platform SDK (VirtualFile, FileEditorManager), Java standard `HttpClient` (for `sw.http`).

---

## File Map

**Create:**
- `src/rider/main/kotlin/com/sonarwhale/script/ScriptFile.kt` — metadata: level, phase, path, inheritOff
- `src/rider/main/kotlin/com/sonarwhale/script/ScriptContext.kt` — mutable request/response/env data + TestResult
- `src/rider/main/kotlin/com/sonarwhale/script/ScriptChainResolver.kt` — walks `.sonarwhale/scripts/` and returns ordered `List<ScriptFile>`
- `src/rider/main/kotlin/com/sonarwhale/script/ScriptEngine.kt` — executes script chain via Rhino, exposes `sw.*`
- `src/rider/main/kotlin/com/sonarwhale/script/SonarwhaleScriptService.kt` — project-level service, writes `sw.d.ts`, orchestrates pre/post execution
- `src/rider/test/kotlin/com/sonarwhale/script/ScriptChainResolverTest.kt`
- `src/rider/test/kotlin/com/sonarwhale/script/ScriptEngineTest.kt`

**Modify:**
- `build.gradle.kts` — add Rhino dependency, test sourceset, JUnit 5
- `src/rider/main/resources/META-INF/plugin.xml` — register `SonarwhaleScriptService`
- `src/rider/main/kotlin/com/sonarwhale/toolwindow/ResponsePanel.kt` — add "Tests" tab
- `src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt` — Create Pre/Post Script buttons + wire scripts into `sendRequest()`

---

## Task 1: Build Setup — Rhino + Test Infrastructure

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/rider/test/kotlin/com/sonarwhale/script/` (directory only)

- [ ] **Step 1: Add Rhino and JUnit 5 to build.gradle.kts**

In `build.gradle.kts`, add a test sourceset and new dependencies. The existing `sourceSets` block only has `main` — add `test`. Add `implementation` for Rhino and `testImplementation` for JUnit 5. Add a `tasks.test` block.

Replace:
```kotlin
sourceSets {
    main {
        java.srcDir("src/rider/main/java")
        kotlin.srcDir("src/rider/main/kotlin")
        resources.srcDir("src/rider/main/resources")
    }
}
```

With:
```kotlin
sourceSets {
    main {
        java.srcDir("src/rider/main/java")
        kotlin.srcDir("src/rider/main/kotlin")
        resources.srcDir("src/rider/main/resources")
    }
    test {
        kotlin.srcDir("src/rider/test/kotlin")
    }
}
```

Replace:
```kotlin
dependencies {
    intellijPlatform {
        rider(ProductVersion, useInstaller = false)
        jetbrainsRuntime()
        // Python Community Edition — installed in every sandbox; active in runIdePython
        plugin("PythonCore:${PythonPluginVersion}")
    }
}
```

With:
```kotlin
dependencies {
    intellijPlatform {
        rider(ProductVersion, useInstaller = false)
        jetbrainsRuntime()
        // Python Community Edition — installed in every sandbox; active in runIdePython
        plugin("PythonCore:${PythonPluginVersion}")
    }
    implementation("org.mozilla:rhino:1.7.15")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

Add after `tasks.compileKotlin { ... }`:
```kotlin
tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Create test directory structure**

```bash
mkdir -p src/rider/test/kotlin/com/sonarwhale/script
```

- [ ] **Step 3: Verify build compiles**

```bash
./gradlew compileKotlin compileTestKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add build.gradle.kts
git commit -m "build: add Rhino dependency and JUnit 5 test infrastructure"
```

---

## Task 2: Data Models — ScriptFile, ScriptContext, TestResult

**Files:**
- Create: `src/rider/main/kotlin/com/sonarwhale/script/ScriptFile.kt`
- Create: `src/rider/main/kotlin/com/sonarwhale/script/ScriptContext.kt`

- [ ] **Step 1: Write failing test for ScriptContext**

Create `src/rider/test/kotlin/com/sonarwhale/script/ScriptContextTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.sonarwhale.script.ScriptContextTest"
```

Expected: FAIL — `ScriptContext`, `MutableRequestContext`, `TestResult` not found.

- [ ] **Step 3: Create ScriptFile.kt**

Create `src/rider/main/kotlin/com/sonarwhale/script/ScriptFile.kt`:

```kotlin
package com.sonarwhale.script

import java.nio.file.Path

enum class ScriptLevel { GLOBAL, TAG, ENDPOINT, REQUEST }
enum class ScriptPhase { PRE, POST }

data class ScriptFile(
    val level: ScriptLevel,
    val phase: ScriptPhase,
    val path: Path,
    val inheritOff: Boolean
)
```

- [ ] **Step 4: Create ScriptContext.kt**

Create `src/rider/main/kotlin/com/sonarwhale/script/ScriptContext.kt`:

```kotlin
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
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests "com.sonarwhale.script.ScriptContextTest"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/script/ScriptFile.kt \
        src/rider/main/kotlin/com/sonarwhale/script/ScriptContext.kt \
        src/rider/test/kotlin/com/sonarwhale/script/ScriptContextTest.kt
git commit -m "feat: add ScriptFile, ScriptContext, TestResult models"
```

---

## Task 3: ScriptChainResolver

**Files:**
- Create: `src/rider/main/kotlin/com/sonarwhale/script/ScriptChainResolver.kt`
- Create: `src/rider/test/kotlin/com/sonarwhale/script/ScriptChainResolverTest.kt`

The resolver finds scripts by walking `.sonarwhale/scripts/` using the sanitized tag, endpoint directory, and request name. It respects `inherit.off` by stopping collection at that level.

**Sanitizing rule:** For a given string, replace ` ` with `_`, replace `/` with `_`, strip leading `_`. For endpoint directory: `"{METHOD}__{sanitized-path}"` where sanitized-path is the path with leading `/` removed then all remaining `/` replaced with `_`.

- [ ] **Step 1: Write failing tests for ScriptChainResolver**

Create `src/rider/test/kotlin/com/sonarwhale/script/ScriptChainResolverTest.kt`:

```kotlin
package com.sonarwhale.script

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class ScriptChainResolverTest {

    @TempDir
    lateinit var tempDir: Path

    private fun resolver() = ScriptChainResolver(tempDir)

    private fun scriptDir(vararg parts: String): Path =
        tempDir.resolve("scripts").let { base ->
            parts.fold(base) { acc, part -> acc.resolve(part) }
        }.also { it.createDirectories() }

    private fun pre(vararg dirs: String): Path =
        scriptDir(*dirs).resolve("pre.js").also { it.createFile(); it.writeText("// pre") }

    private fun post(vararg dirs: String): Path =
        scriptDir(*dirs).resolve("post.js").also { it.createFile(); it.writeText("// post") }

    private fun inheritOff(vararg dirs: String) =
        scriptDir(*dirs).resolve("inherit.off").also { it.createFile() }

    @Test
    fun `resolves global pre script`() {
        val globalPre = pre()
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.any { it.path == globalPre && it.level == ScriptLevel.GLOBAL })
    }

    @Test
    fun `resolves tag pre script`() {
        val tagPre = pre("Users")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.any { it.path == tagPre && it.level == ScriptLevel.TAG })
    }

    @Test
    fun `resolves endpoint pre script`() {
        val endpointPre = pre("Users", "GET__api_users")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.any { it.path == endpointPre && it.level == ScriptLevel.ENDPOINT })
    }

    @Test
    fun `resolves request pre script`() {
        val reqPre = pre("Users", "GET__api_users", "Default")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.any { it.path == reqPre && it.level == ScriptLevel.REQUEST })
    }

    @Test
    fun `pre chain order is global to request`() {
        val globalPre = pre()
        val tagPre = pre("Users")
        val endpointPre = pre("Users", "GET__api_users")
        val reqPre = pre("Users", "GET__api_users", "Default")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        val levels = chain.map { it.level }
        assertEquals(
            listOf(ScriptLevel.GLOBAL, ScriptLevel.TAG, ScriptLevel.ENDPOINT, ScriptLevel.REQUEST),
            levels
        )
    }

    @Test
    fun `post chain order is request to global`() {
        val globalPost = post()
        val tagPost = post("Users")
        val endpointPost = post("Users", "GET__api_users")
        val reqPost = post("Users", "GET__api_users", "Default")
        val chain = resolver().resolvePostChain("Users", "GET", "/api/users", "Default")
        val levels = chain.map { it.level }
        assertEquals(
            listOf(ScriptLevel.REQUEST, ScriptLevel.ENDPOINT, ScriptLevel.TAG, ScriptLevel.GLOBAL),
            levels
        )
    }

    @Test
    fun `inherit off at tag level stops parent scripts`() {
        val globalPre = pre()
        inheritOff("Users")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL })
    }

    @Test
    fun `inherit off at endpoint level stops tag and global`() {
        val globalPre = pre()
        val tagPre = pre("Users")
        inheritOff("Users", "GET__api_users")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL || it.level == ScriptLevel.TAG })
    }

    @Test
    fun `sanitizes path with braces and slashes`() {
        val endpointPre = pre("Users", "GET__api_users_{id}")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users/{id}", "Default")
        assertTrue(chain.any { it.path == endpointPre })
    }

    @Test
    fun `missing scripts directory returns empty chain`() {
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.isEmpty())
    }

    @Test
    fun `missing script file at a level is skipped silently`() {
        val globalPre = pre() // only global exists
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertEquals(1, chain.size)
        assertEquals(ScriptLevel.GLOBAL, chain[0].level)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.sonarwhale.script.ScriptChainResolverTest"
```

Expected: FAIL — `ScriptChainResolver` not found.

- [ ] **Step 3: Implement ScriptChainResolver.kt**

Create `src/rider/main/kotlin/com/sonarwhale/script/ScriptChainResolver.kt`:

```kotlin
package com.sonarwhale.script

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Resolves the ordered list of pre/post script files for a given endpoint + request.
 * Scripts live at [projectRoot]/.sonarwhale/scripts/ in a directory hierarchy.
 *
 * Pre-chain: global → tag → endpoint → request
 * Post-chain: request → endpoint → tag → global  (reversed)
 *
 * inherit.off at any level stops all parent levels from being included.
 */
class ScriptChainResolver(private val projectRoot: Path) {

    private val scriptsRoot: Path get() = projectRoot.resolve("scripts")

    fun resolvePreChain(tag: String, method: String, path: String, requestName: String): List<ScriptFile> =
        buildChain(tag, method, path, requestName, ScriptPhase.PRE)

    fun resolvePostChain(tag: String, method: String, path: String, requestName: String): List<ScriptFile> =
        buildChain(tag, method, path, requestName, ScriptPhase.POST).reversed()

    private fun buildChain(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        phase: ScriptPhase
    ): List<ScriptFile> {
        if (!scriptsRoot.exists()) return emptyList()

        val endpointDirName = sanitizeEndpointDir(method, path)
        val requestDirName  = sanitizeName(requestName)
        val tagDirName      = sanitizeName(tag)
        val fileName        = if (phase == ScriptPhase.PRE) "pre.js" else "post.js"

        data class Level(val dir: Path, val level: ScriptLevel)

        val levels = listOf(
            Level(scriptsRoot, ScriptLevel.GLOBAL),
            Level(scriptsRoot.resolve(tagDirName), ScriptLevel.TAG),
            Level(scriptsRoot.resolve(tagDirName).resolve(endpointDirName), ScriptLevel.ENDPOINT),
            Level(scriptsRoot.resolve(tagDirName).resolve(endpointDirName).resolve(requestDirName), ScriptLevel.REQUEST)
        )

        // Walk from request inward to find the deepest inherit.off
        // Then only keep levels at or below (inner) that point.
        val firstInheritOff = levels.indexOfFirst { it.dir.resolve("inherit.off").exists() }

        val includedLevels = if (firstInheritOff == -1) {
            levels
        } else {
            levels.drop(firstInheritOff)
        }

        return includedLevels.mapNotNull { (dir, level) ->
            val scriptFile = dir.resolve(fileName)
            if (scriptFile.exists() && scriptFile.isRegularFile()) {
                ScriptFile(level = level, phase = phase, path = scriptFile, inheritOff = false)
            } else null
        }
    }

    companion object {
        fun sanitizeName(name: String): String =
            name.trim().replace(' ', '_').replace('/', '_').trimStart('_')

        fun sanitizeEndpointDir(method: String, path: String): String {
            val sanitizedPath = path.trimStart('/').replace('/', '_')
            return "${method.uppercase()}__${sanitizedPath}"
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.sonarwhale.script.ScriptChainResolverTest"
```

Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/script/ScriptChainResolver.kt \
        src/rider/test/kotlin/com/sonarwhale/script/ScriptChainResolverTest.kt
git commit -m "feat: implement ScriptChainResolver with inherit.off support"
```

---

## Task 4: ScriptEngine (Rhino)

**Files:**
- Create: `src/rider/main/kotlin/com/sonarwhale/script/ScriptEngine.kt`
- Create: `src/rider/test/kotlin/com/sonarwhale/script/ScriptEngineTest.kt`

`ScriptEngine` executes a `List<ScriptFile>` in order using Mozilla Rhino. It builds a `sw` JS object from the `ScriptContext` and populates the scope before each chain run.

- [ ] **Step 1: Write failing tests for ScriptEngine**

Create `src/rider/test/kotlin/com/sonarwhale/script/ScriptEngineTest.kt`:

```kotlin
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
        return ScriptFile(ScriptLevel.GLOBAL, ScriptPhase.PRE, file, false)
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
        ctx.testResults  // empty
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
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.sonarwhale.script.ScriptEngineTest"
```

Expected: FAIL — `ScriptEngine` not found.

- [ ] **Step 3: Implement ScriptEngine.kt**

Create `src/rider/main/kotlin/com/sonarwhale/script/ScriptEngine.kt`:

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
 */
class ScriptEngine {

    fun executeChain(scripts: List<ScriptFile>, context: ScriptContext) {
        if (scripts.isEmpty()) return

        val cx = Context.enter()
        cx.optimizationLevel = -1 // interpreted mode — no class generation issues
        cx.languageVersion = Context.VERSION_ES6
        try {
            val scope = cx.initStandardObjects()
            val swObj = buildSwObject(cx, scope, context)
            ScriptableObject.putProperty(scope, "sw", swObj)

            for (script in scripts) {
                runCatching {
                    val code = script.path.readText()
                    cx.evaluateString(scope, code, script.path.name, 1, null)
                }.onFailure { e ->
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
    }

    private fun buildSwObject(cx: Context, scope: Scriptable, context: ScriptContext): NativeObject {
        val sw = NativeObject()

        // ── sw.env ───────────────────────────────────────────────────────────
        val env = NativeObject()
        env.put("get", env, rhino { _, _, _, args ->
            val key = args.getOrNull(0)?.toString() ?: return@rhino null
            context.envSnapshot[key]
        })
        env.put("set", env, rhino { _, _, _, args ->
            val key = args.getOrNull(0)?.toString() ?: return@rhino null
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
        req.put("setHeader", req, rhino { _, _, _, args ->
            val key   = args.getOrNull(0)?.toString() ?: return@rhino null
            val value = args.getOrNull(1)?.toString() ?: ""
            context.request.headers[key] = value
            null
        })
        req.put("setBody", req, rhino { _, _, _, args ->
            context.request.body = args.getOrNull(0)?.toString() ?: ""
            null
        })
        req.put("setUrl", req, rhino { _, _, _, args ->
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
            res.put("json", res, rhino { c, s, _, _ ->
                runCatching { c.evaluateString(s, "(${resp.body})", "json-parse", 1, null) }
                    .getOrDefault(null)
            })
            sw.put("response", sw, res)
        }

        // ── sw.http ──────────────────────────────────────────────────────────
        val http = NativeObject()
        http.put("get", http, rhino { c, s, _, args ->
            val url     = args.getOrNull(0)?.toString() ?: return@rhino null
            val headers = (args.getOrNull(1) as? NativeObject)?.toHeaderMap() ?: emptyMap()
            makeHttpCall(cx, scope, "GET", url, null, headers)
        })
        http.put("post", http, rhino { c, s, _, args ->
            val url     = args.getOrNull(0)?.toString() ?: return@rhino null
            val body    = args.getOrNull(1)?.toString() ?: ""
            val headers = (args.getOrNull(2) as? NativeObject)?.toHeaderMap() ?: emptyMap()
            makeHttpCall(cx, scope, "POST", url, body, headers)
        })
        http.put("request", http, rhino { c, s, _, args ->
            val method  = args.getOrNull(0)?.toString()?.uppercase() ?: "GET"
            val url     = args.getOrNull(1)?.toString() ?: return@rhino null
            val body    = args.getOrNull(2)?.toString()
            val headers = (args.getOrNull(3) as? NativeObject)?.toHeaderMap() ?: emptyMap()
            makeHttpCall(cx, scope, method, url, body, headers)
        })
        sw.put("http", sw, http)

        // ── sw.test ──────────────────────────────────────────────────────────
        sw.put("test", sw, rhino { c, s, _, args ->
            val name = args.getOrNull(0)?.toString() ?: "unnamed"
            val fn   = args.getOrNull(1) as? Function
            val result = if (fn == null) {
                TestResult(name, false, "test() requires a function as second argument")
            } else {
                runCatching { fn.call(c, s, s, emptyArray()) }
                    .fold(
                        onSuccess = { TestResult(name, true, null) },
                        onFailure = { e -> TestResult(name, false, e.message ?: e.javaClass.simpleName) }
                    )
            }
            context.testResults.add(result)
            null
        })

        // ── sw.expect ────────────────────────────────────────────────────────
        sw.put("expect", sw, rhino { c, s, _, args ->
            val actual = args.getOrNull(0)
            buildExpectObject(c, s, actual, context)
        })

        return sw
    }

    private fun buildExpectObject(cx: Context, scope: Scriptable, actual: Any?, context: ScriptContext): NativeObject {
        val expect = NativeObject()
        expect.put("toBe", expect, rhino { _, _, _, args ->
            val expected = args.getOrNull(0)
            val passed = actual == expected
            context.testResults.add(
                TestResult("expect.toBe", passed, if (passed) null else "Expected $expected but got $actual")
            )
            null
        })
        expect.put("toEqual", expect, rhino { _, _, _, args ->
            val expected = args.getOrNull(0)?.toString()
            val passed = actual?.toString() == expected
            context.testResults.add(
                TestResult("expect.toEqual", passed, if (passed) null else "Expected $expected but got $actual")
            )
            null
        })
        expect.put("toBeTruthy", expect, rhino { _, _, _, _ ->
            val passed = actual != null && actual != false && actual.toString() != "false" && actual.toString() != "0"
            context.testResults.add(
                TestResult("expect.toBeTruthy", passed, if (passed) null else "Expected truthy value but got $actual")
            )
            null
        })
        expect.put("toBeFalsy", expect, rhino { _, _, _, _ ->
            val passed = actual == null || actual == false || actual.toString() == "false" || actual.toString() == "0"
            context.testResults.add(
                TestResult("expect.toBeFalsy", passed, if (passed) null else "Expected falsy value but got $actual")
            )
            null
        })
        expect.put("toContain", expect, rhino { _, _, _, args ->
            val substr = args.getOrNull(0)?.toString() ?: ""
            val passed = actual?.toString()?.contains(substr) == true
            context.testResults.add(
                TestResult("expect.toContain", passed, if (passed) null else "Expected $actual to contain '$substr'")
            )
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
        headers: Map<String, String>
    ): NativeObject? {
        return runCatching {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
            headers.forEach { (k, v) -> runCatching { builder.header(k, v) } }
            val publisher = if (body != null)
                HttpRequest.BodyPublishers.ofString(body)
            else
                HttpRequest.BodyPublishers.noBody()
            builder.method(method, publisher)
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

            val resObj = NativeObject()
            resObj.put("status", resObj, response.statusCode())
            resObj.put("body", resObj, response.body())
            val headersObj = NativeObject()
            response.headers().map().forEach { (k, vs) ->
                if (vs.isNotEmpty()) headersObj.put(k, headersObj, vs[0])
            }
            resObj.put("headers", resObj, headersObj)
            resObj.put("json", resObj, rhino { c, s, _, _ ->
                runCatching { c.evaluateString(s, "(${response.body()})", "json-parse", 1, null) }
                    .getOrDefault(null)
            })
            resObj
        }.getOrNull()
    }

    /** Convenience: create a Rhino callable from a Kotlin lambda. */
    private fun rhino(block: (cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<Any?>) -> Any?): org.mozilla.javascript.BaseFunction {
        return object : org.mozilla.javascript.BaseFunction() {
            override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable?, args: Array<out Any?>): Any? =
                block(cx, scope, thisObj, args.toList().toTypedArray())
        }
    }
}

private fun NativeObject.toHeaderMap(): Map<String, String> =
    ids.filterIsInstance<String>().associateWith { get(it, this)?.toString() ?: "" }
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.sonarwhale.script.ScriptEngineTest"
```

Expected: All tests PASS. (The `sw.http` tests are not in the suite since they require a live server — that's intentional. Manual testing covers auth flows.)

- [ ] **Step 5: Run all tests**

```bash
./gradlew test
```

Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/script/ScriptEngine.kt \
        src/rider/test/kotlin/com/sonarwhale/script/ScriptEngineTest.kt
git commit -m "feat: implement ScriptEngine with Rhino — sw.env, sw.request, sw.response, sw.http, sw.test"
```

---

## Task 5: SonarwhaleScriptService + sw.d.ts + plugin.xml

**Files:**
- Create: `src/rider/main/kotlin/com/sonarwhale/script/SonarwhaleScriptService.kt`
- Modify: `src/rider/main/resources/META-INF/plugin.xml`

The service is the single entry point called from `RequestPanel`. It also writes `sw.d.ts` on first use.

- [ ] **Step 1: Create SonarwhaleScriptService.kt**

Create `src/rider/main/kotlin/com/sonarwhale/script/SonarwhaleScriptService.kt`:

```kotlin
package com.sonarwhale.script

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
     * Must be called from a background thread.
     */
    fun executePreScripts(
        endpoint: ApiEndpoint,
        request: SavedRequest,
        url: String,
        headers: Map<String, String>,
        body: String
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
        engine.executeChain(chain, ctx)
        // Flush env changes back to active environment
        flushEnvChanges(ctx.envSnapshot)
        return ctx
    }

    /**
     * Executes post-scripts and returns the collected [TestResult]s.
     * Must be called from a background thread.
     */
    fun executePostScripts(
        endpoint: ApiEndpoint,
        request: SavedRequest,
        statusCode: Int,
        responseHeaders: Map<String, String>,
        responseBody: String,
        scriptContext: ScriptContext
    ): List<TestResult> {
        val response = ResponseContext(statusCode, responseHeaders, responseBody)
        val postCtx = ScriptContext(
            envSnapshot = scriptContext.envSnapshot,
            request = scriptContext.request,
            response = response
        )
        val tag = endpoint.tags.firstOrNull() ?: "Default"
        val chain = resolver.resolvePostChain(tag, endpoint.method.name, endpoint.path, request.name)
        engine.executeChain(chain, postCtx)
        flushEnvChanges(postCtx.envSnapshot)
        return postCtx.testResults
    }

    /**
     * Creates the pre.js or post.js file at the appropriate level for the given endpoint+request.
     * Returns the created/existing file path so the caller can open it in the editor.
     */
    fun getOrCreateScript(
        endpoint: ApiEndpoint,
        request: SavedRequest,
        phase: ScriptPhase,
        level: ScriptLevel = ScriptLevel.REQUEST
    ): Path {
        val tag         = endpoint.tags.firstOrNull() ?: "Default"
        val endpointDir = ScriptChainResolver.sanitizeEndpointDir(endpoint.method.name, endpoint.path)
        val requestDir  = ScriptChainResolver.sanitizeName(request.name)
        val tagDir      = ScriptChainResolver.sanitizeName(tag)
        val fileName    = if (phase == ScriptPhase.PRE) "pre.js" else "post.js"
        val root        = scriptsRoot()

        val dir = when (level) {
            ScriptLevel.GLOBAL   -> root
            ScriptLevel.TAG      -> root.resolve(tagDir)
            ScriptLevel.ENDPOINT -> root.resolve(tagDir).resolve(endpointDir)
            ScriptLevel.REQUEST  -> root.resolve(tagDir).resolve(endpointDir).resolve(requestDir)
        }
        dir.createDirectories()

        val scriptPath = dir.resolve(fileName)
        if (!scriptPath.exists()) {
            ensureSwDts()
            val comment = when (phase) {
                ScriptPhase.PRE  -> "// Pre-script: runs before the HTTP request\n// Available: sw.env, sw.request, sw.http\n\n"
                ScriptPhase.POST -> "// Post-script: runs after the HTTP response\n// Available: sw.env, sw.request, sw.response, sw.http, sw.test, sw.expect\n\n"
            }
            scriptPath.writeText(comment)
        }
        return scriptPath
    }

    /** Writes sw.d.ts to .sonarwhale/scripts/ if it does not exist yet. */
    fun ensureSwDts() {
        val root = scriptsRoot()
        root.createDirectories()
        val dts = root.resolve("sw.d.ts")
        if (!dts.exists()) {
            dts.writeText(SW_DTS_CONTENT)
        }
    }

    private fun scriptsRoot(): Path =
        Path.of(project.basePath ?: ".").resolve(".sonarwhale").resolve("scripts")

    private fun flushEnvChanges(snapshot: MutableMap<String, String>) {
        val stateService = SonarwhaleStateService.getInstance(project)
        val env = stateService.getActiveEnvironment() ?: return
        val updated = env.copy(variables = LinkedHashMap(snapshot))
        stateService.upsertEnvironment(updated)
    }

    companion object {
        fun getInstance(project: Project): SonarwhaleScriptService = project.service()

        private val SW_DTS_CONTENT = """
// Sonarwhale Script API — auto-generated, do not edit
// Place this file at the root of .sonarwhale/scripts/ for IDE autocomplete

interface SwResponse {
  status: number;
  headers: Record<string, string>;
  body: string;
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
  test(name: string, fn: () => boolean | void): void;
  expect(value: any): SwExpect;
};
        """.trimIndent()
    }
}
```

- [ ] **Step 2: Register service in plugin.xml**

In `src/rider/main/resources/META-INF/plugin.xml`, add inside `<extensions defaultExtensionNs="com.intellij">` after the last `projectService` entry:

```xml
    <!-- Project-level service: executes pre/post JS scripts for requests -->
    <projectService serviceImplementation="com.sonarwhale.script.SonarwhaleScriptService"/>
```

- [ ] **Step 3: Verify build**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/script/SonarwhaleScriptService.kt \
        src/rider/main/resources/META-INF/plugin.xml
git commit -m "feat: add SonarwhaleScriptService, sw.d.ts generation, plugin registration"
```

---

## Task 6: RequestPanel — Create Pre/Post Script Buttons

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt`

Add two buttons to the URL bar toolbar that create (or open) the pre/post script for the current request and open it in the IDE editor.

- [ ] **Step 1: Add buttons and imports to RequestPanel.kt**

At the top of the file, add this import:
```kotlin
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.fileEditor.FileEditorManager
import com.sonarwhale.script.SonarwhaleScriptService
import com.sonarwhale.script.ScriptPhase
import com.sonarwhale.script.ScriptLevel
```

Inside the class body, after `setDefaultButton`, add:
```kotlin
private val preScriptButton = JButton("⚡ Pre").apply {
    font = font.deriveFont(10f)
    toolTipText = "Create or open pre-script for this request"
    isFocusable = false
}
private val postScriptButton = JButton("⚡ Post").apply {
    font = font.deriveFont(10f)
    toolTipText = "Create or open post-script for this request"
    isFocusable = false
}
```

- [ ] **Step 2: Wire button actions in init block**

In the `init { ... }` block, after `setDefaultButton.addActionListener { setAsDefault() }`, add:
```kotlin
preScriptButton.addActionListener  { openOrCreateScript(ScriptPhase.PRE) }
postScriptButton.addActionListener { openOrCreateScript(ScriptPhase.POST) }
```

- [ ] **Step 3: Add buttons to the URL bar**

In `buildUrlBar()`, after `gbc.gridx = 5; bar.add(setDefaultButton, gbc)`, add:
```kotlin
gbc.gridx = 6; gbc.insets = Insets(0, 0, 0, 4)
bar.add(preScriptButton, gbc)

gbc.gridx = 7; gbc.insets = Insets(0, 0, 0, 0)
bar.add(postScriptButton, gbc)
```

- [ ] **Step 4: Implement openOrCreateScript helper**

Add this private function to `RequestPanel`:
```kotlin
private fun openOrCreateScript(phase: ScriptPhase) {
    val endpoint = currentEndpoint ?: return
    val request  = currentRequest ?: SavedRequest(name = currentRequestName)
    val scriptService = SonarwhaleScriptService.getInstance(project)

    val path = scriptService.getOrCreateScript(endpoint, request, phase, ScriptLevel.REQUEST)
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
    FileEditorManager.getInstance(project).openFile(vf, true)
}
```

- [ ] **Step 5: Verify build**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt
git commit -m "feat: add Create Pre/Post Script buttons to RequestPanel toolbar"
```

---

## Task 7: ResponsePanel — Tests Tab

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/ResponsePanel.kt`

Wrap the body area in a `JBTabbedPane` with a "Body" tab and a "Tests" tab. The Tests tab is only populated when `showTestResults()` is called after a request with post-scripts.

- [ ] **Step 1: Add JBTabbedPane and test panel to ResponsePanel**

Add imports at the top of `ResponsePanel.kt`:
```kotlin
import com.intellij.ui.components.JBTabbedPane
import com.sonarwhale.script.TestResult
import java.awt.Color
import javax.swing.BorderFactory
import javax.swing.BoxLayout
```

Replace the class field declarations (after `private val openButton = ...`) up through the `init` block. The key change: replace the direct `JBScrollPane(bodyArea)` as CENTER with a `JBTabbedPane`.

Add these fields after `openButton`:
```kotlin
private val tabs = JBTabbedPane()
private val testsPanel = JPanel().apply {
    layout = BoxLayout(this, BoxLayout.Y_AXIS)
    border = JBUI.Borders.empty(8)
}
private val testsScroll = JBScrollPane(testsPanel)
```

- [ ] **Step 2: Update init block to use tabs**

In the `init { }` block, replace:
```kotlin
add(JBScrollPane(bodyArea), BorderLayout.CENTER)
```

With:
```kotlin
tabs.addTab("Body", JBScrollPane(bodyArea))
tabs.addTab("Tests", testsScroll)
add(tabs, BorderLayout.CENTER)
```

- [ ] **Step 3: Add showTestResults() method**

Add this method to `ResponsePanel` after `clear()`:
```kotlin
fun showTestResults(results: List<TestResult>) {
    testsPanel.removeAll()
    if (results.isEmpty()) {
        tabs.setTitleAt(tabs.indexOfComponent(testsScroll), "Tests")
        testsPanel.revalidate()
        testsPanel.repaint()
        return
    }
    val passed = results.count { it.passed }
    val total  = results.size
    tabs.setTitleAt(tabs.indexOfComponent(testsScroll), "Tests ($passed/$total)")

    for (result in results) {
        val row = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
        }
        val icon = JBLabel(if (result.passed) "✓" else "✗").apply {
            foreground = if (result.passed)
                JBColor(Color(0x00, 0xAA, 0x55), Color(0x44, 0xCC, 0x77))
            else
                JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
            font = font.deriveFont(Font.BOLD, 12f)
        }
        val name = JBLabel(result.name).apply {
            font = font.deriveFont(12f)
        }
        row.add(icon)
        row.add(name)
        if (!result.passed && result.error != null) {
            val err = JBLabel("  ${result.error}").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC, 11f)
            }
            row.add(err)
        }
        testsPanel.add(row)
    }

    testsPanel.revalidate()
    testsPanel.repaint()

    // Switch to Tests tab automatically if there are failures
    if (results.any { !it.passed }) {
        tabs.selectedIndex = tabs.indexOfComponent(testsScroll)
    }
}
```

- [ ] **Step 4: Update clear() to reset tests tab**

In the existing `clear()` method, add at the end:
```kotlin
testsPanel.removeAll()
tabs.setTitleAt(tabs.indexOfComponent(testsScroll), "Tests")
testsPanel.revalidate()
```

- [ ] **Step 5: Verify build**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/ResponsePanel.kt
git commit -m "feat: add Tests tab to ResponsePanel for post-script assertions"
```

---

## Task 8: Wire Scripts into RequestPanel.sendRequest()

**Files:**
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt`
- Modify: `src/rider/main/kotlin/com/sonarwhale/toolwindow/DetailPanel.kt` (add `onTestResultsReceived` hookup)

This is the final wiring: pre-scripts run before the HTTP call (modifying url/headers/body), post-scripts run after (running assertions and extracting env vars). `TestResult`s are forwarded to `ResponsePanel`.

- [ ] **Step 1: Add onTestResultsReceived callback to RequestPanel**

In `RequestPanel`, add a callback field after `onDefaultStateChanged`:
```kotlin
var onTestResultsReceived: ((List<TestResult>) -> Unit)? = null
```

Add the import:
```kotlin
import com.sonarwhale.script.TestResult
```

- [ ] **Step 2: Replace sendRequest() with script-aware version**

Replace the entire `private fun sendRequest()` method in `RequestPanel.kt`:

```kotlin
private fun sendRequest() {
    val endpoint = currentEndpoint ?: return
    val rawUrl = computedUrlField.text.trim()
    if (rawUrl.isEmpty()) return

    saveRequest()
    sendButton.isEnabled = false

    val headerRows = headersTable.getRows()
        .filter { it.enabled && it.key.isNotEmpty() }
        .map { it.copy(value = stateService.resolveVariables(it.value)) }
    val bodyContent = bodyPanel.getContent().let { bc ->
        when (bc) {
            is BodyContent.Raw      -> bc.copy(text = stateService.resolveVariables(bc.text))
            is BodyContent.FormData -> bc.copy(rows = bc.rows.map { r -> r.copy(value = stateService.resolveVariables(r.value)) })
            else -> bc
        }
    }

    val savedRequest = currentRequest ?: SavedRequest(name = currentRequestName)
    val scriptService = SonarwhaleScriptService.getInstance(project)

    object : SwingWorker<Triple<Int, String, Long>, Unit>() {
        private var testResults: List<TestResult> = emptyList()
        private var scriptContext: com.sonarwhale.script.ScriptContext? = null

        override fun doInBackground(): Triple<Int, String, Long> {
            // ── Pre-scripts ────────────────────────────────────────────────
            val initialHeaders = headerRows.associate { it.key.trim() to it.value.trim() }.toMutableMap()
            val initialBody = when (val bc = bodyContent) {
                is BodyContent.Raw -> bc.text
                else -> ""
            }
            val ctx = scriptService.executePreScripts(
                endpoint    = endpoint,
                request     = savedRequest,
                url         = rawUrl,
                headers     = initialHeaders,
                body        = initialBody
            )
            scriptContext = ctx

            // Use (possibly mutated) url, headers, body from ScriptContext
            val finalUrl     = ctx.request.url
            val finalHeaders = ctx.request.headers
            val finalBody    = ctx.request.body

            // ── HTTP Request ───────────────────────────────────────────────
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            val builder = HttpRequest.newBuilder()
                .uri(URI.create(finalUrl))
                .timeout(Duration.ofSeconds(30))

            finalHeaders.forEach { (k, v) ->
                runCatching { builder.header(k, v) }
            }

            val hasContentType = finalHeaders.keys.any { it.equals("content-type", ignoreCase = true) }

            when (val bc = bodyContent) {
                is BodyContent.None -> when (endpoint.method.name) {
                    "GET", "HEAD" -> builder.GET()
                    "DELETE"      -> builder.DELETE()
                    else          -> builder.method(endpoint.method.name, HttpRequest.BodyPublishers.noBody())
                }
                is BodyContent.Raw -> {
                    val body = finalBody.ifEmpty { bc.text }
                    if (!hasContentType) builder.header("Content-Type", bc.contentType)
                    val publisher = HttpRequest.BodyPublishers.ofString(body)
                    when (endpoint.method.name) {
                        "POST"        -> builder.POST(publisher)
                        "PUT"         -> builder.PUT(publisher)
                        "DELETE"      -> builder.DELETE()
                        "GET", "HEAD" -> builder.GET()
                        else          -> builder.method(endpoint.method.name, publisher)
                    }
                }
                is BodyContent.FormData -> {
                    if (!hasContentType) builder.header("Content-Type", "application/x-www-form-urlencoded")
                    val encoded = bc.rows.filter { it.enabled && it.key.isNotEmpty() }
                        .joinToString("&") {
                            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                        }
                    val publisher = HttpRequest.BodyPublishers.ofString(encoded)
                    when (endpoint.method.name) {
                        "POST" -> builder.POST(publisher)
                        "PUT"  -> builder.PUT(publisher)
                        else   -> builder.method(endpoint.method.name, publisher)
                    }
                }
                is BodyContent.Binary -> {
                    val path = Paths.get(bc.filePath)
                    val publisher = HttpRequest.BodyPublishers.ofFile(path)
                    when (endpoint.method.name) {
                        "POST" -> builder.POST(publisher)
                        "PUT"  -> builder.PUT(publisher)
                        else   -> builder.method(endpoint.method.name, publisher)
                    }
                }
            }

            val start = System.currentTimeMillis()
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            val duration = System.currentTimeMillis() - start

            // ── Post-scripts ───────────────────────────────────────────────
            val responseHeaders = response.headers().map()
                .mapValues { (_, vs) -> vs.firstOrNull() ?: "" }
            testResults = scriptService.executePostScripts(
                endpoint        = endpoint,
                request         = savedRequest,
                statusCode      = response.statusCode(),
                responseHeaders = responseHeaders,
                responseBody    = response.body(),
                scriptContext   = ctx
            )

            return Triple(response.statusCode(), response.body(), duration)
        }

        override fun done() {
            sendButton.isEnabled = true
            runCatching {
                val (status, body, duration) = get()
                onResponseReceived?.invoke(status, body, duration)
                onTestResultsReceived?.invoke(testResults)
            }.onFailure { e ->
                onResponseReceived?.invoke(0, describeError(e), 0)
                onTestResultsReceived?.invoke(emptyList())
            }
        }
    }.execute()
}
```

- [ ] **Step 3: Hook up onTestResultsReceived in DetailPanel**

Open `src/rider/main/kotlin/com/sonarwhale/toolwindow/DetailPanel.kt`. Find where `requestPanel.onResponseReceived` is set, and add immediately after it:

```kotlin
requestPanel.onTestResultsReceived = { results ->
    responsePanel.showTestResults(results)
}
```

Add the import if not present:
```kotlin
import com.sonarwhale.script.TestResult
```

- [ ] **Step 4: Verify build**

```bash
./gradlew compileKotlin
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Run all tests**

```bash
./gradlew test
```

Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/rider/main/kotlin/com/sonarwhale/toolwindow/RequestPanel.kt \
        src/rider/main/kotlin/com/sonarwhale/toolwindow/DetailPanel.kt
git commit -m "feat: wire pre/post scripts into request flow and display test results"
```

---

## Task 9: Manual Verification

- [ ] **Step 1: Run the plugin in a Rider sandbox**

```bash
./gradlew runIdeCSharp
```

- [ ] **Step 2: Verify script creation**

Open a C# project with a running API. Open the Sonarwhale panel. Select an endpoint. Click "⚡ Pre" — verify `.sonarwhale/scripts/{Tag}/{METHOD}__{path}/{RequestName}/pre.js` is created and opens in the editor. Verify `sw.d.ts` is also created at `.sonarwhale/scripts/sw.d.ts`.

- [ ] **Step 3: Verify env var auth flow**

In the active environment, add `username=admin` and `password=secret`. Write this in the global pre.js:
```js
const res = sw.http.post(
  "http://localhost:5000/auth/login",
  JSON.stringify({ username: sw.env.get("username"), password: sw.env.get("password") }),
  { "Content-Type": "application/json" }
);
sw.env.set("token", res.json().access_token);
sw.request.setHeader("Authorization", "Bearer " + sw.env.get("token"));
```
Send a protected endpoint. Verify the response is 200 (not 401).

- [ ] **Step 4: Verify post-script assertions**

Write in post.js:
```js
sw.test("status is 200", function() {
  if (sw.response.status !== 200) throw new Error("Got " + sw.response.status);
});
```
Send request. Verify "Tests (1/1)" tab appears in ResponsePanel. Verify ✓ shown.

- [ ] **Step 5: Verify inherit.off**

Create `.sonarwhale/scripts/inherit.off` (global level). Create a global `pre.js` that sets an env var. Send request. Verify env var is NOT set (global script is suppressed because inherit.off is on the same level — test with it at tag level instead, blocking global).

- [ ] **Step 6: Final commit tag**

```bash
git tag pre-post-scripts-v1
```

---

## Self-Review Checklist

**Spec coverage:**
- ✅ Hierarchical scripts: global → tag → endpoint → request
- ✅ inherit.off stops parent scripts
- ✅ JavaScript via Rhino
- ✅ sw.env.get/set
- ✅ sw.request.setHeader/setBody/setUrl
- ✅ sw.response (post only)
- ✅ sw.http.get/post/request (synchronous)
- ✅ sw.test() + sw.expect()
- ✅ Pre-chain: global→request order; Post-chain: request→global order
- ✅ .sonarwhale/scripts/ directory (git-trackable, not in .idea/)
- ✅ sw.d.ts auto-generated for IDE autocomplete
- ✅ "Create Pre/Post Script" buttons in RequestPanel
- ✅ Tests tab in ResponsePanel
- ✅ env changes flushed back to active environment
- ✅ Script errors caught as TestResult, don't crash the request
