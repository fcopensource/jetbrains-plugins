// Device Lab Veyra: finding adb, running it, and the list of connected phones and emulators.
// No IntelliJ APIs here, so all of it is unit-tested and runs against a real emulator in the tests.
package com.flightcoders.veyra.devicelab

import java.io.File
import java.util.concurrent.TimeUnit

/** What one adb call printed. [bytes] is the raw output (a screenshot, for example). */
class AdbResult(val exit: Int, val bytes: ByteArray, val error: String) {
    val text: String get() = String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
    val ok: Boolean get() = exit == 0
}

/** Runs adb. Tests replace it with a fake that records the commands. */
fun interface Adb {
    fun run(args: List<String>, timeoutMs: Long): AdbResult
}

class AdbProcess(val path: String) : Adb {
    override fun run(args: List<String>, timeoutMs: Long): AdbResult {
        val process = ProcessBuilder(listOf(path) + args).redirectErrorStream(false).start()
        process.outputStream.close()
        val error = StringBuilder()
        val errReader = Thread { runCatching { error.append(process.errorStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
        var bytes = ByteArray(0)
        val outReader = Thread { runCatching { bytes = process.inputStream.readBytes() } }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return AdbResult(-1, ByteArray(0), "adb didn't answer within ${timeoutMs / 1000} s")
        }
        outReader.join(2000); errReader.join(2000)
        return AdbResult(process.exitValue(), bytes, error.toString().trim())
    }
}

/**
 * Where adb is: the path chosen in settings, the project's local.properties (sdk.dir), ANDROID_HOME / ANDROID_SDK_ROOT,
 * Android Studio's default SDK folder for this OS, then the PATH.
 */
fun findAdb(
    chosen: String?,
    projectDir: String?,
    env: Map<String, String> = System.getenv(),
    home: String = System.getProperty("user.home"),
    windows: Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true),
    mac: Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true),
    exists: (File) -> Boolean = { it.isFile },
): String? {
    val exe = if (windows) "adb.exe" else "adb"
    val sdks = buildList {
        projectDir?.let { File(it, "local.properties") }?.takeIf { it.isFile }?.let { sdkDirFromLocalProperties(it.readText()) }?.let(::add)
        env["ANDROID_HOME"]?.let(::add)
        env["ANDROID_SDK_ROOT"]?.let(::add)
        when {
            windows -> env["LOCALAPPDATA"]?.let { add("$it\\Android\\Sdk") }
            mac -> add("$home/Library/Android/sdk")
            else -> add("$home/Android/Sdk")
        }
    }
    val candidates = buildList {
        chosen?.takeIf { it.isNotBlank() }?.let { add(File(it)) }
        sdks.forEach { add(File(File(it, "platform-tools"), exe)) }
        env["PATH"].orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.forEach { add(File(it, exe)) }
    }
    return candidates.firstOrNull(exists)?.path
}

/** sdk.dir from local.properties, which escapes ':' and '\' on Windows (C\:\\Users\\me\\Android\\Sdk). */
fun sdkDirFromLocalProperties(text: String): String? =
    text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("sdk.dir") && it.contains('=') }
        ?.substringAfter('=')?.trim()?.replace("\\:", ":")?.replace("\\\\", "\\")?.takeIf { it.isNotEmpty() }

data class Device(val serial: String, val state: String, val model: String?, val product: String?) {
    val online: Boolean get() = state == "device"
    val emulator: Boolean get() = serial.startsWith("emulator-") || product?.startsWith("sdk_") == true
    val label: String get() {
        val name = model?.replace('_', ' ')?.replace("x86 64", "x86_64") ?: serial
        val kind = if (emulator) "emulator" else "device"
        return when (state) {
            "device" -> "$name ($kind)"
            "unauthorized" -> "$name: allow USB debugging on the phone"
            "offline" -> "$name: offline"
            else -> "$name: $state"
        }
    }
}

/** `adb devices -l`. */
fun parseDevices(text: String): List<Device> = text.lineSequence()
    .map { it.trim() }
    .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith("*") }
    .mapNotNull { line ->
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 2) return@mapNotNull null
        val fields = parts.drop(2).filter { ':' in it }.associate { it.substringBefore(':') to it.substringAfter(':') }
        Device(parts[0], parts[1], fields["model"], fields["product"])
    }.toList()

/** Quotes a value for the device's shell (sh): 'it''s' style. */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
