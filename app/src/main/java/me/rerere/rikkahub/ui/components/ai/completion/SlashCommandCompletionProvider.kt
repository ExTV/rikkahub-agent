package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Clean
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Code
import me.rerere.hugeicons.stroke.Connect
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Edit03
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.Time02
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.data.ai.AgentMode
import me.rerere.rikkahub.data.files.SkillManager

class SlashCommandCompletionProvider(
    private val currentMode: () -> AgentMode,
    private val skillManager: SkillManager? = null,
) : ChatCompletionProvider {
    override val id: String = "slash_commands"

    override suspend fun complete(context: ChatCompletionContext): ChatCompletionList? {
        if (context.hasSelection) return null
        val command = findSlashCommand(context.text, context.cursor) ?: return null
        val query = command.query.lowercase()
        val mode = currentMode()

        val allCommands = mutableListOf<ChatCompletionItem>()

        // Core curated actions from plan.md & Antigravity lineup
        val actions = listOf(
            ChatCompletionItem(
                label = "/goal",
                insertText = "/goal",
                detail = "Run until the specified goal is completely finished",
                icon = HugeIcons.Time02,
                sortScore = 1000,
            ),
            ChatCompletionItem(
                label = "/compact",
                insertText = "/compact",
                detail = "Compact the conversation now (summarize history to free up context)",
                icon = HugeIcons.Clean,
                sortScore = 990,
            ),
            ChatCompletionItem(
                label = "/schedule",
                insertText = "/schedule",
                detail = "Run an instruction on a recurring schedule or as a one-time timer",
                icon = HugeIcons.Clock02,
                sortScore = 980,
            ),
            ChatCompletionItem(
                label = "/browser",
                insertText = "/browser",
                detail = "Invoke a browser agent for web tasks",
                icon = HugeIcons.Earth,
                sortScore = 970,
            ),
            ChatCompletionItem(
                label = "/grill-me",
                insertText = "/grill-me",
                detail = "Interview me to align on a plan",
                icon = HugeIcons.BubbleChatQuestion,
                sortScore = 950,
            ),
            ChatCompletionItem(
                label = "/teamwork",
                insertText = "/teamwork",
                detail = "Invoke a team of agents to autonomously tackle large projects",
                icon = HugeIcons.Connect,
                sortScore = 940,
            ),
            ChatCompletionItem(
                label = "/learn",
                insertText = "/learn",
                detail = "Reflect on recent successes or corrections to capture reusable skills or rules",
                icon = HugeIcons.Idea01,
                sortScore = 930,
            ),
            ChatCompletionItem(
                label = "/mode",
                insertText = "/mode",
                detail = "Switch mode (Plan / Build)",
                icon = HugeIcons.Tools,
                sortScore = 920,
            ),
            ChatCompletionItem(
                label = "/skill",
                insertText = "/skill",
                detail = "Attach a skill to this prompt",
                icon = HugeIcons.Code,
                sortScore = 910,
            ),
            ChatCompletionItem(
                label = "/imagine",
                insertText = "/imagine",
                detail = "Generate studio-grade images from prompt",
                icon = HugeIcons.Image02,
                sortScore = 900,
            ),
        )

        actions.forEach { action ->
            val name = action.label.removePrefix("/")
            if (name.startsWith(query)) {
                allCommands.add(action)
            }
        }

        // Mode constraint: in Build mode suggest /plan, NEVER /build. In Plan mode suggest /build, NEVER /plan.
        when (mode) {
            AgentMode.BUILD -> {
                if ("plan".startsWith(query)) {
                    allCommands.add(
                        ChatCompletionItem(
                            label = "/plan",
                            insertText = "/plan",
                            detail = "Switch to Plan mode (analyze and design without modifying files)",
                            icon = HugeIcons.Edit03,
                            sortScore = 1050,
                        )
                    )
                }
            }
            AgentMode.PLAN -> {
                if ("build".startsWith(query)) {
                    allCommands.add(
                        ChatCompletionItem(
                            label = "/build",
                            insertText = "/build",
                            detail = "Switch to Build mode (default agent execution and editing)",
                            icon = HugeIcons.Tools,
                            sortScore = 1050,
                        )
                    )
                }
            }
        }

        // Skill sub-command completions
        if (query.startsWith("skill")) {
            val skillQuery = query.removePrefix("skill").trim().removePrefix(":").trim()
            val availableSkills = skillManager?.listSkills() ?: emptyList()
            availableSkills.forEach { skill ->
                if (skillQuery.isEmpty() || skill.name.lowercase().contains(skillQuery)) {
                    allCommands.add(
                        ChatCompletionItem(
                            label = "/skill ${skill.name}",
                            insertText = "/skill ${skill.name}",
                            detail = skill.description.ifBlank { "Attach ${skill.name} skill" },
                            icon = HugeIcons.Code,
                            sortScore = 1100,
                        )
                    )
                }
            }
        }

        val items = allCommands
            .distinctBy { it.label }
            .sortedByDescending { it.sortScore }

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
        if (start > 0 && !prefix[start - 1].isWhitespace()) return null

        val query = prefix.substring(start + 1)
        if (query.any { it.isWhitespace() }) return null
        return SlashCommand(
            query = query,
            range = TextRange(start, cursor),
        )
    }
}
