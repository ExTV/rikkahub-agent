package me.rerere.rikkahub.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-Kotlin coverage for the workspace-tool prefix rules used to keep workspace_ tools
 * out of the global always-allow set (issue #44): [isWorkspaceToolName] guards every
 * write/read of that set, and [migrateWorkspaceToolsFrom] backs the lazy one-shot
 * cleanup of stale entries.
 */
class ToolApprovalPreferencesWorkspaceFilterTest {

    // --- isWorkspaceToolName ----------------------------------------------------------

    @Test
    fun isWorkspaceToolName_workspacePrefixed_true() {
        assertTrue(isWorkspaceToolName("workspace_read_file"))
        assertTrue(isWorkspaceToolName("workspace_shell"))
        assertTrue(isWorkspaceToolName("workspace_"))
    }

    @Test
    fun isWorkspaceToolName_nonWorkspaceTool_false() {
        assertFalse(isWorkspaceToolName("launch_activity"))
        assertFalse(isWorkspaceToolName("read_file"))
        assertFalse(isWorkspaceToolName(""))
        // Must be a prefix match, not a substring match anywhere in the name.
        assertFalse(isWorkspaceToolName("my_workspace_tool"))
    }

    // --- migrateWorkspaceToolsFrom ------------------------------------------------------

    @Test
    fun migrateWorkspaceToolsFrom_noWorkspaceEntries_keepsEverythingRemovesNothing() {
        val stored = setOf("launch_activity", "read_file", "open_url")
        val (kept, removed) = migrateWorkspaceToolsFrom(stored)
        assertEquals(stored, kept)
        assertTrue(removed.isEmpty())
    }

    @Test
    fun migrateWorkspaceToolsFrom_mixedEntries_filtersAndReportsRemoved() {
        val stored = setOf("launch_activity", "workspace_shell", "read_file", "workspace_write_file")
        val (kept, removed) = migrateWorkspaceToolsFrom(stored)
        assertEquals(setOf("launch_activity", "read_file"), kept)
        assertEquals(setOf("workspace_shell", "workspace_write_file"), removed)
    }

    @Test
    fun migrateWorkspaceToolsFrom_allWorkspaceEntries_keepsEmptySet() {
        val stored = setOf("workspace_shell", "workspace_read_file")
        val (kept, removed) = migrateWorkspaceToolsFrom(stored)
        assertTrue(kept.isEmpty())
        assertEquals(stored, removed)
    }

    @Test
    fun migrateWorkspaceToolsFrom_emptySet_noop() {
        val (kept, removed) = migrateWorkspaceToolsFrom(emptySet())
        assertTrue(kept.isEmpty())
        assertTrue(removed.isEmpty())
    }
}
