package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers `mapAvailableModels`'s parsing of a Cloud Code Assist `fetchAvailableModels` response
 * body into the [Model] list the provider offers: the `isInternal` and `gemini-3.1-pro-high`
 * denial filters, and the `supportsImages` / `supportsThinking` / `displayName` mapping. It is a
 * file-private top-level function, so reflection targets the `GeminiProviderKt` facade class like
 * [GeminiProviderRequestTest].
 */
class GeminiProviderAvailableModelsTest {

    private val geminiProviderKt = Class.forName("me.rerere.rikkahub.data.gemini.GeminiProviderKt")
    private val json = Json { ignoreUnknownKeys = true }

    private fun mapModels(body: String): List<Model> {
        val method = geminiProviderKt.getDeclaredMethod(
            "mapAvailableModels",
            String::class.java,
            Json::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, body, json) as List<Model>
    }

    @Test
    fun `gemini-3-1-pro-high is filtered out but the other two models pass through`() {
        val body = """
            {
              "models": {
                "gemini-3.1-pro-high": {"displayName": "Gemini 3.1 Pro (High)"},
                "gemini-3.1-pro-low": {"displayName": "Gemini 3.1 Pro (Low)"},
                "gemini-claude-opus-4-6-thinking": {"displayName": "Claude Opus 4.6 Thinking"}
              }
            }
        """.trimIndent()
        val models = mapModels(body)
        assertEquals(
            setOf("gemini-3.1-pro-low", "gemini-claude-opus-4-6-thinking"),
            models.map { it.modelId }.toSet(),
        )
    }

    @Test
    fun `an isInternal entry is filtered out`() {
        val body = """
            {
              "models": {
                "gemini-internal-test": {"displayName": "Internal", "isInternal": true},
                "gemini-3.1-pro-low": {"displayName": "Gemini 3.1 Pro (Low)"}
              }
            }
        """.trimIndent()
        val models = mapModels(body)
        assertEquals(listOf("gemini-3.1-pro-low"), models.map { it.modelId })
    }

    @Test
    fun `an empty models object returns an empty list`() {
        assertTrue(mapModels("""{"models": {}}""").isEmpty())
    }

    @Test
    fun `an absent models object returns an empty list`() {
        assertTrue(mapModels("""{}""").isEmpty())
    }

    @Test
    fun `supportsImages true adds IMAGE to inputModalities`() {
        val body = """{"models": {"m": {"displayName": "M", "supportsImages": true}}}"""
        val model = mapModels(body).single()
        assertEquals(listOf(Modality.TEXT, Modality.IMAGE), model.inputModalities)
    }

    @Test
    fun `supportsImages false or absent leaves inputModalities as text only`() {
        val body = """{"models": {"m": {"displayName": "M", "supportsImages": false}}}"""
        val model = mapModels(body).single()
        assertEquals(listOf(Modality.TEXT), model.inputModalities)
    }

    @Test
    fun `supportsThinking true adds REASONING alongside the always-present TOOL ability`() {
        val body = """{"models": {"m": {"displayName": "M", "supportsThinking": true}}}"""
        val model = mapModels(body).single()
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), model.abilities)
    }

    @Test
    fun `supportsThinking false or absent leaves only the TOOL ability`() {
        val body = """{"models": {"m": {"displayName": "M", "supportsThinking": false}}}"""
        val model = mapModels(body).single()
        assertEquals(listOf(ModelAbility.TOOL), model.abilities)
    }

    @Test
    fun `a missing displayName falls back to the model id`() {
        val body = """{"models": {"my-model-id": {}}}"""
        val model = mapModels(body).single()
        assertEquals("my-model-id", model.displayName)
    }

    @Test
    fun `supportsThinking true on a GPT-OSS model does not add REASONING`() {
        val body = """{"models": {"gemini-gpt-oss-120b": {"displayName": "GPT-OSS 120B", "supportsThinking": true}}}"""
        val model = mapModels(body).single()
        assertEquals(listOf(ModelAbility.TOOL), model.abilities)
    }

    @Test
    fun `GPT-OSS matching in the ability mapping is case-insensitive`() {
        val body = """{"models": {"GPT-OSS-20B": {"displayName": "GPT-OSS 20B", "supportsThinking": true}}}"""
        val model = mapModels(body).single()
        assertEquals(listOf(ModelAbility.TOOL), model.abilities)
    }

    @Test
    fun `a non GPT-OSS model with supportsThinking true still gets REASONING`() {
        val body = """{"models": {"gemini-claude-opus-4-6-thinking": {"displayName": "Claude Opus 4.6 Thinking", "supportsThinking": true}}}"""
        val model = mapModels(body).single()
        assertEquals(listOf(ModelAbility.TOOL, ModelAbility.REASONING), model.abilities)
    }
}
