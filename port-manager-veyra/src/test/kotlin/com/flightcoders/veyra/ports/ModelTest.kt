// Unit tests for the data model, with real-world output from each operating system.
package com.flightcoders.veyra.ports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelTest {
    private fun proc(pid: Long, name: String, owned: Boolean = true, command: String? = null, cwd: String? = null, path: String? = null) =
        ProcessInfo(pid, name, command = command, cwd = cwd, path = path, owned = owned)

    private fun entry(addresses: List<String>, framework: Framework? = null) = PortEntry(
        key = "1:8080", port = 8080, addresses = addresses, pid = 1, process = proc(1, "x"), framework = framework,
        localOnly = false, protected = false, workspace = null,
    )

    @Test fun `Windows - PowerShell process lines, system programs and the BOM`() {
        val output = "\uFEFF4120\tnode.exe\tC:\\Program Files\\nodejs\\node.exe\t\"C:\\Program Files\\nodejs\\node.exe\" C:\\work\\shop\\node_modules\\vite\\bin\\vite.js\r\n" +
            "1012\tsvchost.exe\t\t\r\n" +
            "7\tpython.exe\tC:\\Windows\\py.exe\tpy -m http.server\r\n"
        val processes = parseWindowsProcesses(output, "C:\\Windows\\")
        assertEquals(3, processes.size)
        assertTrue(processes.getValue(4120).owned)
        assertTrue(processes.getValue(4120).command!!.contains("vite.js"))
        assertFalse("no access to the path: a system program", processes.getValue(1012).owned)
        assertFalse("programs inside %SystemRoot% are system programs", processes.getValue(7).owned)
    }

    @Test fun `Windows - netstat in any language, IPv4 and IPv6`() {
        val output = listOf(
            "Aktive Verbindungen",
            "  Proto  Lokale Adresse         Remoteadresse          Status           PID",
            "  TCP    0.0.0.0:135            0.0.0.0:0              ABHÖREN         1012",
            "  TCP    127.0.0.1:3000         0.0.0.0:0              ABHÖREN         9001",
            "  TCP    192.168.1.5:52000      140.82.112.4:443       HERGESTELLT     9001",
            "  TCP    [::]:5173              [::]:0                 ABHÖREN         4120",
            "  UDP    0.0.0.0:5353           *:*                                    2222",
        ).joinToString("\r\n")
        assertEquals(
            listOf(Listener(135, "0.0.0.0", 1012), Listener(3000, "127.0.0.1", 9001), Listener(5173, "::", 4120)),
            parseNetstat(output),
        )
        val names = parseTasklistCsv("\"node.exe\",\"4120\",\"Console\",\"1\",\"95,512 K\"\r\n\"svchost.exe\",\"1012\",\"Services\",\"0\",\"12,000 K\"")
        assertEquals("node.exe", names.getValue(4120).name)
        assertFalse("ownership unknown in the fallback: nothing is stoppable", names.getValue(4120).owned)
    }

    @Test fun `macOS and Linux - lsof field output, owner and IPv6`() {
        val output = listOf("p501", "cnode", "Lvikram", "f23", "n*:3000", "f24", "n[::1]:3000", "p88", "cpostgres", "Lpostgres", "f7",
            "n127.0.0.1:5432", "p77", "cControlCe", "Lvikram", "f5", "n[fe80::1%lo0]:7000").joinToString("\n")
        val (listeners, processes) = parseLsof(output, "vikram")
        assertEquals(
            listOf(Listener(3000, "0.0.0.0", 501), Listener(3000, "::1", 501), Listener(5432, "127.0.0.1", 88), Listener(7000, "fe80::1", 77)),
            listeners,
        )
        assertTrue(processes.getValue(501).owned)
        assertEquals("node", processes.getValue(501).name)
        assertFalse("another user's program", processes.getValue(88).owned)
        assertEquals("/home/vikram/shop", parseLsofCwd("p501\nfcwd\nn/home/vikram/shop\n")[501])
        assertEquals("node /home/vikram/shop/node_modules/.bin/next dev", parsePs("  501 node /home/vikram/shop/node_modules/.bin/next dev\n")[501])
    }

    @Test fun `Linux fallback - ss output`() {
        val output = listOf(
            "LISTEN 0      511          0.0.0.0:8000      0.0.0.0:*    users:((\"python3\",pid=2100,fd=3))",
            "LISTEN 0      4096   127.0.0.53%lo:53        0.0.0.0:*",
            "LISTEN 0      511             [::]:8000         [::]:*    users:((\"python3\",pid=2100,fd=4))",
        ).joinToString("\n")
        val (listeners, processes) = parseSs(output)
        assertEquals(listOf(Listener(8000, "0.0.0.0", 2100), Listener(8000, "::", 2100)), listeners)
        assertEquals("python3", processes.getValue(2100).name)
    }

    @Test fun `frameworks are recognised from the command line, most specific first`() {
        val cases = listOf(
            Triple("node.exe", "node C:\\app\\node_modules\\vite\\bin\\vite.js", "vite"),
            Triple("node", "node /app/node_modules/.bin/next dev", "next"),
            Triple("node", "next-server (v15.1.0)", "next"),
            Triple("node", "node /app/node_modules/nuxt/bin/nuxt.mjs dev", "nuxt"),
            Triple("node", "node /app/node_modules/react-scripts/scripts/start.js", "cra"),
            Triple("python3", "python3 manage.py runserver", "django"),
            Triple("python", "python -m flask run", "flask"),
            Triple("Python", "/usr/bin/python3 -m uvicorn main:app --reload", "uvicorn"),
            Triple("ruby", "puma 6.4.2 (tcp://localhost:3000) [shop]", "rails"),
            Triple("php", "php artisan serve", "laravel"),
            Triple("java", "java -jar build/libs/app.jar org.springframework.boot.loader.launch.JarLauncher", "spring"),
            Triple("postgres", "/usr/lib/postgresql/16/bin/postgres -D /var/lib/postgresql", "postgres"),
            Triple("com.docker.backend", "", "docker"),
            Triple("node", "node server.js", "node"),
        )
        for ((name, command, id) in cases) assertEquals("$name $command", id, detectFramework(name, command)?.id)
        assertNull(detectFramework("mystery", ""))
        assertEquals("\"export\" is not Expo", "node", detectFramework("node", "node export.js")?.id)
    }

    @Test fun `JetBrains IDEs and other editors are recognised and protected, so the plugin can never close the IDE`() {
        for (name in listOf("idea64.exe", "idea", "webstorm64.exe", "pycharm64.exe", "PhpStorm", "goland64.exe", "rider64.exe", "clion",
            "rubymine64.exe", "rustrover64.exe", "datagrip64.exe", "studio64.exe", "Code.exe", "Cursor.exe", "Veyra Studio.exe")) {
            assertEquals(name, FrameworkKind.EDITOR, detectFramework(name, "-Xmx2048m")?.kind)
        }
        assertEquals("only the program name decides", "node", detectFramework("node.exe", "C:\\Program Files\\JetBrains\\idea64.exe --inspect")?.id)
        val snapshot = buildSnapshot(listOf(Listener(63342, "127.0.0.1", 900)), mapOf(900L to proc(900, "idea64.exe")))
        assertTrue("the IDE's built-in server is never stoppable", snapshot.entries.single().protected)
        val self = buildSnapshot(listOf(Listener(4000, "127.0.0.1", 950)), mapOf(950L to proc(950, "java")), BuildOptions(selfPid = 950))
        assertTrue("the plugin's own process is protected whatever its name", self.entries.single().protected)
    }

    @Test fun `snapshots merge addresses, hide system programs, index by port and process, and find the project`() {
        val listeners = listOf(
            Listener(5173, "::", 10), Listener(5173, "0.0.0.0", 10), Listener(9229, "127.0.0.1", 10),
            Listener(3000, "127.0.0.1", 20), Listener(135, "0.0.0.0", 1012),
            Listener(99999, "0.0.0.0", 30), Listener(5037, "0.0.0.0", 40),
        )
        val processes = mapOf(
            10L to proc(10, "node.exe", command = "node C:\\Work\\Shop\\node_modules\\vite\\bin\\vite.js"),
            20L to proc(20, "python.exe", cwd = "/srv/other"),
            1012L to proc(1012, "svchost.exe", owned = false),
            40L to proc(40, "adb.exe"),
        )
        val snapshot = buildSnapshot(listeners, processes,
            BuildOptions(workspaces = listOf("c:\\work\\shop"), caseInsensitivePaths = true, hidden = setOf(5037), now = 1))
        assertEquals("sorted; system, hidden and invalid ports left out", listOf("3000/20", "5173/10", "9229/10"),
            snapshot.entries.map { "${it.port}/${it.pid}" })
        val vite = snapshot.byPort.getValue(5173).single()
        assertEquals(listOf("0.0.0.0", "::"), vite.addresses)
        assertEquals("vite", vite.framework?.id)
        assertEquals("c:\\work\\shop", vite.workspace)
        assertFalse(vite.localOnly)
        assertEquals(2, snapshot.byPid.getValue(10).size)
        assertTrue(snapshot.byPort.getValue(3000).single().localOnly)
        assertNull(snapshot.byPort.getValue(3000).single().workspace)
        val withSystem = buildSnapshot(listeners, processes, BuildOptions(includeSystem = true))
        assertTrue("shown, but never stoppable", withSystem.byPort.getValue(135).single().protected)
    }

    @Test fun `diffs find new and stopped servers by identity`() {
        val processes = mapOf(101L to proc(101, "node"), 102L to proc(102, "node"))
        val before = buildSnapshot(listOf(Listener(3000, "::", 101)), processes)
        val after = buildSnapshot(listOf(Listener(3000, "::", 101), Listener(4000, "::", 102)), processes)
        assertEquals("the first scan announces nothing", Diff(emptyList(), emptyList()), diffSnapshots(null, after))
        val diff = diffSnapshots(before, after)
        assertEquals(listOf(4000), diff.added.map { it.port })
        assertTrue(diff.removed.isEmpty())
        assertEquals(listOf(4000), diffSnapshots(after, before).removed.map { it.port })
    }

    @Test fun `browser addresses, ports and protection rules`() {
        assertEquals("http://localhost:8080/", pageUrl(entry(listOf("0.0.0.0", "::"))))
        assertEquals("http://localhost:8080/", pageUrl(entry(listOf("::1"))))
        assertEquals("http://192.168.1.5:8080/", pageUrl(entry(listOf("192.168.1.5"))))
        assertEquals("http://[fe80::1]:8080/", pageUrl(entry(listOf("fe80::1"))))
        assertEquals("::1" to 5173, splitHostPort("[::1]:5173"))
        assertEquals("0.0.0.0" to 80, splitHostPort("*:80"))
        assertNull(splitHostPort("127.0.0.1:0"))
        assertNull(splitHostPort("garbage"))
        assertTrue(isProtected(proc(4, "System")))
        assertTrue(isProtected(proc(500, "launchd")))
        assertTrue(isProtected(proc(501, "node", owned = false)))
        assertFalse(isProtected(proc(502, "node")))
        assertFalse(isBrowsable(entry(listOf("::"), Framework("postgres", "PostgreSQL", FrameworkKind.DATABASE))))
        assertTrue(isBrowsable(entry(listOf("::"))))
    }

    @Test fun `entries are grouped - this project, apps, editors and tools, system`() {
        val listeners = listOf(Listener(5173, "::", 100), Listener(8000, "::", 200), Listener(63342, "127.0.0.1", 300),
            Listener(2375, "0.0.0.0", 400), Listener(445, "0.0.0.0", 500))
        val processes = mapOf(
            100L to proc(100, "node", cwd = "/home/a/shop"),
            200L to proc(200, "python3", command = "python3 -m http.server"),
            300L to proc(300, "idea"),
            400L to proc(400, "com.docker.backend"),
            500L to proc(500, "smbd", owned = false),
        )
        val groups = groupEntries(buildSnapshot(listeners, processes, BuildOptions(workspaces = listOf("/home/a/shop"), includeSystem = true)).entries)
        assertEquals(
            listOf(GroupId.WORKSPACE to listOf(5173), GroupId.APPS to listOf(8000), GroupId.EDITORS to listOf(2375, 63342), GroupId.SYSTEM to listOf(445)),
            groups.map { it.id to it.entries.map { e -> e.port } },
        )
        assertTrue(groupEntries(emptyList()).isEmpty())
    }
}
