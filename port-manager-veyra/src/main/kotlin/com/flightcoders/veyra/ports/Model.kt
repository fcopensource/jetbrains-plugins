// Port Manager Veyra: the data model. Pure functions with no IDE or operating-system calls, so every rule here is
// unit-tested with real command output (ModelTest).
//
//   raw sockets ─┐
//                ├─ buildSnapshot ─▶ Snapshot { entries (sorted), byPort, byPid }
//   processes  ──┘                         │
//                                   diffSnapshots(previous, next) ─▶ Diff { added, removed }
package com.flightcoders.veyra.ports

/** One listening TCP socket as the operating system reports it. */
data class Listener(val port: Int, val address: String, val pid: Long)

/** What we know about the program behind a socket. */
data class ProcessInfo(
    val pid: Long,
    val name: String,
    /** Full command line, when the system lets us read it. */
    val command: String? = null,
    /** Executable path (Windows), when readable. */
    val path: String? = null,
    /** Working directory (macOS/Linux), when readable. */
    val cwd: String? = null,
    /** Started by the current user (not a system service): only these can be stopped. */
    val owned: Boolean,
)

enum class FrameworkKind { WEB, DATABASE, RUNTIME, TOOL, EDITOR }

data class Framework(val id: String, val label: String, val kind: FrameworkKind)

/** One row in the Ports tool window: a program listening on a port (all its addresses merged). */
data class PortEntry(
    /** Stable identity across scans: the same process on the same port. */
    val key: String,
    val port: Int,
    /** Every address this port is bound to, e.g. ["0.0.0.0", "::"]. */
    val addresses: List<String>,
    val pid: Long,
    val process: ProcessInfo,
    val framework: Framework?,
    /** Reachable only from this computer (every address is loopback). */
    val localOnly: Boolean,
    /** A system service, another user's program or an IDE/editor (stopping it would close the editor): never stoppable. */
    val protected: Boolean,
    /** The project folder this program runs from, when we can tell. */
    val workspace: String?,
)

data class Snapshot(
    /** Sorted by port, then process ID. */
    val entries: List<PortEntry>,
    val byPort: Map<Int, List<PortEntry>>,
    val byPid: Map<Long, List<PortEntry>>,
    val takenAt: Long,
)

data class Diff(val added: List<PortEntry>, val removed: List<PortEntry>)

fun entryKey(pid: Long, port: Int) = "$pid:$port"

/* ---------- Frameworks ---------- */

private class Rule(val id: String, val label: String, val kind: FrameworkKind, pattern: String) {
    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
}

/** Recognised programs, most specific first: the first rule that matches "name + command line" wins.
    Editors and IDEs are matched on the program name alone, so a server started *from* an IDE is not mistaken for it. */
