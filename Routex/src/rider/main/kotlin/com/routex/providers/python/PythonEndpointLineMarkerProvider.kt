package com.routex.providers.python

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.jetbrains.python.PyTokenTypes
import com.jetbrains.python.psi.PyFunction
import com.routex.RouteXService
import com.routex.RouteXStateService
import com.routex.model.SupportedLanguage
import javax.swing.Icon

/**
 * Adds a play-button gutter icon on every Python function that is a detected API endpoint.
 *
 * Mirrors the behaviour of [com.routex.gutter.RouteXGutterService] for C# files,
 * but uses IntelliJ's standard [LineMarkerProviderDescriptor] which is the idiomatic
 * approach for non-ReSharper languages.
 *
 * Only active when the Python plugin is present (registered via routex-python.xml).
 */
class PythonEndpointLineMarkerProvider : LineMarkerProviderDescriptor() {

    override fun getName(): String = "RouteX endpoint"
    override fun getIcon(): Icon = AllIcons.RunConfigurations.TestState.Run

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        // Only fire on the name identifier of a function to avoid one marker per token.
        if (element.node?.elementType != PyTokenTypes.IDENTIFIER) return null
        val function = element.parent as? PyFunction ?: return null
        if (function.nameIdentifier != element) return null

        val vFile = element.containingFile?.virtualFile ?: return null
        val document = PsiDocumentManager.getInstance(element.project)
            .getDocument(element.containingFile) ?: return null
        val lineNumber = document.getLineNumber(function.textOffset) + 1  // 1-based

        val endpoint = RouteXService.getInstance(element.project).endpoints.find {
            it.language == SupportedLanguage.PYTHON &&
                    it.filePath == vFile.path &&
                    it.lineNumber == lineNumber
        } ?: return null

        val project = element.project
        return LineMarkerInfo(
            element,
            element.textRange,
            AllIcons.RunConfigurations.TestState.Run,
            { _ ->
                val defaultReq = RouteXStateService.getInstance(project).getDefaultRequest(endpoint.id)
                if (defaultReq != null)
                    "Run ${endpoint.httpMethod.name} ${endpoint.route} — ${defaultReq.name}"
                else
                    "Open ${endpoint.httpMethod.name} ${endpoint.route} in RouteX"
            },
            { _, _ ->
                ToolWindowManager.getInstance(project).getToolWindow("RouteX")?.show(null)
                val stateService = RouteXStateService.getInstance(project)
                val defaultReq = stateService.getDefaultRequest(endpoint.id)
                if (defaultReq != null) {
                    RouteXService.getInstance(project).runRequest(endpoint.id, defaultReq.id)
                } else {
                    RouteXService.getInstance(project).selectEndpoint(endpoint.id)
                }
            },
            GutterIconRenderer.Alignment.LEFT,
            { "RouteX: ${endpoint.httpMethod.name} ${endpoint.route}" }
        )
    }
}
