package me.rerere.rikkahub.data.gemini

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.GoogleThoughtMetadata
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessageChoice
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for issue #48: [GeminiStreamChunkAdapter] must forward a Tool part's
 * metadata onto the emitted [StreamChunk.ToolCallStart]/[StreamChunk.ToolCallDelta], or a
 * `thoughtSignature` that Gemini attached directly to the functionCall part (rather than to a
 * preceding thought part) is dropped, and the continuation request comes back
 * `400: Function call is missing a thought_signature`.
 */
class GeminiStreamChunkAdapterTest {

    @Test
    fun `tool part metadata is forwarded to ToolCallStart and ToolCallDelta`() {
        val metadata = GoogleThoughtMetadata(thoughtSignature = "sig-xyz").toMetadata()
        val chunk = MessageChunk(
            id = "resp-1",
            model = "gemini-2.5-pro",
            choices = listOf(
                UIMessageChoice(
                    index = 0,
                    delta = UIMessage(
                        role = MessageRole.ASSISTANT,
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = "tool-1",
                                toolName = "search",
                                input = "{}",
                                metadata = metadata,
                            )
                        ),
                    ),
                    message = null,
                    finishReason = null,
                )
            ),
        )

        val out = GeminiStreamChunkAdapter().translate(chunk)

        val start = out.filterIsInstance<StreamChunk.ToolCallStart>().single()
        val delta = out.filterIsInstance<StreamChunk.ToolCallDelta>().single()
        assertEquals(metadata, start.metadata)
        assertEquals(metadata, delta.metadata)
    }
}