private val RULES = listOf(
    // JetBrains IDEs hold ports themselves (built-in web server, debuggers): stopping one would close the IDE.
    Rule("intellij", "IntelliJ IDEA", FrameworkKind.EDITOR, """^(idea(64)?|idea\.sh|intellij idea[^ ]*)(\.exe)?(\s|$)"""),
    Rule("webstorm", "WebStorm", FrameworkKind.EDITOR, """^webstorm(64)?(\.exe|\.sh)?(\s|$)"""),
    Rule("pycharm", "PyCharm", FrameworkKind.EDITOR, """^pycharm(64)?(\.exe|\.sh)?(\s|$)"""),
    Rule("phpstorm", "PhpStorm", FrameworkKind.EDITOR, """^phpstorm(64)?(\.exe|\.sh)?(\s|$)"""),
    Rule("goland", "GoLand", FrameworkKind.EDITOR, """^goland(64)?(\.exe|\.sh)?(\s|$)"""),
    Rule("rider", "Rider", FrameworkKind.EDITOR, """^rider(64)?(\.exe|\.sh)?(\s|$)"""),
    Rule("clion", "CLion", FrameworkKind.EDITOR, """^clion(64)?(\.exe|\.sh)?(\s|$)"""),
    Rule("rubymine", "RubyMine", FrameworkKind.EDITOR, """^rubymine(64)?(\.exe|\.sh)?(\s|$)"""),
    Rule("rustrover", "RustRover", FrameworkKind.EDITOR, """^rustrover(64)?(\.exe|\.sh)?(\s|$)"""),
    Rule("datagrip", "DataGrip", FrameworkKind.EDITOR, """^datagrip(64)?(\.exe|\.sh)?(\s|$)"""),
    Rule("androidstudio", "Android Studio", FrameworkKind.EDITOR, """^(studio(64)?|android studio)(\.exe|\.sh)?(\s|$)"""),
    Rule("fleet", "Fleet", FrameworkKind.EDITOR, """^fleet(\.exe)?(\s|$)"""),
    Rule("jbtoolbox", "JetBrains Toolbox", FrameworkKind.EDITOR, """^jetbrains[ -]toolbox(\.exe)?(\s|$)"""),
    Rule("vscode", "VS Code", FrameworkKind.EDITOR, """^(code( - insiders)?|code helper[^ ]*|codium|vscodium)(\.exe)?(\s|$)"""),
    Rule("cursor", "Cursor", FrameworkKind.EDITOR, """^cursor( helper[^ ]*)?(\.exe)?(\s|$)"""),
    Rule("windsurf", "Windsurf", FrameworkKind.EDITOR, """^windsurf(\.exe)?(\s|$)"""),
    Rule("veyra", "Veyra Studio", FrameworkKind.EDITOR, """^veyra( studio)?(\.exe)?(\s|$)"""),
    Rule("vite", "Vite", FrameworkKind.WEB, """[\\/]vite[\\/]bin|\bvite(\.js)?(\s|$)|vite(\s+(dev|preview|serve))"""),
    Rule("next", "Next.js", FrameworkKind.WEB, """next-server|[\\/]next[\\/]dist|\bnext\s+(dev|start)\b"""),
    Rule("nuxt", "Nuxt", FrameworkKind.WEB, """\bnux[ti](\.mjs)?\b"""),
    Rule("astro", "Astro", FrameworkKind.WEB, """[\\/]astro[\\/]|\bastro\s+(dev|preview)\b"""),
    Rule("sveltekit", "SvelteKit", FrameworkKind.WEB, """svelte-kit|sveltekit"""),
    Rule("remix", "Remix", FrameworkKind.WEB, """[\\/]@remix-run[\\/]|\bremix\s+(dev|vite)"""),
    Rule("angular", "Angular", FrameworkKind.WEB, """@angular[\\/]cli|\bng(\.js)?\s+serve\b"""),
    Rule("cra", "Create React App", FrameworkKind.WEB, """react-scripts"""),
    Rule("gatsby", "Gatsby", FrameworkKind.WEB, """\bgatsby\b"""),
    Rule("storybook", "Storybook", FrameworkKind.WEB, """storybook"""),
    Rule("expo", "Expo", FrameworkKind.WEB, """\bexpo\b"""),
    Rule("webpack", "webpack", FrameworkKind.WEB, """webpack(-dev-server|\s+serve)"""),
    Rule("parcel", "Parcel", FrameworkKind.WEB, """[\\/]parcel[\\/]|\bparcel\s"""),
    Rule("liveserver", "Live Server", FrameworkKind.WEB, """live-server|five-server"""),
    Rule("django", "Django", FrameworkKind.WEB, """manage\.py["']?\s+runserver"""),
    Rule("flask", "Flask", FrameworkKind.WEB, """flask\s+run|-m\s+flask"""),
    Rule("uvicorn", "Uvicorn / FastAPI", FrameworkKind.WEB, """uvicorn|fastapi\s+(dev|run)"""),
    Rule("rails", "Rails", FrameworkKind.WEB, """\brails\s+s(erver)?\b|\bpuma\b"""),
    Rule("laravel", "Laravel", FrameworkKind.WEB, """artisan["']?\s+serve"""),
    Rule("php", "PHP server", FrameworkKind.WEB, """\bphp(\.exe)?["']?\s+-S\s"""),
    Rule("hugo", "Hugo", FrameworkKind.WEB, """\bhugo(\.exe)?["']?\s+server"""),
    Rule("jupyter", "Jupyter", FrameworkKind.WEB, """jupyter"""),
    Rule("spring", "Spring Boot", FrameworkKind.WEB, """spring-boot|org\.springframework"""),
    Rule("quarkus", "Quarkus", FrameworkKind.WEB, """quarkus"""),
    Rule("ktor", "Ktor", FrameworkKind.WEB, """io\.ktor"""),
    Rule("tomcat", "Tomcat", FrameworkKind.WEB, """org\.apache\.catalina|catalina\.(sh|bat)"""),
    Rule("dotnet", ".NET", FrameworkKind.WEB, """\bdotnet(\.exe)?["']?\s+(run|watch)|aspnetcore"""),
    Rule("postgres", "PostgreSQL", FrameworkKind.DATABASE, """\bpostgres(\.exe)?\b"""),
    Rule("mysql", "MySQL", FrameworkKind.DATABASE, """\bmysqld(\.exe)?\b|\bmariadbd?\b"""),
    Rule("mongodb", "MongoDB", FrameworkKind.DATABASE, """\bmongod(\.exe)?\b"""),
    Rule("redis", "Redis", FrameworkKind.DATABASE, """redis-server"""),
    Rule("docker", "Docker", FrameworkKind.TOOL, """com\.docker|docker-proxy|\bvpnkit\b|wslrelay|docker desktop"""),
    Rule("deno", "Deno", FrameworkKind.RUNTIME, """\bdeno(\.exe)?\b"""),
    Rule("bun", "Bun", FrameworkKind.RUNTIME, """\bbun(\.exe)?\b"""),
    Rule("node", "Node.js", FrameworkKind.RUNTIME, """\bnode(\.exe)?\b"""),
    Rule("python", "Python", FrameworkKind.RUNTIME, """\bpython[\d.]*(\.exe)?\b"""),
    Rule("java", "Java", FrameworkKind.RUNTIME, """\bjava(w)?(\.exe)?\b"""),
    Rule("ruby", "Ruby", FrameworkKind.RUNTIME, """\bruby(\.exe)?\b"""),
    Rule("go", "Go", FrameworkKind.RUNTIME, """go-build|\bgo\s+run\b"""),
)

