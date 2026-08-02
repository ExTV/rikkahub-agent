package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactionPlannerTest {
    @Test
    fun `partition sources keeps every character within the input budget`() {
        val source = "alpha ".repeat(120) + "\n" + "beta ".repeat(120)
        val budget = 100

        val groups = ContextCompactionPlanner.partitionSources(listOf(source), budget)

        assertTrue(groups.size > 1)
        assertTrue(groups.all { group ->
            ContextCompactionPlanner.estimateTokens(group.joinToString("\n\n")) <= budget
        })
        assertEquals(
            source.filterNot(Char::isWhitespace),
            groups.flatten().joinToString("").filterNot(Char::isWhitespace),
        )
    }

    @Test
    fun `input budget reserves room for prompt and summary`() {
        val budget = ContextCompactionPlanner.inputBudgetTokens(
            contextLength = 8_192,
            targetTokens = 2_000,
        )

        assertTrue(budget >= 512)
        assertTrue(budget <= 6_144)
        assertTrue(budget + 2_000 + 768 <= 8_192)
    }

    @Test
    fun `large compression contexts use 100k map chunks`() {
        val modelInputBudget = ContextCompactionPlanner.inputBudgetTokens(
            contextLength = 400_000,
            targetTokens = 2_000,
        )

        assertEquals(100_000, ContextCompactionPlanner.mapInputBudgetTokens(modelInputBudget))
    }

    @Test
    fun `large CJK source is split into safe map chunks`() {
        val source = "文".repeat(400_000)

        val groups = ContextCompactionPlanner.partitionSources(
            sources = listOf(source),
            maxInputTokens = 100_000,
        )

        assertEquals(4, groups.size)
        assertTrue(groups.all { group ->
            ContextCompactionPlanner.estimateTokens(group.joinToString("\n\n")) <= 100_000
        })
        assertEquals(source, groups.flatten().joinToString(""))
    }

    @Test
    fun `source smaller than map budget stays in one request`() {
        val source = "a".repeat(300_000)

        val groups = ContextCompactionPlanner.partitionSources(
            sources = listOf(source),
            maxInputTokens = 100_000,
        )

        assertEquals(1, groups.size)
        assertEquals(source, groups.single().single())
    }

    @Test
    fun `source text retains executed tool input and output`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "read_file",
                    input = "{\"path\":\"notes.txt\"}",
                    output = listOf(UIMessagePart.Text("important file content")),
                ),
            ),
        )

        val source = ContextCompactionPlanner.sourceText(message)

        assertTrue(source.contains("read_file"))
        assertTrue(source.contains("notes.txt"))
        assertTrue(source.contains("important file content"))
    }
}
