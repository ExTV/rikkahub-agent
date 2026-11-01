package me.rerere.rikkahub.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private const val TAG = "ToolApprovalPreferences"

/** Workspace tools (created by createWorkspaceTools) are per-workspace, never
 *  per-app, so this prefix must never be granted or read from the global
 *  always-allow set. */
private const val WORKSPACE_TOOL_PREFIX = "workspace_"

private val Context.toolApprovalDataStore by preferencesDataStore(name = "tool_approval")

/** True iff [name] is a workspace tool (reserved "workspace_" prefix, see
 *  createWorkspaceTools). Pure so the prefix rule can be unit-tested without a
 *  DataStore instance. */
internal fun isWorkspaceToolName(name: String): Boolean = name.startsWith(WORKSPACE_TOOL_PREFIX)

/** Splits [stored] into (kept, removed) where removed is every workspace_-prefixed
 *  entry. Pure - backs both the read-time filter and the one-shot migration below. */
internal fun migrateWorkspaceToolsFrom(stored: Set<String>): Pair<Set<String>, Set<String>> {
    val removed = stored.filterTo(mutableSetOf(), ::isWorkspaceToolName)
    return (stored - removed) to removed
}

/**
 * Persistent tool-approval prefs. Two pieces:
 *
 * 1. **"Always Allow" allow-list** — per-tool grants that survive app restart until the
 *    user revokes them from Settings → Tool approvals. The HARDLINE floor still applies
 *    even with Always Allow granted.
 *
 * 2. **"I AM STUPID" global auto-approve** — single boolean that, when true, treats every
 *    tool as pre-approved across every conversation and every surface (in-app, Telegram,
 *    cron). HARDLINE STILL APPLIES — there is no override for that. This is the user's
 *    explicit "I trust the agent fully, stop asking me" escape hatch. Surfaced as a
 *    bright-red toggle behind a confirm dialog because it's a live foot-gun.
 *
 * The companion in-memory "Allow for this chat" allow-list lives in
 * [me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList] and resets on /new or app
 * process restart.
 */
class ToolApprovalPreferences(private val context: Context) {
    private val store = context.toolApprovalDataStore
    private val K_ALWAYS_ALLOW = stringSetPreferencesKey("always_allow_tool_names")
    private val K_GLOBAL_YOLO = booleanPreferencesKey("global_auto_approve_yolo")

    // Filtered on every read: a stale workspace_-prefixed entry (from before this
    // filtering existed) must never resurface in the settings list or an auto-approve
    // check, even before the lazy migration below gets a chance to persist the cleanup.
    val alwaysAllowFlow: Flow<Set<String>> = store.data.map {
        migrateWorkspaceToolsFrom(it[K_ALWAYS_ALLOW].orEmpty()).first
    }

    /** Live flow of the "I AM STUPID" global auto-approve flag. Default false. */
    val globalYoloFlow: Flow<Boolean> = store.data.map {
        it[K_GLOBAL_YOLO] ?: false
    }

    suspend fun current(): Set<String> = migrateAwayWorkspaceTools()

    /** Snapshot read of the YOLO flag. Used by the per-call auto-approval check. */
    suspend fun currentYolo(): Boolean = globalYoloFlow.first()

    suspend fun setYolo(enabled: Boolean) {
        store.edit { it[K_GLOBAL_YOLO] = enabled }
    }

    suspend fun grantAlways(toolName: String) {
        // Workspace tools are per-workspace, not per-app; ChatService routes those
        // through WorkspaceRepository.setToolApproval instead, so this should never be
        // called with one. Refuse rather than silently poisoning the global set.
        if (isWorkspaceToolName(toolName)) {
            Log.w(TAG, "refusing to add workspace tool '$toolName' to the global always-allow set")
            return
        }
        store.edit { it[K_ALWAYS_ALLOW] = (it[K_ALWAYS_ALLOW].orEmpty()) + toolName }
    }

    /** One-shot lazy migration: strips any stale workspace_-prefixed entry out of the
     *  persisted always-allow set (left over from before this filtering existed) and
     *  persists the cleanup. Cheap on the steady-state path: only opens a write
     *  transaction when there is actually something to remove. */
    private suspend fun migrateAwayWorkspaceTools(): Set<String> {
        val stored = store.data.first()[K_ALWAYS_ALLOW].orEmpty()
        val (_, removed) = migrateWorkspaceToolsFrom(stored)
        if (removed.isEmpty()) return stored
        // Return the post-edit snapshot, not the pre-edit `stored` read above: a concurrent
        // grantAlways landing between that read and this edit is persisted by DataStore's
        // read-modify-write but would otherwise be dropped from this call's return value.
        val result = store.edit { prefs ->
            val (cleaned, actuallyRemoved) = migrateWorkspaceToolsFrom(prefs[K_ALWAYS_ALLOW].orEmpty())
            if (actuallyRemoved.isNotEmpty()) {
                Log.i(TAG, "removing stale workspace tool grants from always-allow: $actuallyRemoved")
                prefs[K_ALWAYS_ALLOW] = cleaned
            }
        }
        return migrateWorkspaceToolsFrom(result[K_ALWAYS_ALLOW].orEmpty()).first
    }

    suspend fun revoke(toolName: String) {
        store.edit { it[K_ALWAYS_ALLOW] = (it[K_ALWAYS_ALLOW].orEmpty()) - toolName }
    }

    suspend fun revokeAll() {
        store.edit { it.remove(K_ALWAYS_ALLOW) }
    }
}