fun detectFramework(name: String, command: String?): Framework? {
    val text = "$name ${command ?: ""}"
    return RULES.firstOrNull { it.regex.containsMatchIn(text) }?.let { Framework(it.id, it.label, it.kind) }
}

/** Whether "Open in Browser" makes sense: everything except databases (Docker and editors often serve web pages). */
fun isBrowsable(entry: PortEntry) = entry.framework?.kind != FrameworkKind.DATABASE

/* ---------- Addresses ---------- */

private val LOOPBACK = Regex("""^(127\.|::1$|::ffff:127\.|localhost$)""", RegexOption.IGNORE_CASE)
fun isLoopback(address: String) = LOOPBACK.containsMatchIn(address)
private fun isWildcard(address: String) = address == "0.0.0.0" || address == "::" || address == "*" || address == "[::]"

/** The address to open in a browser: localhost unless the server listens on one specific other address. */
fun browserHost(entry: PortEntry): String {
    if (entry.addresses.any { isWildcard(it) || isLoopback(it) }) return "localhost"
    val address = entry.addresses.firstOrNull() ?: "localhost"
    return if (address.contains(':')) "[$address]" else address
}

fun pageUrl(entry: PortEntry) = "http://${browserHost(entry)}:${entry.port}/"

private val HOST_PORT = Regex("""^\[?([^\]]*?)\]?:(\d+)$""")

/** "host:port", "[v6]:port" or "*:port" → (address, port). */
fun splitHostPort(text: String): Pair<String, Int>? {
    val match = HOST_PORT.find(text.trim()) ?: return null
    val port = match.groupValues[2].toIntOrNull() ?: return null
    if (port !in 1..65535) return null
    val host = match.groupValues[1]
    val address = if (host == "*") "0.0.0.0" else host.replace(Regex("%.*$"), "") // drop IPv6 zone ("fe80::1%lo0")
    return address to port
}

/* ---------- Protection ---------- */

/** System programs that hold ports and must never be stopped from an IDE. */
private val SYSTEM_NAMES = setOf(
    "system", "idle", "svchost.exe", "services.exe", "lsass.exe", "wininit.exe", "winlogon.exe", "csrss.exe", "smss.exe",
    "spoolsv.exe", "searchindexer.exe", "msmpeng.exe", "launchd", "systemd", "systemd-resolved", "init", "sshd", "rapportd",
    "controlcenter", "mdnsresponder", "cupsd", "avahi-daemon", "dnsmasq", "chronyd", "rpcbind",
)

fun isProtected(process: ProcessInfo) = process.pid <= 4 || !process.owned || process.name.lowercase() in SYSTEM_NAMES

/* ---------- Building and comparing snapshots ---------- */

data class BuildOptions(
    /** Project folder paths, to mark programs running from them. */
    val workspaces: List<String> = emptyList(),
    /** Include system services and other users' programs (shown, but never stoppable). */
    val includeSystem: Boolean = false,
    /** Ports never shown. */
    val hidden: Set<Int> = emptySet(),
    /** Paths compare case-insensitively (Windows, macOS). */
    val caseInsensitivePaths: Boolean = false,
    /** The IDE's own process: always protected, whatever its name. */
    val selfPid: Long? = null,
    val now: Long = System.currentTimeMillis(),
)

