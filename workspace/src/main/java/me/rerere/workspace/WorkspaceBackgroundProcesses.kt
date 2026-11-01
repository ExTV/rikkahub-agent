package me.rerere.workspace

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// 每个流保留的最大字符数（尾部保留，超出部分从头部丢弃）
const val MAX_BG_OUTPUT_CHARS = 32 * 1024

// 单个 workspace 允许同时运行的后台进程数上限
const val MAX_BG_PROCESSES = 5

// 单个 workspace 保留的后台任务记录数上限（含已退出的），超出后淘汰最旧的已退出记录
private const val MAX_ENTRIES_PER_ROOT = 16

/**
 * Bounded tail buffer: keeps only the most recently appended [maxChars] characters,
 * dropping from the front when the limit is exceeded, and tracks how many characters
 * have been dropped so callers can surface that to the model. No disk persistence.
 */
class TailBuffer(private val maxChars: Int) {
    private val builder = StringBuilder()

    @Volatile
    var droppedChars: Long = 0
        private set

    fun append(text: String) {
        if (text.isEmpty()) return
        synchronized(builder) {
            builder.append(text)
            val overflow = builder.length - maxChars
            if (overflow > 0) {
                builder.delete(0, overflow)
                droppedChars += overflow
            }
        }
    }

    fun text(): String = synchronized(builder) { builder.toString() }

    /** Returns the current text and dropped-char count as one consistent snapshot. */
    fun snapshot(): Pair<String, Long> = synchronized(builder) { builder.toString() to droppedChars }
}

data class BackgroundStatus(
    val id: String,
    val command: String,
    val cwd: String,
    val running: Boolean,
    val exitCode: Int?,
    val startedAtMillis: Long,
    val stdout: String,
    val stderr: String,
    val droppedStdout: Long,
    val droppedStderr: Long,
)

/**
 * Process-lifetime registry for background shell processes started via
 * [WorkspaceShellRunner.start]. Owns draining stdout/stderr into bounded [TailBuffer]s
 * and the running-process cap; the caller (see [WorkspaceManager]) is responsible for
 * actually constructing the proot [Process].
 */
class WorkspaceBackgroundProcesses(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val entries = ConcurrentHashMap<String, Entry>()
    private val idCounter = AtomicInteger(0)

    /**
     * Registers [process] as a background task for [root] and starts daemon drain
     * threads for its stdout/stderr. Throws [IllegalStateException] if [root] already
     * has [MAX_BG_PROCESSES] running processes, in which case [process] is killed
     * before the exception is thrown.
     */
    fun start(root: String, process: Process, command: String, cwd: String): BackgroundStatus {
        synchronized(entries) {
            val runningForRoot = entries.values.count { it.root == root && it.process.isAlive }
            if (runningForRoot >= MAX_BG_PROCESSES) {
                process.destroyForcibly()
                throw IllegalStateException(
                    "Too many running background processes for this workspace (max $MAX_BG_PROCESSES). " +
                        "Kill one with workspace_background_kill first."
                )
            }
            val id = "bg_${idCounter.incrementAndGet()}"
            val entry = Entry(
                id = id,
                root = root,
                process = process,
                command = command,
                cwd = cwd,
                startedAtMillis = nowMillis(),
            )
            entries[id] = entry
            evictOldestExited(root)
            return entry.toStatus()
        }
    }

    /** Returns the status for [id], or null if it does not exist or belongs to a different [root]. */
    fun status(root: String, id: String): BackgroundStatus? {
        val entry = entries[id] ?: return null
        return if (entry.root == root) entry.toStatus() else null
    }

    fun list(root: String): List<BackgroundStatus> =
        entries.values
            .filter { it.root == root }
            .sortedBy { it.startedAtMillis }
            .map { it.toStatus() }

    /**
     * Kills and removes the entry for [id], but only if it belongs to [root]. Returns
     * false (without touching the entry) if [id] does not exist or belongs to another
     * workspace.
     */
    fun kill(root: String, id: String): Boolean = synchronized(entries) {
        val entry = entries[id] ?: return@synchronized false
        if (entry.root != root) return@synchronized false
        entries.remove(id)
        entry.process.destroyForcibly()
        true
    }

    /** Kills and removes every entry for [root]. Used when a workspace is deleted. */
    fun killAll(root: String): Unit = synchronized(entries) {
        entries.values
            .filter { it.root == root }
            .forEach { entry -> entries.remove(entry.id)?.process?.destroyForcibly() }
    }

    private fun evictOldestExited(root: String) {
        val forRoot = entries.values.filter { it.root == root }
        val overflow = forRoot.size - MAX_ENTRIES_PER_ROOT
        if (overflow <= 0) return
        forRoot
            .filter { !it.process.isAlive }
            .sortedBy { it.startedAtMillis }
            .take(overflow)
            .forEach { entries.remove(it.id) }
    }

    private fun Entry.toStatus(): BackgroundStatus {
        val alive = process.isAlive
        if (!alive) {
            // 进程刚退出时, drain 线程可能还没来得及把管道里剩余的字节写入 TailBuffer;
            // 这里等一下(有界), 确保退出后上报的 output 是完整的, 呼应 readResult 里
            // 对 StreamCollector 的 join(1_000) 处理
            stdoutDrainer.join(1_000)
            stderrDrainer.join(1_000)
        }
        val (stdoutText, stdoutDropped) = stdout.snapshot()
        val (stderrText, stderrDropped) = stderr.snapshot()
        return BackgroundStatus(
            id = id,
            command = command,
            cwd = cwd,
            running = alive,
            exitCode = if (alive) null else process.exitValue(),
            startedAtMillis = startedAtMillis,
            stdout = stdoutText,
            stderr = stderrText,
            droppedStdout = stdoutDropped,
            droppedStderr = stderrDropped,
        )
    }

    private class Entry(
        val id: String,
        val root: String,
        val process: Process,
        val command: String,
        val cwd: String,
        val startedAtMillis: Long,
    ) {
        val stdout = TailBuffer(MAX_BG_OUTPUT_CHARS)
        val stderr = TailBuffer(MAX_BG_OUTPUT_CHARS)
        val stdoutDrainer = Drainer(process.inputStream, stdout)
        val stderrDrainer = Drainer(process.errorStream, stderr)
    }
}

/** Drains [stream] into [buffer] on a daemon thread until EOF, mirroring StreamCollector. */
private class Drainer(stream: InputStream, buffer: TailBuffer) {
    private val thread = Thread {
        try {
            stream.bufferedReader().use { reader ->
                val chunk = CharArray(4096)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    buffer.append(String(chunk, 0, read))
                }
            }
        } catch (_: IOException) {
            // 进程被强杀或自然退出时流会被关闭，阻塞中的 read 会抛异常，保留已读取内容即可
        }
    }.apply {
        // daemon: 进程若残留 fd 导致 read() 永久阻塞，也不会阻止 JVM 退出
        isDaemon = true
        start()
    }

    fun join(millis: Long) = thread.join(millis)
}
