package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `build falls back to sourceEndIndex plus one when tailStartNodeId is missing`() {
        val nodes = listOf("one", "two", "three").map { text ->
            MessageNode(messages = listOf(UIMessage.user(text)))
        }
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes,
        )
        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = "summary of one",
            tailStartNodeId = Uuid.random(), // not present in the conversation
            sourceEndNodeId = nodes[0].id,
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 100,
            createdAt = Instant.now(),
        )

        val view = ContextCompactionView.build(conversation, compaction)

        assertEquals(compaction, view.compaction)
        assertEquals(1, view.rawTailStartIndex)
        assertEquals(listOf("summary of one", "two", "three"), view.messages.map { it.toText() })
    }

    @Test
    fun `compactedPrefixUnchanged is true for mutations confined to the raw tail`() {
        val nodes = listOf("one", "two", "three").map { text ->
            MessageNode(messages = listOf(UIMessage.user(text)))
        }
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes,
        )
        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = "summary",
            tailStartNodeId = nodes[1].id,
            sourceEndNodeId = nodes[0].id,
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 100,
            createdAt = Instant.now(),
        )

        // tail node edited: a new selected message on nodes[1]
        val tailEdited = nodes.toMutableList().apply {
            this[1] = nodes[1].copy(
                messages = nodes[1].messages + UIMessage.user("two edited"),
                selectIndex = 1,
            )
        }
        assertTrue(ContextCompactionView.compactedPrefixUnchanged(compaction, nodes, tailEdited))

        // tail node deleted
        val tailDeleted = listOf(nodes[0], nodes[2])
        assertTrue(ContextCompactionView.compactedPrefixUnchanged(compaction, nodes, tailDeleted))

        // nodes appended
        val appended = nodes + MessageNode(messages = listOf(UIMessage.user("four")))
        assertTrue(ContextCompactionView.compactedPrefixUnchanged(compaction, nodes, appended))
    }

    @Test
    fun `buildForRange clips the tail and keeps the summary at index 0`() {
        val nodes = listOf("one", "two", "three", "four", "five").map { text ->
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

        // Regenerating node index 3 ("four"): the range should stop before it.
        val view = ContextCompactionView.buildForRange(conversation, compaction, endExclusive = 3)

        assertEquals(2, view!!.rawTailStartIndex)
        assertEquals(listOf("summary of one and two", "three"), view.messages.map { it.toText() })
    }

    @Test
    fun `buildForRange returns null when the compaction does not resolve`() {
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

        assertEquals(null, ContextCompactionView.buildForRange(conversation, stale, endExclusive = 1))
        assertEquals(null, ContextCompactionView.buildForRange(conversation, null, endExclusive = 1))
    }

    @Test
    fun `buildForRange returns null when endExclusive is inside the compacted prefix`() {
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

        // endExclusive = 1 lands inside the compacted prefix (rawTailStartIndex is 2).
        assertEquals(null, ContextCompactionView.buildForRange(conversation, compaction, endExclusive = 1))
    }

    @Test
    fun `merging a regenerate result adds the reply as a variant of the boundary node`() {
        val nodes = listOf("one", "two", "three", "four", "five").map { text ->
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
        // endExclusive = 3 regenerates node 3 ("four"): context is [summary, three].
        val view = ContextCompactionView.buildForRange(conversation, compaction, endExclusive = 3)!!
        val reply = UIMessage.assistant("regenerated four")

        val merged = ContextCompactionView.mergeGeneratedMessages(
            conversation = conversation,
            view = view,
            generatedMessages = view.messages + reply,
        )

        // The regenerated reply lands as a new variant on node 3, not appended at the end.
        assertEquals(5, merged.messageNodes.size)
        val regeneratedNode = merged.messageNodes[3]
        assertEquals(listOf("four", "regenerated four"), regeneratedNode.messages.map { it.toText() })
        assertEquals(1, regeneratedNode.selectIndex)
        // The context node and the node after are untouched.
        assertSame(nodes[2], merged.messageNodes[2])
        assertSame(nodes[4], merged.messageNodes[4])
        // The synthetic summary never becomes a node.
        assertTrue(merged.messageNodes.none { node -> node.messages.any { it.id == view.messages.first().id } })
    }

    @Test
    fun `two build calls on the same compaction produce identical summary message identity`() {
        val nodes = listOf("one", "two", "three").map { text ->
            MessageNode(messages = listOf(UIMessage.user(text)))
        }
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes,
        )
        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = "summary",
            tailStartNodeId = nodes[1].id,
            sourceEndNodeId = nodes[0].id,
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 100,
            createdAt = Instant.now(),
        )

        val first = ContextCompactionView.build(conversation, compaction).messages.first()
        val second = ContextCompactionView.build(conversation, compaction).messages.first()

        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
    }

    @Test
    fun `compactedPrefixUnchanged is false when the compacted prefix itself changes`() {
        val nodes = listOf("zero", "one", "two", "three").map { text ->
            MessageNode(messages = listOf(UIMessage.user(text)))
        }
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes,
        )
        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = "summary",
            tailStartNodeId = nodes[2].id,
            sourceEndNodeId = nodes[1].id,
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 100,
            createdAt = Instant.now(),
        )

        // prefix node's selected message changes (source-end node itself)
        val prefixEdited = nodes.toMutableList().apply {
            this[1] = nodes[1].copy(
                messages = nodes[1].messages + UIMessage.user("one edited"),
                selectIndex = 1,
            )
        }
        assertFalse(ContextCompactionView.compactedPrefixUnchanged(compaction, nodes, prefixEdited))

        // a prefix node other than the source-end node is deleted
        val prefixDeleted = listOf(nodes[0], nodes[1], nodes[2], nodes[3]).filterNot { it.id == nodes[0].id }
        assertFalse(ContextCompactionView.compactedPrefixUnchanged(compaction, nodes, prefixDeleted))

        // source-end node absent from after
        val sourceEndGone = listOf(nodes[0], nodes[2], nodes[3])
        assertFalse(ContextCompactionView.compactedPrefixUnchanged(compaction, nodes, sourceEndGone))
    }

    @Test
    fun `mergeGeneratedMessages matches the old nested-scan algorithm on a large history`() {
        val nodeCount = 3000
        val tailSize = 300
        val nodes = (0 until nodeCount).map { i ->
            MessageNode(messages = listOf(UIMessage.user("node-$i")))
        }
        val conversation = Conversation(
            assistantId = Uuid.random(),
            messageNodes = nodes,
        )
        val tailStartIndex = nodeCount - tailSize
        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = "summary of large history",
            tailStartNodeId = nodes[tailStartIndex].id,
            sourceEndNodeId = nodes[tailStartIndex - 1].id,
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 100_000,
            createdAt = Instant.now(),
        )
        val view = ContextCompactionView.build(conversation, compaction)
        assertEquals(tailSize + 1, view.messages.size)

        // Update case: a few tail messages come back with different content but the same id.
        val updateGenerated = view.messages.mapIndexed { index, message ->
            if (index == 1 || index == tailSize / 2 || index == tailSize) {
                message.copy(parts = listOf(UIMessagePart.Text(message.toText() + " updated")))
            } else {
                message
            }
        }
        assertMergeMatchesOldAlgorithm(conversation, view, updateGenerated)

        // Append case: the unchanged tail plus a brand new assistant reply.
        val appendGenerated = view.messages + UIMessage.assistant("brand new reply")
        assertMergeMatchesOldAlgorithm(conversation, view, appendGenerated)

        // Regenerate-boundary case: regenerate a node in the middle of the tail.
        val regenerateEndExclusive = tailStartIndex + tailSize / 2
        val regenerateView = ContextCompactionView.buildForRange(conversation, compaction, regenerateEndExclusive)!!
        val regenerateGenerated = regenerateView.messages + UIMessage.assistant("regenerated reply")
        assertMergeMatchesOldAlgorithm(conversation, regenerateView, regenerateGenerated)
    }

    private fun assertMergeMatchesOldAlgorithm(
        conversation: Conversation,
        view: CompactedMessageView,
        generatedMessages: List<UIMessage>,
    ) {
        val expected = oldMergeGeneratedMessages(conversation, view, generatedMessages)
        val actual = ContextCompactionView.mergeGeneratedMessages(conversation, view, generatedMessages)
        // Compare messages + selectIndex per node, not MessageNode.id: a freshly appended node's
        // id is Uuid.random() (see toMessageNode()), so two independently computed appends -
        // even from the exact same algorithm - never share an id. That randomness is orthogonal
        // to whether the merge logic itself is equivalent.
        assertEquals(
            expected.messageNodes.map { it.messages to it.selectIndex },
            actual.messageNodes.map { it.messages to it.selectIndex },
        )
    }

    /**
     * Reference copy of the nested per-message scan `mergeGeneratedMessages` used before issue
     * #109's HashMap lookup, kept here only to prove the replacement is behaviorally identical.
     */
    private fun oldMergeGeneratedMessages(
        conversation: Conversation,
        view: CompactedMessageView,
        generatedMessages: List<UIMessage>,
    ): Conversation {
        val inputSize = view.messages.size
        val nodes = conversation.messageNodes.toMutableList()
        generatedMessages.forEachIndexed { index, message ->
            val nodeIndex = nodes.indexOfFirst { node ->
                node.messages.any { it.id == message.id }
            }
            if (nodeIndex >= 0) {
                val node = nodes[nodeIndex]
                val messageIndex = node.messages.indexOfFirst { it.id == message.id }
                val replacement = ContextCompactionPresentation.preserveDisplayTools(
                    previous = node.messages[messageIndex],
                    replacement = message,
                )
                if (node.messages[messageIndex] != replacement) {
                    nodes[nodeIndex] = node.copy(
                        messages = node.messages.toMutableList().apply {
                            this[messageIndex] = replacement
                        },
                    )
                }
            } else if (index >= inputSize) {
                val boundaryNodeIndex = view.rawTailStartIndex + index - 1
                if (boundaryNodeIndex <= nodes.lastIndex) {
                    val node = nodes[boundaryNodeIndex]
                    val newMessages = node.messages + message
                    nodes[boundaryNodeIndex] = node.copy(
                        messages = newMessages,
                        selectIndex = newMessages.lastIndex,
                    )
                } else {
                    nodes += message.toMessageNode()
                }
            }
        }

        return conversation.copy(messageNodes = nodes)
    }
}
