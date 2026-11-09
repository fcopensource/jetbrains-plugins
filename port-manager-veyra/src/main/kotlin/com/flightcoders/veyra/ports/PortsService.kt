// Port Manager Veyra: one scanner for the whole IDE. It refreshes in the background every few seconds while any
// project window shows ports, and tells listeners (tool windows, status-bar widgets, notifications) what changed.
package com.flightcoders.veyra.ports

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Settings that persist across restarts. */
@Service(Service.Level.APP)
@State(name = "PortManagerVeyra", storages = [Storage("portManagerVeyra.xml")])
class PortsSettings : PersistentStateComponent<PortsSettings.State> {
    class State {
        var showSystem: Boolean = false
        var notifyOnStart: Boolean = true
        var refreshSeconds: Int = 5
        var hiddenPorts: MutableSet<Int> = mutableSetOf()
    }

    private var state = State()
    override fun getState(): State = state
    override fun loadState(state: State) { this.state = state }

    companion object { fun get(): PortsSettings = service() }
}

fun interface PortsListener {
    /** Called on the EDT after every scan. `diff` is empty for the first scan. */
    fun updated(snapshot: Snapshot, diff: Diff)
}

@Service(Service.Level.APP)
class PortsService : Disposable {
    private val scanner = Scanner()
    private val listeners = CopyOnWriteArrayList<PortsListener>()
    private val scanning = AtomicBoolean(false)
    private var task: ScheduledFuture<*>? = null

    @Volatile var snapshot: Snapshot? = null
        private set
    @Volatile var lastError: String? = null
        private set

    /** Registers a listener for as long as `parent` lives, and starts refreshing. */
    fun subscribe(parent: Disposable, listener: PortsListener) {
        listeners += listener
        Disposer.register(parent) {
            listeners -= listener
            if (listeners.isEmpty()) stop()
        }
        start()
        snapshot?.let { ApplicationManager.getApplication().invokeLater { listener.updated(it, Diff(emptyList(), emptyList())) } }
    }

    private fun start() = synchronized(this) {
        if (task != null) return
        val seconds = PortsSettings.get().state.refreshSeconds.coerceIn(2, 60).toLong()
        task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ refresh() }, 0, seconds, TimeUnit.SECONDS)
    }

    private fun stop() = synchronized(this) { task?.cancel(false); task = null }

    /** Scans now on a background thread (ignored if a scan is already running). */
    fun refreshNow(fresh: Boolean = false) {
        AppExecutorUtil.getAppExecutorService().execute { refresh(fresh) }
    }

    private fun options(): BuildOptions {
        val settings = PortsSettings.get().state
        val workspaces = ProjectManager.getInstance().openProjects.mapNotNull { it.basePath }
        return BuildOptions(
            workspaces = workspaces, includeSystem = settings.showSystem, hidden = settings.hiddenPorts.toSet(),
            selfPid = ProcessHandle.current().pid(),
        )
    }

    /** Synchronous scan for actions that must act on current data (Free a Port, Stop). Call off the EDT. */
    fun scanNow(): Snapshot = publish(scanner.scan(options(), fresh = true))

    private fun refresh(fresh: Boolean = false) {
        if (!scanning.compareAndSet(false, true)) return
        try {
            publish(scanner.scan(options(), fresh))
            lastError = null
        } catch (error: Exception) {
            lastError = error.message ?: error.toString()
            thisLogger().info("Port scan failed", error)
        } finally {
            scanning.set(false)
        }
    }

    private fun publish(next: Snapshot): Snapshot {
        val diff = diffSnapshots(snapshot, next)
        snapshot = next
        ApplicationManager.getApplication().invokeLater { listeners.forEach { it.updated(next, diff) } }
        return next
    }

    override fun dispose() = stop()

    companion object { fun get(): PortsService = service() }
}
