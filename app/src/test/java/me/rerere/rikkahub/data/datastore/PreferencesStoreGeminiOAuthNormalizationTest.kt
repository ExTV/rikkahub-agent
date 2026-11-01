package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Model
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Covers `dropDeniedGeminiOAuthModels`, the `models` transform the GeminiOAuth branch of the
 * settings-load normalization pass applies. Task E (#26): the fetch-side deny-list only stops
 * `gemini-3.1-pro-high` being re-added by the merge in `mergeCodexModels`; a copy already
 * persisted before that filter existed survives untouched otherwise, so this evicts it
 * read-side on every load. It is a file-private top-level function, so reflection targets the
 * `PreferencesStoreKt` facade class, the same pattern `GeminiProviderAvailableModelsTest` uses
 * for `mapAvailableModels`.
 */
class PreferencesStoreGeminiOAuthNormalizationTest {

    private val preferencesStoreKt = Class.forName("me.rerere.rikkahub.data.datastore.PreferencesStoreKt")

    private fun dropDenied(models: List<Model>): List<Model> {
        val method = preferencesStoreKt.getDeclaredMethod("dropDeniedGeminiOAuthModels", List::class.java)
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return method.invoke(null, models) as List<Model>
    }

    @Test
    fun `a persisted gemini-3-1-pro-high entry is dropped but other models pass through`() {
        val low = Model(modelId = "gemini-3.1-pro-low", displayName = "Gemini 3.1 Pro (Low)")
        val denied = Model(modelId = "gemini-3.1-pro-high", displayName = "Gemini 3.1 Pro (High)")
        val other = Model(modelId = "gemini-claude-opus-4-6-thinking", displayName = "Claude Opus 4.6 Thinking")

        val result = dropDenied(listOf(low, denied, other))

        assertEquals(setOf("gemini-3.1-pro-low", "gemini-claude-opus-4-6-thinking"), result.map { it.modelId }.toSet())
    }

    @Test
    fun `a list without the denied model is returned unchanged`() {
        val low = Model(modelId = "gemini-3.1-pro-low", displayName = "Gemini 3.1 Pro (Low)")
        val other = Model(modelId = "gemini-claude-opus-4-6-thinking", displayName = "Claude Opus 4.6 Thinking")

        val result = dropDenied(listOf(low, other))

        assertEquals(listOf(low, other), result)
    }

    @Test
    fun `the existing distinctBy id de-duplication still applies`() {
        val sharedId = Uuid.random()
        val first = Model(id = sharedId, modelId = "gemini-3.1-pro-low", displayName = "First")
        val duplicate = Model(id = sharedId, modelId = "gemini-3.1-pro-low", displayName = "Duplicate")

        val result = dropDenied(listOf(first, duplicate))

        assertEquals(listOf(first), result)
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<Model>(), dropDenied(emptyList()))
    }
}
