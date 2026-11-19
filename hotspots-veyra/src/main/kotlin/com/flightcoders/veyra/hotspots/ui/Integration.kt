// Where Hotspots meets the rest of the IDE: actions, the Project view, the status bar and startup.
package com.flightcoders.veyra.hotspots.ui

import com.flightcoders.veyra.hotspots.HotspotsListener
import com.flightcoders.veyra.hotspots.HotspotsService
import com.flightcoders.veyra.hotspots.HotspotsSettings
import com.flightcoders.veyra.hotspots.Level
import com.flightcoders.veyra.hotspots.PERIODS
import com.flightcoders.veyra.hotspots.Status
import com.flightcoders.veyra.hotspots.ago
import com.flightcoders.veyra.hotspots.explain
import com.flightcoders.veyra.hotspots.level
import com.flightcoders.veyra.hotspots.markdownReport
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectView
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

private const val GROUP = "Hotspots Veyra"
val FLAME = IconLoader.getIcon("/icons/flame.svg", HotspotsService::class.java)

// ------------------------------------------------------------------------------------------------ startup

class HotspotsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        val service = HotspotsService.of(project)
        project.messageBus.connect(service).subscribe(HotspotsListener.TOPIC, HotspotsListener {
            if (service.status != Status.SCANNING) ProjectView.getInstance(project).refresh()
        })
        service.start()
    }
}

// ------------------------------------------------------------------------------------------------ actions

private fun fileOf(e: AnActionEvent): VirtualFile? = e.getData(CommonDataKeys.VIRTUAL_FILE)

class RefreshAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { HotspotsService.of(it).refresh() } }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class ChangePeriodAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = HotspotsSettings.of(project).state
        JBPopupFactory.getInstance().createPopupChooserBuilder(PERIODS)
            .setTitle("How Much Git History to Read")
            .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { (m, label) -> if (m == settings.months) "$label  ✓" else label })
            .setItemChosenCallback { (m, _) -> if (m != settings.months) { settings.months = m; HotspotsService.of(project).refresh() } }
            .createPopup().showInBestPositionFor(e.dataContext)
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class CopyReportAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = HotspotsService.of(project)
        val a = service.analysis ?: return notify(project, "Nothing to report yet: Hotspots is still reading your git history.")
        CopyPasteManager.getInstance().setContents(StringSelection(markdownReport(a, service.months, project.name)))
        notify(project, "Hotspots report copied. Paste it into an issue, a pull request or your team chat.")
    }
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project?.let { HotspotsService.of(it).analysis } != null }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class ShowMapAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        HotspotsToolWindowFactory.reveal(project, fileOf(e)?.let { HotspotsService.of(project).statFor(it)?.path })
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class ExplainAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = fileOf(e) ?: return
        val service = HotspotsService.of(project)
        val a = service.analysis
        val f = service.statFor(file)
        if (a == null || f == null) return notify(project, if (service.status == Status.SCANNING) "Hotspots is still reading your git history." else "Hotspots has no history for ${file.name} (it may be new, generated or left out).")
        val lv = level(f.score)
        val n = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification("${f.name}: ${lv.label}${if (f.rank > 0) " (#${f.rank})" else ""}", explain(f, a) + " Last changed ${ago(f.lastChange, a.until)}.", if (lv == Level.CRITICAL || lv == Level.HIGH) NotificationType.WARNING else NotificationType.INFORMATION)
        n.addAction(NotificationAction.createSimpleExpiring("Show in Map") { HotspotsToolWindowFactory.reveal(project, f.path) })
        n.addAction(NotificationAction.createSimpleExpiring("File History") { showHistory(project, f.path) })
        if (a.coupledWith(f.path).isNotEmpty()) n.addAction(NotificationAction.createSimpleExpiring("Open Files That Change With It") { openTogether(project, f.path) })
        n.notify(project)
    }
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null && fileOf(e)?.isDirectory == false }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class OpenTogetherAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val f = fileOf(e)?.let { HotspotsService.of(project).statFor(it) } ?: return
        openTogether(project, f.path)
    }
    override fun update(e: AnActionEvent) {
        val project = e.project; val file = fileOf(e)
        val service = project?.let { HotspotsService.of(it) }
        e.presentation.isEnabledAndVisible = file != null && service?.statFor(file)?.let { service.analysis?.coupledWith(it.path)?.isNotEmpty() } == true
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

fun openTogether(project: Project, path: String) {
    val together = HotspotsService.of(project).analysis?.coupledWith(path).orEmpty()
    if (together.isEmpty()) return notify(project, "No files change together with this one often enough to be sure.")
    for (c in together.take(5)) HotspotsService.of(project).fileFor(c.b)?.let { FileEditorManager.getInstance(project).openFile(it, false) }
}

/** The file's recent commits; pick one to see what it changed in this file. */
fun showHistory(project: Project, path: String) {
    val dir = HotspotsService.of(project).root ?: return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reading the history of ${path.substringAfterLast('/')}", true) {
        override fun run(indicator: ProgressIndicator) {
            val out = runCatching { HotspotsService.git(dir, listOf("log", "--no-merges", "--format=%h%x1f%s%x1f%an%x1f%at", "-n", "100", "--", path), 30_000) }.getOrDefault("")
            val now = System.currentTimeMillis()
            val items = out.lines().filter { it.isNotBlank() }.map { it.split('\u001f') }.filter { it.size == 4 }
                .map { (h, s, a, t) -> HistoryItem(h, s, "$a · ${ago((t.toLongOrNull() ?: 0) * 1000, now)}") }
            ApplicationManager.getApplication().invokeLater {
                if (items.isEmpty()) return@invokeLater notify(project, "No commits found for $path.")
                JBPopupFactory.getInstance().createPopupChooserBuilder(items)
                    .setTitle("${path.substringAfterLast('/')}: Last ${items.size} Commits")
                    .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { "${it.subject}    —  ${it.meta}" })
                    .setNamerForFiltering { "${it.subject} ${it.meta}" }
                    .setItemChosenCallback { item -> showCommit(project, path, item) }
                    .createPopup().showCenteredInCurrentWindow(project)
            }
        }
    })
}

