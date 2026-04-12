package com.routex.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.routex.RouteXStateService
import com.routex.gutter.SourceLocationService
import com.routex.model.ApiEndpoint
import com.routex.model.EndpointStatus
import com.routex.model.HttpMethod
import com.routex.model.SavedRequest
import java.awt.Color
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.UUID
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

// ── Node types ────────────────────────────────────────────────────────────────

class EndpointNode(val endpoint: ApiEndpoint) {
    override fun toString() = "${endpoint.method.name.padEnd(7)} ${endpoint.path}"
}

class RequestNode(val endpoint: ApiEndpoint, val request: SavedRequest) {
    override fun toString() = request.name
}

class ControllerNode(val name: String, val endpoints: List<ApiEndpoint>) {
    override fun toString() = name
}

object NoResults { override fun toString() = "No endpoints found" }

// ── Tree ──────────────────────────────────────────────────────────────────────

class EndpointTree(private val project: Project) : Tree() {

    // Callbacks wired by RouteXPanel
    var onEndpointSelected:   ((ApiEndpoint) -> Unit)?              = null
    var onControllerSelected: ((ControllerNode) -> Unit)?           = null
    var onRequestSelected:    ((ApiEndpoint, SavedRequest) -> Unit)? = null

    private val stateService = RouteXStateService.getInstance(project)

    // Last known endpoint list — needed to rebuild request sub-nodes without
    // losing the endpoint data.
    private var currentEndpoints: List<ApiEndpoint> = emptyList()

