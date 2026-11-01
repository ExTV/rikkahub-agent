package me.rerere.rikkahub.ui.pages.chat

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.ContextCompactionPresentation
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid

/**
 * A display-only grouping of assistant nodes separated by automatic context compression.
 *
 * Generation still retains every node independently, which keeps tool replay and provider
 * history correct. The UI renders the cards and continuation text as one assistant message.
 */
internal data class AutoCompactionMessageGroup(
    val nodes: List<MessageNode>,
) {
    init {
        require(nodes.isNotEmpty())
    }

    val id: Uuid = nodes.first().id
    val terminalNode: MessageNode = nodes.last()

    val displayMessage: UIMessage = terminalNode.currentMessage.copy(
        parts = nodes.flatMap { it.currentMessage.parts },
        annotations = nodes.flatMap { it.currentMessage.annotations },
        createdAt = nodes.first().currentMessage.createdAt,
    )
}

internal fun List<MessageNode>.groupAutomaticCompactionMessages(): List<AutoCompactionMessageGroup> {
    if (isEmpty()) return emptyList()

    val groups = mutableListOf<AutoCompactionMessageGroup>()
    var index = 0
    while (index < size) {
        val groupedNodes = mutableListOf(this[index])
        while (
            groupedNodes.last().currentMessage.role == MessageRole.ASSISTANT &&
            ContextCompactionPresentation.hasDisplayTool(groupedNodes.last().currentMessage) &&
            index + 1 < size &&
            this[index + 1].currentMessage.role == MessageRole.ASSISTANT
        ) {
            index++
            groupedNodes += this[index]
        }
        groups += AutoCompactionMessageGroup(groupedNodes)
        index++
    }
    return groups
}
