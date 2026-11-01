package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

class WorkspaceBackgroundProcessesTest {

    // ---- TailBuffer: pure, deterministic (no process spawning) ----

    @Test
    fun `TailBuffer keeps only the tail once over capacity`() {
        val buffer = TailBuffer(5)
        buffer.append("abcdefgh")

        assertEquals("defgh", buffer.text())
        assertEquals(3L, buffer.droppedChars)
    }

    @Test
    fun `TailBuffer accumulates across appends and reports total dropped`() {
        val buffer = TailBuffer(5)
        buffer.append("abc")
        assertEquals("abc", buffer.text())
        assertEquals(0L, buffer.droppedChars)

        buffer.append("defgh")
        assertEquals("defgh", buffer.text())
        assertEquals(3L, buffer.droppedChars)

        buffer.append("ij")
        assertEquals("fghij", buffer.text())
        assertEquals(5L, buffer.droppedChars)
    }

    @Test
    fun `TailBuffer under capacity keeps everything and drops nothing`() {
        val buffer = TailBuffer(100)
        buffer.append("short")

        assertEquals("short", buffer.text())
        assertEquals(0L, buffer.droppedChars)
    }

    // ---- WorkspaceBackgroundProcesses registry: pure, using a fake Process that
    // never spawns an OS process, so cap/kill/eviction logic is deterministic. ----

    @Test
    fun `start enforces the running-process cap per root`() {
        val registry = WorkspaceBackgroundProcesses()
        repeat(MAX_BG_PROCESSES) { i ->
            registry.start("root-a", FakeProcess(), "cmd-$i", "")
        }

        val extra = FakeProcess()
        try {
            registry.start("root-a", extra, "one-too-many", "")
            fail("Expected IllegalStateException when exceeding MAX_BG_PROCESSES")
        } catch (_: IllegalStateException) {
            // expected
        }
        // The rejected process must be killed, not leaked.
        assertFalse(extra.isAlive)

        // A different root is unaffected by root-a's cap.
        val other = registry.start("root-b", FakeProcess(), "cmd", "")
        assertTrue(other.running)
    }

    @Test
    fun `kill removes the entry and destroys the process`() {
        val registry = WorkspaceBackgroundProcesses()
        val process = FakeProcess()
        val status = registry.start("root-a", process, "sleep", "")

        assertTrue(registry.kill("root-a", status.id))
        assertFalse(process.isAlive)
        assertNull(registry.status("root-a", status.id))
        assertFalse(registry.kill("root-a", status.id))
    }

    @Test
    fun `status and kill are scoped to the owning root`() {
        val registry = WorkspaceBackgroundProcesses()
        val process = FakeProcess()
        val status = registry.start("a", process, "cmd", "")

        // A different workspace root must not be able to read or kill another root's task.
        assertNull(registry.status("b", status.id))
        assertFalse(registry.kill("b", status.id))
        assertTrue(process.isAlive)

        // The owning root can still read and kill it.
        assertNotNull(registry.status("a", status.id))
        assertTrue(registry.kill("a", status.id))
        assertFalse(process.isAlive)
    }

    @Test
    fun `killAll destroys every process for the root but leaves other roots alone`() {
        val registry = WorkspaceBackgroundProcesses()
        val a1 = FakeProcess()
        val a2 = FakeProcess()
        val b1 = FakeProcess()
        registry.start("root-a", a1, "cmd", "")
        registry.start("root-a", a2, "cmd", "")
        val bStatus = registry.start("root-b", b1, "cmd", "")

        registry.killAll("root-a")

        assertFalse(a1.isAlive)
        assertFalse(a2.isAlive)
        assertTrue(registry.list("root-a").isEmpty())
        assertTrue(b1.isAlive)
        assertNotNull(registry.status("root-b", bStatus.id))
    }

    @Test
    fun `status reports exit code once the process has exited`() {
        val registry = WorkspaceBackgroundProcesses()
        val process = FakeProcess()
        val status = registry.start("root-a", process, "cmd", "")
        assertTrue(status.running)
        assertNull(status.exitCode)

        process.finish(3)

        val updated = registry.status("root-a", status.id)
        assertNotNull(updated)
        assertFalse(updated!!.running)
        assertEquals(3, updated.exitCode)
    }

    // ---- Manager + HostShellRunner: best-effort, real process spawn.
    // Kept because they proved reliable locally (fast-exiting / immediately-alive
    // checks only, bounded polling, no fixed sleeps); drop to device-only acceptance
    // if they prove flaky in CI. ----

    @Test
    fun `background command output is captured and status reflects completion`() {
        val baseDir = Files.createTempDirectory("workspace-bg-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val started = manager.startBackground(root, "printf hello")
        try {
            val finished = pollUntilFinished(manager, root, started.id)
            assertEquals("hello", finished.stdout)
            assertEquals(0, finished.exitCode)
        } finally {
            manager.killBackground(root, started.id)
        }
    }

    @Test
    fun `blocking background command can be killed`() {
        val baseDir = Files.createTempDirectory("workspace-bg-kill-test").toFile()
        val manager = WorkspaceManager(baseDir)
        val root = "test-workspace"
        manager.ensureWorkspace(root)

        val started = manager.startBackground(root, "cat")
        try {
            assertTrue(manager.backgroundStatus(root, started.id)?.running == true)
            assertTrue(manager.killBackground(root, started.id))
            assertNull(manager.backgroundStatus(root, started.id))
        } finally {
            manager.killBackground(root, started.id)
        }
    }

    private fun pollUntilFinished(
        manager: WorkspaceManager,
        root: String,
        id: String,
        timeoutMillis: Long = 5_000,
    ): BackgroundStatus {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val status = manager.backgroundStatus(root, id) ?: error("Background task disappeared: $id")
            if (!status.running) return status
            Thread.sleep(25)
        }
        error("Background task did not finish within ${timeoutMillis}ms: $id")
    }

    /** Minimal fake that never spawns an OS process, for deterministic registry tests. */
    private class FakeProcess : Process() {
        @Volatile
        private var alive = true

        @Volatile
        private var exit = 0

        fun finish(exitCode: Int) {
            exit = exitCode
            alive = false
        }

        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            alive = false
            return exit
        }

        override fun exitValue(): Int {
            check(!alive) { "process hasn't exited" }
            return exit
        }

        override fun destroy() {
            alive = false
        }

        override fun isAlive(): Boolean = alive
    }
}
