package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for [selectUncachedImageUrls], the pure selection that decides whether OCR
 * has any uncached work to do (and therefore whether to flash the "Recognizing
 * image..." status at all).
 */
class OcrTransformerTest {

    private fun userMessage(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.USER,
        parts = parts.toList(),
    )

    @Test
    fun `no images returns empty`() {
        val messages = listOf(userMessage(UIMessagePart.Text("hello")))
        val result = selectUncachedImageUrls(messages) { false }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `cached-only images return empty`() {
        val messages = listOf(
            userMessage(UIMessagePart.Image("file:///a.png")),
            userMessage(UIMessagePart.Image("file:///b.png")),
        )
        val result = selectUncachedImageUrls(messages) { true }
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mixed cache state returns only misses`() {
        val messages = listOf(
            userMessage(UIMessagePart.Image("file:///cached.png")),
            userMessage(UIMessagePart.Image("file:///missing.png")),
        )
        val cached = setOf("file:///cached.png")
        val result = selectUncachedImageUrls(messages) { url -> url in cached }
        assertEquals(listOf("file:///missing.png"), result)
    }

    @Test
    fun `non-file urls are ignored`() {
        val messages = listOf(
            userMessage(UIMessagePart.Image("https://example.com/a.png")),
            userMessage(UIMessagePart.Image("file:///local.png")),
        )
        val result = selectUncachedImageUrls(messages) { false }
        assertEquals(listOf("file:///local.png"), result)
    }

    @Test
    fun `empty message list returns empty`() {
        val result = selectUncachedImageUrls(emptyList()) { false }
        assertTrue(result.isEmpty())
    }
}
