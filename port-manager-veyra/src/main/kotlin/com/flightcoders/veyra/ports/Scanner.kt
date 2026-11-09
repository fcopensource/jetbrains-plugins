// Port Manager Veyra: asks the operating system which programs listen on TCP ports, and stops them.
// Every command runs without a shell and with a timeout; each platform has a fallback.
//
// Speed: listing sockets is cheap (netstat ~0.1 s, lsof), but reading process details is not (PowerShell ~1 s).
// The Scanner keeps a cache of process details keyed by PID and only looks up processes it hasn't seen.
// PIDs that stop listening are evicted, so a reused PID is always looked up again.
package com.flightcoders.veyra.ports

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

enum class Platform { WINDOWS, MAC, LINUX;
    companion object {
        val current: Platform = System.getProperty("os.name").lowercase().let {
            when { it.startsWith("windows") -> WINDOWS; it.contains("mac") || it.contains("darwin") -> MAC; else -> LINUX }
        }
    }
}

class CommandFailed(message: String) : Exception(message)

/** Runs a program (no shell) and returns its output. `noMatchIsEmpty`: for lsof and ps, exit code 1 means
    "nothing matched" (for example, no port in use at all): an empty answer, not a failure. */
internal fun run(command: List<String>, timeoutSeconds: Long = 8, noMatchIsEmpty: Boolean = false): String {
    val process = ProcessBuilder(command).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    process.outputStream.close()
    val output = StringBuilder()
    val reader = Thread { output.append(process.inputStream.readBytes().toString(StandardCharsets.UTF_8)) }.apply { isDaemon = true; start() }
    if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        throw CommandFailed("${command.first()} did not answer within $timeoutSeconds s")
    }
    reader.join(2000)
    val code = process.exitValue()
    if (code != 0 && !(noMatchIsEmpty && code == 1)) throw CommandFailed("${command.first()} exited with $code")
    return output.toString()
}

class Scanner(private val platform: Platform = Platform.current) {
    /** Process details by PID, for the processes currently holding ports. */
    private val cache = HashMap<Long, ProcessInfo>()
    private val lock = Any()

    /** Everything listening right now. `fresh` ignores the cache (used before stopping a process). */
    fun scan(options: BuildOptions = BuildOptions(), fresh: Boolean = false): Snapshot = synchronized(lock) {
        val listeners: List<Listener>
        val known = HashMap<Long, ProcessInfo>()
        if (platform == Platform.WINDOWS) listeners = windowsListeners()
        else {
            val (found, processes) = unixListeners()
            listeners = found
            known.putAll(processes)
        }

        val pids = listeners.mapTo(hashSetOf()) { it.pid }
        cache.keys.retainAll(pids)
        val missing = pids.filter { it > 0 && (fresh || it !in cache) }
        if (missing.isNotEmpty()) {
            if (platform == Platform.WINDOWS) {
                val found = windowsProcesses(missing)
                for (pid in missing) cache[pid] = found[pid] ?: ProcessInfo(pid, "PID $pid", owned = false)
            } else {
                val details = unixDetails(missing)
                for (pid in missing) {
                    val base = known[pid] ?: ProcessInfo(pid, "PID $pid", owned = false)
                    val (command, cwd) = details[pid] ?: (null to null)
                    cache[pid] = base.copy(command = command ?: base.command, cwd = cwd ?: base.cwd)
                }
            }
        }
        // lsof's name and owner are always current; the cache adds the slower command line and folder.
        val processes = pids.mapNotNull { pid ->
            val cached = cache[pid]
            val live = known[pid]
            val info = if (live != null && cached != null) cached.copy(name = live.name, owned = live.owned) else cached ?: live
            info?.let { pid to it }
        }.toMap()
        return buildSnapshot(listeners, processes, options.copy(caseInsensitivePaths = platform != Platform.LINUX))
    }

    /* ---------- Listening sockets ---------- */

    /** Windows: netstat for both IP versions (fast, and parsed language-independently). */
    private fun windowsListeners(): List<Listener> =
        parseNetstat(run(listOf("netstat", "-ano", "-p", "TCP"))) +
            parseNetstat(runCatching { run(listOf("netstat", "-ano", "-p", "TCPv6")) }.getOrDefault(""))

