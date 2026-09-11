package me.rerere.rikkahub.data.ai.tools

import me.rerere.workspace.WorkspaceTreeEntry
import me.rerere.workspace.WorkspaceTreeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [40] G1/G2 pure helpers in WorkspaceTools.kt:
 * - [String.isOutsideWritableRoots]: the writable-root prefix check, including the ".." smuggle fix.
 * - [formatWorkspaceTree]: the workspace_read_folder model-facing text renderer.
 */
class WorkspaceToolsTest {

    @Test
    fun `clean path inside a writable root is not outside`() {
        assertFalse("/workspace/notes.txt".isOutsideWritableRoots())
        assertFalse("/tmp/scratch".isOutsideWritableRoots())
        assertFalse("/workspace".isOutsideWritableRoots())
        // /skills is a writable safe zone as of upstream 019c46aee.
        assertFalse("/skills/foo".isOutsideWritableRoots())
    }

    @Test
    fun `path outside every writable root is outside`() {
        assertTrue("/etc/passwd".isOutsideWritableRoots())
        assertTrue("/var/log/x".isOutsideWritableRoots())
    }

    @Test
    fun `dotdot smuggle attempt is treated as outside even with a writable prefix`() {
        assertTrue("/workspace/../etc/x".isOutsideWritableRoots())
        assertTrue("/tmp/../../etc/passwd".isOutsideWritableRoots())
    }

    @Test
    fun `formatWorkspaceTree renders empty directory`() {
        val text = formatWorkspaceTree("/workspace", WorkspaceTreeResult(entries = emptyList(), truncated = false))
        assertEquals("/workspace/ (empty)", text)
    }

    @Test
    fun `formatWorkspaceTree indents nested entries by depth`() {
        val result = WorkspaceTreeResult(
            entries = listOf(
                WorkspaceTreeEntry(path = "src", name = "src", isDirectory = true, sizeBytes = 0, depth = 1),
                WorkspaceTreeEntry(path = "src/a.txt", name = "a.txt", isDirectory = false, sizeBytes = 12, depth = 2),
                WorkspaceTreeEntry(path = "b.txt", name = "b.txt", isDirectory = false, sizeBytes = 3, depth = 1),
            ),
            truncated = false,
        )

        val text = formatWorkspaceTree("/workspace", result)

        assertEquals(
            """
            /workspace/
              src/
                a.txt (12 bytes)
              b.txt (3 bytes)
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `formatWorkspaceTree appends a truncation notice when capped`() {
        val result = WorkspaceTreeResult(
            entries = listOf(
                WorkspaceTreeEntry(path = "a.txt", name = "a.txt", isDirectory = false, sizeBytes = 1, depth = 1),
            ),
            truncated = true,
        )

        val text = formatWorkspaceTree("/workspace", result)

        assertTrue(text.contains("truncated"))
        assertTrue(text.endsWith("truncated: showing 1 entries; narrow the path for a complete listing)"))
    }
}
