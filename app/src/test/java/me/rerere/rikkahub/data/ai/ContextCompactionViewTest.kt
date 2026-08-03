package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ContextCompactionViewTest {
    @Test
    fun `compacted view preserves original nodes and replaces only model prefix`() {
        val nodes = listOf("one", "two", "three", "four").map { text ->
            MessageNode(messages = listOf(UIMessage.user(text)))
        }
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes,
        )
        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = "summary of one and two",
            tailStartNodeId = nodes[2].id,
            sourceEndNodeId = nodes[1].id,
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 100,
            createdAt = Instant.now(),
        )

        val view = ContextCompactionView.build(conversation, compaction)

        assertEquals(listOf("summary of one and two", "three", "four"), view.messages.map { it.toText() })
        assertEquals(4, conversation.messageNodes.size)
        conversation.messageNodes.zip(nodes).forEach { (actual, original) ->
            assertSame(original, actual)
        }
    }

    @Test
    fun `stale boundary falls back to complete original context`() {
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(MessageNode(messages = listOf(UIMessage.user("original")))),
        )
        val stale = ConversationCompaction(
            conversationId = conversation.id,
            summary = "stale summary",
            tailStartNodeId = Uuid.random(),
            sourceEndNodeId = Uuid.random(),
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 100,
            createdAt = Instant.now(),
        )

        val view = ContextCompactionView.build(conversation, stale)

        assertEquals(listOf("original"), view.messages.map { it.toText() })
        assertEquals(null, view.compaction)
    }

    @Test
    fun `generation result appends reply without persisting synthetic summary`() {
        val nodes = listOf("one", "two", "three", "four").map { text ->
            MessageNode(messages = listOf(UIMessage.user(text)))
        }
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes,
        )
        val view = ContextCompactionView.build(
            conversation = conversation,
            compaction = ConversationCompaction(
                conversationId = conversation.id,
                summary = "summary of one and two",
                tailStartNodeId = nodes[2].id,
                sourceEndNodeId = nodes[1].id,
                summaryModelId = Uuid.random(),
                isAuto = false,
                sourceTokenEstimate = 100,
                createdAt = Instant.now(),
            ),
        )
        val reply = UIMessage.assistant("answer")

        val merged = ContextCompactionView.mergeGeneratedMessages(
            conversation = conversation,
            view = view,
            generatedMessages = view.messages + reply,
        )

        assertEquals(listOf("one", "two", "three", "four", "answer"), merged.currentMessages.map { it.toText() })
        nodes.forEachIndexed { index, original -> assertSame(original, merged.messageNodes[index]) }
    }

    @Test
    fun `generation result can update raw tail without touching compacted prefix`() {
        val nodes = listOf("one", "two", "three").map { text ->
            MessageNode(messages = listOf(UIMessage.user(text)))
        }
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes,
        )
        val view = ContextCompactionView.build(
            conversation = conversation,
            compaction = ConversationCompaction(
                conversationId = conversation.id,
                summary = "summary",
                tailStartNodeId = nodes[2].id,
                sourceEndNodeId = nodes[1].id,
                summaryModelId = Uuid.random(),
                isAuto = true,
                sourceTokenEstimate = 100,
                createdAt = Instant.now(),
            ),
        )
        val updatedTail = nodes[2].currentMessage.copy(parts = listOf(me.rerere.ai.ui.UIMessagePart.Text("updated three")))

        val merged = ContextCompactionView.mergeGeneratedMessages(
            conversation = conversation,
            view = view,
            generatedMessages = listOf(view.messages.first(), updatedTail),
        )

        assertEquals(listOf("one", "two", "updated three"), merged.currentMessages.map { it.toText() })
        assertSame(nodes[0], merged.messageNodes[0])
        assertSame(nodes[1], merged.messageNodes[1])
    }

    @Test
    fun `message boundary compaction keeps all 55 raw tool results after summary`() {
        val userNode = MessageNode(messages = listOf(UIMessage.user("research")))
        val tools = (1..55).map { index ->
            UIMessagePart.Tool(
                toolCallId = "call-$index",
                toolName = "search-$index",
                input = "query-$index",
                output = listOf(UIMessagePart.Text("result-$index")),
            )
        }
        val toolNode = MessageNode(
            messages = listOf(UIMessage(role = me.rerere.ai.core.MessageRole.ASSISTANT, parts = tools))
        )
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(userNode, toolNode),
        )
        val report = ContextCompactionPlanner.rawContextRetentionReport(
            listOf(toolNode.currentMessage)
        )
        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = "summary of research request\n\n$report",
            tailStartNodeId = toolNode.id,
            sourceEndNodeId = userNode.id,
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 10_000,
            createdAt = Instant.now(),
        )

        val view = ContextCompactionView.build(conversation, compaction)
        val retainedTools = view.messages
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Tool>()

        assertEquals(55, retainedTools.size)
        (1..55).forEach { index ->
            val retained = retainedTools.single { it.toolCallId == "call-$index" }
            assertEquals("result-$index", (retained.output.single() as UIMessagePart.Text).text)
        }
        assertTrue(view.messages.first().toText().contains("completed_tool_calls=55"))
    }
}
