// Aura in the IDE: the status bar, the Aura tool window, actions and settings.
package com.flightcoders.veyra.aura.ui

import com.flightcoders.veyra.aura.AuraListener
import com.flightcoders.veyra.aura.AuraService
import com.flightcoders.veyra.aura.dayKey
import com.flightcoders.veyra.aura.fmt
import com.flightcoders.veyra.aura.liveStreak
import com.flightcoders.veyra.aura.rankFor
import com.flightcoders.veyra.aura.signed
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.util.Base64
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

val SPARKLE = IconLoader.getIcon("/icons/aura.svg", AuraService::class.java)
const val TOOL_WINDOW = "Aura"

// ------------------------------------------------------------------------------------------------ status bar

class AuraStatusBarFactory : StatusBarWidgetFactory {
    override fun getId() = "AuraVeyra.Widget"
    override fun getDisplayName() = "Aura Veyra"
    override fun isAvailable(project: Project) = AuraService.instance.settings.let { it.enabled && it.statusBar }
    override fun createWidget(project: Project): StatusBarWidget = AuraWidget(project)
}

/** "1,150 aura · 7-day streak", and each move for a few seconds as it happens: "+15 aura · Cleared every error in Cart.kt". */
class AuraWidget(private val project: Project) : CustomStatusBarWidget {
    private val label = JBLabel(SPARKLE).apply {
        border = JBUI.Borders.empty(0, 6)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { AuraToolWindowFactory.reveal(project) } })
    }
    private val timer = Timer(1000) { update() }

    override fun ID() = "AuraVeyra.Widget"
    override fun getComponent(): JComponent = label
    override fun install(statusBar: StatusBar) {
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(AuraListener.TOPIC, AuraListener { ApplicationManager.getApplication().invokeLater { update() } })
        timer.start()
        update()
    }
    override fun dispose() { timer.stop() }

    /** The text the widget shows now. */
    fun text(): String {
        val service = AuraService.instance
        service.flash?.let { (text, until) -> if (service.now() < until) return if (text.length > 60) text.take(59) + "…" else text }
        val s = service.aura
        val streak = liveStreak(s, service.now())
        return "${fmt(s.total)} aura" + if (streak >= 2) " · $streak-day streak" else ""
    }

    private fun update() {
        val service = AuraService.instance
        val s = service.aura
        val (rank, next, progress) = rankFor(s.total)
        label.text = text()
        label.toolTipText = "<html><b>${rank.name}</b> · ${fmt(s.total)} aura<br>${rank.vibe}<br><br>" +
            (if (next != null) "${(progress * 100).toInt()}% of the way to <b>${next.name}</b> (${fmt(next.min)})" else "Max rank. Respect.") +
            "<br>Today: <b>${signed(s.days[dayKey(service.now())] ?: 0)}</b><br><br>Click to open your aura.</html>"
    }
}

// ------------------------------------------------------------------------------------------------ tool window

class AuraToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content: JComponent = if (JBCefApp.isSupported()) AuraBrowser(project, toolWindow.disposable).component else fallback(project, toolWindow.disposable)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(content, "", false))
    }

    /** Without the IDE's built-in browser: the numbers and the buttons, in plain Swing. */
    private fun fallback(project: Project, parent: Disposable): JComponent {
        val label = JBLabel().apply { border = JBUI.Borders.empty(16); verticalAlignment = JBLabel.TOP }
        fun refresh() {
            val s = AuraService.instance.aura
            val (rank, next, _) = rankFor(s.total)
            label.text = "<html><h1>${fmt(s.total)} aura</h1><h2>${rank.name}</h2><p>${rank.vibe}</p>" +
                (next?.let { "<p>${fmt(it.min - s.total)} aura to ${it.name}</p>" } ?: "") +
                "<p>Streak: ${liveStreak(s, AuraService.instance.now())} days</p><br>" +
                s.events.takeLast(15).reversed().joinToString("<br>") { "${signed(it.aura)} · ${it.text}" } + "</html>"
        }
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(AuraListener.TOPIC, AuraListener { ApplicationManager.getApplication().invokeLater { refresh() } })
        refresh()
        return JPanel(BorderLayout()).apply {
            add(label, BorderLayout.CENTER)
            add(JButton("Copy Aura Caption").apply { addActionListener { AuraService.instance.copyCaption() } }, BorderLayout.SOUTH)
        }
    }

    companion object {
        private val browsers = HashMap<Project, AuraBrowser>()

        fun reveal(project: Project, card: Boolean = false) {
            val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW) ?: return
            window.activate { if (card) browsers[project]?.makeCard() ?: notify(project, "Open the Aura tool window to make your aura card.") }
        }
        internal fun register(project: Project, browser: AuraBrowser?) { if (browser == null) browsers.remove(project) else browsers[project] = browser }
        /** For tests. */
        fun browser(project: Project): AuraBrowser? = browsers[project]
    }
}

