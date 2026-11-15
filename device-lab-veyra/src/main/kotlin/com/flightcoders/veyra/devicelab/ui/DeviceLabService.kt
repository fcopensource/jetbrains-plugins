// Device Lab Veyra: one per project. Knows adb, the connected devices, the chosen device and app, and what each
// device looked like before Device Lab changed it, so Reset can put it back. All adb calls run off the UI thread,
// one at a time, in order.
package com.flightcoders.veyra.devicelab.ui

import com.flightcoders.veyra.devicelab.AdbProcess
import com.flightcoders.veyra.devicelab.Device
import com.flightcoders.veyra.devicelab.DeviceControl
import com.flightcoders.veyra.devicelab.DeviceState
import com.flightcoders.veyra.devicelab.applicationIds
import com.flightcoders.veyra.devicelab.deepLinks
import com.flightcoders.veyra.devicelab.findAdb
import com.flightcoders.veyra.devicelab.parseDevices
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

private const val ADB_KEY = "veyra.deviceLab.adbPath"
private const val APP_KEY = "veyra.deviceLab.app"
private const val DEVICE_KEY = "veyra.deviceLab.device"

/** What the panel shows. */
data class LabSnapshot(
    val adbPath: String?,
    val devices: List<Device>,
    val selected: Device?,
    val state: DeviceState?,
    val changed: Boolean,
)

@Service(Service.Level.PROJECT)
class DeviceLabService(private val project: Project) : Disposable {
    private val queue = AppExecutorUtil.createBoundedApplicationPoolExecutor("Device Lab Veyra", 1)
    private val listeners = mutableListOf<(LabSnapshot) -> Unit>()
    private val baselines = mutableMapOf<String, DeviceState>()
    private var poll: ScheduledFuture<*>? = null

    @Volatile var snapshot = LabSnapshot(null, emptyList(), null, null, false); private set

    var app: String
        get() = PropertiesComponent.getInstance(project).getValue(APP_KEY) ?: detectedApps().firstOrNull().orEmpty()
        set(value) = PropertiesComponent.getInstance(project).setValue(APP_KEY, value.trim())

    var chosenAdb: String?
        get() = PropertiesComponent.getInstance().getValue(ADB_KEY)
        set(value) { PropertiesComponent.getInstance().setValue(ADB_KEY, value); refresh() }

    fun detectedApps(): List<String> = project.basePath?.let { runCatching { applicationIds(File(it)) }.getOrNull() }.orEmpty()
    fun detectedLinks() = project.basePath?.let { runCatching { deepLinks(File(it)) }.getOrNull() }.orEmpty()

    fun control(): DeviceControl? {
        val adb = snapshot.adbPath ?: return null
        val device = snapshot.selected?.takeIf { it.online } ?: return null
        return DeviceControl(AdbProcess(adb), device.serial)
    }

    /** Listens while [parent] lives; the device list is checked every few seconds while anyone listens. */
    fun subscribe(parent: Disposable, listener: (LabSnapshot) -> Unit) {
        synchronized(listeners) { listeners += listener }
        Disposer.register(parent) {
            synchronized(listeners) { listeners -= listener; if (listeners.isEmpty()) { poll?.cancel(false); poll = null } }
        }
        listener(snapshot)
        if (poll == null) poll = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay({ queue.execute { scan(false) } }, 0, 3, TimeUnit.SECONDS)
    }

    fun refresh() = queue.execute { scan(true) }

    fun select(serial: String) {
        PropertiesComponent.getInstance(project).setValue(DEVICE_KEY, serial)
        queue.execute { scan(true) }
    }

    /**
     * Runs [change] on the selected device, then reads its settings again. The first change on a device remembers
     * its settings, for Reset. [done] gets an error message or null, on the UI thread.
     */
    fun change(what: String, done: (String?) -> Unit = {}, change: (DeviceControl, DeviceState) -> Unit) = queue.execute {
        val control = control()
        val error = if (control == null) "Connect a phone or start an emulator first" else runCatching {
            val state = snapshot.state ?: control.readState()
            baselines.putIfAbsent(control.serial, state)
            change(control, state)
        }.exceptionOrNull()?.let { "$what failed: ${it.message}" }
        scan(true)
        ApplicationManager.getApplication().invokeLater({ done(error) }, project.disposed)
    }

    /** Runs a read or an app action on the device queue; [done] gets the result on the UI thread. */
    fun <T> run(work: (DeviceControl) -> T, done: (Result<T>) -> Unit) = queue.execute {
        val control = control()
        val result = if (control == null) Result.failure(IllegalStateException("Connect a phone or start an emulator first")) else runCatching { work(control) }
        ApplicationManager.getApplication().invokeLater({ done(result) }, project.disposed)
    }

    /** Puts the selected device back the way it was before Device Lab first changed it. */
    fun reset(done: (String?) -> Unit) {
        val serial = snapshot.selected?.serial
        val before = serial?.let { baselines[it] }
        if (before == null) { done(null); return }
        change("Reset", { error -> if (error == null) baselines.remove(serial); done(error) }) { control, _ -> control.restore(before) }
    }

    private fun scan(readState: Boolean) {
        val adb = findAdb(chosenAdb, project.basePath)
        val devices = adb?.let { runCatching { parseDevices(AdbProcess(it).run(listOf("devices", "-l"), 10_000).text) }.getOrNull() }.orEmpty()
        val wanted = PropertiesComponent.getInstance(project).getValue(DEVICE_KEY)
        val selected = devices.firstOrNull { it.serial == wanted } ?: devices.firstOrNull { it.online } ?: devices.firstOrNull()
        val sameDevice = selected?.serial == snapshot.selected?.serial && selected?.online == snapshot.selected?.online
        val state = when {
            selected == null || !selected.online || adb == null -> null
            readState || !sameDevice || snapshot.state == null -> runCatching { DeviceControl(AdbProcess(adb), selected.serial).readState() }.getOrNull()
            else -> snapshot.state
        }
        val next = LabSnapshot(adb, devices, selected, state, selected?.serial?.let { it in baselines } == true)
        if (next == snapshot) return
        snapshot = next
        val copy = synchronized(listeners) { listeners.toList() }
        ApplicationManager.getApplication().invokeLater({ copy.forEach { it(next) } }, project.disposed)
    }

    override fun dispose() { poll?.cancel(false); queue.shutdownNow() }

    companion object { fun get(project: Project): DeviceLabService = project.service() }
}
