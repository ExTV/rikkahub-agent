package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ShizukuCommandRunner], the Android/Shizuku-independent process-execution
 * core that [ShizukuUserService] wraps. Because it is a plain [ProcessBuilder] launcher it
 * runs (and is testable) as-is on the host JVM: these tests spawn real `sh` subprocesses,
 * unlike most other tests in this module. Everything Shizuku-specific (the AIDL bind, running
 * under the shell UID) is out of scope here, see [ShizukuManager], and can only be verified
 * on a device with Shizuku actually running.
 */
class ShizukuCommandRunnerTest {

    @Test
    fun `successful command returns stdout, stderr and exit code`() {
        val result = ShizukuCommandRunner.run(
            command = "echo hello; echo world 1>&2",
            timeoutMs = 5_000,
            maxStdoutBytes = 8_000,
            maxStderrBytes = 8_000,
        )
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals(0, result["exit_code"]!!.jsonPrimitive.int)
        assertEquals("hello\n", result["stdout"]!!.jsonPrimitive.content)
        assertEquals("world\n", result["stderr"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a command that reads stdin gets EOF instead of hanging until the timeout`() {
        // `cat` with no file blocks on stdin; with stdin left open it runs out the full timeout
        // and captures nothing, which looks like a transport failure.
        val result = ShizukuCommandRunner.run(
            command = "cat; echo done",
            timeoutMs = 5_000,
            maxStdoutBytes = 8_000,
            maxStderrBytes = 8_000,
        )
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals("done\n", result["stdout"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nonzero exit code is reported as unsuccessful`() {
        val result = ShizukuCommandRunner.run(
            command = "exit 3",
            timeoutMs = 5_000,
            maxStdoutBytes = 8_000,
            maxStderrBytes = 8_000,
        )
        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        assertEquals(3, result["exit_code"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a command not found on PATH still round-trips a structured result, not an exception`() {
        val result = ShizukuCommandRunner.run(
            command = "definitely_not_a_real_command_xyz",
            timeoutMs = 5_000,
            maxStdoutBytes = 8_000,
            maxStderrBytes = 8_000,
        )
        // The shell itself reports "command not found" via a nonzero exit, this never throws
        // out of run(), it's a normal (if unsuccessful) result envelope.
        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        assertTrue(result["exit_code"]!!.jsonPrimitive.int != 0)
    }

    @Test
    fun `stdout over the cap is truncated with an explicit marker`() {
        // 5000 raw 'x' bytes (no newlines) piped through a cap of 100.
        val result = ShizukuCommandRunner.run(
            command = "head -c 5000 /dev/zero | tr '\\0' 'x'",
            timeoutMs = 5_000,
            maxStdoutBytes = 100,
            maxStderrBytes = 8_000,
        )
        val stdout = result["stdout"]!!.jsonPrimitive.content
        assertTrue(stdout.startsWith("x".repeat(100)))
        assertTrue(stdout.contains("truncated"))
    }

    @Test
    fun `output under the cap is not marked as truncated`() {
        val result = ShizukuCommandRunner.run(
            command = "echo short",
            timeoutMs = 5_000,
            maxStdoutBytes = 8_000,
            maxStderrBytes = 8_000,
        )
        assertFalse(result["stdout"]!!.jsonPrimitive.content.contains("truncated"))
    }

    @Test
    fun `command that outlasts the timeout is destroyed and reports partial output`() {
        val result = ShizukuCommandRunner.run(
            command = "echo before; sleep 5; echo after",
            timeoutMs = 300,
            maxStdoutBytes = 8_000,
            maxStderrBytes = 8_000,
        )
        assertEquals("command_timeout", result["error"]!!.jsonPrimitive.content)
        // The process is killed mid-sleep, so "after" must never appear, only what was
        // flushed before the timeout fired.
        assertTrue(result["partial_stdout"]!!.jsonPrimitive.content.contains("before"))
        assertFalse(result["partial_stdout"]!!.jsonPrimitive.content.contains("after"))
    }

    @Test
    fun `command finishing comfortably inside the timeout does not time out`() {
        val result = ShizukuCommandRunner.run(
            command = "echo quick",
            timeoutMs = 5_000,
            maxStdoutBytes = 8_000,
            maxStderrBytes = 8_000,
        )
        assertFalse(result.containsKey("error"))
        assertEquals("quick\n", result["stdout"]!!.jsonPrimitive.content)
    }
}