/** Turns raw sockets plus process details into the tool window's rows: deduplicated, enriched, sorted and indexed. */
fun buildSnapshot(listeners: List<Listener>, processes: Map<Long, ProcessInfo>, options: BuildOptions = BuildOptions()): Snapshot {
    data class Merged(val port: Int, val pid: Long, val addresses: MutableSet<String> = linkedSetOf())
    val merged = linkedMapOf<String, Merged>()
    for (listener in listeners) {
        if (listener.port !in 1..65535 || listener.pid < 0) continue
        if (listener.port in options.hidden) continue
        merged.getOrPut(entryKey(listener.pid, listener.port)) { Merged(listener.port, listener.pid) }.addresses += listener.address
    }

    fun fold(text: String) = (if (options.caseInsensitivePaths) text.lowercase() else text).trimEnd('/', '\\')
    val workspaces = options.workspaces.map { it to fold(it) }.sortedByDescending { it.second.length }
    fun workspaceOf(process: ProcessInfo): String? {
        val places = listOfNotNull(process.cwd, process.command, process.path).filter { it.isNotEmpty() }.map(::fold)
        return workspaces.firstOrNull { (_, ws) -> places.any { it == ws || it.contains("$ws/") || it.contains("$ws\\") } }?.first
    }

    val entries = mutableListOf<PortEntry>()
    for ((key, item) in merged) {
        val process = processes[item.pid] ?: ProcessInfo(item.pid, if (item.pid == 0L) "System" else "PID ${item.pid}", owned = false)
        val isSystem = isProtected(process)
        if (isSystem && !options.includeSystem) continue
        val addresses = item.addresses.sorted()
        val framework = detectFramework(process.name, process.command)
        entries += PortEntry(
            key = key, port = item.port, pid = item.pid, process = process, addresses = addresses, framework = framework,
            localOnly = addresses.all(::isLoopback),
            protected = isSystem || framework?.kind == FrameworkKind.EDITOR || item.pid == options.selfPid,
            workspace = workspaceOf(process),
        )
    }
    entries.sortWith(compareBy<PortEntry> { it.port }.thenBy { it.pid })
    return Snapshot(entries, entries.groupBy { it.port }, entries.groupBy { it.pid }, options.now)
}

/* ---------- Grouping for the tool window ---------- */

enum class GroupId(val label: String) {
    WORKSPACE("This Project"), APPS("Apps & Dev Servers"), EDITORS("Editors & Tools"), SYSTEM("System & Other Users"),
}

data class Group(val id: GroupId, val entries: List<PortEntry>)

fun groupOf(entry: PortEntry): GroupId = when {
    entry.protected && !entry.process.owned -> GroupId.SYSTEM
    entry.framework?.kind == FrameworkKind.EDITOR -> GroupId.EDITORS
    entry.workspace != null -> GroupId.WORKSPACE
    entry.framework?.kind == FrameworkKind.TOOL -> GroupId.EDITORS
    else -> GroupId.APPS
}

/** The tool window's sections, in a fixed order, leaving out empty ones (entries keep their port order). */
fun groupEntries(entries: List<PortEntry>): List<Group> {
    val buckets = entries.groupBy(::groupOf)
    return GroupId.entries.mapNotNull { id -> buckets[id]?.let { Group(id, it) } }
}

/** What changed between two scans, by entry identity (process + port). */
fun diffSnapshots(previous: Snapshot?, next: Snapshot): Diff {
    if (previous == null) return Diff(emptyList(), emptyList())
    val before = previous.entries.mapTo(hashSetOf()) { it.key }
    val after = next.entries.mapTo(hashSetOf()) { it.key }
    return Diff(next.entries.filter { it.key !in before }, previous.entries.filter { it.key !in after })
}

/* ---------- Parsers for each operating system's output ---------- */

/** Windows: `netstat -ano -p TCP`. The state column is translated on non-English Windows, so listening sockets are
    recognised by their foreign address (0.0.0.0:0 or [::]:0) instead. */
fun parseNetstat(output: String): List<Listener> {
    val listening = Regex("""^(0\.0\.0\.0|\[::]|\*):(0|\*)$""")
    return output.lineSequence().mapNotNull { line ->
        val columns = line.trim().split(Regex("""\s+"""))
        if (columns.size < 5 || !columns[0].startsWith("TCP", ignoreCase = true)) return@mapNotNull null
        if (!listening.matches(columns[2])) return@mapNotNull null
        val local = splitHostPort(columns[1]) ?: return@mapNotNull null
        val pid = columns.last().toLongOrNull() ?: return@mapNotNull null
        Listener(local.second, local.first, pid)
    }.toList()
}

