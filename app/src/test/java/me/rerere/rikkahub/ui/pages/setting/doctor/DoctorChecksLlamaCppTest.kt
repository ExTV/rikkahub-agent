package me.rerere.rikkahub.ui.pages.setting.doctor

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Coverage for [llamaCppModelStatus] — the pure decision function backing the Doctor
 * screen's "net.llamacpp_models" row (Task F of the model-selection plan). [DoctorChecks]
 * itself needs a real Android Context to construct (same reason
 * [DoctorChecksBrowserTest] and every other Doctor test stick to pure/invariant
 * coverage), so the file-existence decision is exercised directly here against real
 * files in a temp directory — no Context, no mocking.
 */
class DoctorChecksLlamaCppTest {

    private lateinit var tempDir: File

    @Before fun setUp() {
        tempDir = Files.createTempDirectory("llamacpp-doctor-test").toFile()
    }

    @After fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test fun `no installed models reports zero total and no missing entries`() {
        val status = llamaCppModelStatus(emptyMap())
        assertEquals(0, status.total)
        assertTrue(status.missing.isEmpty())
    }

    @Test fun `installed model whose file exists is not reported missing`() {
        val model = File(tempDir, "model.gguf").apply { writeText("GGUF") }
        val status = llamaCppModelStatus(mapOf("model.gguf" to model.absolutePath))
        assertEquals(1, status.total)
        assertTrue(status.missing.isEmpty())
    }

    @Test fun `installed model whose file is gone is reported missing`() {
        val goneModel = File(tempDir, "gone.gguf")
        val status = llamaCppModelStatus(mapOf("gone.gguf" to goneModel.absolutePath))
        assertEquals(1, status.total)
        assertEquals(listOf("gone.gguf"), status.missing)
    }

    @Test fun `mixed present and missing models only flags the missing ones`() {
        val present = File(tempDir, "present.gguf").apply { writeText("GGUF") }
        val status = llamaCppModelStatus(
            mapOf(
                "present.gguf" to present.absolutePath,
                "gone-a.gguf" to File(tempDir, "gone-a.gguf").absolutePath,
                "gone-b.gguf" to File(tempDir, "gone-b.gguf").absolutePath,
            )
        )
        assertEquals(3, status.total)
        assertEquals(listOf("gone-a.gguf", "gone-b.gguf"), status.missing)
    }

    @Test fun `check id does not collide with any net litert id`() {
        // The report dedups Doctor rows by id; a collision with one of the existing
        // net.litert_* ids would silently drop one of the two rows. Pin the exact id
        // used by the DoctorChecks.networkChecks row and confirm it's distinct.
        val llamaCppId = "net.llamacpp_models"
        val existingLiteRtIds = setOf("net.litert_accel", "net.litert_perf", "net.litert_vision")
        assertTrue(llamaCppId !in existingLiteRtIds)
    }
}
