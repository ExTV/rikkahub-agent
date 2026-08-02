package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.toMessageNode

data class CompactedMessageView(
    val messages: List<UIMessage>,
    val compaction: ConversationCompaction?,
    val rawTailStartIndex: Int,
    /** Present only for the request that created a new automatic compaction. */
    val newlyCreatedAutoCompaction: ConversationCompaction? = null,
)

object ContextCompactionView {
    fun build(
        conversation: Conversation,
        compaction: ConversationCompaction?,
    ): CompactedMessageView {
        if (compaction == null) return rawView(conversation)

        val sourceEndIndex = conversation.messageNodes.indexOfFirst {
            it.id == compaction.sourceEndNodeId
        }
        val tailStartIndex = compaction.tailStartNodeId?.let { tailStartNodeId ->
            conversation.messageNodes.indexOfFirst { it.id == tailStartNodeId }
        } ?: (sourceEndIndex + 1)
        if (
            sourceEndIndex < 0 ||
            tailStartIndex != sourceEndIndex + 1 ||
            tailStartIndex !in 0..conversation.messageNodes.size
        ) {
            return rawView(conversation)
        }

        return CompactedMessageView(
            messages = ContextCompactionPresentation.stripDisplayTools(
                listOf(UIMessage.user(compaction.summary)) +
                    conversation.currentMessages.drop(tailStartIndex),
            ),
            compaction = compaction,
            rawTailStartIndex = tailStartIndex,
        )
    }

    /**
     * Merges a generation result produced from a compacted context back into the complete
     * conversation. The synthetic summary is request-only and must never become a message node.
     */
    fun mergeGeneratedMessages(
        conversation: Conversation,
        view: CompactedMessageView,
        generatedMessages: List<UIMessage>,
    ): Conversation {
        require(view.compaction != null) { "A compacted view is required" }

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
                nodes += message.toMessageNode()
            }
        }

        return conversation.copy(messageNodes = nodes)
    }

    private fun rawView(conversation: Conversation) = CompactedMessageView(
        messages = ContextCompactionPresentation.stripDisplayTools(conversation.currentMessages),
        compaction = null,
        rawTailStartIndex = 0,
    )
}
