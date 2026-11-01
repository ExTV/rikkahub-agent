package me.rerere.locallm.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity checks for the curated catalog. These are cheap invariants that would otherwise
 * only surface as a failed multi-gigabyte download or a model that loads with the wrong
 * sampler on a user's device.
 */
class LiteRtCatalogTest {

    @Test
    fun `every catalog entry has curated config defaults, not the unknown-file fallback`() {
        LiteRtCatalog.ENTRIES.forEach { entry ->
            val config = entry.config()
            assertEquals(
                "${entry.displayName} is missing a LiteRtModelDefaults entry",
                entry.modelFile,
                config.modelFile,
            )
            assertTrue(
                "${entry.displayName} fell through to the fallback config",
                config.sizeBytes > 0L,
            )
        }
    }

    @Test
    fun `catalog and config agree on size and memory requirement`() {
        LiteRtCatalog.ENTRIES.forEach { entry ->
            val config = entry.config()
            assertEquals(
                "${entry.displayName}: size differs between catalog and config",
                entry.sizeBytes,
                config.sizeBytes,
            )
            assertEquals(
                "${entry.displayName}: minDeviceMemoryGb differs between catalog and config",
                entry.minDeviceMemoryGb,
                config.minDeviceMemoryGb,
            )
        }
    }

    @Test
    fun `model files are unique so lookups cannot be ambiguous`() {
        val files = LiteRtCatalog.ENTRIES.map { it.modelFile }
        assertEquals(files.size, files.toSet().size)
    }

    @Test
    fun `download urls are https resolve urls ending in litertlm`() {
        LiteRtCatalog.ENTRIES.forEach { entry ->
            val url = entry.resolveUrl()
            assertTrue(url, url.startsWith("https://huggingface.co/"))
            assertTrue(url, "/resolve/main/" in url)
            assertTrue(url, url.endsWith(".litertlm"))
        }
    }

    @Test
    fun `every entry advertises tool support, which the agent loop depends on`() {
        LiteRtCatalog.ENTRIES.forEach { entry ->
            assertTrue(
                "${entry.displayName} is in the catalog but not tagged as tool-capable",
                "tools" in entry.tags,
            )
            assertTrue(
                "${entry.displayName} must derive the TOOL ability",
                me.rerere.ai.provider.ModelAbility.TOOL in
                    LiteRtModelMetadata.deriveCapabilities(entry.modelFile).abilities,
            )
        }
    }

    @Test
    fun `a max-tokens default never exceeds the file's baked context ceiling`() {
        LiteRtCatalog.ENTRIES.forEach { entry ->
            val config = entry.config()
            config.maxContextLength?.let { ceiling ->
                assertTrue(
                    "${entry.displayName}: maxTokens ${config.maxTokens} exceeds ceiling $ceiling",
                    config.maxTokens <= ceiling,
                )
            }
        }
    }

    @Test
    fun `display name falls back to a readable form for a pasted model`() {
        assertEquals("Gemma 4 E2B", LiteRtCatalog.displayNameFor("gemma-4-E2B-it.litertlm"))
        assertEquals("some-random-model", LiteRtCatalog.displayNameFor("some-random-model.litertlm"))
    }

    @Test
    fun `context ceiling is recovered from an ekv filename marker`() {
        assertEquals(
            4096,
            LiteRtModelDefaults.contextCeilingFromFileName(
                "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"
            ),
        )
        assertEquals(
            1280,
            LiteRtModelDefaults.contextCeilingFromFileName("qwen3_0.6b_q4_block32_ekv1280.litertlm"),
        )
        assertNull(LiteRtModelDefaults.contextCeilingFromFileName("gemma-4-E2B-it.litertlm"))
    }

    @Test
    fun `an unknown file inherits the ceiling encoded in its name`() {
        val config = LiteRtModelDefaults.forModelFile("mystery-model_q8_ekv2048.litertlm")
        assertEquals(2048, config.maxContextLength)
        assertTrue(config.maxTokens <= 2048)
        assertNotNull(config.modelFile)
    }
}
