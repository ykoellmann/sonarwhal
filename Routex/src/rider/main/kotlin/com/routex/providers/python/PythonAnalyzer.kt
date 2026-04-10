package com.routex.providers.python

import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.jetbrains.python.psi.PyFile
import com.jetbrains.python.psi.PyFunction
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyListLiteralExpression
import com.jetbrains.python.psi.PyNamedParameter
import com.jetbrains.python.psi.PyStringLiteralExpression
import com.routex.model.ApiEndpoint
import com.routex.model.ApiParameter
import com.routex.model.EndpointMeta
import com.routex.model.HttpMethod
import com.routex.model.ParameterSource
import com.routex.model.SupportedLanguage
import java.security.MessageDigest
import java.util.Base64

/**
 * Stateless PSI analyzer for Python web endpoint decorators.
 *
 * Supports:
 *  - Flask classic:   @app.route("/path", methods=["GET", "POST"])
 *  - Flask 2.0+:      @app.get("/path"), @bp.delete("/path/<int:id>")
 *  - FastAPI / Starlette: @router.get("/path"), @app.post("/path/{id}")
 *
 * Any receiver name is accepted — detection is purely based on decorator method name.
 */
object PythonAnalyzer {

    private val METHOD_DECORATORS: Map<String, HttpMethod?> = mapOf(
        "get"     to HttpMethod.GET,
        "post"    to HttpMethod.POST,
        "put"     to HttpMethod.PUT,
        "delete"  to HttpMethod.DELETE,
        "patch"   to HttpMethod.PATCH,
        "head"    to HttpMethod.HEAD,
        "options" to HttpMethod.OPTIONS,
        "route"   to null   // HTTP methods read from methods=[...] keyword arg
    )

    // Parameters that are framework-injected and should not be treated as API params.
    private val FRAMEWORK_PARAMS = setOf("self", "cls", "request", "req", "response", "resp", "db", "session")

    // Python primitive / collection type names that are NOT Pydantic models.
    private val PRIMITIVE_TYPES = setOf(
        "str", "int", "float", "bool", "bytes", "None", "Any",
        "list", "dict", "tuple", "set", "List", "Dict", "Tuple", "Set",
        "Sequence", "Iterable", "Optional", "Union"
    )

    fun analyze(file: PyFile): List<ApiEndpoint> {
        val filePath = file.virtualFile?.path ?: return emptyList()
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return emptyList()
        return file.topLevelFunctions.flatMap { fn ->
            extractFromFunction(fn, filePath, document)
        }
    }

    private fun extractFromFunction(
        function: PyFunction,
        filePath: String,
        document: Document
    ): List<ApiEndpoint> {
        val decorators = function.decoratorList?.decorators ?: return emptyList()
        val endpoints = mutableListOf<ApiEndpoint>()

        for (decorator in decorators) {
            val decoratorName = decorator.name ?: continue
            if (!METHOD_DECORATORS.containsKey(decoratorName)) continue

            val argList = decorator.argumentList ?: continue
            val allArgs = argList.arguments

            // First positional (non-keyword) argument is the route string.
            val routeExpr = allArgs.firstOrNull { it !is PyKeywordArgument }
            val rawRoute = (routeExpr as? PyStringLiteralExpression)?.stringValue

            val confidence: Float
            val warnings: List<String>
            val route: String

            if (rawRoute != null) {
                route = normalizeRoute(rawRoute)
                confidence = 0.8f
                warnings = emptyList()
            } else {
                // Dynamic route — variable or f-string, can't resolve statically.
                confidence = 0.5f
                warnings = listOf("Dynamic route — path may be incomplete")
                route = "/${function.name}"
            }

            val httpMethods: List<HttpMethod> = if (decoratorName == "route") {
                // Flask @app.route — extract HTTP methods from methods=[...] keyword arg.
                // getKeywordArgument lives on PyCallExpression, not PyArgumentList, so we
                // iterate allArgs directly instead.
                val methodsExpr = allArgs
                    .filterIsInstance<PyKeywordArgument>()
                    .find { it.keyword == "methods" }
                    ?.valueExpression
                extractFlaskMethods(methodsExpr)
            } else {
                listOf(METHOD_DECORATORS[decoratorName] ?: continue)
            }

            val pathParams = extractPathParams(route)
            val pathParamNames = pathParams.map { it.name }.toSet()
            val queryAndBodyParams = extractNonPathParams(function, pathParamNames)

            val lineNumber = document.getLineNumber(function.textOffset) + 1  // 1-based

            for (httpMethod in httpMethods) {
                endpoints.add(
                    ApiEndpoint(
                        id = computeHash("$filePath:${function.textOffset}:${httpMethod.name}"),
                        httpMethod = httpMethod,
                        route = route,
                        rawRouteSegments = route.split("/").filter { it.isNotEmpty() },
                        filePath = filePath,
                        lineNumber = lineNumber,
                        controllerName = null,
                        methodName = route.trimStart('/'),
                        language = SupportedLanguage.PYTHON,
                        parameters = pathParams + queryAndBodyParams,
                        auth = null,
                        responses = emptyList(),
                        meta = EndpointMeta(
                            contentHash = computeHash("${httpMethod.name}:$route"),
                            analysisConfidence = confidence,
                            analysisWarnings = warnings
                        )
                    )
                )
            }
        }

        return endpoints
    }

