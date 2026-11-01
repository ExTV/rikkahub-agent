package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.json
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression coverage for issue #48: [GoogleStreamDecoder] must carry a Gemini thought part's
 * `thoughtSignature` onto the emitted Reasoning metadata, or `GoogleProvider.addModelMessage`'s
 * `carriedSig` forwarding has nothing to copy onto the following functionCall part, and the
 * continuation request comes back `400: Function call is missing a thought_signature`.
 */
class GoogleStreamDecoderTest {

    private fun partsEvent(vararg parts: JsonObject): SseEvent {
        val payload = buildJsonObject {
            put("candidates", buildJsonArray {
                add(buildJsonObject {
                    put("content", buildJsonObject {
                        put("parts", buildJsonArray { parts.forEach { add(it) } })
                    })
                })
            })
        }
        return SseEvent(data = json.encodeToString(payload))
    }

    private fun accept(vararg parts: JsonObject): List<StreamChunk> {
        val decoder = GoogleStreamDecoder(responseId = "resp-1", model = "gemini-2.5-pro")
        return decoder.accept(partsEvent(*parts)).chunks
    }

    @Test
    fun `thought part with signature carries thoughtSignature on reasoning metadata`() {
        val chunks = accept(
            buildJsonObject {
                put("text", "pondering")
                put("thought", true)
                put("thoughtSignature", "sig-abc")
            }
        )

        val start = chunks.filterIsInstance<StreamChunk.ReasoningStart>().single()
        val delta = chunks.filterIsInstance<StreamChunk.ReasoningDelta>().single()
        assertEquals("sig-abc", start.metadata?.let(::decodeSignature))
        assertEquals("sig-abc", delta.metadata?.let(::decodeSignature))
        assertEquals("pondering", delta.text)
    }

    @Test
    fun `thought part without signature still yields null metadata`() {
        val chunks = accept(
            buildJsonObject {
                put("text", "pondering")
                put("thought", true)
            }
        )

        val delta = chunks.filterIsInstance<StreamChunk.ReasoningDelta>().single()
        assertNull(delta.metadata)
    }

    @Test
    fun `signature-only thought part with empty text still emits reasoning chunks`() {
        val chunks = accept(
            buildJsonObject {
                put("text", "")
                put("thought", true)
                put("thoughtSignature", "sig-empty-text")
            }
        )

        val start = chunks.filterIsInstance<StreamChunk.ReasoningStart>().single()
        val delta = chunks.filterIsInstance<StreamChunk.ReasoningDelta>().single()
        assertEquals("sig-empty-text", start.metadata?.let(::decodeSignature))
        assertEquals("sig-empty-text", delta.metadata?.let(::decodeSignature))
        assertEquals("", delta.text)
    }

    private fun decodeSignature(metadata: JsonObject) =
        json.decodeFromJsonElement<GoogleThoughtMetadata>(metadata).thoughtSignature

    @Test
    fun `a signature on the thought part survives into the serialized functionCall`() {
        val decoder = GoogleStreamDecoder(responseId = "resp-e2e", model = "gemini-2.5-pro")
        val thoughtChunks = decoder.accept(
            partsEvent(
                buildJsonObject {
                    put("text", "thinking")
                    put("thought", true)
                    put("thoughtSignature", "sig-e2e")
                }
            )
        ).chunks
        val toolChunks = decoder.accept(
            partsEvent(
                buildJsonObject {
                    put("functionCall", buildJsonObject {
                        put("name", "search")
                        put("args", buildJsonObject { put("q", "x") })
                    })
                }
            )
        ).chunks

        var messages = listOf(UIMessage.user("find x"), UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
        val handler = StreamChunkHandler()
        (thoughtChunks + toolChunks).forEach { chunk -> messages = handler.handle(messages, chunk) }

        // Serialization only treats a Tool part as a functionCall once it has been executed
        // (UIMessagePart.Tool.isExecuted == output.isNotEmpty()); mirror what the app does after
        // running the tool, so this test exercises the same grouping the real continuation
        // request goes through.
        val assistant = messages.last()
        messages = messages.dropLast(1) + assistant.copy(parts = assistant.parts.map { part ->
            if (part is UIMessagePart.Tool) part.copy(output = listOf(UIMessagePart.Text("result"))) else part
        })

        val provider = GoogleProvider(OkHttpClient())
        val model = Model(modelId = "gemini-2.5-pro", displayName = "Gemini 2.5 Pro")
        val requestBody = provider.buildCompletionRequestBody(messages, TextGenerationParams(model = model))

        val functionCallParts = requestBody["contents"]!!.jsonArray
            .flatMap { it.jsonObject["parts"]!!.jsonArray }
            .filter { it.jsonObject.containsKey("functionCall") }
        val functionCall = functionCallParts.single().jsonObject
        assertEquals("sig-e2e", functionCall["thoughtSignature"]?.jsonPrimitive?.contentOrNull)
    }
}
