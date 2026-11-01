package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context

/**
 * Shared entry point for the "wake the screen before doing something the user can see"
 * pattern. Every interactive tool calls this before its body so headless runs (Telegram
 * bot, cron, sub-agent) don't launch activities or fire gestures against a dark screen.
 *
 * Delegates to [ScreenWaker.wakeIfOff], which already lives in WakeScreenTool.kt and is
 * idempotent (no-op when the screen is already on). Safe to call from any coroutine
 * context; never throws. Returns true on success, false on failure (and logs a warning,
 * guarded so JVM unit tests with a NULL_CONTEXT that reach this helper through validation
 * paths don't hit "method not mocked" from android.util.Log).
 */
fun wakeScreenIfNeeded(context: Context): Boolean {
    return try {
        if (!ScreenWaker.isInteractive(context)) {
            ScreenWaker.wakeIfOff(context)
        }
        true
    } catch (t: Throwable) {
        // Log guarded: JVM unit tests reach this helper on validation paths where
        // android.util.Log would throw "not mocked".
        runCatching { android.util.Log.w("WakeHelper", "wake failed: ${t.message}") }
        false
    }
}
