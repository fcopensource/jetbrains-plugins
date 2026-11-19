// Reads the project's git history in the background, keeps the latest analysis, and tells the UI when it changes.
package com.flightcoders.veyra.hotspots

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.messages.Topic
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun interface HotspotsListener {
    fun changed()
    companion object { @JvmField val TOPIC = Topic.create("Hotspots Veyra", HotspotsListener::class.java) }
}

enum class Status { IDLE, SCANNING, READY, NO_GIT, FAILED }

@Service(Service.Level.PROJECT)
class HotspotsService(private val project: Project) : Disposable {
    @Volatile var analysis: Analysis? = null; private set
    @Volatile var status = Status.IDLE; private set
    @Volatile var months = 12; private set
    @Volatile var error: String? = null; private set
    private val generation = AtomicInteger()
    private var lastHead: String? = null
    private var poll: ScheduledFuture<*>? = null

    val root: File? get() = project.guessProjectDir()?.takeIf { it.isInLocalFileSystem }?.let { File(it.path) }

    fun start() {
        refresh()
        // A commit, pull or checkout moves HEAD: check now and then, and refresh when it does.
        poll = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({
            if (project.isDisposed || status == Status.SCANNING) return@scheduleWithFixedDelay
            val dir = root ?: return@scheduleWithFixedDelay
            val head = runCatching { git(dir, listOf("rev-parse", "HEAD"), 10_000).trim() }.getOrNull()
            if (head != null && lastHead != null && head != lastHead) ApplicationManager.getApplication().invokeLater { refresh() }
        }, 60, 60, TimeUnit.SECONDS)
    }

    fun refresh() {
        val dir = root ?: run { status = Status.NO_GIT; publish(); return }
        val gen = generation.incrementAndGet()
        val settings = HotspotsSettings.of(project).state
        val months = settings.months
        val maxCommits = settings.maxCommits
        val globs = HotspotsSettings.of(project).excludeGlobs
        status = Status.SCANNING; publish()
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Reading git history", true) {
            override fun run(indicator: ProgressIndicator) {
                val result = runCatching { read(dir, months, maxCommits, globs, indicator) }
                ApplicationManager.getApplication().invokeLater {
                    if (gen != generation.get() || project.isDisposed) return@invokeLater
                    result.onSuccess { r ->
                        if (r == null) { status = Status.NO_GIT } else { analysis = r.first; lastHead = r.second; this@HotspotsService.months = months; status = Status.READY; error = null }
                    }.onFailure { e -> if (e !is com.intellij.openapi.progress.ProcessCanceledException) { LOG.warn(e); status = Status.FAILED; error = e.message } }
                    publish()
                }
            }
        })
    }

    private fun read(dir: File, months: Int, maxCommits: Int, globs: List<Regex>, indicator: ProgressIndicator): Pair<Analysis, String?>? {
        val inside = runCatching { git(dir, listOf("rev-parse", "--is-inside-work-tree"), 15_000).trim() == "true" }.getOrDefault(false)
        if (!inside) return null
        val now = System.currentTimeMillis()
        val since = if (months > 0) now - (months * 30.4375 * DAY).toLong() else null

        indicator.isIndeterminate = false
        indicator.text = "Measuring files"
        val tracked = git(dir, listOf("ls-files", "-z"), 60_000).split('\u0000').filter { it.isNotEmpty() && !isExcluded(it, globs) }
        val shapes = HashMap<String, Shape>()
        for ((i, rel) in tracked.withIndex()) {
            indicator.checkCanceled()
            if (i % 200 == 0) indicator.fraction = i.toDouble() / tracked.size / 2
            val f = File(dir, rel)
            if (!f.isFile || f.length() > 512 * 1024) continue
            val bytes = runCatching { Files.readAllBytes(f.toPath()) }.getOrNull() ?: continue
            if ((0 until minOf(bytes.size, 8000)).any { bytes[it] == 0.toByte() }) continue // binary
            shapes[rel] = measure(String(bytes, StandardCharsets.UTF_8))
        }

        indicator.text = "Reading git history"; indicator.fraction = 0.6
        val args = mutableListOf("log", "--no-merges", "--no-renames", "--numstat", "--format=$GIT_LOG_FORMAT", "--relative", "-n$maxCommits")
        if (since != null) args += "--since=${java.time.Instant.ofEpochMilli(since)}"
        val commits = parseGitLog(runCatching { git(dir, args + listOf("--", "."), 300_000) }.getOrDefault(""))
        // When each author last committed at all, including before the period, to know who is still around.
        val lastSeen = HashMap<String, Long>()
        if (since != null) runCatching { git(dir, listOf("log", "--no-merges", "--format=%aN%x1f%aE%x1f%at", "-n$maxCommits", "--", "."), 300_000) }.getOrNull()
            ?.lineSequence()?.forEach { line -> val p = line.split('\u001f'); if (p.size == 3) p[2].trim().toLongOrNull()?.let { t -> lastSeen.merge(identity(p[0], p[1]), t * 1000, ::maxOf) } }
        indicator.fraction = 0.9
        val head = runCatching { git(dir, listOf("rev-parse", "HEAD"), 10_000).trim() }.getOrNull()
        return analyze(commits, shapes, Options(now, since), lastSeen) to head
    }

    /** The analysis entry for a file in this project, if it has one. */
    fun statFor(file: VirtualFile): FileStat? {
        val dir = root ?: return null
        val path = file.path
        val base = dir.path.replace('\\', '/').trimEnd('/')
        if (!path.startsWith("$base/", ignoreCase = File.separatorChar == '\\')) return null
        return analysis?.byPath?.get(path.substring(base.length + 1))
    }

    fun fileFor(rel: String): VirtualFile? = root?.let { LocalFileSystem.getInstance().findFileByIoFile(File(it, rel)) }

    /** Listeners update Swing, so they are always told on the UI thread. */
    private fun publish() {
        val app = ApplicationManager.getApplication()
        val send = { if (!project.isDisposed) project.messageBus.syncPublisher(HotspotsListener.TOPIC).changed() }
        if (app.isDispatchThread) send() else app.invokeLater(send)
    }

    override fun dispose() { poll?.cancel(false) }

    companion object {
        private val LOG = logger<HotspotsService>()
        fun of(project: Project): HotspotsService = project.service()

        fun git(dir: File, args: List<String>, timeoutMs: Int): String {
            val cmd = GeneralCommandLine(listOf("git") + args).withWorkDirectory(dir).withCharset(StandardCharsets.UTF_8)
                .withEnvironment("GIT_TERMINAL_PROMPT", "0").withEnvironment("LC_ALL", "C")
            val out = CapturingProcessHandler(cmd).runProcess(timeoutMs)
            if (out.isTimeout) throw IllegalStateException("git ${args.firstOrNull()} timed out")
            if (out.exitCode != 0) throw IllegalStateException("git ${args.firstOrNull()} failed: ${out.stderr.take(300)}")
            return out.stdout
        }
    }
}
