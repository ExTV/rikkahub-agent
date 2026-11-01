package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterImagesApiTest {

    // ---- request body building ----
    // /models' supported_parameters describes the chat-completions path, not /images (live
    // data: FLUX.2 lists only ["seed"], gpt-image lists chat params, none list "n" or
    // "aspect_ratio"), so the body never gates on it: "n" is never sent (count > 1 is
    // sequential calls instead - see collectSequentialImages), aspect_ratio and
    // input_references are always sent when the caller has them.

    @Test
    fun request_body_never_sends_n() {
        val model = Model(modelId = "black-forest-labs/flux.2", supportedParameters = listOf("seed"))
        val body = buildOpenRouterImagesRequestBody(model, "a cat", aspectRatio = ImageAspectRatio.SQUARE)
        assertFalse(body.containsKey("n"))
    }

    @Test
    fun request_body_always_sends_aspect_ratio() {
        val model = Model(modelId = "x/y", supportedParameters = listOf("seed"))
        val landscape = buildOpenRouterImagesRequestBody(model, "p", ImageAspectRatio.LANDSCAPE)
        val portrait = buildOpenRouterImagesRequestBody(model, "p", ImageAspectRatio.PORTRAIT)
        val square = buildOpenRouterImagesRequestBody(model, "p", ImageAspectRatio.SQUARE)
        assertEquals("16:9", landscape["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("9:16", portrait["aspect_ratio"]!!.jsonPrimitive.content)
        assertEquals("1:1", square["aspect_ratio"]!!.jsonPrimitive.content)
    }

    @Test
    fun request_body_always_sends_aspect_ratio_even_when_supported_parameters_is_empty() {
        val model = Model(modelId = "unknown/model")
        val body = buildOpenRouterImagesRequestBody(model, "p", ImageAspectRatio.LANDSCAPE)
        assertEquals("16:9", body["aspect_ratio"]!!.jsonPrimitive.content)
    }

    @Test
    fun request_body_includes_input_references_when_present() {
        // supportedParameters lacks "input_references" entirely (as real /models data does)
        // and it's still sent - its presence is the edit request itself.
        val model = Model(modelId = "x/y", supportedParameters = listOf("seed"))
        val body = buildOpenRouterImagesRequestBody(
            model, "edit me", ImageAspectRatio.SQUARE,
            inputReferences = listOf("data:image/png;base64,AAA"),
        )
        val refs = body["input_references"]!!.jsonArray
        assertEquals(1, refs.size)
        val first = refs[0].jsonObject
        assertEquals("image_url", first["type"]!!.jsonPrimitive.content)
        assertEquals("data:image/png;base64,AAA", first["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test
    fun request_body_omits_input_references_when_absent() {
        val model = Model(modelId = "x/y")
        val body = buildOpenRouterImagesRequestBody(model, "p", ImageAspectRatio.SQUARE)
        assertFalse(body.containsKey("input_references"))
    }

    // ---- sequential-call count emulation (collectSequentialImages) ----

    @Test
    fun collect_sequential_runs_block_count_times() = runBlocking {
        var calls = 0
        val result = collectSequentialImages(3) {
            calls++
            listOf("img$calls")
        }
        assertEquals(3, calls)
        assertEquals(listOf("img1", "img2", "img3"), result)
    }

    @Test
    fun collect_sequential_returns_partial_results_on_later_failure() = runBlocking {
        var calls = 0
        val result = collectSequentialImages(3) {
            calls++
            if (calls == 2) error("boom")
            listOf("img$calls")
        }
        assertEquals(listOf("img1"), result)
    }

    @Test
    fun collect_sequential_throws_when_first_call_fails() = runBlocking {
        var threw = false
        try {
            collectSequentialImages<String>(3) { error("boom") }
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun collect_sequential_of_one_runs_once() = runBlocking {
        var calls = 0
        val result = collectSequentialImages(1) {
            calls++
            listOf("img")
        }
        assertEquals(1, calls)
        assertEquals(listOf("img"), result)
    }

    // ---- response parsing ----

    @Test
    fun parses_png_and_jpeg_media_types() {
        val bodyStr = """
            {"created": 1, "data": [
              {"b64_json": "AAA", "media_type": "image/png"},
              {"b64_json": "BBB", "media_type": "image/jpeg"}
            ]}
        """.trimIndent()
        val items = parseOpenRouterImagesResponse(bodyStr)
        assertEquals(2, items.size)
        assertEquals("AAA", items[0].data)
        assertEquals("image/png", items[0].mimeType)
        assertEquals("BBB", items[1].data)
        assertEquals("image/jpeg", items[1].mimeType)
    }

    @Test
    fun skips_svg_items_but_keeps_others() {
        val bodyStr = """
            {"data": [
              {"b64_json": "AAA", "media_type": "image/svg+xml"},
              {"b64_json": "BBB", "media_type": "image/webp"}
            ]}
        """.trimIndent()
        val items = parseOpenRouterImagesResponse(bodyStr)
        assertEquals(1, items.size)
        assertEquals("image/webp", items[0].mimeType)
    }

    @Test
    fun all_svg_items_raises_clear_error() {
        val bodyStr = """{"data": [{"b64_json": "AAA", "media_type": "image/svg+xml"}]}"""
        val ex = try {
            parseOpenRouterImagesResponse(bodyStr)
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(ex != null && ex.message!!.contains("SVG"))
    }

    // ---- 404 fallback decision ----

    @Test
    fun fallback_triggered_on_405_regardless_of_body() {
        assertTrue(shouldFallbackToChatCompletionsImage(405, ""))
        assertTrue(shouldFallbackToChatCompletionsImage(405, """{"error":{"message":"nope"}}"""))
    }

    @Test
    fun fallback_triggered_on_404_with_non_json_error_body() {
        // Historical endpoint-missing case: a proxy without /images returns HTML or nothing.
        assertTrue(shouldFallbackToChatCompletionsImage(404, "<html>Not Found</html>"))
        assertTrue(shouldFallbackToChatCompletionsImage(404, ""))
        assertTrue(shouldFallbackToChatCompletionsImage(404, """{"no_error_field": true}"""))
    }

    @Test
    fun fallback_not_triggered_on_404_with_json_error_body() {
        // Real OpenRouter "model not found" - must be surfaced, not swallowed.
        assertFalse(
            shouldFallbackToChatCompletionsImage(404, """{"error":{"message":"model not found"}}""")
        )
    }

    @Test
    fun fallback_not_triggered_on_other_codes() {
        assertFalse(shouldFallbackToChatCompletionsImage(400, ""))
        assertFalse(shouldFallbackToChatCompletionsImage(401, ""))
        assertFalse(shouldFallbackToChatCompletionsImage(500, ""))
        assertFalse(shouldFallbackToChatCompletionsImage(200, ""))
    }

    @Test
    fun error_message_extracts_message_field() {
        val msg = extractOpenRouterErrorMessage(400, """{"error": {"message": "bad prompt"}}""")
        assertTrue(msg.contains("bad prompt"))
        assertTrue(msg.contains("400"))
    }

    @Test
    fun error_message_falls_back_to_raw_body() {
        val msg = extractOpenRouterErrorMessage(500, "not json")
        assertTrue(msg.contains("not json"))
    }

    // ---- vector-model exclusion ----

    @Test
    fun vector_model_id_suffix_is_svg_only() {
        assertTrue(isSvgOnlyImageModel("recraft/recraft-v3-vector"))
        assertTrue(isSvgOnlyImageModel("recraft/RECRAFT-V3-VECTOR"))
        assertFalse(isSvgOnlyImageModel("recraft/recraft-v3"))
    }

    @Test
    fun output_format_enum_of_only_svg_is_svg_only() {
        assertTrue(isSvgOnlyImageModel("some/model", outputFormats = listOf("svg")))
        assertFalse(isSvgOnlyImageModel("some/model", outputFormats = listOf("svg", "png")))
        assertFalse(isSvgOnlyImageModel("some/model", outputFormats = emptyList()))
    }

    @Test
    fun openRouterModelFromJson_excludes_vector_model_from_image_type() {
        val json = """
            {"id":"recraft/recraft-v3-vector","name":"Recraft V3 Vector",
             "architecture":{"input_modalities":["text"],"output_modalities":["image","text"]},
             "supported_parameters":["output_format"]}
        """.trimIndent()
        val m = openRouterModelFromJson(kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject)!!
        assertTrue(m.type != me.rerere.ai.provider.ModelType.IMAGE)
    }

    @Test
    fun openRouterModelFromJson_keeps_non_vector_image_model_as_image_type() {
        val json = """
            {"id":"black-forest-labs/flux.2","name":"FLUX.2",
             "architecture":{"input_modalities":["text"],"output_modalities":["image"]},
             "supported_parameters":["n","aspect_ratio"]}
        """.trimIndent()
        val m = openRouterModelFromJson(kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject)!!
        assertEquals(me.rerere.ai.provider.ModelType.IMAGE, m.type)
    }

    // ---- chat-completions fallback body (used by both generate and edit) ----

    @Test
    fun chat_completions_body_is_plain_string_content_without_references() {
        val body = buildOpenRouterChatCompletionsImageBody("x/y", "a cat", ImageAspectRatio.SQUARE)
        val message = body["messages"]!!.jsonArray[0].jsonObject
        assertEquals("a cat", message["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun chat_completions_body_uses_content_parts_with_references() {
        val body = buildOpenRouterChatCompletionsImageBody(
            "x/y", "edit me", ImageAspectRatio.SQUARE,
            inputReferences = listOf("data:image/png;base64,AAA"),
        )
        val message = body["messages"]!!.jsonArray[0].jsonObject
        val parts = message["content"]!!.jsonArray
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun chat_completions_response_parses_data_uri_images() {
        val bodyStr = """
            {"choices": [{"message": {"images": [
              {"image_url": {"url": "data:image/png;base64,AAA"}}
            ]}}]}
        """.trimIndent()
        val items = parseOpenRouterChatCompletionsImageResponse(bodyStr)
        assertEquals(1, items.size)
        assertEquals("image/png", items[0].mimeType)
        assertEquals("AAA", items[0].data)
    }
}
