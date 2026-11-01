package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the CCA request post-processing helpers in [GeminiProvider]: Code Assist fronts
 * Anthropic models that reject a thinking budget under 1024, so `raiseThinkingBudgetToClaudeFloor`
 * must run - and be seen by `raiseMaxTokensAboveThinkingBudget` - before the request is sent. It
 * also fronts GPT-OSS models, which reject any `thinkingConfig` at all, so
 * `stripThinkingConfigForGptOss` must run before both of those. All three are file-private
 * top-level functions, so reflection targets the `GeminiProviderKt` facade class Kotlin generates
 * for a file's top-level declarations.
 */
class GeminiProviderRequestTest {

    private val geminiProviderKt = Class.forName("me.rerere.rikkahub.data.gemini.GeminiProviderKt")

    private fun invokeRaiseThinkingBudget(request: JsonObject): JsonObject {
        val method = geminiProviderKt.getDeclaredMethod(
            "raiseThinkingBudgetToClaudeFloor",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(null, request) as JsonObject
    }

    private fun invokeRaiseMaxTokens(request: JsonObject): JsonObject {
        val method = geminiProviderKt.getDeclaredMethod(
            "raiseMaxTokensAboveThinkingBudget",
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(null, request) as JsonObject
    }

    private fun invokeStripThinkingConfigForGptOss(modelId: String, request: JsonObject): JsonObject {
        val method = geminiProviderKt.getDeclaredMethod(
            "stripThinkingConfigForGptOss",
            String::class.java,
            JsonObject::class.java
        )
        method.isAccessible = true
        return method.invoke(null, modelId, request) as JsonObject
    }

    private fun requestWithBudget(budget: Int): JsonObject = buildJsonObject {
        put("generationConfig", buildJsonObject {
            put("thinkingConfig", buildJsonObject {
                put("thinkingBudget", budget)
            })
        })
    }

    private fun budgetOf(request: JsonObject): Int? =
        request["generationConfig"]?.jsonObject
            ?.get("thinkingConfig")?.jsonObject
            ?.get("thinkingBudget")?.jsonPrimitive?.intOrNull

    @Test
    fun `a budget below Claude's floor is raised to 1024`() {
        val raised = invokeRaiseThinkingBudget(requestWithBudget(1000))
        assertEquals(1024, budgetOf(raised))
    }

    @Test
    fun `a budget of 0 (reasoning off) is left untouched`() {
        val raised = invokeRaiseThinkingBudget(requestWithBudget(0))
        assertEquals(0, budgetOf(raised))
    }

    @Test
    fun `a budget already above the floor is left untouched`() {
        val raised = invokeRaiseThinkingBudget(requestWithBudget(8000))
        assertEquals(8000, budgetOf(raised))
    }

    @Test
    fun `the clamp runs before the max-tokens raise so the raise sees 1024, not 1000`() {
        val clamped = invokeRaiseThinkingBudget(requestWithBudget(1000))
        val finalRequest = invokeRaiseMaxTokens(clamped)
        val maxTokens = finalRequest["generationConfig"]?.jsonObject
            ?.get("maxOutputTokens")?.jsonPrimitive?.intOrNull
        // 1024 (clamped budget) + THINKING_ANSWER_HEADROOM (8192); if the clamp ran after this
        // raise instead of before it, the result would be 1000 + 8192 = 9192.
        assertEquals(1024 + 8192, maxTokens)
    }

    // --- GPT-OSS thinkingConfig stripping ---------------------------------------------------

    @Test
    fun `a GPT-OSS request has no thinkingConfig after full post-processing`() {
        val stripped = invokeStripThinkingConfigForGptOss("gemini-gpt-oss-120b", requestWithBudget(8000))
        val finalRequest = invokeRaiseMaxTokens(invokeRaiseThinkingBudget(stripped))
        assertNull(finalRequest["generationConfig"]?.jsonObject?.get("thinkingConfig"))
    }

    @Test
    fun `GPT-OSS matching is case-insensitive`() {
        val stripped = invokeStripThinkingConfigForGptOss("GPT-OSS-20B", requestWithBudget(8000))
        assertNull(stripped["generationConfig"]?.jsonObject?.get("thinkingConfig"))
    }

    @Test
    fun `a Claude request keeps its thinkingConfig untouched`() {
        val request = requestWithBudget(8000)
        val stripped = invokeStripThinkingConfigForGptOss("gemini-claude-opus-4-6-thinking", request)
        assertEquals(8000, budgetOf(stripped))
    }
}
