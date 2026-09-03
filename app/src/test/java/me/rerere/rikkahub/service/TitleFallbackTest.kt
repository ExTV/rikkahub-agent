package me.rerere.rikkahub.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TitleFallbackTest {
    @Test
    fun `uses first non-blank line of the first user message`() {
        val messages = listOf(
            UIMessage.user(prompt = "Hello there\nsecond line"),
        )

        assertEquals("Hello there", titleFallbackFrom(messages))
    }

    @Test
    fun `skips a leading assistant message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Hi, how can I help?"))),
            UIMessage.user(prompt = "Fix my build error"),
        )

        assertEquals("Fix my build error", titleFallbackFrom(messages))
    }

    @Test
    fun `skips blank lines to find the first non-blank one`() {
        val messages = listOf(
            UIMessage.user(prompt = "\n   \nActual first line\nmore text"),
        )

        assertEquals("Actual first line", titleFallbackFrom(messages))
    }

    @Test
    fun `collapses internal whitespace runs to one space`() {
        val messages = listOf(
            UIMessage.user(prompt = "too    many     spaces"),
        )

        assertEquals("too many spaces", titleFallbackFrom(messages))
    }

    @Test
    fun `caps the result at TITLE_FALLBACK_MAX_CHARS`() {
        val longLine = "a".repeat(TITLE_FALLBACK_MAX_CHARS + 20)
        val messages = listOf(
            UIMessage.user(prompt = longLine),
        )

        val result = titleFallbackFrom(messages)
        assertEquals(TITLE_FALLBACK_MAX_CHARS, result?.length)
        assertEquals("a".repeat(TITLE_FALLBACK_MAX_CHARS), result)
    }

    @Test
    fun `returns null when there is no user message text`() {
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("only assistant text"))),
        )

        assertNull(titleFallbackFrom(messages))
    }

    @Test
    fun `returns null when there is no user message at all`() {
        assertNull(titleFallbackFrom(emptyList()))
    }

    @Test
    fun `returns null when the user message has only blank lines`() {
        val messages = listOf(
            UIMessage.user(prompt = "   \n\t\n   "),
        )

        assertNull(titleFallbackFrom(messages))
    }

    @Test
    fun `shouldWriteTitle is true when forced regardless of stored title`() {
        assertEquals(true, shouldWriteTitle(force = true, storedTitle = "Existing title"))
        assertEquals(true, shouldWriteTitle(force = true, storedTitle = ""))
    }

    @Test
    fun `shouldWriteTitle is true when not forced but stored title is blank`() {
        assertEquals(true, shouldWriteTitle(force = false, storedTitle = ""))
        assertEquals(true, shouldWriteTitle(force = false, storedTitle = "   "))
    }

    @Test
    fun `shouldWriteTitle is false when not forced and stored title is already set`() {
        assertEquals(false, shouldWriteTitle(force = false, storedTitle = "Existing title"))
    }
}
