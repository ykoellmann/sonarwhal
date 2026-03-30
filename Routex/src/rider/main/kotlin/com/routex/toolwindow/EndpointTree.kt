package com.routex.toolwindow

import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.routex.model.ApiEndpoint
import com.routex.model.HttpMethod
import java.awt.Color
import java.awt.Component
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class EndpointTree : Tree() {

    var onEndpointSelected: ((ApiEndpoint?) -> Unit)? = null

    init {
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = EndpointTreeCellRenderer()

        addTreeSelectionListener { e ->
            val node = e.path?.lastPathComponent as? DefaultMutableTreeNode
            val endpoint = node?.userObject as? ApiEndpoint
            onEndpointSelected?.invoke(endpoint)
        }
    }

    fun updateEndpoints(endpoints: List<ApiEndpoint>) {
        val root = DefaultMutableTreeNode("root")

        val grouped = endpoints.groupBy { it.controllerName ?: "Minimal APIs" }
        for ((controller, eps) in grouped.entries.sortedBy { it.key }) {
            val controllerNode = DefaultMutableTreeNode(controller)
            for (ep in eps.sortedBy { it.route }) {
                controllerNode.add(DefaultMutableTreeNode(ep))
            }
            root.add(controllerNode)
        }

        model = DefaultTreeModel(root)
        expandAllRows()
    }

    private fun expandAllRows() {
        var i = 0
        while (i < rowCount) {
            expandRow(i)
            i++
        }
    }
}

private class EndpointTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return

        when (val userObject = node.userObject) {
            is ApiEndpoint -> {
                val methodColor = httpMethodColor(userObject.httpMethod)
                append(userObject.httpMethod.name.padEnd(7), SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_BOLD,
                    methodColor
                ))
                append(" ${userObject.route}", SimpleTextAttributes.REGULAR_ATTRIBUTES)

                if (userObject.meta.analysisWarnings.isNotEmpty()) {
                    append(" ⚠", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.YELLOW))
                }
            }
            is String -> {
                append(userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            }
        }
    }

    private fun httpMethodColor(method: HttpMethod): Color = when (method) {
        HttpMethod.GET -> JBColor(Color(0x00, 0x99, 0x44), Color(0x44, 0xCC, 0x77))
        HttpMethod.POST -> JBColor(Color(0x00, 0x66, 0xCC), Color(0x44, 0x99, 0xFF))
        HttpMethod.PUT -> JBColor(Color(0xCC, 0x66, 0x00), Color(0xFF, 0x99, 0x33))
        HttpMethod.DELETE -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        HttpMethod.PATCH -> JBColor(Color(0x88, 0x00, 0xCC), Color(0xBB, 0x44, 0xFF))
        HttpMethod.HEAD -> JBColor(Color(0x44, 0x44, 0x88), Color(0x88, 0x88, 0xCC))
        HttpMethod.OPTIONS -> JBColor(Color(0x44, 0x44, 0x44), Color(0x88, 0x88, 0x88))
    }
}