/** The Aura panel (the same page as Veyra Aura for VS Code) in the IDE's built-in browser. */
class AuraBrowser(private val project: Project, parent: Disposable) : Disposable {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    @Volatile private var ready = false
    @Volatile private var pendingCard = false
    /** For tests: the total the page last said it rendered. */
    @Volatile var renderedTotal: Int? = null
        private set
    val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, this)
        Disposer.register(this, browser)
        Disposer.register(this, query)
        query.addHandler { message -> handle(message); null }
        val css = resource("/aura/panel.css")
        val script = resource("/aura/panel.js")
        val font = UIUtil.getLabelFont().family
        val bridge = "window.acquireVsCodeApi=function(){return{postMessage:function(m){var request=JSON.stringify(m);${query.inject("request")}}}};"
        browser.loadHTML("""<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Your Aura</title>
            <style>body{--vscode-font-family:"$font",system-ui,sans-serif;--vscode-editor-font-family:"JetBrains Mono",Consolas,monospace}</style>
            <style>$css</style></head><body><main id="app"></main><script>$bridge</script><script>$script</script></body></html>""")
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(AuraListener.TOPIC, AuraListener { push() })
        AuraToolWindowFactory.register(project, this)
    }

    private fun resource(path: String) = AuraService::class.java.getResourceAsStream(path)!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    private fun handle(message: String) {
        when (Regex(""""type"\s*:\s*"(\w+)"""").find(message)?.groupValues?.get(1)) {
            "ready" -> { ready = true; push(); if (pendingCard) { pendingCard = false; makeCard() } }
            "rendered" -> renderedTotal = Regex(""""total"\s*:\s*(-?\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()
            "caption" -> { AuraService.instance.copyCaption(); notify(project, "Caption copied. Paste it with your aura card.") }
            "card" -> Regex("""data:image/png;base64,([A-Za-z0-9+/=]+)""").find(message)?.groupValues?.get(1)?.let { saveCard(Base64.getDecoder().decode(it)) }
        }
    }

    private fun run(js: String) = browser.cefBrowser.executeJavaScript(js, browser.cefBrowser.url, 0)
    fun push() { if (ready) run("window.postMessage({type:'state',data:${AuraService.instance.snapshotJson()}},'*')") }
    fun makeCard() { if (ready) run("window.postMessage({type:'makeCard'},'*')") else pendingCard = true }

    private fun saveCard(png: ByteArray) {
        val service = AuraService.instance
        service.cardTarget?.let { Files.write(it, png); service.copyCaption(); return }
        ApplicationManager.getApplication().invokeLater {
            val dialog = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Save Your Aura Card", "A 1200 × 630 image to share", "png"), project)
            val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            val target = dialog.save(base, "my-aura-card.png") ?: return@invokeLater
            Files.write(target.file.toPath(), png)
            service.copyCaption()
            val n = NotificationGroupManager.getInstance().getNotificationGroup(AuraService.GROUP)
                .createNotification("Aura card saved", "The caption is copied too. Post it!", NotificationType.INFORMATION)
            n.addAction(NotificationAction.createSimpleExpiring("Open Card") {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target.file.toPath())?.let { FileEditorManager.getInstance(project).openFile(it, true) }
            })
            n.notify(project)
        }
    }

    override fun dispose() { AuraToolWindowFactory.register(project, null) }
}

fun notify(project: Project?, text: String) =
    NotificationGroupManager.getInstance().getNotificationGroup(AuraService.GROUP).createNotification(text, NotificationType.INFORMATION).notify(project)

// ------------------------------------------------------------------------------------------------ actions

class ShowAuraAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { AuraToolWindowFactory.reveal(it) } }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class ShareAuraCardAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) { e.project?.let { AuraToolWindowFactory.reveal(it, card = true) } }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class CopyAuraCaptionAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) { AuraService.instance.copyCaption(); notify(e.project, "Aura caption copied.") }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

class ResetAuraAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val ok = Messages.showYesNoDialog(e.project, "Reset your aura to 0? Your rank, streak and history go too.", "Reset My Aura", "Reset", "Cancel", null)
        if (ok == Messages.YES) AuraService.instance.reset()
    }
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}

// ------------------------------------------------------------------------------------------------ settings

class AuraConfigurable : BoundConfigurable("Aura Veyra") {
    private val s get() = AuraService.instance.settings
    private val notificationChoices = listOf("rankUps", "all", "off")
    private val notificationLabels = mapOf("rankUps" to "When I rank up", "all" to "Rank ups and every move worth 10+ aura", "off" to "Never (status bar only)")

    override fun createPanel() = panel {
        row { checkBox("Earn aura while I code").bindSelected({ s.enabled }, { s.enabled = it }) }
        row { checkBox("Roast mode: lose a little aura for \"wip\" commit messages, failing tests and builds, and leftover debugger statements").bindSelected({ s.roast }, { s.roast = it }) }
        row { checkBox("Show my aura in the status bar").bindSelected({ s.statusBar }, { s.statusBar = it }) }
        row("Notifications:") {
            comboBox(notificationChoices, com.intellij.ui.SimpleListCellRenderer.create("") { notificationLabels[it] ?: it })
                .bindItem({ s.notifications }, { s.notifications = it ?: "rankUps" })
        }
        row { comment("Everything stays on your computer: Aura only counts what the IDE already sees, and stores it with your IDE settings.") }
    }

    override fun apply() {
        super.apply()
        AuraService.instance.changed()
    }
}
