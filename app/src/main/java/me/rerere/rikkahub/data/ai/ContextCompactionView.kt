package me.rerere.rikkahub.data.ai

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.data.model.toMessageNode
import kotlin.time.toKotlinInstant
import kotlin.uuid.Uuid

data class CompactedMessageView(
    val messages: List<UIMessage>,
    val compaction: ConversationCompaction?,
    val rawTailStartIndex: Int,
    /** Present only for the request that created a new automatic compaction. */
    val newlyCreatedAutoCompaction: ConversationCompaction? = null,
)

object ContextCompactionView {
    /**
     * Builds the synthetic summary message with an identity stable across every request built
     * from the same [compaction], instead of the fresh random id / wall-clock createdAt that
     * `UIMessage.user()` defaults to. `TimeReminderTransformer` injects a time reminder before
     * the first user message of a request; with a per-request identity here, that first message
     * - and so the whole request prefix - would change on every request, defeating provider
     * prompt caching after compaction (RC3).
     */
    internal fun summaryMessage(compaction: ConversationCompaction): UIMessage =
        UIMessage.user(compaction.summary).copy(
            createdAt = compaction.createdAt.toKotlinInstant()
                .toLocalDateTime(TimeZone.currentSystemDefault()),
            // sourceEndNodeId is a MessageNode id, never a message id, so reusing it as this
            // synthetic message's id cannot collide with a real message's id.
            id = compaction.sourceEndNodeId,
        )

    fun build(
        conversation: Conversation,
        compaction: ConversationCompaction?,
    ): CompactedMessageView {
        if (compaction == null) return rawView(conversation)

        val sourceEndIndex = conversation.messageNodes.indexOfFirst {
            it.id == compaction.sourceEndNodeId
        }
        val resolvedTailStartIndex = compaction.tailStartNodeId?.let { tailStartNodeId ->
            conversation.messageNodes.indexOfFirst { it.id == tailStartNodeId }
        }
        // A non-null tailStartNodeId that no longer resolves (its node was deleted) falls back
        // to sourceEndIndex + 1, same as a null tailStartNodeId: the next node simply becomes
        // the tail start rather than invalidating the whole compaction.
        val tailStartIndex = resolvedTailStartIndex?.takeIf { it >= 0 } ?: (sourceEndIndex + 1)
        if (
            sourceEndIndex < 0 ||
            tailStartIndex != sourceEndIndex + 1 ||
            tailStartIndex !in 0..conversation.messageNodes.size
        ) {
            return rawView(conversation)
        }

        return CompactedMessageView(
            messages = ContextCompactionPresentation.stripDisplayTools(
                listOf(summaryMessage(compaction)) +
                    conversation.currentMessages.drop(tailStartIndex),
            ),
            compaction = compaction,
            rawTailStartIndex = tailStartIndex,
        )
    }

    /**
     * Request view for regenerating at [endExclusive] (an index into [Conversation.messageNodes],
     * exclusive). Returns null when the stored compaction does not apply to that range, in which
     * case the caller keeps its raw behaviour.
     */
    fun buildForRange(
        conversation: Conversation,
        compaction: ConversationCompaction?,
        endExclusive: Int,
    ): CompactedMessageView? {
        val view = build(conversation, compaction)
        if (view.compaction == null) return null
        // rawTailStartIndex > endExclusive means the regenerated node sits inside the compacted
        // prefix; clearCompactionIfPrefixChanged already clears the compaction in that case, so
        // this check is only a defensive fallback.
        if (endExclusive !in view.rawTailStartIndex..conversation.messageNodes.size) return null

        return view.copy(
            messages = ContextCompactionPresentation.stripDisplayTools(
                listOf(summaryMessage(view.compaction)) +
                    conversation.currentMessages.subList(view.rawTailStartIndex, endExclusive),
            ),
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
                // The generated list is [summary] + tail, so generated index i >= 1 corresponds
                // to node view.rawTailStartIndex + i - 1. On the normal (non-regenerate) path the
                // view's tail always runs to the end of the conversation, so this always lands
                // past the last node and falls through to the append below - byte-identical to
                // before this was made positional for the regenerate case.
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

    private fun rawView(conversation: Conversation) = CompactedMessageView(
        messages = ContextCompactionPresentation.stripDisplayTools(conversation.currentMessages),
        compaction = null,
        rawTailStartIndex = 0,
    )

    /** (node id, selected message id) for every node up to and including the compaction's source-end node, or null if that node is absent. */
    fun compactedPrefixSignature(
        nodes: List<MessageNode>,
        compaction: ConversationCompaction,
    ): List<Pair<Uuid, Uuid>>? {
        val end = nodes.indexOfFirst { it.id == compaction.sourceEndNodeId }
        if (end < 0) return null
        return nodes.take(end + 1).map { it.id to it.currentMessage.id }
    }

    /** True when [after] still carries the exact compacted prefix that [before] had. */
    fun compactedPrefixUnchanged(
        compaction: ConversationCompaction,
        before: List<MessageNode>,
        after: List<MessageNode>,
    ): Boolean {
        val expected = compactedPrefixSignature(before, compaction) ?: return false
        return compactedPrefixSignature(after, compaction) == expected
    }
}
