package com.routex.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.routex.RouteXStateService
import com.routex.model.ApiEndpoint
import com.routex.model.HttpMethod
import com.routex.model.SavedRequest
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

// ── Node types ────────────────────────────────────────────────────────────────

/** Schema node: the detected endpoint (not directly runnable without a SavedRequest). */
class EndpointNode(val endpoint: ApiEndpoint) {
    private fun displayName() = if (endpoint.controllerName != null) "${endpoint.methodName}()" else endpoint.methodName
    override fun toString() = "${endpoint.httpMethod.name.padEnd(7)} ${displayName()}"
}

/** A named, runnable request under an EndpointNode. */
class SavedRequestNode(val request: SavedRequest, val endpoint: ApiEndpoint) {
    override fun toString() = request.name
}

/** The "+ New Request" leaf at the bottom of each endpoint's children. */
class AddRequestNode(val endpoint: ApiEndpoint) {
    override fun toString() = "New Request"
}

class ControllerNode(val name: String, val endpoints: List<ApiEndpoint>) {
    override fun toString() = name
}

object NoResults {
    override fun toString() = "No endpoints found"
}

// ── Tree ──────────────────────────────────────────────────────────────────────

class EndpointTree(private val project: Project) : Tree() {

    var onEndpointSelected: ((ApiEndpoint?) -> Unit)? = null
    var onControllerSelected: ((ControllerNode) -> Unit)? = null
    var onGoToSource: ((ApiEndpoint) -> Unit)? = null
    var onRequestSelected: ((ApiEndpoint, SavedRequest) -> Unit)? = null
    var onAddRequest: ((ApiEndpoint) -> Unit)? = null
    var onRenameRequest: ((ApiEndpoint, SavedRequest) -> Unit)? = null

    private var currentEndpoints: List<ApiEndpoint> = emptyList()

    init {
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = EndpointTreeCellRenderer()

        addTreeSelectionListener { e ->
            val node = e.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val obj = node.userObject) {
                is SavedRequestNode -> onRequestSelected?.invoke(obj.endpoint, obj.request)
                is EndpointNode     -> {
                    // Clicking the schema node selects its default/first request, if any
                    val req = RouteXStateService.getInstance(project).getDefaultRequest(obj.endpoint.id)
                    if (req != null) onRequestSelected?.invoke(obj.endpoint, req)
                    else onEndpointSelected?.invoke(obj.endpoint)
                }
                is ControllerNode   -> onControllerSelected?.invoke(obj)
                else                -> onEndpointSelected?.invoke(null)
            }
        }

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                when {
                    SwingUtilities.isRightMouseButton(e) -> showPopup(e)
                    e.clickCount == 2                    -> handleDoubleClick(e)
                }
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }
        })
    }

    private fun handleDoubleClick(e: MouseEvent) {
        val row = getRowForLocation(e.x, e.y).takeIf { it >= 0 } ?: return
        val node = getPathForRow(row)?.lastPathComponent as? DefaultMutableTreeNode ?: return
        if (node.userObject is EndpointNode) {
            onAddRequest?.invoke((node.userObject as EndpointNode).endpoint)
        }
    }

    private fun showPopup(e: MouseEvent) {
        val row = getRowForLocation(e.x, e.y).takeIf { it >= 0 } ?: return
        setSelectionRow(row)
        val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return

        val group = DefaultActionGroup()
        when (val obj = node.userObject) {
            is EndpointNode -> {
                group.add(popupAction("Go to Source", AllIcons.Actions.Forward) { onGoToSource?.invoke(obj.endpoint) })
                group.add(popupAction("New Request", AllIcons.General.Add) { onAddRequest?.invoke(obj.endpoint) })
            }
            is SavedRequestNode -> {
                group.add(popupAction("Rename", AllIcons.Actions.Edit) { onRenameRequest?.invoke(obj.endpoint, obj.request) })
                if (!obj.request.isDefault) {
                    group.add(popupAction("Set as Default", AllIcons.Actions.Execute) {
                        RouteXStateService.getInstance(project).setDefault(obj.endpoint.id, obj.request.id)
                        refreshTree()
                    })
                }
                group.addSeparator()
                group.add(popupAction("Remove", AllIcons.Actions.GC) {
                    RouteXStateService.getInstance(project).removeRequest(obj.endpoint.id, obj.request.id)
                    refreshTree()
                })
            }
            else -> return
        }
        ActionManager.getInstance()
            .createActionPopupMenu("RouteX.Tree", group)
            .component
            .show(e.component, e.x, e.y)
    }

    private fun popupAction(text: String, icon: Icon? = null, handler: () -> Unit) =
        object : AnAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = handler()
        }

    /** Rebuild tree from stored endpoints, refreshing request children from state. */
    fun refreshTree() = updateEndpoints(currentEndpoints)

    fun updateEndpoints(endpoints: List<ApiEndpoint>) {
        currentEndpoints = endpoints
        val state = RouteXStateService.getInstance(project)
        val root = DefaultMutableTreeNode("root")

        if (endpoints.isEmpty()) {
            root.add(DefaultMutableTreeNode(NoResults))
        } else {
            val grouped = endpoints.groupBy { it.controllerName ?: "Minimal APIs" }
            for ((controller, eps) in grouped.entries.sortedBy { it.key }) {
                val controllerNode = DefaultMutableTreeNode(ControllerNode(controller, eps))
                for (ep in eps.sortedBy { it.methodName }) {
                    val epNode = DefaultMutableTreeNode(EndpointNode(ep))
                    state.getRequests(ep.id).forEach { req ->
                        epNode.add(DefaultMutableTreeNode(SavedRequestNode(req, ep)))
                    }
                    controllerNode.add(epNode)
                }
                root.add(controllerNode)
            }
        }

        model = DefaultTreeModel(root)
        expandAllRows()
    }

    /** Selects the EndpointNode (schema) matching the given endpoint id. */
    fun selectEndpoint(id: String): Boolean {
        val root = model?.root as? DefaultMutableTreeNode ?: return false
        val nodes = root.breadthFirstEnumeration()
        while (nodes.hasMoreElements()) {
            val node = nodes.nextElement() as? DefaultMutableTreeNode ?: continue
            val en = node.userObject as? EndpointNode ?: continue
            if (en.endpoint.id == id) {
                val path = javax.swing.tree.TreePath(node.path)
                selectionPath = path
                scrollPathToVisible(path)
                return true
            }
        }
        return false
    }

    private fun expandAllRows() {
        var i = 0
        while (i < rowCount) { expandRow(i); i++ }
    }
}

