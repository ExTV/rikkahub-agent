package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ContextCompactionPresentationTest {
    @Test
    fun `display-only compaction tool is retained in chat but removed from request view`() {
        val assistant = UIMessage.assistant("tool completed")
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(assistant))),
        )
        val presentationTool = ContextCompactionPresentation.createTool(
            compaction = sampleCompaction(conversation),
        )

        val displayedConversation = ContextCompactionPresentation.attachToMessage(
            conversation = conversation,
            messageId = assistant.id,
            tool = presentationTool,
        )
        val displayedMessage = displayedConversation.currentMessages.single()
        assertTrue(ContextCompactionPresentation.hasDisplayTool(displayedMessage))

        val requestView = ContextCompactionView.build(displayedConversation, compaction = null)
        assertFalse(ContextCompactionPresentation.hasDisplayTool(requestView.messages.single()))
        assertEquals("tool completed", requestView.messages.single().toText())
    }

    @Test
    fun `stream updates preserve an attached compaction tool`() {
        val history = MessageNode(messages = listOf(UIMessage.user("earlier context")))
        val assistant = UIMessage.assistant("tool completed")
        val assistantNode = MessageNode(messages = listOf(assistant))
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(history, assistantNode),
        )
        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = "compressed context",
            tailStartNodeId = assistantNode.id,
            sourceEndNodeId = history.id,
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 12_345,
            createdAt = Instant.now(),
        )
        val withCard = ContextCompactionPresentation.attachToMessage(
            conversation = conversation,
            messageId = assistant.id,
            tool = ContextCompactionPresentation.createTool(compaction),
        )
        val requestView = ContextCompactionView.build(withCard, compaction)
        val streamedUpdate = assistant.copy(parts = listOf(UIMessagePart.Text("tool completed with final state")))

        val merged = ContextCompactionView.mergeGeneratedMessages(
            conversation = withCard,
            view = requestView,
            generatedMessages = listOf(requestView.messages.first(), streamedUpdate),
        )

        assertEquals(
            listOf("tool completed with final state"),
            merged.currentMessages.last().parts
                .filterIsInstance<UIMessagePart.Text>()
                .map { it.text },
        )
        assertTrue(ContextCompactionPresentation.hasDisplayTool(merged.currentMessages.last()))
    }

    @Test
    fun `stream updates keep compaction card before parts emitted afterwards`() {
        val initial = UIMessage(
            role = me.rerere.ai.core.MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("search results"),
                UIMessagePart.Tool(
                    toolCallId = "search-before-compaction",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("first result")),
                ),
            ),
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(initial))),
        )
        val card = ContextCompactionPresentation.createTool(sampleCompaction(conversation))
        val withCard = ContextCompactionPresentation.attachToMessage(
            conversation = conversation,
            messageId = initial.id,
            tool = card,
        )
        val replacement = initial.copy(
            parts = initial.parts + UIMessagePart.Tool(
                toolCallId = "search-after-compaction",
                toolName = "web_fetch",
                input = "{}",
                output = listOf(UIMessagePart.Text("second result")),
            ),
        )

        val restored = ContextCompactionPresentation.preserveDisplayTools(
            previous = withCard.currentMessages.single(),
            replacement = replacement,
        )

        assertEquals(
            listOf(
                "search_web",
                ContextCompactionPresentation.TOOL_NAME,
                "web_fetch",
            ),
            restored.parts.filterIsInstance<UIMessagePart.Tool>().map { it.toolName },
        )
    }

    @Test
    fun `compaction card reports retained raw tool count`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user("earlier context")))),
        )
        val compaction = sampleCompaction(conversation).copy(
            summary = "compressed context\n\n" +
                "[Raw context retained verbatim after this summary]\n" +
                "raw_messages=1\ncompleted_tool_calls=55\n" +
                "[End raw context retention report]"
        )

        val tool = ContextCompactionPresentation.createTool(compaction)

        assertTrue(tool.input.contains("\"retained_raw_tool_calls\":55"))
        assertTrue((tool.output.single() as UIMessagePart.Text).text.contains("completed_tool_calls=55"))
    }

    private fun sampleCompaction(conversation: Conversation) = ConversationCompaction(
        conversationId = conversation.id,
        summary = "compressed context",
        tailStartNodeId = null,
        sourceEndNodeId = conversation.messageNodes.single().id,
        summaryModelId = Uuid.random(),
        isAuto = true,
        sourceTokenEstimate = 12_345,
        createdAt = Instant.now(),
    )
}
