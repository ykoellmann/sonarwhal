package com.routex.providers.python

import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import com.jetbrains.python.psi.PyFunction
import com.routex.RouteXService
import com.routex.model.ApiEndpoint
import com.routex.model.SupportedLanguage

/**
 * Intercepts Find Usages on Python functions that are detected API endpoints.
 *
 * When the user invokes Find Usages on such a function, this factory:
 *   1. Injects a synthetic [RouteXPythonUsageInfo] entry (displayed as "RouteX: GET /users/{id}")
 *      at the top of the results — mirroring how [RouteXEndpointOccurrence] appears in C#.
 *   2. Navigates the RouteX tool window to highlight the matching endpoint.
 *   3. Lets normal Find Usages continue so real call-site references are also listed.
 *
 * Only active when the Python plugin is present (registered via routex-python.xml).
 */
class PythonEndpointFindUsagesHandlerFactory : FindUsagesHandlerFactory() {

    override fun canFindUsages(element: PsiElement): Boolean {
        if (element !is PyFunction) return false
        return resolveEndpoint(element) != null
    }

    override fun createFindUsagesHandler(element: PsiElement, forHighlightUsages: Boolean): FindUsagesHandler? {
        if (element !is PyFunction) return null
        val endpoint = resolveEndpoint(element) ?: return null
        return PythonEndpointFindUsagesHandler(element, endpoint)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveEndpoint(function: PyFunction): ApiEndpoint? {
        val vFile = function.containingFile?.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(function.project)
            .getDocument(function.containingFile) ?: return null
        val lineNumber = document.getLineNumber(function.textOffset) + 1
        return RouteXService.getInstance(function.project).endpoints.find {
            it.language == SupportedLanguage.PYTHON &&
                    it.filePath == vFile.path &&
                    it.lineNumber == lineNumber
        }
    }
}

// ── Handler ───────────────────────────────────────────────────────────────────

private class PythonEndpointFindUsagesHandler(
    function: PyFunction,
    private val endpoint: ApiEndpoint
) : FindUsagesHandler(function) {

    override fun getPrimaryElements(): Array<PsiElement> = arrayOf(psiElement)

    override fun processElementUsages(
        element: PsiElement,
        processor: Processor<in UsageInfo>,
        options: FindUsagesOptions
    ): Boolean {
        // Inject the synthetic RouteX occurrence at the top of results.
        processor.process(RouteXPythonUsageInfo(element, endpoint))

        // Navigate the tool window to this endpoint as a side effect.
        ApplicationManager.getApplication().invokeLater {
            ToolWindowManager.getInstance(element.project).getToolWindow("RouteX")?.show(null)
            RouteXService.getInstance(element.project).selectEndpoint(endpoint.id)
        }

        // Continue with standard find usages (call sites, references, etc.).
        return super.processElementUsages(element, processor, options)
    }
}