// ── Cell renderer ─────────────────────────────────────────────────────────────

private class EndpointTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean,
        expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        clear()
        val node = value as? DefaultMutableTreeNode ?: return

        when (val obj = node.userObject) {
            is EndpointNode -> {
                val ep = obj.endpoint
                append(ep.httpMethod.name.padEnd(7), SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, methodColor(ep.httpMethod)))
                val displayName = if (ep.controllerName != null) "${ep.methodName}()" else ep.methodName
                append(" $displayName", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (ep.meta.analysisWarnings.isNotEmpty())
                    append(" ⚠", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.YELLOW))
            }
            is SavedRequestNode -> {
                val req = obj.request
                icon = if (req.isDefault) AllIcons.Actions.Execute else AllIcons.Actions.Edit
                append(req.name, if (req.isDefault) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (req.isDefault) append("  ★", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor(Color(0xCC, 0xAA, 0x00), Color(0xFF, 0xDD, 0x55))))
            }
            is ControllerNode -> {
                append(obj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${obj.endpoints.size}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY))
            }
            is String   -> append(obj, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            is NoResults -> append("No endpoints found", SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY))
        }
    }

    private fun methodColor(method: HttpMethod): Color = when (method) {
        HttpMethod.GET     -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x4C, 0xC4, 0x7F))
        HttpMethod.POST    -> JBColor(Color(0x00, 0x77, 0xDD), Color(0x44, 0x99, 0xFF))
        HttpMethod.PUT     -> JBColor(Color(0xCC, 0x66, 0x00), Color(0xFF, 0x99, 0x33))
        HttpMethod.DELETE  -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        HttpMethod.PATCH   -> JBColor(Color(0x88, 0x00, 0xCC), Color(0xBB, 0x44, 0xFF))
        HttpMethod.HEAD    -> JBColor(Color(0x44, 0x44, 0x88), Color(0x88, 0x88, 0xCC))
        HttpMethod.OPTIONS -> JBColor(Color(0x44, 0x44, 0x44), Color(0x88, 0x88, 0x88))
    }
}
