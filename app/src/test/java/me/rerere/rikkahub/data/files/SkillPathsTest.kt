package me.rerere.rikkahub.data.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SkillPathsTest {
    @Test
    fun `parse supports CRLF frontmatter`() {
        val content = "---\r\nname: test-skill\r\ndescription: test\r\n---\r\n\r\nbody"

        val frontmatter = SkillFrontmatterParser.parse(content)

        assertEquals("test-skill", frontmatter["name"])
        assertEquals("test", frontmatter["description"])
        assertEquals("body", SkillFrontmatterParser.extractBody(content))
    }

    @Test
    fun `parse supports YAML syntax`() {
        val content = """
            ---
            name: test-skill
            description: |
              A multiline description
              with a colon: supported
            metadata:
              author: tester
            ---
            body
        """.trimIndent()

        val frontmatter = SkillFrontmatterParser.parse(content)

        assertEquals("test-skill", frontmatter["name"])
        assertEquals(
            "A multiline description\nwith a colon: supported\n",
            frontmatter["description"],
        )
    }

    @Test
    fun `parse returns empty frontmatter for malformed YAML`() {
        val content = "---\nname: [invalid\n---\nbody"

        val frontmatter = SkillFrontmatterParser.parse(content)

        assertNull(frontmatter["name"])
        assertEquals("body", SkillFrontmatterParser.extractBody(content))
    }

    @Test
    fun `resolve skill dir rejects traversal and nested names`() {
        val skillsRoot = Files.createTempDirectory("skills-root").toFile()

        try {
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "../upload"))
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "foo/bar"))
            assertNull(SkillPaths.resolveSkillDir(skillsRoot, "foo\\bar"))
            assertNotNull(SkillPaths.resolveSkillDir(skillsRoot, "valid-skill"))
        } finally {
            skillsRoot.deleteRecursively()
        }
    }

    @Test
    fun `resolve skill file rejects sibling prefix escape`() {
        val skillsRoot = Files.createTempDirectory("skills-root").toFile()
        val skillDir = File(skillsRoot, "foo").apply { mkdirs() }
        File(skillsRoot, "foobar").apply { mkdirs() }

        try {
            val safeFile = SkillPaths.resolveSkillFile(skillDir, "notes.md")
            val escapedFile = SkillPaths.resolveSkillFile(skillDir, "../foobar/secret.md")

            assertEquals(File(skillDir, "notes.md").canonicalFile, safeFile)
            assertNull(escapedFile)
        } finally {
            skillsRoot.deleteRecursively()
        }
    }

    // Backup-restore reuses resolveSkillFile to guard the upload folder against zip-slip:
    // a malicious backup entry like "upload/../../databases/rikka_hub.db" must not escape.
    @Test
    fun `resolve file rejects absolute and deep traversal (upload-restore zip-slip)`() {
        val root = Files.createTempDirectory("upload-root").toFile()
        val uploadDir = File(root, "upload").apply { mkdirs() }

        try {
            assertNotNull(SkillPaths.resolveSkillFile(uploadDir, "photo.png"))
            assertNull(SkillPaths.resolveSkillFile(uploadDir, "../../databases/rikka_hub.db"))
            assertNull(SkillPaths.resolveSkillFile(uploadDir, "/data/local/tmp/evil"))
        } finally {
            root.deleteRecursively()
        }
    }

    // Backup-restore reuses resolveSkillFile to guard the images folder against zip-slip too
    // (issue #39): a malicious backup entry like "images/../../databases/rikka_hub.db" must
    // not escape, mirroring the upload-restore guard above.
    @Test
    fun `resolve file rejects absolute and deep traversal (images-restore zip-slip)`() {
        val root = Files.createTempDirectory("images-root").toFile()
        val imagesDir = File(root, "images").apply { mkdirs() }

        try {
            val safeName = "images/foo.png".substringAfter("images/")
            val traversalName = "images/../../databases/rikka_hub.db".substringAfter("images/")

            assertEquals(File(imagesDir, "foo.png").canonicalFile, SkillPaths.resolveSkillFile(imagesDir, safeName))
            assertNull(SkillPaths.resolveSkillFile(imagesDir, traversalName))
            assertNull(SkillPaths.resolveSkillFile(imagesDir, "/data/local/tmp/evil"))
        } finally {
            root.deleteRecursively()
        }
    }
}
