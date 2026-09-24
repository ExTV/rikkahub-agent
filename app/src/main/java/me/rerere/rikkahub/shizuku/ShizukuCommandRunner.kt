package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.ai.tools.local.BoundedOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Pure command-execution core for the Shizuku user service: runs [command] through a shell,
 * captures bounded stdout/stderr, and enforces [timeoutMs] by destroying the process.
 *
 * Deliberately free of Android and Shizuku dependencies: [ProcessBuilder] runs the same way
 * whether this code executes inside the app's own process or inside the separate shell-UID
 * process Shizuku spawns for [ShizukuUserService], so it also runs (and is unit-tested) as a
 * plain JVM process launcher on the host, no device or emulator required. Reuses
 * [BoundedOutputStream] so stdout/stderr truncation behaves identically to the other shell
 * tools (SSH, Termux).
 */
internal object ShizukuCommandRunner {

    fun run(command: String, timeoutMs: Int, maxStdoutBytes: Int, maxStderrBytes: Int): JsonObject {
        val process = try {
            ProcessBuilder("sh", "-c", command).start()
        } catch (e: IOException) {
            return buildJsonObject {
                put("error", "exec_failed")
                put("reason", e.message ?: e::class.java.simpleName)
            }
        }

        // Give stdin an immediate EOF: a command that reads it (`cat` with no file, a prompt)
        // would otherwise block until timeoutMs and capture nothing.
        runCatching { process.outputStream.close() }

        val stdoutSink = BoundedOutputStream(maxStdoutBytes)
        val stderrSink = BoundedOutputStream(maxStderrBytes)
        // Drain both streams concurrently on daemon threads. A command that fills its stdout
        // pipe buffer (a common size is 64KB) will block forever if nothing reads it, so
        // waitFor() alone, without a concurrent reader, can deadlock on chatty output long
        // before timeoutMs ever fires.
        val stdoutThread = Thread({ runCatching { process.inputStream.copyTo(stdoutSink) } }, "shizuku-stdout")
            .apply { isDaemon = true; start() }
        val stderrThread = Thread({ runCatching { process.errorStream.copyTo(stderrSink) } }, "shizuku-stderr")
            .apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(1_000)
            stderrThread.join(1_000)
            return buildJsonObject {
                put("error", "command_timeout")
                put(
                    "recovery",
                    "Command did not complete within ${timeoutMs / 1000}s. Bump timeout_ms if the " +
                        "command genuinely needs longer. Partial output captured before the " +
                        "timeout is included."
                )
                put("partial_stdout", stdoutSink.snapshot())
                put("partial_stderr", stderrSink.snapshot())
            }
        }

        stdoutThread.join(2_000)
        stderrThread.join(2_000)
        val exitCode = process.exitValue()
        return buildJsonObject {
            put("success", exitCode == 0)
            put("exit_code", exitCode)
            put("stdout", stdoutSink.snapshot())
            put("stderr", stderrSink.snapshot())
        }
    }
}
