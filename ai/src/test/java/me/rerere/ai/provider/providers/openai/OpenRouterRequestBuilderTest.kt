package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.OpenRouterRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterRequestBuilderTest {
    @Test
    fun default_routing_emits_null() {
        assertNull(buildProviderObject(OpenRouterRouting(), hasToolsOrSchema = false))
    }

    @Test
    fun sort_only() {
        val o = buildProviderObject(OpenRouterRouting(sort = "throughput"), false)!!
        assertEquals("throughput", o["sort"]!!.jsonPrimitive.content)
        assertFalse(o.containsKey("allow_fallbacks"))
    }

    @Test
    fun order_emits_allow_fallbacks() {
        val o = buildProviderObject(
            OpenRouterRouting(order = listOf("anthropic"), allowFallbacks = false), false
        )!!
        assertEquals(listOf("anthropic"), o["order"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(o["allow_fallbacks"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun max_price() {
        val o = buildProviderObject(
            OpenRouterRouting(maxPricePrompt = 1.0, maxPriceCompletion = 2.0), false
        )!!
        val mp = o["max_price"]!!.jsonObject
        assertEquals(1.0, mp["prompt"]!!.jsonPrimitive.double, 0.0)
        assertEquals(2.0, mp["completion"]!!.jsonPrimitive.double, 0.0)
    }

    @Test
    fun require_parameters_forced_with_tools() {
        val o = buildProviderObject(OpenRouterRouting(), hasToolsOrSchema = true)!!
        assertTrue(o["require_parameters"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun data_collection_deny() {
        val o = buildProviderObject(OpenRouterRouting(dataCollection = "deny"), false)!!
        assertEquals("deny", o["data_collection"]!!.jsonPrimitive.content)
    }

    @Test
    fun no_fallback_models_emits_null() {
        assertNull(buildFallbackModelsArray("openai/gpt-4o", OpenRouterRouting()))
    }

    @Test
    fun fallback_models_emitted_in_order() {
        val a = buildFallbackModelsArray(
            "openai/gpt-4o",
            OpenRouterRouting(fallbackModels = listOf("anthropic/claude-sonnet-4.5", "google/gemini-2.5-pro")),
        )!!
        assertEquals(
            listOf("anthropic/claude-sonnet-4.5", "google/gemini-2.5-pro"),
            a.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun fallback_models_drop_primary_blanks_and_duplicates() {
        val a = buildFallbackModelsArray(
            "openai/gpt-4o",
            OpenRouterRouting(
                fallbackModels = listOf(" openai/gpt-4o ", "", "anthropic/claude-sonnet-4.5", "anthropic/claude-sonnet-4.5"),
            ),
        )!!
        assertEquals(listOf("anthropic/claude-sonnet-4.5"), a.map { it.jsonPrimitive.content })
    }

    @Test
    fun fallback_models_all_filtered_emits_null() {
        assertNull(
            buildFallbackModelsArray(
                "openai/gpt-4o",
                OpenRouterRouting(fallbackModels = listOf("openai/gpt-4o", " ")),
            ),
        )
    }

    @Test
    fun fallback_models_do_not_trigger_provider_object() {
        assertNull(
            buildProviderObject(
                OpenRouterRouting(fallbackModels = listOf("anthropic/claude-sonnet-4.5")),
                hasToolsOrSchema = false,
            ),
        )
    }
}
