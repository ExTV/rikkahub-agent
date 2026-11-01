package me.rerere.rikkahub.ui.pages.extensions.workspace

import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Coverage for the pure helpers backing the workspace Files tab tree view and folder
 * export (#40 G3/G4): [flattenWorkspaceTree] and [planWorkspaceFolderExport].
 */
class WorkspaceDetailVMTest {

    private fun file(name: String, path: String) = WorkspaceFileEntry(
        path = path,
        name = name,
        isDirectory = false,
        sizeBytes = 1,
        updatedAt = 0L,
    )

    private fun dir(name: String, path: String) = WorkspaceFileEntry(
        path = path,
        name = name,
        isDirectory = true,
        sizeBytes = 0,
        updatedAt = 0L,
    )

    @Test
    fun `collapsed directories contribute a single row`() {
        val entries = listOf(dir("src", "src"), file("b.txt", "b.txt"))

        val rows = flattenWorkspaceTree(entries, expandedPaths = emptySet(), childrenCache = emptyMap())

        assertEquals(listOf("src" to 0, "b.txt" to 0), rows.map { it.entry.path to it.depth })
    }

    @Test
    fun `expanded directory inlines its cached children at depth plus one`() {
        val entries = listOf(dir("src", "src"), file("b.txt", "b.txt"))
        val cache = mapOf("src" to listOf(file("a.txt", "src/a.txt")))

        val rows = flattenWorkspaceTree(entries, expandedPaths = setOf("src"), childrenCache = cache)

        assertEquals(
            listOf("src" to 0, "src/a.txt" to 1, "b.txt" to 0),
            rows.map { it.entry.path to it.depth },
        )
    }

    @Test
    fun `nested expansion recurses through the cache`() {
        val entries = listOf(dir("src", "src"))
        val cache = mapOf(
            "src" to listOf(dir("nested", "src/nested")),
            "src/nested" to listOf(file("c.txt", "src/nested/c.txt")),
        )

        val rows = flattenWorkspaceTree(entries, expandedPaths = setOf("src", "src/nested"), childrenCache = cache)

        assertEquals(
            listOf("src" to 0, "src/nested" to 1, "src/nested/c.txt" to 2),
            rows.map { it.entry.path to it.depth },
        )
    }

    @Test
    fun `expanded directory without a cache entry yet contributes no children`() {
        val entries = listOf(dir("src", "src"))

        val rows = flattenWorkspaceTree(entries, expandedPaths = setOf("src"), childrenCache = emptyMap())

        assertEquals(listOf("src" to 0), rows.map { it.entry.path to it.depth })
    }

    @Test
    fun `export plan enumerates parents before children with correct parent paths`() {
        val listing = mapOf(
            "notes" to listOf(dir("sub", "notes/sub"), file("a.txt", "notes/a.txt")),
            "notes/sub" to listOf(file("b.txt", "notes/sub/b.txt")),
        )

        val plan = planWorkspaceFolderExport("notes", listing)

        assertEquals(
            listOf(
                Triple("notes/sub", "notes", true),
                Triple("notes/sub/b.txt", "notes/sub", false),
                Triple("notes/a.txt", "notes", false),
            ),
            plan.map { Triple(it.sourcePath, it.parentPath, it.isDirectory) },
        )
    }

    @Test
    fun `export plan for an empty folder is empty`() {
        val plan = planWorkspaceFolderExport("empty", mapOf("empty" to emptyList()))
        assertEquals(emptyList<Any>(), plan)
    }
}