data class HistoryItem(val hash: String, val subject: String, val meta: String)

private fun showCommit(project: Project, path: String, item: HistoryItem) {
    val dir = HotspotsService.of(project).root ?: return
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading ${item.hash}", true) {
        override fun run(indicator: ProgressIndicator) {
            val diff = runCatching { HotspotsService.git(dir, listOf("show", "--format=commit %H%nAuthor: %an%nDate:   %ad%n%n    %s%n", item.hash, "--", path), 30_000) }.getOrDefault("")
            ApplicationManager.getApplication().invokeLater {
                val file = LightVirtualFile("${item.hash} ${path.substringAfterLast('/')}.diff", FileTypeManager.getInstance().getFileTypeByExtension("diff"), diff)
                file.isWritable = false
                FileEditorManager.getInstance(project).openFile(file, true)
            }
        }
    })
}

fun notify(project: Project, text: String) =
    NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(text, NotificationType.INFORMATION).notify(project)

// ------------------------------------------------------------------------------------------------ project view

class HotspotsProjectViewDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val project = node.project ?: return
        val file = node.virtualFile?.takeIf { !it.isDirectory } ?: return
        val settings = HotspotsSettings.of(project).state
        if (!settings.projectViewFlames) return
        val f = HotspotsService.of(project).statFor(file) ?: return
        val lv = level(f.score)
        if (f.rank == 0 || f.rank > settings.flameCount || lv == Level.LOW || lv == Level.NONE) return
        if (data.coloredText.isEmpty()) data.addText(data.presentableText ?: file.name, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, data.forcedTextForeground))
        data.addText("  🔥 #${f.rank}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, levelColor(lv)))
        data.tooltip = "Hotspot #${f.rank}: ${f.commits} commit${if (f.commits == 1) "" else "s"}, ${f.loc} lines"
    }
}

// ------------------------------------------------------------------------------------------------ status bar

class HotspotsStatusBarFactory : StatusBarWidgetFactory {
    override fun getId() = ID
    override fun getDisplayName() = "Hotspots Veyra"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = HotspotsWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar) = true
    companion object { const val ID = "VeyraHotspots.Widget" }
}

class HotspotsWidget(private val project: Project) : CustomStatusBarWidget {
    private val label = JBLabel().apply {
        border = JBUI.Borders.empty(0, 6); cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = current()?.path
                HotspotsToolWindowFactory.reveal(project, path)
            }
        })
    }
    private val connection = project.messageBus.connect()

    init {
        connection.subscribe(HotspotsListener.TOPIC, HotspotsListener { update() })
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) = update()
        })
        update()
    }

    private fun current() = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.let { HotspotsService.of(project).statFor(it) }

    private fun update() {
        val a = HotspotsService.of(project).analysis
        val f = current()
        if (!HotspotsSettings.of(project).state.statusBar || a == null || f == null || f.commits == 0) { label.isVisible = false; return }
        val lv = level(f.score)
        label.isVisible = true
        label.icon = FLAME
        label.text = if (f.rank in 1..20) "Hotspot #${f.rank}" else lv.label
        label.foreground = if (lv == Level.CRITICAL || lv == Level.HIGH) levelColor(lv) else null
        label.toolTipText = "<html>${tooltipHtml(f, a)}<br><br>${esc(explain(f, a))}<br><br><i>Click to see it in the map</i></html>"
    }

    override fun ID() = HotspotsStatusBarFactory.ID
    override fun getComponent(): JComponent = label
    override fun install(statusBar: StatusBar) {}
    override fun dispose() { connection.disconnect() }
}
