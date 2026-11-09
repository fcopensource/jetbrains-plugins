// End-to-end tests on the real operating system: start a real server in a child process, find it with the real
// Scanner, then stop it with the real stopProcess. Nothing here is mocked.
package com.flightcoders.veyra.ports

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SystemTest {
    private var child: Process? = null
    private val folder: File = Files.createTempDirectory("veyra-ports-test").toFile()

    @After fun cleanUp() {
        child?.let { it.descendants().forEach(ProcessHandle::destroyForcibly); it.destroyForcibly() }
        folder.deleteRecursively()
    }

    /** Starts a small Java server (single-file source launch) in [folder] and returns its port once it is listening. */
    private fun startServer(): Pair<Process, Int> {
        val source = File(folder, "Holder.java")
        source.writeText(
            """
            import java.net.*;
            public class Holder {
                public static void main(String[] args) throws Exception {
                    try (ServerSocket socket = new ServerSocket()) {
                        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
                        System.out.println("PORT " + socket.getLocalPort());
                        System.out.flush();
                        Thread.sleep(120_000);
                    }
                }
            }
            """.trimIndent()
        )
        val java = File(System.getProperty("java.home"), "bin/" + if (Platform.current == Platform.WINDOWS) "java.exe" else "java")
        // A tiny heap: the server only holds one socket, and some machines have little memory to spare.
        val process = ProcessBuilder(java.path, "-Xmx16m", "-Xss256k", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=16m", "Holder.java")
            .directory(folder).redirectErrorStream(true).start()
        child = process
        val reader = process.inputStream.bufferedReader()
        // Skip any JVM warnings printed before our own line.
        val line = generateSequence { reader.readLine() }.firstOrNull { it.startsWith("PORT ") }
            ?: throw AssertionError("the test server did not start")
        val port = line.removePrefix("PORT ").trim().toInt()
        return process to port
    }

    private fun scanFor(scanner: Scanner, port: Int, pid: Long, options: BuildOptions = BuildOptions(includeSystem = true)): PortEntry? {
        val end = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < end) {
            scanner.scan(options, fresh = true).byPort[port]?.firstOrNull { it.pid == pid }?.let { return it }
            Thread.sleep(300)
        }
        return null
    }

    @Test fun `the scanner finds a real server, with its program, address and project folder`() {
        val (process, port) = startServer()
        val entry = scanFor(Scanner(), port, process.pid(), BuildOptions(workspaces = listOf(folder.canonicalPath), includeSystem = true))
        assertNotNull("port $port (PID ${process.pid()}) was not found", entry)
        entry!!
        assertTrue(entry.process.name, entry.process.name.lowercase().startsWith("java"))
        assertEquals("java", entry.framework?.id)
        assertTrue("bound to loopback: ${entry.addresses}", entry.localOnly)
        assertEquals("http://localhost:$port/", pageUrl(entry))
        assertTrue("started by this user", entry.process.owned)
        assertFalse("our own program is stoppable", entry.protected)
        if (Platform.current != Platform.WINDOWS) {
            // macOS/Linux report the working directory; Windows reports the command line, which contains Holder.java's folder.
            assertEquals(folder.canonicalPath, entry.workspace)
        }
    }

    @Test fun `stopping frees the port and ends the process`() {
        val (process, port) = startServer()
        val scanner = Scanner()
        assertNotNull(scanFor(scanner, port, process.pid()))
        assertTrue("stopProcess reported the process still running", stopProcess(process.pid()))
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        assertFalse(isAlive(process.pid()))
        assertTrue("the port is free again", scanner.scan(BuildOptions(includeSystem = true), fresh = true).byPort[port].isNullOrEmpty())
        ServerSocket(port).close() // and can be reused
    }

    @Test fun `the IDE's own process and system processes can never be stopped`() {
        val self = ProcessHandle.current().pid()
        assertTrue(runCatching { stopProcess(self) }.isFailure)
        assertTrue(runCatching { stopProcess(4) }.isFailure)
        assertTrue(runCatching { stopProcess(0) }.isFailure)
        assertTrue("the test JVM is still running", isAlive(self))
    }

    @Test fun `a full scan of this computer completes quickly and is well formed`() {
        val started = System.currentTimeMillis()
        val snapshot = Scanner().scan(BuildOptions(includeSystem = true), fresh = true)
        val seconds = (System.currentTimeMillis() - started) / 1000.0
        assertTrue("scan took $seconds s", seconds < 20)
        assertEquals(snapshot.entries.sortedWith(compareBy<PortEntry> { it.port }.thenBy { it.pid }), snapshot.entries)
        assertTrue(snapshot.entries.all { it.port in 1..65535 && it.addresses.isNotEmpty() })
        assertEquals("keys are unique", snapshot.entries.size, snapshot.entries.map { it.key }.toSet().size)
    }
}
