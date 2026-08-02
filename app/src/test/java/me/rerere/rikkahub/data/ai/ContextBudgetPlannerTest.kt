package me.rerere.rikkahub.data.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetPlannerTest {
    @Test
    fun `reported usage is used as baseline for trailing messages`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text("prior response")),
                usage = TokenUsage(
                    promptTokens = 5_000,
                    completionTokens = 1_000,
                    totalTokens = 6_000,
                ),
            ),
            UIMessage.user("x".repeat(300)),
        )

        assertEquals(6_108, ContextBudgetPlanner.estimateInputTokens(messages))
    }

    @Test
    fun `tool output appended after provider usage is included in the next request estimate`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "read_file",
                        input = "{\"path\":\"notes.txt\"}",
                        output = listOf(UIMessagePart.Text("x".repeat(3_000))),
                    ),
                ),
                usage = TokenUsage(
                    promptTokens = 5_000,
                    completionTokens = 1_000,
                    totalTokens = 6_000,
                ),
            ),
        )

        assertEquals(7_000, ContextBudgetPlanner.estimateInputTokens(messages))
    }

    @Test
    fun `plan triggers at configured percentage`() {
        val below = listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = emptyList(),
                usage = TokenUsage(
                    promptTokens = 5_000,
                    completionTokens = 1_000,
                    totalTokens = 6_000,
                ),
            ),
            UIMessage.user("x".repeat(300)),
        )
        val above = below.dropLast(1) + UIMessage.user("x".repeat(1_200))

        assertFalse(ContextBudgetPlanner.plan(below, 8_000, 80).shouldCompact)
        assertTrue(ContextBudgetPlanner.plan(above, 8_000, 80).shouldCompact)
        assertEquals(6_400, ContextBudgetPlanner.plan(above, 8_000, 80).triggerTokens)
        assertEquals(400, ContextBudgetPlanner.plan(above, 8_000, 5).triggerTokens)
    }

    @Test
    fun `plan supports a manual token threshold in thousands`() {
        val messages = listOf(UIMessage.user("x".repeat(36_000)))

        val plan = ContextBudgetPlanner.plan(
            messages = messages,
            contextLength = 128_000,
            thresholdPercent = 80,
            thresholdTokensK = 12,
        )

        assertEquals(12_000, plan.triggerTokens)
        assertTrue(plan.shouldCompact)
    }

    @Test
    fun `plan reserves output and prompt budget before triggering`() {
        val plan = ContextBudgetPlanner.plan(
            messages = listOf(UIMessage.user("x".repeat(24_000))),
            contextLength = 10_000,
            thresholdPercent = 95,
            reservedTokens = 3_000,
        )

        assertEquals(7_000, plan.triggerTokens)
        assertTrue(plan.shouldCompact)
    }

    @Test
    fun `tail selection keeps minimum recent messages`() {
        val messages = List(6) { UIMessage.user("x".repeat(300)) }

        assertEquals(
            2,
            ContextBudgetPlanner.chooseTailStartIndex(
                messages = messages,
                targetTokens = 250,
                minimumRecentMessages = 4,
            ),
        )
    }
}
