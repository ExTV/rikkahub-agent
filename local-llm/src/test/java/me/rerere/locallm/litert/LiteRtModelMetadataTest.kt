package me.rerere.locallm.litert

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Test

class LiteRtModelMetadataTest {

    @Test
    fun `Gemma-4-E2B-it derives multimodal + thinking + tool (Gallery-parity)`() {
        val caps = LiteRtModelMetadata.deriveCapabilities("gemma-4-E2B-it.litertlm")
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            caps.inputModalities,
        )
        assertEquals(
            setOf(ModelAbility.TOOL, ModelAbility.REASONING),
            caps.abilities.toSet(),
        )
    }

    @Test
    fun `Gemma-4-E4B-it derives multimodal + thinking + tool (Gallery-parity)`() {
        val caps = LiteRtModelMetadata.deriveCapabilities("gemma-4-E4B-it.litertlm")
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            caps.inputModalities,
        )
        assertEquals(
            setOf(ModelAbility.TOOL, ModelAbility.REASONING),
            caps.abilities.toSet(),
        )
    }

    @Test
    fun `Qwen2_5-1_5B-Instruct derives text + tool only`() {
        val caps = LiteRtModelMetadata.deriveCapabilities(
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        )
        assertEquals(listOf(Modality.TEXT), caps.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL), caps.abilities)
    }

    @Test
    fun `Qwen3 derives text + thinking + tool`() {
        val caps = LiteRtModelMetadata.deriveCapabilities("qwen3_4b_mixed_int4.litertlm")
        assertEquals(listOf(Modality.TEXT), caps.inputModalities)
        assertEquals(
            setOf(ModelAbility.TOOL, ModelAbility.REASONING),
            caps.abilities.toSet(),
        )
    }

    @Test
    fun `Phi-4-mini derives text + tool only`() {
        val caps = LiteRtModelMetadata.deriveCapabilities(
            "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
        )
        assertEquals(listOf(Modality.TEXT), caps.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL), caps.abilities)
    }

    @Test
    fun `unknown HF-URL-pasted model falls back to text + tool`() {
        val caps = LiteRtModelMetadata.deriveCapabilities("some-random-model-i-pasted.litertlm")
        assertEquals(listOf(Modality.TEXT), caps.inputModalities)
        assertEquals(listOf(ModelAbility.TOOL), caps.abilities)
    }

    @Test
    fun `merge preserves user-set abilities and modalities, only adds catalog ones`() {
        val current = LiteRtModelMetadata.Capabilities(
            inputModalities = listOf(Modality.TEXT),
            abilities = emptyList(),
        )
        val target = LiteRtModelMetadata.Capabilities(
            inputModalities = listOf(Modality.TEXT, Modality.IMAGE),
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        )
        val merged = LiteRtModelMetadata.mergeAdditive(current, target)
        assertEquals(
            listOf(Modality.TEXT, Modality.IMAGE),
            merged.inputModalities,
        )
        assertEquals(
            setOf(ModelAbility.TOOL, ModelAbility.REASONING),
            merged.abilities.toSet(),
        )
    }

    @Test
    fun `merge does NOT remove abilities the user kept`() {
        val current = LiteRtModelMetadata.Capabilities(
            inputModalities = listOf(Modality.TEXT),
            abilities = listOf(ModelAbility.TOOL, ModelAbility.REASONING),
        )
        val target = LiteRtModelMetadata.Capabilities(
            inputModalities = listOf(Modality.TEXT),
            abilities = listOf(ModelAbility.TOOL),
        )
        val merged = LiteRtModelMetadata.mergeAdditive(current, target)
        assertEquals(
            setOf(ModelAbility.TOOL, ModelAbility.REASONING),
            merged.abilities.toSet(),
        )
    }
}