    init {
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        isRootVisible   = false
        showsRootHandles = true
        cellRenderer    = EndpointTreeCellRenderer()

        // Full-row selection: clicking anywhere on a row (not just the text) selects it
        putClientProperty("JTree.fullRowSelection", true)

        addTreeSelectionListener { e ->
            val node = e.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val obj = node.userObject) {
                is EndpointNode   -> onEndpointSelected?.invoke(obj.endpoint)
                is ControllerNode -> onControllerSelected?.invoke(obj)
                is RequestNode    -> onRequestSelected?.invoke(obj.endpoint, obj.request)
            }
        }

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                handleMouse(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) handleMouse(e)
            }
        })

        // F4 → Jump to Source (standard IntelliJ shortcut)
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_F4) {
                    val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val epId = when (val obj = node.userObject) {
                        is EndpointNode -> obj.endpoint.id
                        is RequestNode  -> obj.endpoint.id
                        else            -> return
                    }
                    SourceLocationService.getInstance(project).navigate(epId)
                }
            }
        })
    }

    // ── Mouse handling ────────────────────────────────────────────────────────

    private fun handleMouse(e: MouseEvent) {
        // Select row on any click in the row area (full-width hit test)
        val row = rowForY(e.y)
        if (row >= 0) setSelectionRow(row)

        if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger) {
            if (row >= 0) showContextMenu(e)
        }
    }

    /**
     * Returns the row index for a given Y coordinate, using full-width detection.
     * Unlike [getRowForLocation], this does not require the X to be over the text.
     */
    private fun rowForY(y: Int): Int {
        for (row in 0 until rowCount) {
            val bounds = getRowBounds(row) ?: continue
            if (y >= bounds.y && y < bounds.y + bounds.height) return row
        }
        return -1
    }

    // ── Context menu ──────────────────────────────────────────────────────────

    private fun showContextMenu(e: MouseEvent) {
        val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val group = DefaultActionGroup()

        when (val obj = node.userObject) {
            is EndpointNode -> buildEndpointMenu(group, obj.endpoint)
            is RequestNode  -> buildRequestMenu(group, obj.endpoint, obj.request)
            else            -> return
        }

        val popup = ActionManager.getInstance()
            .createActionPopupMenu(ActionPlaces.POPUP, group)
        popup.component.show(e.component, e.x, e.y)
    }

    private fun buildEndpointMenu(group: DefaultActionGroup, endpoint: ApiEndpoint) {
        val locService = SourceLocationService.getInstance(project)
        if (locService.canNavigate(endpoint.id)) {
            group.add(object : AnAction("Jump to Source", "Open the C# controller method in the editor", AllIcons.Actions.EditSource) {
                override fun actionPerformed(e: AnActionEvent) {
                    locService.navigate(endpoint.id)
                }
            })
            group.add(Separator.getInstance())
        }

        group.add(object : AnAction("New Request", "Create a new saved request for this endpoint", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                val name = Messages.showInputDialog(
                    project, "Request name:", "New Request", null
                )?.trim()?.takeIf { it.isNotBlank() } ?: return
                val req = SavedRequest(id = UUID.randomUUID().toString(), name = name, isDefault = false)
                stateService.upsertRequest(endpoint.id, req)
                refreshRequests(endpoint.id)
                // Select the new request node
                val newNode = findRequestNode(endpoint.id, req.id)
                if (newNode != null) {
                    val path = javax.swing.tree.TreePath(newNode.path)
                    selectionPath = path
                    scrollPathToVisible(path)
                }
            }
        })

        group.add(Separator.getInstance())

        group.add(object : AnAction("Copy Path", "Copy endpoint path to clipboard", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                cb.setContents(java.awt.datatransfer.StringSelection(endpoint.path), null)
            }
        })
    }

    private fun buildRequestMenu(group: DefaultActionGroup, endpoint: ApiEndpoint, request: SavedRequest) {
        group.add(object : AnAction("Rename…", "Rename this request", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                val newName = Messages.showInputDialog(
                    project, "Request name:", "Rename Request", null, request.name, null
                )?.trim()?.takeIf { it.isNotBlank() } ?: return
                stateService.upsertRequest(endpoint.id, request.copy(name = newName))
                refreshRequests(endpoint.id)
            }
        })

        if (!request.isDefault) {
            group.add(object : AnAction("Set as Default", "Run this request via gutter icon", AllIcons.RunConfigurations.TestState.Run) {
                override fun actionPerformed(e: AnActionEvent) {
                    stateService.setDefault(endpoint.id, request.id)
                    refreshRequests(endpoint.id)
                }
            })
        }

        group.add(Separator.getInstance())

        group.add(object : AnAction("Delete", "Delete this request", AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                val answer = Messages.showYesNoDialog(
                    project, "Delete request \"${request.name}\"?", "Delete Request", null
                )
                if (answer != Messages.YES) return
                stateService.removeRequest(endpoint.id, request.id)
                refreshRequests(endpoint.id)
                // Fall back to selecting the parent endpoint node
                val epNode = findEndpointNode(endpoint.id)
                if (epNode != null) {
                    val path = javax.swing.tree.TreePath(epNode.path)
                    selectionPath = path
                    onEndpointSelected?.invoke(endpoint)
                }
            }
        })

        group.add(Separator.getInstance())

        group.add(object : AnAction("Copy Path", "Copy endpoint path to clipboard", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                cb.setContents(java.awt.datatransfer.StringSelection(endpoint.path), null)
            }
        })
    }

    // ── Tree building ─────────────────────────────────────────────────────────

    fun updateEndpoints(endpoints: List<ApiEndpoint>) {
        currentEndpoints = endpoints
        val prevSelection = selectedPathObject()
        rebuildTree()
        restoreSelection(prevSelection)
    }

    /** Rebuilds only the request sub-nodes for one endpoint (e.g. after save). */
    fun refreshRequests(endpointId: String) {
        val root = model?.root as? DefaultMutableTreeNode ?: return
        val treeModel = model as? DefaultTreeModel ?: return

        root.breadthFirstEnumeration().toList()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { (it.userObject as? EndpointNode)?.endpoint?.id == endpointId }
            ?.let { epNode ->
                epNode.removeAllChildren()
                val endpoint = currentEndpoints.firstOrNull { it.id == endpointId } ?: return@let
                stateService.getRequests(endpointId).forEach { req ->
                    epNode.add(DefaultMutableTreeNode(RequestNode(endpoint, req)))
                }
                treeModel.nodeStructureChanged(epNode)
                expandPath(javax.swing.tree.TreePath(epNode.path))
            }
    }

    private fun rebuildTree() {
        val root = DefaultMutableTreeNode("root")

        if (currentEndpoints.isEmpty()) {
            root.add(DefaultMutableTreeNode(NoResults))
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
                root.add(ctrlNode)
            }
        }

        model = DefaultTreeModel(root)
        expandAllRows()
    }

    // ── Selection helpers ─────────────────────────────────────────────────────

    fun selectEndpoint(id: String): Boolean {
        val node = findEndpointNode(id) ?: return false
        val path = javax.swing.tree.TreePath(node.path)
        selectionPath = path
        scrollPathToVisible(path)
        return true
    }

    fun selectRequest(endpointId: String, requestId: String): Boolean {
        val node = findRequestNode(endpointId, requestId) ?: return false
        val path = javax.swing.tree.TreePath(node.path)
        selectionPath = path
        scrollPathToVisible(path)
        return true
    }

    private fun findEndpointNode(endpointId: String): DefaultMutableTreeNode? {
        val root = model?.root as? DefaultMutableTreeNode ?: return null
        return root.breadthFirstEnumeration().toList()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { (it.userObject as? EndpointNode)?.endpoint?.id == endpointId }
    }

    private fun findRequestNode(endpointId: String, requestId: String): DefaultMutableTreeNode? {
        val root = model?.root as? DefaultMutableTreeNode ?: return null
        return root.breadthFirstEnumeration().toList()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull {
                val obj = it.userObject as? RequestNode ?: return@firstOrNull false
                obj.endpoint.id == endpointId && obj.request.id == requestId
            }
    }

    private fun selectedPathObject(): Any? {
        val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject
    }

    private fun restoreSelection(prev: Any?) {
        when (prev) {
            is EndpointNode -> selectEndpoint(prev.endpoint.id)
            is RequestNode  -> {
                val node = findRequestNode(prev.endpoint.id, prev.request.id) ?: return
                val path = javax.swing.tree.TreePath(node.path)
                selectionPath = path
            }
        }
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
                val ep        = obj.endpoint
                val isRemoved = ep.status == EndpointStatus.REMOVED
                val styleFlag = if (isRemoved) SimpleTextAttributes.STYLE_STRIKEOUT or SimpleTextAttributes.STYLE_BOLD
                                else SimpleTextAttributes.STYLE_BOLD
                val methodColor = if (isRemoved) JBColor.GRAY else methodColor(ep.method)
                val pathAttr    = if (isRemoved)
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.GRAY)
                else SimpleTextAttributes.REGULAR_ATTRIBUTES

                append(ep.method.name.padEnd(7), SimpleTextAttributes(styleFlag, methodColor))
                append(" ${ep.path}", pathAttr)
                ep.summary?.takeIf { it.isNotBlank() }?.let { s ->
                    val short = if (s.length > 40) s.take(40) + "…" else s
                    append("  $short", SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY))
                }
                icon = null
            }
            is RequestNode -> {
                val isDefault = obj.request.isDefault
                append(
                    obj.request.name,
                    if (isDefault) SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null)
                    else SimpleTextAttributes.REGULAR_ATTRIBUTES
                )
                if (isDefault) {
                    append("  default", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor.GRAY))
                }
                icon = AllIcons.Nodes.Tag
            }
            is ControllerNode -> {
                append(obj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${obj.endpoints.size}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY))
                icon = null
            }
            is NoResults -> append("No endpoints found", SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY))
            is String    -> append(obj, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
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
