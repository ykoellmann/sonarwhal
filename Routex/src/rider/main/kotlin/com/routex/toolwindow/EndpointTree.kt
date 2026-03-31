package com.routex.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.routex.model.ApiEndpoint
import com.routex.model.HttpMethod
import java.awt.Color
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Wrapper stored in tree nodes so toString() is always readable even if the
 * cell renderer's `is` check fails (e.g. classloader edge cases during hot-reload).
 */
class EndpointNode(val endpoint: ApiEndpoint) {
    override fun toString() = "${endpoint.httpMethod.name.padEnd(7)} ${endpoint.methodName}()"
}

/** Represents a controller/group node in the tree. */
class ControllerNode(val name: String, val endpoints: List<ApiEndpoint>) {
    override fun toString() = name
}

object NoResults {
    override fun toString() = "No endpoints found"
}

class EndpointTree(private val project: Project) : Tree() {

    var onEndpointSelected: ((ApiEndpoint?) -> Unit)? = null
    var onControllerSelected: ((ControllerNode) -> Unit)? = null
    var onGoToSource: ((ApiEndpoint) -> Unit)? = null

    init {
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = EndpointTreeCellRenderer()

        addTreeSelectionListener { e ->
            val node = e.path?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val obj = node.userObject) {
                is EndpointNode    -> onEndpointSelected?.invoke(obj.endpoint)
                is ControllerNode  -> onControllerSelected?.invoke(obj)
                else               -> onEndpointSelected?.invoke(null)
            }
        }

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isRightMouseButton(e)) showPopup(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }
        })
    }

    private fun showPopup(e: MouseEvent) {
        val row = getRowForLocation(e.x, e.y).takeIf { it >= 0 } ?: return
        setSelectionRow(row)
        val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val en = node.userObject as? EndpointNode ?: return

        val menu = JPopupMenu()

        val goToSource = JMenuItem("Go to Source")
        goToSource.addActionListener { onGoToSource?.invoke(en.endpoint) }
        menu.add(goToSource)

        menu.show(e.component, e.x, e.y)
    }

    /** Selects the tree node matching the given endpoint id. Returns true if found. */
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

    fun updateEndpoints(endpoints: List<ApiEndpoint>) {
        val root = DefaultMutableTreeNode("root")

        if (endpoints.isEmpty()) {
            root.add(DefaultMutableTreeNode(NoResults))
        } else {
            val grouped = endpoints.groupBy { it.controllerName ?: "Minimal APIs" }
            for ((controller, eps) in grouped.entries.sortedBy { it.key }) {
                val controllerNode = DefaultMutableTreeNode(ControllerNode(controller, eps))
                for (ep in eps.sortedBy { it.methodName }) {
                    controllerNode.add(DefaultMutableTreeNode(EndpointNode(ep)))
                }
                root.add(controllerNode)
            }
        }

        model = DefaultTreeModel(root)
        expandAllRows()
    }

    private fun expandAllRows() {
        var i = 0
        while (i < rowCount) { expandRow(i); i++ }
    }
}

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
                append(" ${ep.methodName}()", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (ep.meta.analysisWarnings.isNotEmpty())
                    append(" ⚠", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.YELLOW))
            }
            is ControllerNode -> {
                append(obj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${obj.endpoints.size}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY))
            }
            is String -> append(obj, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
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
