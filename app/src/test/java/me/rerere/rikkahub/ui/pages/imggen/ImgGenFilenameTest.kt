package me.rerere.rikkahub.ui.pages.imggen

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Coverage for [sanitizeFilenameComponent] (#39): a model display name containing a path
 * separator (e.g. an OpenRouter id like "google/gemini-2.5-flash-image-preview") must not
 * split the saved image into a subdirectory, since the DB only stores the file's last
 * path segment.
 */
class ImgGenFilenameTest {

    private lateinit var dir: File

    @Test fun `slash in name stays a single path segment`() {
        dir = Files.createTempDirectory("imggen-filename-test").toFile()
        try {
            val sanitized = sanitizeFilenameComponent("google/gemini-2.5-flash-image-preview")
            val file = File(dir, "${sanitized}.png")

            assertEquals(dir, file.parentFile)
            assertEquals("${sanitized}.png", file.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `backslash in name stays a single path segment`() {
        dir = Files.createTempDirectory("imggen-filename-test").toFile()
        try {
            val sanitized = sanitizeFilenameComponent("weird\\model\\name")
            val file = File(dir, "${sanitized}.png")

            assertEquals(dir, file.parentFile)
            assertEquals("${sanitized}.png", file.name)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `plain name is unchanged`() {
        assertEquals("gpt-image-1", sanitizeFilenameComponent("gpt-image-1"))
    }
}
