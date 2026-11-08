package me.rerere.rikkahub.data.ai.limits

import me.rerere.rikkahub.data.preferences.TermuxDefaults

/**
 * App-wide @Volatile runtime holder for tool execution limits that span all tool families
 * (not just Termux). Holds the per-turn wall-clock budget, max tool steps, and the
 * idle/progress watchdog settings.
 */
object ToolRuntimeLimits {
    @Volatile var turnBudgetMs: Long = TermuxDefaults.DEFAULT_TURN_BUDGET_MS

    /**
     * Tool-call iterations a single turn may run before it is force-ended.
     */
    @Volatile var maxToolSteps: Int = TermuxDefaults.DEFAULT_MAX_TOOL_STEPS

    /**
     * Idle activity timeout: if no streaming tokens or tool events occur within this duration,
     * the stalled turn is terminated. Default is 5 minutes (300,000 ms).
     */
    @Volatile var idleTimeoutMs: Long = 5 * 60 * 1000L

    /**
     * Unrestricted Long-Task mode: when enabled, allows tasks to run up to 4 hours
     * while enforcing the [idleTimeoutMs] progress watchdog.
     */
    @Volatile var unrestrictedLongTasks: Boolean = false

    fun effectiveMaxTurnDurationMs(): Long =
        if (unrestrictedLongTasks) 4L * 3600L * 1000L else turnBudgetMs
}
