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
        request: SavedRequest? = null,
        collectionId: String = ""
    ): Path {
        val fileName = if (phase == ScriptPhase.PRE) "pre.js" else "post.js"
        val root = scriptsRoot()
        return when (level) {
            ScriptLevel.GLOBAL      -> root.resolve(fileName)
            ScriptLevel.COLLECTION  -> root.resolve("collections").resolve(collectionId).resolve(fileName)
            ScriptLevel.TAG         -> root.resolve(sanitize(tag)).resolve(fileName)
            ScriptLevel.ENDPOINT    -> root.resolve(sanitize(tag))
                .resolve(endpointDir(endpoint)).resolve(fileName)
            ScriptLevel.REQUEST     -> root.resolve(sanitize(tag))
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
                ScriptLevel.GLOBAL      -> 0
                ScriptLevel.COLLECTION  -> 2
                ScriptLevel.TAG         -> 1
                ScriptLevel.ENDPOINT    -> 2
                ScriptLevel.REQUEST     -> 3
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
     * Writes sw.d.ts and tsconfig.json to .sonarwhale/scripts/.
     * sw.d.ts is written once (user may edit it); tsconfig.json is always overwritten.
     *
     * tsconfig.json with allowJs:true triggers IntelliJ's TypeScript Language Service
     * for the .js script files, which correctly handles 'declare const sw' from sw.d.ts.
     * jsconfig.json does NOT do this — it uses a lighter JS-only analysis that ignores .d.ts.
     */
    fun ensureSwDts() {
        val root = scriptsRoot()
        root.createDirectories()
        val dts = root.resolve("sw.d.ts")
        if (!dts.exists()) dts.writeText(SW_DTS_CONTENT)
        writeTsConfig()
    }

    private fun writeTsConfig() {
        val root = scriptsRoot()
        root.createDirectories()
        // Always overwrite — auto-generated, must stay current.
        // allowJs + checkJs activates the TypeScript Language Service for .js files so
        // that 'declare const sw' in sw.d.ts is visible as a global in all script files.
        root.resolve("tsconfig.json").writeText("""
            {
              "compilerOptions": {
                "allowJs": true,
                "checkJs": true,
                "strict": false,
                "target": "ES6",
                "noEmit": true
              },
              "files": ["sw.d.ts"],
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
