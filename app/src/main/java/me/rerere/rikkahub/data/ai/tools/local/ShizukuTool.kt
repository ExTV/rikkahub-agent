package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.shizuku.ShizukuManager

private const val DEFAULT_TIMEOUT_MS = 30_000
private const val MIN_TIMEOUT_MS = 1_000
/** Matches ssh_exec's ceiling (300s / 5 min). */
private const val MAX_TIMEOUT_MS = 300_000

/**
 * Run a shell command with Shizuku's privileges (the shell UID, the same level `adb shell`
 * gets, no root, no su). Requires the Shizuku app installed, its service running, and
 * permission granted from Settings -> Shizuku; the permission is never requested from this
 * tool or automatically, only from an explicit tap on that settings screen.
 *
 * Approval-gated in [me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults] and its `command`
 * argument is checked against [me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard] like
 * every other shell surface (SSH, Termux).
 */
fun shizukuExecTool(context: Context): Tool = Tool(
    name = "shizuku_exec",
    description = """
        Run a shell command with Shizuku's privileges (the shell UID - the same level `adb
        shell` gets, no root). Returns stdout, stderr, and exit code. Requires the Shizuku app
        installed, its service running, and permission granted from Settings -> Shizuku.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Shell command to run, e.g. 'pm list packages -3'")
                })
                put("timeout_ms", buildJsonObject {
                    put("type", "integer")
                    put("description", "Timeout in milliseconds. Default $DEFAULT_TIMEOUT_MS, max $MAX_TIMEOUT_MS.")
                })
            },
            required = listOf("command")
        )
    },
    execute = { input ->
        val command = input.jsonObject["command"]?.jsonPrimitive?.contentOrNull
        if (command.isNullOrBlank()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "command is required") }.toString()
                )
            )
        }
        val timeoutMs = (input.jsonObject["timeout_ms"]?.jsonPrimitive?.intOrNull ?: DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val result = ShizukuManager.exec(context, command, timeoutMs)
        listOf(UIMessagePart.Text(result.toString()))
    }
)
