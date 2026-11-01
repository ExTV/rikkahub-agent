package me.rerere.rikkahub.data.ai.transformers

import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.workspace.WorkspaceShellStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [buildWorkspaceReminder] — the pure state -> system-prompt selector.
 *
 * Contract:
 * - bound + READY            -> full `<workspace>` block advertising the workspace_* tools
 * - bound + not READY        -> `<workspace-setup>` telling the model how to guide the user to
 *                               install/repair the rootfs (tailored per DISABLED/INSTALLING/BROKEN)
 * - unbound, workspaces exist -> `<workspace-setup>` telling the model how to guide binding via +
 * - no workspace at all       -> null (nothing injected)
 *
 * Note: the READY block is the only one containing the exact tag `<workspace>`; the guidance
 * blocks use `<workspace-setup>`, so `contains("<workspace>")` distinguishes "tools live" from
 * "tools unavailable, here's how to enable".
 */
class WorkspaceReminderTransformerTest {

    private fun workspace(status: WorkspaceShellStatus, name: String = "demo") = WorkspaceEntity(
        id = "id-$name",
        name = name,
        root = "id-$name",
        shellStatus = status.name,
        createdAt = 0L,
        updatedAt = 0L,
    )

    @Test
    fun `bound and READY yields the full workspace block with tools`() {
        val prompt = buildWorkspaceReminder(workspace(WorkspaceShellStatus.READY, "proj"), hasAnyWorkspace = true)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<workspace>"))
        assertTrue(prompt.contains("proj"))
        assertTrue(prompt.contains("workspace_shell"))
    }

    @Test
    fun `bound but DISABLED yields setup guidance to install the rootfs`() {
        val prompt = buildWorkspaceReminder(workspace(WorkspaceShellStatus.DISABLED), hasAnyWorkspace = true)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<workspace-setup>"))
        // must NOT masquerade as the tools-live block
        assertFalse(prompt.contains("<workspace>"))
        assertTrue(prompt.contains("install its rootfs"))
        assertTrue(prompt.contains(WorkspaceShellStatus.DISABLED.name))
    }

    @Test
    fun `bound but INSTALLING tells the user to wait`() {
        val prompt = buildWorkspaceReminder(workspace(WorkspaceShellStatus.INSTALLING), hasAnyWorkspace = true)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<workspace-setup>"))
        assertTrue(prompt.contains("installing"))
    }

    @Test
    fun `bound but BROKEN tells the user to reinstall or repair`() {
        val prompt = buildWorkspaceReminder(workspace(WorkspaceShellStatus.BROKEN), hasAnyWorkspace = true)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<workspace-setup>"))
        assertTrue(prompt.contains("broken"))
    }

    @Test
    fun `unbound but workspaces exist yields binding guidance`() {
        val prompt = buildWorkspaceReminder(workspace = null, hasAnyWorkspace = true)
        requireNotNull(prompt)
        assertTrue(prompt.contains("<workspace-setup>"))
        assertFalse(prompt.contains("<workspace>"))
        assertTrue(prompt.contains("+ button"))
    }

    @Test
    fun `no workspace at all injects nothing`() {
        val prompt = buildWorkspaceReminder(workspace = null, hasAnyWorkspace = false)
        assertNull(prompt)
    }
}
