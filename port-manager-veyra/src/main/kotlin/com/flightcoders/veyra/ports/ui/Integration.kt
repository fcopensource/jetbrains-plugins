// Port Manager Veyra: the Ports tool window, the status-bar counter and "your server started" notifications.
package com.flightcoders.veyra.ports.ui

import com.flightcoders.veyra.ports.FrameworkKind
import com.flightcoders.veyra.ports.PortsService
import com.flightcoders.veyra.ports.PortsSettings
import com.flightcoders.veyra.ports.Snapshot
import com.flightcoders.veyra.ports.isBrowsable
import com.flightcoders.veyra.ports.pageUrl
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.event.MouseEvent
import java.io.File

class PortsToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = ContentFactory.getInstance().createContent(null, "", false)
        content.component = PortsPanel(project, content)
        toolWindow.contentManager.addContent(content)
    }
}

/* ---------- Status bar ---------- */

private const val WIDGET_ID = "VeyraPorts.Widget"

class PortsStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = WIDGET_ID
    override fun getDisplayName() = "Ports in Use"
    override fun createWidget(project: Project): StatusBarWidget = PortsStatusBarWidget(project)
}

private class PortsStatusBarWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
    private var statusBar: StatusBar? = null
    private var snapshot: Snapshot? = null

    override fun ID() = WIDGET_ID
    override fun getPresentation() = this
    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        PortsService.get().subscribe(this) { next, _ -> snapshot = next; statusBar.updateWidget(WIDGET_ID) }
    }
    override fun dispose() { statusBar = null }

    private fun apps() = snapshot?.entries.orEmpty().filter { !it.protected }
    override fun getText(): String = apps().size.let { if (it == 1) "1 port" else "$it ports" }
    override fun getAlignment() = Component.CENTER_ALIGNMENT
    override fun getTooltipText(): String {
        val apps = apps()
        if (apps.isEmpty()) return "No apps are listening on a port. Click to open Ports."
        return apps.take(8).joinToString("<br>", "<html><b>Ports in use</b><br>", if (apps.size > 8) "<br>…</html>" else "</html>") {
            ":${it.port} ${it.framework?.label ?: it.process.name}"
        }
    }
    override fun getClickConsumer() = Consumer<MouseEvent> {
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }
}

/* ---------- "Your server started" notifications ---------- */

/** When a dev server or database starts from this project's folder, offers to open it or stop it. */
class PortsStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val base = project.basePath?.let { File(it).path.lowercase().trimEnd('\\', '/') } ?: return
        val lifetime: Disposable = Disposer.newDisposable("Port Manager Veyra notifications")
        Disposer.register(project, lifetime)
        PortsService.get().subscribe(lifetime) { _, diff ->
            if (!PortsSettings.get().state.notifyOnStart) return@subscribe
            for (entry in diff.added) {
                val mine = entry.workspace?.let { File(it).path.lowercase().trimEnd('\\', '/') } == base
                if (!mine || entry.protected || entry.framework?.kind == FrameworkKind.EDITOR) continue
                val name = entry.framework?.label ?: entry.process.name
                val notification = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification("$name is running on port ${entry.port}", pageUrl(entry), NotificationType.INFORMATION)
                if (isBrowsable(entry)) notification.addAction(NotificationAction.createSimpleExpiring("Open in browser") { BrowserUtil.browse(pageUrl(entry)) })
                notification.addAction(NotificationAction.createSimpleExpiring("Stop") { confirmAndStop(project, entry) })
                notification.notify(project)
            }
        }
    }
}
