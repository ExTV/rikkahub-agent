package me.rerere.rikkahub.data.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.ai.limits.ToolRuntimeLimits

private val Context.agentDataStore by preferencesDataStore(name = "agent_prefs")

/**
 * DataStore-backed settings store for app-wide Agent execution and tool runtime limits.
 * Manages per-turn budget, max tool steps, idle activity watchdog, and unrestricted mode.
 */
class AgentPreferences(private val context: Context) {

    private val store = context.agentDataStore

    private val turnBudgetKey = longPreferencesKey("turn_budget_ms")
    private val maxToolStepsKey = intPreferencesKey("max_tool_steps")
    private val idleTimeoutKey = longPreferencesKey("idle_timeout_ms")
    private val unrestrictedLongTasksKey = booleanPreferencesKey("unrestricted_long_tasks")

    init {
        val initial = snapshotBlocking()
        ToolRuntimeLimits.turnBudgetMs = initial.turnBudgetMs
        ToolRuntimeLimits.maxToolSteps = initial.maxToolSteps
        ToolRuntimeLimits.idleTimeoutMs = initial.idleTimeoutMs
        ToolRuntimeLimits.unrestrictedLongTasks = initial.unrestrictedLongTasks

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        scope.launch {
            turnBudgetFlow()
                .distinctUntilChanged()
                .onEach { ToolRuntimeLimits.turnBudgetMs = it }
                .collect {}
        }
        scope.launch {
            maxToolStepsFlow()
                .distinctUntilChanged()
                .onEach { ToolRuntimeLimits.maxToolSteps = it }
                .collect {}
        }
        scope.launch {
            idleTimeoutFlow()
                .distinctUntilChanged()
                .onEach { ToolRuntimeLimits.idleTimeoutMs = it }
                .collect {}
        }
        scope.launch {
            unrestrictedLongTasksFlow()
                .distinctUntilChanged()
                .onEach { ToolRuntimeLimits.unrestrictedLongTasks = it }
                .collect {}
        }
    }

    fun turnBudgetFlow(): Flow<Long> = store.data.map { prefs ->
        TermuxDefaults.clampTurnBudgetMs(
            prefs[turnBudgetKey] ?: TermuxDefaults.DEFAULT_TURN_BUDGET_MS
        )
    }

    fun maxToolStepsFlow(): Flow<Int> = store.data.map { prefs ->
        TermuxDefaults.clampMaxToolSteps(
            prefs[maxToolStepsKey] ?: TermuxDefaults.DEFAULT_MAX_TOOL_STEPS
        )
    }

    fun idleTimeoutFlow(): Flow<Long> = store.data.map { prefs ->
        prefs[idleTimeoutKey]?.coerceIn(30_000L, 30 * 60 * 1000L) ?: (5 * 60 * 1000L)
    }

    fun unrestrictedLongTasksFlow(): Flow<Boolean> = store.data.map { prefs ->
        prefs[unrestrictedLongTasksKey] ?: false
    }

    suspend fun setTurnBudgetMs(ms: Long) {
        store.edit { it[turnBudgetKey] = TermuxDefaults.clampTurnBudgetMs(ms) }
    }

    suspend fun setMaxToolSteps(steps: Int) {
        store.edit { it[maxToolStepsKey] = TermuxDefaults.clampMaxToolSteps(steps) }
    }

    suspend fun setIdleTimeoutMs(ms: Long) {
        store.edit { it[idleTimeoutKey] = ms.coerceIn(30_000L, 30 * 60 * 1000L) }
    }

    suspend fun setUnrestrictedLongTasks(enabled: Boolean) {
        store.edit { it[unrestrictedLongTasksKey] = enabled }
    }

    suspend fun snapshot(): AgentRuntimeConfig {
        val prefs = store.data.first()
        return AgentRuntimeConfig(
            turnBudgetMs = TermuxDefaults.clampTurnBudgetMs(
                prefs[turnBudgetKey] ?: TermuxDefaults.DEFAULT_TURN_BUDGET_MS
            ),
            maxToolSteps = TermuxDefaults.clampMaxToolSteps(
                prefs[maxToolStepsKey] ?: TermuxDefaults.DEFAULT_MAX_TOOL_STEPS
            ),
            idleTimeoutMs = prefs[idleTimeoutKey]?.coerceIn(30_000L, 30 * 60 * 1000L) ?: (5 * 60 * 1000L),
            unrestrictedLongTasks = prefs[unrestrictedLongTasksKey] ?: false,
        )
    }

    fun snapshotBlocking(): AgentRuntimeConfig = kotlinx.coroutines.runBlocking { snapshot() }
}

data class AgentRuntimeConfig(
    val turnBudgetMs: Long,
    val maxToolSteps: Int,
    val idleTimeoutMs: Long,
    val unrestrictedLongTasks: Boolean,
)
