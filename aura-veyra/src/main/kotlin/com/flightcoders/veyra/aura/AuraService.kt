// Your aura: stored with the IDE's settings (aura-veyra.xml), shared by every project and window.
package com.flightcoders.veyra.aura

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.util.messages.Topic
import java.awt.datatransfer.StringSelection
import java.util.Base64

/** Told whenever your aura changes. */
fun interface AuraListener {
    fun auraChanged()
    companion object { @JvmField val TOPIC = Topic.create("Aura Veyra changed", AuraListener::class.java) }
}

@Service(Service.Level.APP)
@State(name = "AuraVeyra", storages = [Storage("aura-veyra.xml")])
class AuraService : PersistentStateComponent<AuraService.Stored>, Disposable {
    /** Settings and aura, as the IDE saves them. Events are tab-separated: time, move, aura, text (Base64). */
    class Stored {
        var enabled = true
        var roast = true
        /** "rankUps", "all" or "off". */
        var notifications = "rankUps"
        var statusBar = true
        var total = 0
        var streak = 0
        var bestStreak = 0
        var lastDay = ""
        var activeDay = ""
        var activeMinutes = 0
        var days: MutableMap<String, Int> = HashMap()
        var counts: MutableMap<String, Int> = HashMap()
        var events: MutableList<String> = ArrayList()
    }

    private var stored = Stored()
    override fun getState() = stored
    override fun loadState(state: Stored) { stored = state }
    val settings: Stored get() = stored

    /** For tests: the clock, and how long errors must exist before clearing them counts. */
    var now: () -> Long = { System.currentTimeMillis() }
    var errorAgeMs = 10_000L
    /** For tests: where Share My Aura Card writes the image instead of asking. */
    var cardTarget: java.nio.file.Path? = null

    /** The last move, shown in the status bar for a few seconds. */
    @Volatile var flash: Pair<String, Long>? = null
        private set

    var aura: AuraState
        get() = AuraState(
            total = stored.total, days = stored.days.toMap(), streak = stored.streak, bestStreak = stored.bestStreak, lastDay = stored.lastDay,
            counts = stored.counts.toMap(), activeDay = stored.activeDay, activeMinutes = stored.activeMinutes,
            events = stored.events.mapNotNull(::decode),
        )
        private set(s) {
            stored.total = s.total; stored.streak = s.streak; stored.bestStreak = s.bestStreak; stored.lastDay = s.lastDay
            stored.activeDay = s.activeDay; stored.activeMinutes = s.activeMinutes
            stored.days = HashMap(s.days); stored.counts = HashMap(s.counts)
            stored.events = s.events.map(::encode).toMutableList()
        }