    // ── Route normalisation ────────────────────────────────────────────────────

    /**
     * Normalises Flask and FastAPI route syntax to a common `{name}` form:
     *   Flask:   `/users/<int:id>` → `/users/{id}`
     *   FastAPI: `/users/{id:int}` → `/users/{id}`
     */
    private fun normalizeRoute(raw: String): String {
        var route = raw
        // Flask <type:name> or <name> → {name}
        route = route.replace(Regex("<(?:[^:>]+:)?([^>]+)>"), "{$1}")
        // FastAPI {name:type} → {name}
        route = route.replace(Regex("\\{([^}:]+):[^}]+\\}"), "{$1}")
        // Strip optional marker {name?} → {name}
        route = route.replace(Regex("\\{([^}]+)\\?\\}"), "{$1}")
        if (!route.startsWith("/")) route = "/$route"
        return route
    }

    // ── Parameter extraction ───────────────────────────────────────────────────

    private fun extractPathParams(route: String): List<ApiParameter> =
        Regex("\\{([^}]+)\\}").findAll(route).map { match ->
            ApiParameter(
                name = match.groupValues[1],
                type = "string",
                source = ParameterSource.PATH,
                required = true
            )
        }.toList()

    private fun extractNonPathParams(function: PyFunction, pathNames: Set<String>): List<ApiParameter> =
        function.parameterList.parameters
            .filterIsInstance<PyNamedParameter>()
            .filter { param ->
                val name = param.name ?: return@filter false
                name !in FRAMEWORK_PARAMS && name !in pathNames
            }
            .mapNotNull { param ->
                val name = param.name ?: return@mapNotNull null
                val annotationText = param.annotation?.value?.text?.trim() ?: "str"
                val isOptional = annotationText.startsWith("Optional") ||
                        annotationText.startsWith("typing.Optional") ||
                        param.hasDefaultValue()

                // Heuristic: capitalised type name that is not a known primitive → Pydantic body model.
                val baseType = annotationText.removePrefix("Optional[").removeSuffix("]").trim()
                val source = if (baseType.firstOrNull()?.isUpperCase() == true && baseType !in PRIMITIVE_TYPES)
                    ParameterSource.BODY
                else
                    ParameterSource.QUERY

                ApiParameter(
                    name = name,
                    type = baseType,
                    source = source,
                    required = !isOptional
                )
            }

    // ── Flask method list extraction ───────────────────────────────────────────

    private fun extractFlaskMethods(methodsExpr: com.intellij.psi.PsiElement?): List<HttpMethod> {
        val listExpr = methodsExpr as? PyListLiteralExpression
            ?: return listOf(HttpMethod.GET)  // Flask default when methods= is omitted

        val methods: List<HttpMethod> = listExpr.elements
            .filterIsInstance<PyStringLiteralExpression>()
            .mapNotNull { nameToMethod(it.stringValue.uppercase()) }

        return methods.ifEmpty { listOf(HttpMethod.GET) }
    }

    private fun nameToMethod(name: String): HttpMethod? = when (name) {
        "GET"     -> HttpMethod.GET
        "POST"    -> HttpMethod.POST
        "PUT"     -> HttpMethod.PUT
        "DELETE"  -> HttpMethod.DELETE
        "PATCH"   -> HttpMethod.PATCH
        "HEAD"    -> HttpMethod.HEAD
        "OPTIONS" -> HttpMethod.OPTIONS
        else      -> null
    }

    // ── Hashing (mirrors C# SHA256 approach) ──────────────────────────────────

    fun computeHash(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(bytes).substring(0, 8)
    }
}