/** Windows: the tab-separated lines printed by our PowerShell query (ProcessId, Name, ExecutablePath, CommandLine).
    `systemRoot` is %SystemRoot%: programs inside it are system programs. Without access to the executable path, the
    program belongs to the system or another user. */
fun parseWindowsProcesses(output: String, systemRoot: String = "C:\\Windows"): Map<Long, ProcessInfo> {
    val root = systemRoot.lowercase().trimEnd('\\', '/') + "\\"
    val processes = linkedMapOf<Long, ProcessInfo>()
    for (line in output.removePrefix("\uFEFF").lineSequence()) {
        val cells = line.split('\t')
        val pid = cells.getOrNull(0)?.trim()?.toLongOrNull() ?: continue
        fun cell(i: Int) = cells.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() }
        val path = cell(2)
        processes[pid] = ProcessInfo(pid, cell(1) ?: "PID $pid", command = cell(3), path = path,
            owned = path != null && !path.lowercase().startsWith(root))
    }
    return processes
}

/** Windows fallback: `tasklist /fo csv /nh` → process names (ownership unknown, so nothing is stoppable). */
fun parseTasklistCsv(output: String): Map<Long, ProcessInfo> {
    val cell = Regex("\"((?:[^\"]|\"\")*)\"")
    val processes = linkedMapOf<Long, ProcessInfo>()
    for (line in output.lineSequence()) {
        val cells = cell.findAll(line).map { it.groupValues[1].replace("\"\"", "\"") }.toList()
        val pid = cells.getOrNull(1)?.toLongOrNull() ?: continue
        processes[pid] = ProcessInfo(pid, cells[0], owned = false)
    }
    return processes
}

/** macOS/Linux: `lsof -nP -iTCP -sTCP:LISTEN -F pcLn`. Each line is a field: p=PID, c=command, L=login, n=address. */
fun parseLsof(output: String, user: String? = null): Pair<List<Listener>, Map<Long, ProcessInfo>> {
    val listeners = mutableListOf<Listener>()
    val processes = linkedMapOf<Long, ProcessInfo>()
    var current: Long? = null
    for (line in output.lineSequence()) {
        if (line.isEmpty()) continue
        val value = line.substring(1)
        when (line[0]) {
            'p' -> {
                val pid = value.toLongOrNull() ?: continue
                current = pid
                processes.getOrPut(pid) { ProcessInfo(pid, "PID $pid", owned = user == null) }
            }
            'c' -> current?.let { processes[it] = processes.getValue(it).copy(name = value) }
            'L' -> current?.let { processes[it] = processes.getValue(it).copy(owned = user == null || value == user) }
            'n' -> current?.let { pid -> splitHostPort(value.substringBefore("->"))?.let { listeners += Listener(it.second, it.first, pid) } }
        }
    }
    return listeners to processes
}

/** Linux fallback: `ss -ltnpH` (users:(("node",pid=1234,fd=23))). Only the user's own processes show a PID. */
fun parseSs(output: String): Pair<List<Listener>, Map<Long, ProcessInfo>> {
    val listeners = mutableListOf<Listener>()
    val processes = linkedMapOf<Long, ProcessInfo>()
    val owner = Regex("""\("([^"]+)",pid=(\d+)""")
    for (line in output.lineSequence()) {
        val columns = line.trim().split(Regex("""\s+"""))
        if (columns.size < 4 || !columns[0].equals("LISTEN", ignoreCase = true)) continue
        val local = splitHostPort(columns[3]) ?: continue
        for (match in owner.findAll(line)) {
            val pid = match.groupValues[2].toLong()
            listeners += Listener(local.second, local.first, pid)
            processes.getOrPut(pid) { ProcessInfo(pid, match.groupValues[1], owned = true) }
        }
    }
    return listeners to processes
}

/** macOS/Linux: `ps -o pid=,args= -p …` → full command lines. */
fun parsePs(output: String): Map<Long, String> {
    val line = Regex("""^\s*(\d+)\s+(.+?)\s*$""")
    return output.lineSequence().mapNotNull { line.find(it) }.associate { it.groupValues[1].toLong() to it.groupValues[2] }
}

/** macOS: `lsof -a -d cwd -Fn -p 1,2,3` → each process's working directory. */
fun parseLsofCwd(output: String): Map<Long, String> {
    val cwd = linkedMapOf<Long, String>()
    var pid: Long? = null
    for (line in output.lineSequence()) {
        if (line.startsWith("p")) pid = line.substring(1).toLongOrNull()
        else if (line.startsWith("n")) pid?.let { cwd[it] = line.substring(1) }
    }
    return cwd
}
