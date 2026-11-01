package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Coverage for [WorkspaceFileSystem.tree] (backs the workspace_read_folder tool, #40 G2):
 * directory-first ordering, depth capping, and entry-count capping with the truncation flag.
 */
class WorkspaceTreeTest {

    @Test
    fun `nested directories and files are listed depth-first with directories before files`() {
        val root = Files.createTempDirectory("workspace-tree-test").toFile()
        val fileSystem = WorkspaceFileSystem()
        fileSystem.writeText(root, "b.txt", "b")
        fileSystem.writeText(root, "src/a.txt", "a")
        fileSystem.writeText(root, "src/nested/c.txt", "c")

        val result = fileSystem.tree(root)

        assertFalse(result.truncated)
        // 目录优先: src 排在 b.txt 之前; 同理 src 内 nested 目录排在 a.txt 文件之前,
        // 且 nested 的子项在返回到 a.txt 之前先被深度优先展开
        assertEquals(
            listOf(
                "src" to 1,
                "src/nested" to 2,
                "src/nested/c.txt" to 3,
                "src/a.txt" to 2,
                "b.txt" to 1,
            ),
            result.entries.map { it.path to it.depth },
        )
        assertTrue(result.entries.first { it.path == "src" }.isDirectory)
        assertEquals(1L, result.entries.first { it.path == "b.txt" }.sizeBytes)
    }

    @Test
    fun `entry count beyond maxListEntries is truncated`() {
        val root = Files.createTempDirectory("workspace-tree-cap-test").toFile()
        val fileSystem = WorkspaceFileSystem(WorkspaceConfig(maxListEntries = 3))
        repeat(5) { i -> fileSystem.writeText(root, "file-$i.txt", "x") }

        val result = fileSystem.tree(root)

        assertTrue(result.truncated)
        assertEquals(3, result.entries.size)
    }

    @Test
    fun `depth beyond maxDepth is truncated`() {
        val root = Files.createTempDirectory("workspace-tree-depth-test").toFile()
        val fileSystem = WorkspaceFileSystem()
        fileSystem.writeText(root, "a/b/c/d.txt", "deep")

        val result = fileSystem.tree(root, maxDepth = 2)

        assertTrue(result.truncated)
        assertEquals(listOf("a", "a/b"), result.entries.map { it.path })
    }

    @Test
    fun `internal l2s files are excluded like list()`() {
        val root = Files.createTempDirectory("workspace-tree-hidden-test").toFile()
        val fileSystem = WorkspaceFileSystem()
        fileSystem.writeText(root, "visible.txt", "v")
        java.io.File(root, ".l2s.internal").writeText("hidden")

        val result = fileSystem.tree(root)

        assertEquals(listOf("visible.txt"), result.entries.map { it.path })
    }
}
