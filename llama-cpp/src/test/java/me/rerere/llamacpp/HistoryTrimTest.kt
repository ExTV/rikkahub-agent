package me.rerere.llamacpp

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryTrimTest {

    private fun conversation(turns: Int) = (1..turns).map { UIMessage.user("message number $it") }

    @Test
    fun `a conversation inside the budget is untouched`() {
        val messages = conversation(3)
        assertEquals(messages, ChatRequestMapper.trimToBudget(messages, budgetBytes = 100_000))
    }

    @Test
    fun `oldest turns are dropped first when the budget is exceeded`() {
        val messages = conversation(50)
        val trimmed = ChatRequestMapper.trimToBudget(messages, budgetBytes = 200)
        assertTrue("must drop something", trimmed.size < messages.size)
        assertTrue("the newest turn must survive", trimmed.last() == messages.last())
        assertTrue("the oldest turn must be gone", !trimmed.contains(messages.first()))
    }

    @Test
    fun `the newest turn is never dropped even if it alone exceeds the budget`() {
        // Dropping it would send an empty conversation, which is worse than a
        // prompt that overflows and produces a readable error.
        val messages = conversation(5)
        val trimmed = ChatRequestMapper.trimToBudget(messages, budgetBytes = 1)
        assertEquals(1, trimmed.size)
        assertEquals(messages.last(), trimmed.first())
    }

    @Test
    fun `a system message must not push out the only user turn`() {
        // Regression: the old loop stopped at working.size > 1, so [system, user]
        // over budget dropped the user turn (the only droppable message) and left
        // just the system message. conversation(5) above never caught this because
        // it has no system message, so the survivor happened to be the newest turn
        // by coincidence, not because the system-present case was exercised.
        val messages = listOf(
            UIMessage.system("s".repeat(10_000)),
            UIMessage.user("hi"),
        )
        val trimmed = ChatRequestMapper.trimToBudget(messages, budgetBytes = 50)
        assertTrue(
            "a user-role message must survive trimming",
            trimmed.any { it.role == MessageRole.USER },
        )
        assertEquals(messages, trimmed)
    }

    @Test
    fun `the newest turn survives when older turns sit behind a system message`() {
        val messages = listOf(
            UIMessage.system("s".repeat(10_000)),
            UIMessage.user("first"),
            UIMessage.assistant("second"),
            UIMessage.user("third"),
        )
        val trimmed = ChatRequestMapper.trimToBudget(messages, budgetBytes = 50)
        assertTrue(
            "a user-role message must survive trimming",
            trimmed.any { it.role == MessageRole.USER },
        )
        assertEquals(messages.first(), trimmed.first())
        assertEquals(messages.last(), trimmed.last())
    }
}
