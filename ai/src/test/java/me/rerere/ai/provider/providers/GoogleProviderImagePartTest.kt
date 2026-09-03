package me.rerere.ai.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers issue #37: GoogleProvider.parseMessagePart used to emit an inlineData image part's `url`
 * as the bare base64 payload, with no `data:<mime>;base64,` prefix. Every other producer and
 * consumer in this codebase (Base64ImageToLocalFileTransformer, FileEncoder.encodeBase64, ...)
 * assumes that prefix, so a natively generated image was never persisted to disk and was silently
 * dropped from every later request once `encodeBase64` started throwing on it.
 */
class GoogleProviderImagePartTest {

    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    private fun invokeParseMessagePart(json: String): UIMessagePart? {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "parseMessagePart",
            JsonObject::class.java,
            Int::class.javaPrimitiveType
        )
        method.isAccessible = true
        return method.invoke(provider, Json.parseToJsonElement(json).jsonObject, 0) as UIMessagePart?
    }

    private fun invokeBuildContents(messages: List<UIMessage>): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildContents",
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, messages) as JsonArray
    }

    @Test
    fun `an inlineData part parses into an Image with a proper data URL prefix`() {
        val payload = "aGVsbG8="
        val image = invokeParseMessagePart(
            """{"inlineData":{"mimeType":"image/png","data":"$payload"}}"""
        ) as? UIMessagePart.Image
        requireNotNull(image) { "expected an Image part" }

        assertTrue(
            "url must start with a proper data URL prefix, not the bare payload",
            image.url.startsWith("data:image/png;base64,")
        )
        assertEquals(payload, image.url.substringAfter(","))
    }

    @Test
    fun `a generated image round trips back to the same inlineData payload on the next turn`() {
        val payload = "aGVsbG8="
        val image = invokeParseMessagePart(
            """{"inlineData":{"mimeType":"image/png","data":"$payload"}}"""
        ) as UIMessagePart.Image

        val messages = listOf(UIMessage(role = MessageRole.USER, parts = listOf(image)))
        val result = invokeBuildContents(messages)

        assertEquals(1, result.size)
        val parts = result[0].jsonObject["parts"]!!.jsonArray
        val inlineData = parts.single().jsonObject["inlineData"]!!.jsonObject
        assertEquals("later turns must re-encode the same payload, not drop it", payload, inlineData["data"]?.jsonPrimitive?.content)
        assertEquals("image/png", inlineData["mimeType"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a user message whose only part is an unencodable image produces no contents entry`() {
        val message = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Image(url = "content://not-a-supported-scheme"))
        )

        val result = invokeBuildContents(listOf(message))

        assertEquals(
            "a turn with no parts must never be sent, not sent as an empty \"parts\" array",
            0,
            result.size
        )
    }

    @Test
    fun `a thought inline image still becomes a Reasoning part`() {
        val part = invokeParseMessagePart(
            """{"inlineData":{"mimeType":"image/png","data":"aGVsbG8="},"thought":true}"""
        )

        assertTrue("draft images must still become Reasoning, not Image", part is UIMessagePart.Reasoning)
    }
}
