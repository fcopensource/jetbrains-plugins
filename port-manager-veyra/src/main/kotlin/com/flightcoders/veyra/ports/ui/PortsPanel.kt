// Port Manager Veyra: the Ports tool window. A tree of listening ports grouped into This Project, Apps & Dev
// Servers, Editors & Tools and System, updated live. Double-click opens a port in the browser.
package com.flightcoders.veyra.ports.ui

import com.flightcoders.veyra.ports.Diff
import com.flightcoders.veyra.ports.FrameworkKind
import com.flightcoders.veyra.ports.GroupId
import com.flightcoders.veyra.ports.PortEntry
import com.flightcoders.veyra.ports.PortsService
import com.flightcoders.veyra.ports.PortsSettings
import com.flightcoders.veyra.ports.Snapshot
import com.flightcoders.veyra.ports.groupOf
import com.flightcoders.veyra.ports.isBrowsable
import com.flightcoders.veyra.ports.pageUrl
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.actionSystem.DataSink
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.tree.TreeUtil
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

val PORT_ENTRY: DataKey<PortEntry> = DataKey.create("VeyraPorts.Entry")

private class GroupNode(val id: GroupId, val count: Int)

class PortsPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true), UiDataProvider {
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = Renderer()
        emptyText.text = "Looking for ports in use…"
    }
    private val collapsed = mutableSetOf(GroupId.SYSTEM)

    init {
        val toolbar = ActionManager.getInstance().createActionToolbar("VeyraPorts", ActionManager.getInstance().getAction("VeyraPorts.Toolbar") as ActionGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)
        setContent(ScrollPaneFactory.createScrollPane(tree, true))
        PopupHandler.installPopupMenu(tree, "VeyraPorts.Context", "VeyraPortsPopup")
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) selected()?.takeIf(::isBrowsable)?.let { BrowserUtil.browse(pageUrl(it)) }
            }
        })
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) { groupOf(event.path)?.let { collapsed -= it } }
            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) { groupOf(event.path)?.let { collapsed += it } }
        })
        PortsService.get().subscribe(parent) { snapshot, _: Diff -> show(snapshot) }
    }

    private fun groupOf(path: TreePath) = ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? GroupNode)?.id

    /** The group shown for an entry in *this* project's window: servers from other open projects count as apps. */
    private fun groupFor(entry: PortEntry): GroupId {
        val group = groupOf(entry)
        if (group != GroupId.WORKSPACE) return group
        val here = project.basePath?.let { File(it).path.lowercase().trimEnd('\\', '/') }
        return if (here != null && entry.workspace?.let { File(it).path.lowercase().trimEnd('\\', '/') } == here) GroupId.WORKSPACE else GroupId.APPS
    }

    private fun show(snapshot: Snapshot) {
        val selectedKey = selected()?.key
        root.removeAllChildren()
        val groups = snapshot.entries.groupBy(::groupFor)
        for (id in GroupId.entries) {
            val entries = groups[id] ?: continue
            val node = DefaultMutableTreeNode(GroupNode(id, entries.size))
            entries.forEach { node.add(DefaultMutableTreeNode(it)) }
            root.add(node)
        }
        model.reload()
        for (i in 0 until root.childCount) {
            val node = root.getChildAt(i) as DefaultMutableTreeNode
            if ((node.userObject as GroupNode).id !in collapsed) tree.expandPath(TreePath(node.path))
        }
        if (selectedKey != null) {
            TreeUtil.treeNodeTraverser(root).filter(DefaultMutableTreeNode::class.java)
                .find { (it.userObject as? PortEntry)?.key == selectedKey }?.let { tree.selectionPath = TreePath(it.path) }
        }
        val error = PortsService.get().lastError
        tree.emptyText.text = when {
            error != null -> "Couldn't read ports: $error"
            PortsSettings.get().state.showSystem -> "No ports in use"
            else -> "No apps are listening on a port. Use Show System Ports to see services."
        }
    }

    fun selected(): PortEntry? = (tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? PortEntry

    override fun uiDataSnapshot(sink: DataSink) {
        sink[PORT_ENTRY] = selected()
    }

    private class Renderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject) {
                is GroupNode -> {
                    icon = when (item.id) {
                        GroupId.WORKSPACE -> AllIcons.Nodes.Module
                        GroupId.APPS -> AllIcons.Nodes.Services
                        GroupId.EDITORS -> AllIcons.Nodes.Plugin
                        GroupId.SYSTEM -> AllIcons.Nodes.Locked
                    }
                    append(item.id.label, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is PortEntry -> {
                    icon = when {
                        item.protected -> AllIcons.Nodes.Locked
                        item.framework?.kind == FrameworkKind.DATABASE -> AllIcons.Nodes.DataTables
                        item.framework?.kind == FrameworkKind.WEB -> AllIcons.General.Web
                        else -> AllIcons.Nodes.Console
                    }
                    append(":${item.port}", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("   ")
                    item.framework?.let { append(it.label + "  ", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) }
                    append("${item.process.name} · PID ${item.pid}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("   ${if (item.localOnly) "this computer only" else item.addresses.joinToString(", ")}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    (item.process.cwd ?: item.process.path)?.let { append("   $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES) }
                    toolTipText = item.process.command ?: item.process.path
                }
            }
        }
    }
}
