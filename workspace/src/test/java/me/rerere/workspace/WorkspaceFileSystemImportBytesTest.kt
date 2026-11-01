package me.rerere.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class WorkspaceFileSystemImportBytesTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fileSystem(maxImportBytes: Long) =
        WorkspaceFileSystem(WorkspaceConfig(maxImportBytes = maxImportBytes))

    @Test
    fun `importBytes rejects a stream larger than the import cap`() {
        val root = tempFolder.newFolder("workspace")
        val fs = fileSystem(maxImportBytes = 10)
        val oversized = ByteArrayInputStream(ByteArray(11) { 'a'.code.toByte() })

        assertThrows(IllegalArgumentException::class.java) {
            fs.importBytes(root, "upload.bin", oversized)
        }
    }

    @Test
    fun `importBytes deletes the partial file after rejecting an oversized stream`() {
        val root = tempFolder.newFolder("workspace")
        val fs = fileSystem(maxImportBytes = 10)
        val oversized = ByteArrayInputStream(ByteArray(11) { 'a'.code.toByte() })

        assertThrows(IllegalArgumentException::class.java) {
            fs.importBytes(root, "upload.bin", oversized)
        }

        assertFalse(File(root, "upload.bin").exists())
    }

    @Test
    fun `importBytes accepts a stream within the import cap`() {
        val root = tempFolder.newFolder("workspace")
        val fs = fileSystem(maxImportBytes = 10)
        val withinCap = ByteArrayInputStream(ByteArray(10) { 'a'.code.toByte() })

        val entry = fs.importBytes(root, "upload.bin", withinCap)

        assertEquals(10L, entry.sizeBytes)
    }

    @Test
    fun `importBytes cap is independent of the write cap`() {
        val root = tempFolder.newFolder("workspace")
        val fs = WorkspaceFileSystem(WorkspaceConfig(maxWriteBytes = 10, maxImportBytes = 100))
        val largerThanWriteCap = ByteArrayInputStream(ByteArray(11) { 'a'.code.toByte() })

        val entry = fs.importBytes(root, "upload.bin", largerThanWriteCap)

        assertEquals(11L, entry.sizeBytes)
    }
}
