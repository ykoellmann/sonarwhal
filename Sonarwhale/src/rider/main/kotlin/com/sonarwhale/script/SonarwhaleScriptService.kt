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
        console: ConsoleOutput = ConsoleOutput()  // caller should provide shared instance; default discards entries
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
        console: ConsoleOutput = ConsoleOutput()  // caller should provide shared instance; default discards entries
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
            val depth = when (level) {
                ScriptLevel.GLOBAL   -> 0
                ScriptLevel.TAG      -> 1
                ScriptLevel.ENDPOINT -> 2
                ScriptLevel.REQUEST  -> 3
            }
            val refPath = "../".repeat(depth) + "sw.d.ts"
            val header = "/// <reference path=\"$refPath\" />\n"
            val comment = when (phase) {
                ScriptPhase.PRE  -> "// Pre-script: runs before the HTTP request\n// Available: sw.env, sw.request, sw.http\n\n"
                ScriptPhase.POST -> "// Post-script: runs after the HTTP response\n// Available: sw.env, sw.request, sw.response, sw.http, sw.test, sw.expect\n\n"
            }
            scriptPath.writeText(header + comment)
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

    /**
     * Writes sw.d.ts, sw.js (JSDoc globals for JS library analysis), and jsconfig.json.
     * sw.d.ts / sw.js are written once (user may edit sw.d.ts); jsconfig.json is always overwritten.
     */
    fun ensureSwDts() {
        val root = scriptsRoot()
        root.createDirectories()
        val dts = root.resolve("sw.d.ts")
        if (!dts.exists()) dts.writeText(SW_DTS_CONTENT)
        // sw.js provides the same API as JSDoc so IntelliJ's JavaScript library analysis
        // (which reads var declarations + @type, not .d.ts ambient declarations) can
        // expose 'sw' globally in .js script files.
        val swJs = root.resolve("sw.js")
        swJs.writeText(SW_JS_CONTENT)
        writeJsConfig()
    }

    private fun writeJsConfig() {
        val root = scriptsRoot()
        root.createDirectories()
        // Always overwrite — this file is auto-generated and must stay up to date.
        // "files" explicitly adds sw.d.ts and sw.js to the compilation so 'sw.*'
        // is visible in all .js script files regardless of nesting depth.
        root.resolve("jsconfig.json").writeText("""
            {
              "compilerOptions": {
                "checkJs": true,
                "strict": false,
                "target": "ES6"
              },
              "files": ["sw.d.ts", "sw.js"],
              "include": ["./**/*.js"]
            }
        """.trimIndent())
    }

    fun getScriptsRoot(): Path = scriptsRoot()

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

        // JSDoc-style globals for IntelliJ's JavaScript library analysis.
        // IntelliJ reads 'var x' + @type JSDoc from library .js files to expose
        // globals — it does NOT use 'declare const' from .d.ts for this purpose.
        private val SW_JS_CONTENT = """
// Sonarwhale Script API — auto-generated, do not edit
// This file provides 'sw' autocompletion in .js script files via JSDoc.

/**
 * @typedef {{status: number, headers: Object.<string,string>, body: string, error: (string|undefined), json: function(): *}} SwHttpResponse
 */

/**
 * @typedef {{
 *   toBe: function(*): void,
 *   toEqual: function(*): void,
 *   toBeTruthy: function(): void,
 *   toBeFalsy: function(): void,
 *   toContain: function(string): void
 * }} SwExpect
 */

/**
 * Sonarwhale script API — available in all pre/post scripts.
 * @type {{
 *   env: {
 *     get: function(string): (string|undefined),
 *     set: function(string, string): void
 *   },
 *   request: {
 *     url: string,
 *     method: string,
 *     headers: Object.<string,string>,
 *     body: string,
 *     setHeader: function(string, string): void,
 *     setBody: function(string): void,
 *     setUrl: function(string): void
 *   },
 *   response: {
 *     status: number,
 *     headers: Object.<string,string>,
 *     body: string,
 *     json: function(): *
 *   },
 *   http: {
 *     get: function(string, Object=): SwHttpResponse,
 *     post: function(string, string, Object=): SwHttpResponse,
 *     request: function(string, string, *, Object=): SwHttpResponse
 *   },
 *   test: function(string, function(): void): void,
 *   expect: function(*): SwExpect
 * }}
 */
var sw;
        """.trimIndent()

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
