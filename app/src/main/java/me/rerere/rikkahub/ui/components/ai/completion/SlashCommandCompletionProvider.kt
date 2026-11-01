package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.data.ai.AgentMode

class SlashCommandCompletionProvider(
    private val currentMode: () -> AgentMode,
) : ChatCompletionProvider {
    override val id: String = "slash_commands"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection) return null
        val command = findSlashCommand(context.text, context.cursor) ?: return null
        val query = command.query.lowercase()
        val mode = currentMode()

        val items = when (mode) {
            AgentMode.BUILD -> {
                // In Build mode, ONLY suggest /plan
                if ("plan".startsWith(query)) {
                    listOf(
                        ChatCompletionItem(
                            label = "/plan",
                            insertText = "/plan",
                            detail = "Switch to Plan mode (analyze and design without modifying files)",
                            icon = HugeIcons.Edit03,
                            sortScore = 1000,
                        )
                    )
                } else {
                    emptyList()
                }
            }
            AgentMode.PLAN -> {
                // In Plan mode, ONLY suggest /build
                if ("build".startsWith(query)) {
                    listOf(
                        ChatCompletionItem(
                            label = "/build",
                            insertText = "/build",
                            detail = "Switch to Build mode (default agent execution and editing)",
                            icon = HugeIcons.Tools,
                            sortScore = 1000,
                        )
                    )
                } else {
                    emptyList()
                }
            }
        }

        if (items.isEmpty()) return null
        return ChatCompletionList(
            providerId = id,
            replacementRange = command.range,
            items = items,
        )
    }

    private data class SlashCommand(
        val query: String,
        val range: TextRange,
    )

    private fun findSlashCommand(text: String, cursor: Int): SlashCommand? {
        if (cursor < 0 || cursor > text.length) return null
        val prefix = text.substring(0, cursor)
        val start = prefix.lastIndexOf('/')
        if (start < 0) return null
        // Command slash must be at start of input or after whitespace/newline
        if (start > 0 && !prefix[start - 1].isWhitespace()) return null

        val query = prefix.substring(start + 1)
        if (query.any { it.isWhitespace() }) return null
        return SlashCommand(
            query = query,
            range = TextRange(start, cursor),
        )
    }
}
