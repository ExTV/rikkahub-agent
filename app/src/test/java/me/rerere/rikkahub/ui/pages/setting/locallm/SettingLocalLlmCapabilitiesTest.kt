package me.rerere.rikkahub.ui.pages.setting.locallm

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.litert.LiteRtModelMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the runtime-aware `deriveLocalModelCapabilities` fix: before this, every call site
 * routed unconditionally through `LiteRtModelMetadata.deriveCapabilities`, which would stamp
 * a GGUF with LiteRT-derived capabilities (and could grant IMAGE input, which llama.cpp never
 * supports in this build).
 */
class SettingLocalLlmCapabilitiesTest {

    @Test
    fun `llama cpp catalog file gets TOOL and REASONING from its tags`() {
        val caps = deriveLocalModelCapabilities(LocalRuntime.LlamaCpp, "Qwen3-4B-Q4_K_M.gguf")

        assertEquals(listOf(Modality.TEXT), caps.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), caps.abilities)
    }

    @Test
    fun `llama cpp file outside the catalog still gets TOOL, never REASONING`() {
        val caps = deriveLocalModelCapabilities(LocalRuntime.LlamaCpp, "some-manual-model.gguf")

        assertEquals(listOf(Modality.TEXT), caps.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL), caps.abilities)
    }

    @Test
    fun `llama cpp never derives IMAGE input, unlike LiteRT`() {
        val caps = deriveLocalModelCapabilities(LocalRuntime.LlamaCpp, "Qwen3-0.6B-Q8_0.gguf")

        assertFalse(Modality.IMAGE in caps.inputModalities)
    }

    @Test
    fun `LiteRT branch is unchanged, still routes through LiteRtModelMetadata`() {
        val direct = LiteRtModelMetadata.deriveCapabilities("some-litert-file.litertlm")
        val viaHelper = deriveLocalModelCapabilities(LocalRuntime.LiteRT, "some-litert-file.litertlm")

        assertEquals(direct, viaHelper)
    }

    @Test
    fun `enableAfterFirstDownload enables a llama cpp provider, not just LiteRT`() {
        val enabled = enableAfterFirstDownload(ProviderSetting.LlamaCppLocal(enabled = false))

        assertTrue((enabled as ProviderSetting.LlamaCppLocal).enabled)
    }

    @Test
    fun `enableAfterFirstDownload still enables a LiteRT provider`() {
        val enabled = enableAfterFirstDownload(ProviderSetting.LiteRtLocal(enabled = false))

        assertTrue((enabled as ProviderSetting.LiteRtLocal).enabled)
    }

    @Test
    fun `enableAfterFirstDownload leaves an unrelated provider untouched`() {
        val original = ProviderSetting.OpenAI(enabled = false)

        assertEquals(original, enableAfterFirstDownload(original))
    }

    @Test
    fun `registerInstalledModel appends when no existing model shares the modelId`() {
        val provider = ProviderSetting.LlamaCppLocal()
        val model = Model(modelId = "Qwen3-0.6B-Q8_0.gguf", displayName = "Qwen3-0.6B-Q8_0.gguf")

        val result = registerInstalledModel(provider, model) as ProviderSetting.LlamaCppLocal

        assertEquals(listOf(model), result.models)
    }

    @Test
    fun `registerInstalledModel updates the existing entry instead of appending a duplicate`() {
        val original = Model(modelId = "Qwen3-0.6B-Q8_0.gguf", displayName = "old name")
        val provider = ProviderSetting.LlamaCppLocal(models = listOf(original))
        // Re-installing the same file (e.g. a re-download) derives a fresh Model with a new
        // random id, matching what collectInstallProgress builds on every Done event.
        val reinstalled = Model(modelId = "Qwen3-0.6B-Q8_0.gguf", displayName = "Qwen3-0.6B-Q8_0.gguf")

        val result = registerInstalledModel(provider, reinstalled) as ProviderSetting.LlamaCppLocal

        // Exactly one entry survives, carrying the ORIGINAL model's id (so a subsequent
        // id-keyed delete still finds it) but the freshly derived fields.
        assertEquals(1, result.models.size)
        assertEquals(original.id, result.models.single().id)
        assertEquals("Qwen3-0.6B-Q8_0.gguf", result.models.single().displayName)
    }

    @Test
    fun `registerInstalledModel leaves other installed models on the provider untouched`() {
        val other = Model(modelId = "other.gguf", displayName = "other.gguf")
        val target = Model(modelId = "target.gguf", displayName = "old")
        val provider = ProviderSetting.LlamaCppLocal(models = listOf(other, target))
        val reinstalled = Model(modelId = "target.gguf", displayName = "target.gguf")

        val result = registerInstalledModel(provider, reinstalled) as ProviderSetting.LlamaCppLocal

        assertEquals(2, result.models.size)
        assertTrue(result.models.any { it.id == other.id && it.displayName == "other.gguf" })
    }
}
