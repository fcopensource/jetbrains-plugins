// Port Manager Veyra: the actions in the Ports toolbar, the context menu and Tools menu.
package com.flightcoders.veyra.ports.ui

import com.flightcoders.veyra.ports.PortEntry
import com.flightcoders.veyra.ports.PortsService
import com.flightcoders.veyra.ports.PortsSettings
import com.flightcoders.veyra.ports.isAlive
import com.flightcoders.veyra.ports.isBrowsable
import com.flightcoders.veyra.ports.pageUrl
import com.flightcoders.veyra.ports.stopProcess
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.datatransfer.StringSelection
import java.io.File

const val NOTIFICATION_GROUP = "Port Manager Veyra"
const val TOOL_WINDOW_ID = "Ports"

fun notify(project: Project?, text: String, type: NotificationType = NotificationType.INFORMATION) {
    NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP).createNotification(text, type).notify(project)
}

private fun describe(entry: PortEntry) = buildString {
    append(entry.framework?.label?.let { "$it (${entry.process.name})" } ?: entry.process.name)
    append(" on port ${entry.port}")
}

/**
 * Asks, then stops the program behind `entry` on a background thread and reports the result. Refuses protected
 * programs (system services, other users' programs, IDEs). If the program also holds other ports, says so.
 */
fun confirmAndStop(project: Project?, entry: PortEntry) {
    if (entry.protected) {
        Messages.showInfoMessage(project, "${describe(entry)} is ${if (entry.process.owned) "an editor or IDE" else "a system service or another user's program"}, so Port Manager Veyra won't stop it.", "Can't Stop This Program")
        return
    }
    val others = PortsService.get().snapshot?.byPid?.get(entry.pid).orEmpty().filter { it.port != entry.port }.map { it.port }
    val alsoFrees = if (others.isEmpty()) "" else "\n\nThis also frees port${if (others.size > 1) "s" else ""} ${others.joinToString(", ")}."
    val answer = Messages.showYesNoDialog(project, "Stop ${describe(entry)}? (PID ${entry.pid})$alsoFrees", "Stop Program", "Stop", "Cancel", Messages.getWarningIcon())
    if (answer != Messages.YES) return
    object : Task.Backgroundable(project, "Stopping ${entry.process.name}", false) {
        override fun run(indicator: ProgressIndicator) {
            val stopped = runCatching { stopProcess(entry.pid) }.getOrElse { false } || !isAlive(entry.pid)
            PortsService.get().refreshNow(fresh = true)
            if (stopped) notify(project, "Stopped ${describe(entry)}. Port ${entry.port} is free.")
            else notify(project, "Couldn't stop ${describe(entry)}. It may need administrator rights.", NotificationType.WARNING)
        }
    }.queue()
}

/** Base for actions on the selected port. */
abstract class EntryAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    protected fun entry(e: AnActionEvent): PortEntry? = e.getData(PORT_ENTRY)
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = entry(e)?.let(::enabledFor) == true }
    open fun enabledFor(entry: PortEntry) = true
}

class OpenInBrowserAction : EntryAction() {
    override fun enabledFor(entry: PortEntry) = isBrowsable(entry)
    override fun actionPerformed(e: AnActionEvent) { entry(e)?.let { BrowserUtil.browse(pageUrl(it)) } }
}

class CopyAddressAction : EntryAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val entry = entry(e) ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(pageUrl(entry)))
        notify(e.project, "Copied ${pageUrl(entry)}")
    }
}

class StopAction : EntryAction() {
    override fun enabledFor(entry: PortEntry) = !entry.protected
    override fun actionPerformed(e: AnActionEvent) { entry(e)?.let { confirmAndStop(e.project, it) } }
}

class RevealFolderAction : EntryAction() {
    private fun folder(entry: PortEntry) = (entry.process.cwd ?: entry.process.path?.let { File(it).parent })?.let(::File)?.takeIf { it.isDirectory }
    override fun enabledFor(entry: PortEntry) = folder(entry) != null
    override fun actionPerformed(e: AnActionEvent) { entry(e)?.let(::folder)?.let(RevealFileAction::openDirectory) }
}

class HidePortAction : EntryAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val entry = entry(e) ?: return
        PortsSettings.get().state.hiddenPorts += entry.port
        PortsService.get().refreshNow()
        notify(e.project, "Port ${entry.port} is hidden. Show it again with Show Hidden Ports.")
    }
}

class RefreshAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) = PortsService.get().refreshNow(fresh = true)
}

class ToggleSystemAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = PortsSettings.get().state.showSystem
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        PortsSettings.get().state.showSystem = state
        PortsService.get().refreshNow()
    }
}

class ToggleNotifyAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = PortsSettings.get().state.notifyOnStart
    override fun setSelected(e: AnActionEvent, state: Boolean) { PortsSettings.get().state.notifyOnStart = state }
}

class ShowHiddenPortsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = PortsSettings.get().state.hiddenPorts.isNotEmpty() }
    override fun actionPerformed(e: AnActionEvent) {
        val hidden = PortsSettings.get().state.hiddenPorts
        val list = hidden.sorted().joinToString(", ")
        hidden.clear()
        PortsService.get().refreshNow()
        notify(e.project, "Showing port${if (list.contains(',')) "s" else ""} $list again.")
    }
}

/** "Free a Port…": type a port number and stop whatever is listening on it. */
class FreePortAction : AnAction(AllIcons.Actions.Cancel), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val validator = object : InputValidator {
            override fun checkInput(input: String?) = input?.trim()?.toIntOrNull() in 1..65535
            override fun canClose(input: String?) = checkInput(input)
        }
        val port = Messages.showInputDialog(project, "Which port do you want to free?", "Free a Port", null, "3000", validator)?.trim()?.toIntOrNull() ?: return
        object : Task.Backgroundable(project, "Finding the program on port $port", false) {
            private var entries: List<PortEntry> = emptyList()
            override fun run(indicator: ProgressIndicator) {
                entries = PortsService.get().scanNow().byPort[port].orEmpty()
                if (entries.isEmpty()) {
                    // Maybe a system program, hidden by default: look with system programs included.
                    val settings = PortsSettings.get().state
                    if (!settings.showSystem) {
                        settings.showSystem = true
                        try { entries = PortsService.get().scanNow().byPort[port].orEmpty() } finally { settings.showSystem = false; PortsService.get().refreshNow() }
                    }
                }
            }
            override fun onSuccess() {
                when {
                    entries.isEmpty() -> notify(project, "Port $port is already free.")
                    else -> confirmAndStop(project, entries.first())
                }
            }
        }.queue()
    }
}

/** Tools → Show Ports in Use. */
class ShowPortsAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.activate(null)
    }
}
