package me.rerere.rikkahub.data.api

import kotlinx.serialization.decodeFromString
import me.rerere.rikkahub.data.model.HfModelDetail
import me.rerere.rikkahub.data.model.HfSibling
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [HuggingFaceModelSearch.toFilesResult], the pure gating/filtering decision split out
 * of [HuggingFaceModelSearch.listGgufFiles] specifically so it needs no network call to test,
 * and the wire-format decoding of HuggingFace's `gated` field, verified against the live
 * `/api/models/{repo}` response on 2026-08-03 (see HfGatedSerializer's doc).
 */
class HuggingFaceModelSearchTest {

    @Test
    fun `a public repo's siblings are filtered down to gguf files with a known size`() {
        val detail = HfModelDetail(
            id = "Qwen/Qwen3-4B-GGUF",
            gated = false,
            private = false,
            siblings = listOf(
                HfSibling(".gitattributes", size = 1798),
                HfSibling("LICENSE", size = 11544),
                HfSibling("Qwen3-4B-Q4_K_M.gguf", size = 2497280256),
                HfSibling("Qwen3-4B-Q8_0.gguf", size = 4211308416),
                HfSibling("README.md", size = 100),
            ),
        )

        val result = HuggingFaceModelSearch.toFilesResult(detail)

        assertTrue(result is HuggingFaceModelSearch.FilesResult.Files)
        val files = (result as HuggingFaceModelSearch.FilesResult.Files).entries
        assertEquals(
            listOf(
                HuggingFaceModelSearch.GgufFileEntry("Qwen3-4B-Q4_K_M.gguf", 2497280256),
                HuggingFaceModelSearch.GgufFileEntry("Qwen3-4B-Q8_0.gguf", 4211308416),
            ),
            files,
        )
    }

    @Test
    fun `a gated repo is RequiresAccess regardless of its siblings`() {
        val detail = HfModelDetail(
            id = "meta-llama/Llama-3.1-8B-Instruct-GGUF",
            gated = true,
            private = false,
            siblings = listOf(HfSibling("model.gguf", size = 1_000_000)),
        )

        assertEquals(HuggingFaceModelSearch.FilesResult.RequiresAccess, HuggingFaceModelSearch.toFilesResult(detail))
    }

    @Test
    fun `a private repo is RequiresAccess even when not gated`() {
        val detail = HfModelDetail(
            id = "someone/private-repo",
            gated = false,
            private = true,
            siblings = listOf(HfSibling("model.gguf", size = 1_000_000)),
        )

        assertEquals(HuggingFaceModelSearch.FilesResult.RequiresAccess, HuggingFaceModelSearch.toFilesResult(detail))
    }

    @Test
    fun `a sibling without a size is dropped rather than reported with a bogus size`() {
        val detail = HfModelDetail(
            id = "some/repo",
            siblings = listOf(HfSibling("model.gguf", size = null)),
        )

        val result = HuggingFaceModelSearch.toFilesResult(detail) as HuggingFaceModelSearch.FilesResult.Files
        assertEquals(emptyList<HuggingFaceModelSearch.GgufFileEntry>(), result.entries)
    }

    @Test
    fun `a repo with no gguf files at all reports an empty Files result, not RequiresAccess`() {
        val detail = HfModelDetail(
            id = "some/repo",
            siblings = listOf(HfSibling("README.md", size = 100)),
        )

        assertEquals(
            HuggingFaceModelSearch.FilesResult.Files(emptyList()),
            HuggingFaceModelSearch.toFilesResult(detail),
        )
    }

    @Test
    fun `resolveUrl builds the same resolve-main URL shape ModelInstall already normalises to`() {
        assertEquals(
            "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            HuggingFaceModelSearch.resolveUrl("Qwen/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf"),
        )
    }

    @Test
    fun `gated decodes the literal JSON boolean false as public`() {
        val detail = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"Qwen/Qwen3-4B-GGUF","gated":false,"private":false,"siblings":[]}""",
        )
        assertEquals(false, detail.gated)
    }

    @Test
    fun `gated decodes the HuggingFace gate-kind strings as gated`() {
        val manual = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"x/y","gated":"manual","private":false,"siblings":[]}""",
        )
        val auto = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"x/y","gated":"auto","private":false,"siblings":[]}""",
        )
        assertEquals(true, manual.gated)
        assertEquals(true, auto.gated)
    }

    @Test
    fun `size is null when blobs were not requested, matching the un-parameterised endpoint`() {
        val detail = JsonInstant.decodeFromString<HfModelDetail>(
            """{"id":"x/y","gated":false,"private":false,"siblings":[{"rfilename":"model.gguf"}]}""",
        )
        assertEquals(listOf(HfSibling("model.gguf", size = null)), detail.siblings)
    }
}
