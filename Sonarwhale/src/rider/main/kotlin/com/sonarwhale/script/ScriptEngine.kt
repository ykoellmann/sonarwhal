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
                            // treat explicit `return false` (Rhino returns java.lang.Boolean false) as failure
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
