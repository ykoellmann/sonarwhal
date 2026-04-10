package com.routex.providers.python

import com.intellij.usageView.UsageInfo
import com.intellij.psi.PsiElement
import com.routex.model.ApiEndpoint

/**
 * Synthetic Find Usages entry representing a RouteX endpoint occurrence.
 *
 * Mirrors the role of [com.routex.FindResultRouteXEndpoint] /
 * [com.routex.RouteXEndpointOccurrence] in the C# / ReSharper pipeline,
 * but adapted for IntelliJ's [UsageInfo]-based Find Usages API.
 *
 * The [element] is the [com.jetbrains.python.psi.PyFunction] declaration so
 * that standard navigation (clicking the usage) jumps to the function.
 * The endpoint metadata is carried alongside for display and tool-window selection.
 */
class RouteXPythonUsageInfo(element: PsiElement, val endpoint: ApiEndpoint) : UsageInfo(element) {

    /** Text shown in the Find Usages "Comment" column. */
    fun describe(): String = "RouteX: ${endpoint.httpMethod.name} ${endpoint.route}"
}