    init {
        // Typing, in any editor: active minutes, locked-in sessions and late nights.
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.isInLocalFileSystem) onEdit()
            }
        }, this)
    }

    // ------------------------------------------------------------------------------------------------ moves

    /** Gives (or takes) the aura for a move. Returns what happened (nothing when a daily limit is reached). */
    @Synchronized
    fun give(id: MoveId, vars: Map<String, Any> = emptyMap(), key: String? = null): List<AuraEvent> {
        if (!stored.enabled) return emptyList()
        val r = award(aura, id, now(), vars, roast = stored.roast, key = key)
        if (r.events.isEmpty()) return emptyList()
        aura = r.state
        val total = r.events.sumOf { it.aura }
        flash = "${signed(total)} aura · ${r.events.last().text}" to now() + 3500
        changed()
        if (stored.notifications == "all") r.events.filter { kotlin.math.abs(it.aura) >= 10 }.forEach { notify("${signed(it.aura)} aura · ${it.text}") }
        r.rankUp?.let { rankUp(it) }
        return r.events
    }

    private var lastMinute = -1L
    @Synchronized
    fun onEdit() {
        if (!stored.enabled) return
        val minute = now() / 60_000
        if (minute == lastMinute) return
        lastMinute = minute
        val (next, locked) = activeMinute(aura, now())
        aura = next
        if (locked) give(MoveId.LOCKED_IN)
        if (isNight(now())) give(MoveId.NIGHT_OWL)
    }

    // Errors per file: when a file's errors have all gone (after being there a while), that's a clear.
    private val errorsSince = HashMap<String, Long>()
    @Synchronized fun problemsAppeared(path: String) { errorsSince.putIfAbsent(path, now()) }
    @Synchronized fun problemsDisappeared(path: String, name: String, open: Boolean) {
        val since = errorsSince.remove(path) ?: return
        if (open && now() - since >= errorAgeMs) give(MoveId.CLEARED_ERRORS, mapOf("file" to name))
    }

    /** A file is being saved: [before] is its line count on disk, [text] what's being written. */
    fun onSave(path: String, name: String, extension: String?, text: String, before: Int?, hasErrors: Boolean) {
        val after = text.lines().size
        if (before != null && before - after >= 50) give(MoveId.DELETED_LINES, mapOf("n" to before - after, "file" to name))
        if (extension?.lowercase() in setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte") && hasDebugger(text)) give(MoveId.DEBUGGER_LEFT, mapOf("file" to name), key = path)
        else if (!hasErrors) give(MoveId.CLEAN_SAVE)
    }

    fun onCommit(message: String) {
        give(MoveId.COMMIT)
        when (judgeCommitMessage(message)) {
            Verdict.GOOD -> give(MoveId.GOOD_COMMIT_MESSAGE)
            Verdict.BAD -> give(MoveId.BAD_COMMIT_MESSAGE, mapOf("msg" to message.trim().lineSequence().first().take(30)))
            Verdict.OK -> Unit
        }
    }

    fun onTests(passed: Boolean) { give(if (passed) MoveId.TESTS_PASSED else MoveId.TESTS_FAILED) }
    fun onBuild(passed: Boolean) { give(if (passed) MoveId.BUILD_PASSED else MoveId.BUILD_FAILED) }

    @Synchronized
    fun reset() { aura = AuraState(); flash = null; changed() }

    fun changed() = ApplicationManager.getApplication().messageBus.syncPublisher(AuraListener.TOPIC).auraChanged()

    // ------------------------------------------------------------------------------------------------ sharing

    fun caption(): String = caption(aura, now())
    fun copyCaption() = CopyPasteManager.getInstance().setContents(StringSelection(caption()))

    /** Everything the Aura panel shows, as JSON for its script. */
    fun snapshotJson(): String {
        val s = aura; val t = now()
        val (rank, next, progress) = rankFor(s.total)
        fun r(x: Rank) = """{"name":${js(x.name)},"emoji":${js(x.emoji)},"min":${if (x.min == Int.MIN_VALUE) -1 else x.min},"vibe":${js(x.vibe)}}"""
        val week = lastWeek(s, t).joinToString(",") { """{"key":${js(it.key)},"day":${js(it.day)},"aura":${it.aura}}""" }
        val top = topMoves(s, t).joinToString(",") { """{"emoji":${js(it.emoji)},"label":${js(it.label)},"aura":${it.aura},"times":${it.times}}""" }
        val events = s.events.takeLast(40).reversed().joinToString(",") { """{"t":${it.t},"id":${js(it.id.name)},"aura":${it.aura},"text":${js(it.text)},"emoji":${js(it.emoji)},"ago":${js(ago(it.t, t))}}""" }
        return """{"total":${s.total},"totalText":${js(fmt(s.total))},"rank":${r(rank)},"next":${next?.let(::r) ?: "null"},"progress":$progress,""" +
            """"ranks":[${RANKS.joinToString(",") { r(it) }}],"streak":${liveStreak(s, t)},"bestStreak":${s.bestStreak},"today":${s.days[dayKey(t)] ?: 0},""" +
            """"week":[$week],"top":[$top],"events":[$events],"caption":${js(caption(s, t))},"roast":${stored.roast}}"""
    }

    private fun rankUp(rank: Rank) {
        if (stored.notifications == "off") return
        val n = NotificationGroupManager.getInstance().getNotificationGroup(GROUP)
            .createNotification("Rank up: ${rank.name}", "${rank.vibe} You have ${fmt(aura.total)} aura.", NotificationType.INFORMATION)
        n.addAction(NotificationAction.createSimpleExpiring("Share Aura Card") { activeProject()?.let { com.flightcoders.veyra.aura.ui.AuraToolWindowFactory.reveal(it, card = true) } })
        n.addAction(NotificationAction.createSimpleExpiring("Open Aura") { activeProject()?.let { com.flightcoders.veyra.aura.ui.AuraToolWindowFactory.reveal(it) } })
        n.notify(activeProject())
    }

    private fun notify(text: String) =
        NotificationGroupManager.getInstance().getNotificationGroup(GROUP).createNotification(text, NotificationType.INFORMATION).notify(activeProject())

    override fun dispose() {}

    companion object {
        const val GROUP = "Aura Veyra"
        val instance: AuraService get() = service()
        fun activeProject(): Project? = ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed }

        // The text is Base64: the IDE's XML settings can't keep characters like emoji outside the basic range.
        // The emoji comes from the move.
        private fun encode(e: AuraEvent) =
            listOf(e.t, e.id.name, e.aura, Base64.getEncoder().encodeToString(e.text.toByteArray(Charsets.UTF_8))).joinToString("\t")
        private fun decode(line: String): AuraEvent? {
            val p = line.split('\t')
            if (p.size < 4) return null
            val id = runCatching { MoveId.valueOf(p[1]) }.getOrNull() ?: return null
            val text = runCatching { String(Base64.getDecoder().decode(p[3]), Charsets.UTF_8) }.getOrNull() ?: return null
            return AuraEvent(p[0].toLongOrNull() ?: return null, id, p[2].toIntOrNull() ?: return null, text, MOVES.getValue(id).emoji)
        }
    }
}

/** A JSON string literal. */
fun js(s: String): String = buildString {
    append('"')
    for (c in s) when {
        c == '"' -> append("\\\""); c == '\\' -> append("\\\\"); c == '\n' -> append("\\n"); c == '\r' -> append("\\r"); c == '\t' -> append("\\t")
        c == '<' -> append("\\u003c"); c < ' ' || c == ' ' || c == ' ' -> append(String.format("\\u%04x", c.code))
        else -> append(c)
    }
    append('"')
}
