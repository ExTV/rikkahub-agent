package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ContextCompactionPresentation
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class AutoCompactionMessageGroupTest {
    @Test
    fun `compaction card and continuation render as one assistant message`() {
        val user = MessageNode(messages = listOf(UIMessage.user("question")))
        val firstAssistant = UIMessage.assistant("I will inspect it.")
        val firstAssistantNode = MessageNode(messages = listOf(firstAssistant))
        val compaction = Conversation(
            assistantId = Uuid.random(),
            messageNodes = listOf(user, firstAssistantNode),
        ).let { conversation ->
            ConversationCompaction(
                conversationId = conversation.id,
                summary = "compressed history",
                tailStartNodeId = null,
                sourceEndNodeId = firstAssistantNode.id,
                summaryModelId = Uuid.random(),
                isAuto = true,
                sourceTokenEstimate = 10_000,
                createdAt = Instant.now(),
            )
        }
        val firstWithCard = firstAssistantNode.copy(
            messages = listOf(
                firstAssistant.copy(
                    parts = firstAssistant.parts + ContextCompactionPresentation.createTool(compaction),
                )
            ),
        )
        val continuation = MessageNode(messages = listOf(UIMessage.assistant("Here is the answer.")))

        val groups = listOf(user, firstWithCard, continuation).groupAutomaticCompactionMessages()

        assertEquals(2, groups.size)
        assertEquals(listOf(user.id), groups.first().nodes.map { it.id })
        assertEquals(listOf(firstWithCard.id, continuation.id), groups.last().nodes.map { it.id })
        assertEquals(
            listOf("I will inspect it.", "Here is the answer."),
            groups.last().displayMessage.parts
                .filterIsInstance<UIMessagePart.Text>()
                .map { it.text },
        )
    }

    @Test
    fun `ordinary adjacent assistant messages remain separate`() {
        val first = MessageNode(messages = listOf(UIMessage.assistant("one")))
        val second = MessageNode(messages = listOf(UIMessage.assistant("two")))

        val groups = listOf(first, second).groupAutomaticCompactionMessages()

        assertEquals(2, groups.size)
    }

    @Test
    fun `repeated automatic compactions remain one visual assistant message`() {
        val firstMessage = UIMessage.assistant("first")
        val first = MessageNode(
            messages = listOf(firstMessage.copy(
                parts = firstMessage.parts + presentationTool(),
            )),
        )
        val secondMessage = UIMessage.assistant("second")
        val second = MessageNode(
            messages = listOf(secondMessage.copy(
                parts = secondMessage.parts + presentationTool(),
            )),
        )
        val final = MessageNode(messages = listOf(UIMessage.assistant("final")))

        val groups = listOf(first, second, final).groupAutomaticCompactionMessages()

        assertEquals(1, groups.size)
        assertEquals(listOf(first.id, second.id, final.id), groups.single().nodes.map { it.id })
    }

    private fun presentationTool() = ContextCompactionPresentation.createTool(
        ConversationCompaction(
            conversationId = Uuid.random(),
            summary = "compressed history",
            tailStartNodeId = null,
            sourceEndNodeId = Uuid.random(),
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 10_000,
            createdAt = Instant.now(),
        )
    )
}
