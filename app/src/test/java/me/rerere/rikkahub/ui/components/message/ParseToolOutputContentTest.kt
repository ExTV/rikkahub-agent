package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [parseToolOutputContent] — regression coverage for #93: a truncated
 * (or otherwise non-JSON) tool result must not collapse into an empty [kotlinx.serialization.json.JsonObject],
 * which used to make bespoke card renderers (e.g. search) read "no results" and show
 * an empty card instead of falling back to the raw-text preview.
 */
class ParseToolOutputContentTest {

    private fun toolWithOutput(text: String) = UIMessagePart.Tool(
        toolCallId = "call-1",
        toolName = "search_web",
        input = "{}",
        output = listOf(UIMessagePart.Text(text)),
    )

    @Test
    fun `not-yet-executed tool has no content`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "search_web",
            input = "{}",
            output = emptyList(),
        )

        assertNull(parseToolOutputContent(tool))
    }

    @Test
    fun `non-JSON output such as a truncation notice parses to null instead of an empty object`() {
        val truncationNotice =
            "[Tool output truncated: 45000 characters total] search returned 10 results... <preview>"
        val tool = toolWithOutput(truncationNotice)

        // Must be null (routes to the raw/default preview), not an empty JsonObject that
        // would make SearchWebToolUI.items() silently read "[]" from nothing.
        assertNull(parseToolOutputContent(tool))
    }

    @Test
    fun `valid-JSON search output still parses with its items intact`() {
        val json = """{"answer":"summary","items":[{"url":"https://a.example","title":"A"},{"url":"https://b.example","title":"B"}]}"""
        val tool = toolWithOutput(json)

        val content = parseToolOutputContent(tool)
        assertTrue(content != null)
        val obj = content!!.jsonObjectOrNull
        assertTrue(obj != null)
        assertEquals("summary", obj!!["answer"]?.jsonPrimitive?.content)
        assertEquals(2, obj["items"]?.jsonArray?.size)
    }

    @Test
    fun `the truncation notice text itself is untouched by parsing`() {
        val truncationNotice = "[Tool output truncated: 45000 characters total] <preview>"
        val tool = toolWithOutput(truncationNotice)

        parseToolOutputContent(tool)

        // parseToolOutputContent must not mutate tool.output - the raw notice text the
        // default preview renders from has to survive verbatim.
        val rawText = tool.output.filterIsInstance<UIMessagePart.Text>().single().text
        assertEquals(truncationNotice, rawText)
    }
}
