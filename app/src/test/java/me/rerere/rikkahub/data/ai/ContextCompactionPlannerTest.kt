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
        assertTrue(source.contains("must be retained in summary"))
    }

    @Test
    fun `source text retains every tool record in one assistant response`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                executedTool("tool-b", "input-b", "result-b"),
                executedTool("tool-c", "input-c", "result-c"),
                executedTool("tool-d", "input-d", "result-d"),
            ),
        )

        val source = ContextCompactionPlanner.sourceText(message)

        listOf("tool-b", "input-b", "result-b", "tool-c", "input-c",
            "result-c", "tool-d", "input-d", "result-d").forEach { evidence ->
            assertTrue("Missing tool evidence: $evidence", source.contains(evidence))
        }
    }

    @Test
    fun `required retention instruction demands concrete tool outcomes`() {
        val instruction = ContextCompactionPlanner.requiredToolRetentionInstructions()

        assertTrue(instruction.contains("tool name"))
        assertTrue(instruction.contains("factual outcome"))
        assertTrue(instruction.contains("file paths"))
    }

    @Test
    fun `mandatory tool digest retains each completed tool result`() {
        val messages = listOf(
            UIMessage.user("Task A"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    executedTool("tool-b", "input-b", "result-b"),
                    executedTool("tool-c", "input-c", "result-c"),
                    executedTool("tool-d", "input-d", "result-d"),
                ),
            ),
        )

        val digest = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = messages,
            maxTokens = 1_000,
        )

        listOf("tool-b", "result-b", "tool-c", "result-c", "tool-d", "result-d")
            .forEach { evidence ->
                assertTrue("Missing digest evidence: $evidence", digest.contains(evidence))
            }
    }

    @Test
    fun `mandatory tool digest carries prior retained tools into subsequent compaction`() {
        val firstDigest = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = listOf(
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(executedTool("tool-b", "input-b", "result-b")),
                ),
            ),
            maxTokens = 1_000,
        )
        val secondDigest = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = listOf(
                UIMessage.user("[Summary of previous conversation]\n\n$firstDigest"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(executedTool("tool-c", "input-c", "result-c")),
                ),
            ),
            maxTokens = 1_000,
        )

        listOf("tool-b", "result-b", "tool-c", "result-c").forEach { evidence ->
            assertTrue("Missing retained tool evidence: $evidence", secondDigest.contains(evidence))
        }
    }

    @Test
    fun `automatic tail keeps the requested recent tool calls`() {
        val messages = listOf(
            UIMessage.user("old context"),
            toolMessage("first"),
            toolMessage("second"),
            UIMessage.user("follow up"),
            toolMessage("third"),
        )

        assertEquals(
            2,
            ContextCompactionPlanner.automaticTailStartIndex(
                messages = messages,
                rawTailStartIndex = 0,
                keepRecentToolCalls = 2,
            ),
        )
        assertEquals(
            messages.size,
            ContextCompactionPlanner.automaticTailStartIndex(
                messages = messages,
                rawTailStartIndex = 0,
                keepRecentToolCalls = 0,
            ),
        )
    }

    @Test
    fun `automatic tail falls back to full compaction when all raw messages must be retained`() {
        val messages = listOf(UIMessage.user("old context"), toolMessage("only tool"))

        assertEquals(
            messages.size,
            ContextCompactionPlanner.automaticTailStartIndex(
                messages = messages,
                rawTailStartIndex = 1,
                keepRecentToolCalls = 1,
            ),
        )
    }

    private fun toolMessage(name: String) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            executedTool(name, "{}", "result"),
        ),
    )

    private fun executedTool(name: String, input: String, output: String) = UIMessagePart.Tool(
        toolCallId = "call-$name",
        toolName = name,
        input = input,
        output = listOf(UIMessagePart.Text(output)),
    )
}