    /** macOS/Linux: lsof (process name and owner included), or ss on Linux without lsof. */
    private fun unixListeners(): Pair<List<Listener>, Map<Long, ProcessInfo>> = try {
        parseLsof(run(listOf("lsof", "-nP", "-iTCP", "-sTCP:LISTEN", "-F", "pcLn"), noMatchIsEmpty = true), System.getProperty("user.name"))
    } catch (error: Exception) {
        if (platform != Platform.LINUX) throw error
        parseSs(run(listOf("ss", "-ltnpH")))
    }

    /* ---------- Process details ---------- */

    /** Windows: name, executable path and command line for the given PIDs, in one PowerShell call (tab-separated). */
    private fun windowsProcesses(pids: List<Long>): Map<Long, ProcessInfo> {
        val systemRoot = System.getenv("SystemRoot") ?: "C:\\Windows"
        val filter = pids.joinToString(" OR ") { "ProcessId=$it" }
        // Sent as -EncodedCommand (Base64 of UTF-16LE) so no quoting can be mangled on the way to PowerShell.
        val script = "\$ErrorActionPreference='SilentlyContinue'; [Console]::OutputEncoding=[Text.Encoding]::UTF8; " +
            "Get-CimInstance Win32_Process -Filter '$filter' | ForEach-Object { " +
            "([string]\$_.ProcessId) + [char]9 + \$_.Name + [char]9 + \$_.ExecutablePath + [char]9 + (\$_.CommandLine -replace '[\\t\\r\\n]',' ') }"
        val encoded = java.util.Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
        runCatching {
            val found = parseWindowsProcesses(run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded), 15), systemRoot)
            if (found.isNotEmpty()) return found
        }
        // PowerShell unavailable or blocked: names only (and nothing stoppable, since ownership is unknown).
        val names = parseTasklistCsv(runCatching { run(listOf("tasklist", "/fo", "csv", "/nh")) }.getOrDefault(""))
        return pids.mapNotNull { pid -> names[pid]?.let { pid to it } }.toMap()
    }

    /** macOS/Linux: full command lines and working directories for the given PIDs. */
    private fun unixDetails(pids: List<Long>): Map<Long, Pair<String?, String?>> {
        val list = pids.joinToString(",")
        val commands = runCatching { parsePs(run(listOf("ps", "-o", "pid=,args=", "-p", list), noMatchIsEmpty = true)) }.getOrDefault(emptyMap())
        val cwds: Map<Long, String> = if (platform == Platform.LINUX) {
            pids.mapNotNull { pid -> runCatching { File("/proc/$pid/cwd").toPath().toRealPath().toString() }.getOrNull()?.let { pid to it } }.toMap()
        } else {
            runCatching { parseLsofCwd(run(listOf("lsof", "-a", "-d", "cwd", "-Fn", "-p", list), noMatchIsEmpty = true)) }.getOrDefault(emptyMap())
        }
        return pids.associateWith { commands[it] to cwds[it] }
    }
}

/* ---------- Stopping ---------- */

fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

private fun until(timeoutMs: Long, done: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (done()) return true
        Thread.sleep(100)
    }
    return done()
}

/**
 * Stops a process (and on Windows, the processes it started). macOS/Linux ask nicely first (SIGTERM); `force`
 * sends SIGKILL. Windows console programs can't be asked to close, so they are always ended.
 * Returns whether the process is gone. Refuses system PIDs and the IDE itself.
 */
fun stopProcess(pid: Long, force: Boolean = false, platform: Platform = Platform.current): Boolean {
    require(pid > 4 && pid != ProcessHandle.current().pid()) { "That process can't be stopped." }
    if (platform == Platform.WINDOWS) {
        try { run(listOf("taskkill", "/PID", pid.toString(), "/T", "/F")) } catch (error: CommandFailed) { if (isAlive(pid)) throw error }
    } else {
        ProcessHandle.of(pid).ifPresent { if (force) it.destroyForcibly() else it.destroy() }
    }
    return until(if (force || platform == Platform.WINDOWS) 3000 else 4000) { !isAlive(pid) }
}
