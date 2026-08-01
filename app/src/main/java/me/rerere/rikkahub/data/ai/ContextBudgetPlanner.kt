package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

data class ContextBudgetPlan(
    val estimatedInputTokens: Int,
    val triggerTokens: Int,
    val shouldCompact: Boolean,
)

/**
 * Conservative provider-independent context estimator.
 *
 * The most recent real usage is preferred because it includes provider-specific system and
 * tool-schema overhead. Messages added after that response are estimated locally. Providers
 * that do not report usage fall back to estimating the complete message list.
 */
object ContextBudgetPlanner {
    private const val CHARS_PER_TOKEN = 3
    private const val MESSAGE_OVERHEAD_TOKENS = 8
    private const val MEDIA_PART_TOKENS = 1_024

    fun plan(
        messages: List<UIMessage>,
        contextLength: Int,
        thresholdPercent: Int,
        thresholdTokensK: Int? = null,
        reservedTokens: Int = 0,
    ): ContextBudgetPlan {
        require(contextLength > 0) { "contextLength must be positive" }
        val normalizedThreshold = thresholdPercent.coerceIn(5, 95)
        val safeInputBudget = (contextLength.toLong() - reservedTokens.coerceAtLeast(0).toLong())
            .coerceAtLeast(1L)
        val configuredTriggerTokens = thresholdTokensK?.let { tokensK ->
            (tokensK.toLong().coerceAtLeast(1L) * 1_000L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        } ?: (contextLength.toLong() * normalizedThreshold / 100L)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val triggerTokens = minOf(configuredTriggerTokens.toLong(), safeInputBudget)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val estimatedInputTokens = estimateInputTokens(messages)
        return ContextBudgetPlan(
            estimatedInputTokens = estimatedInputTokens,
            triggerTokens = triggerTokens,
            shouldCompact = estimatedInputTokens >= triggerTokens,
        )
    }

    fun estimateInputTokens(messages: List<UIMessage>): Int {
        val usageIndex = messages.indexOfLast { message ->
            val usage = message.usage
            usage != null && (usage.totalTokens > 0 || usage.promptTokens + usage.completionTokens > 0)
        }
        val estimate = if (usageIndex >= 0) {
            val usage = messages[usageIndex].usage!!
            val reportedTotal = usage.totalTokens.takeIf { it > 0 }
                ?: (usage.promptTokens + usage.completionTokens)
            reportedTotal.toLong() + messages
                .drop(usageIndex + 1)
                .sumOf(::estimateMessageTokens)
        } else {
            messages.sumOf(::estimateMessageTokens)
        }
        return estimate.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun estimateMessageTokens(message: UIMessage): Long {
        val contentTokens = message.parts.sumOf(::estimatePartTokens)
        return MESSAGE_OVERHEAD_TOKENS + contentTokens
    }

    /** Selects a recent tail whose estimated size fits [targetTokens]. */
    fun chooseTailStartIndex(
        messages: List<UIMessage>,
        targetTokens: Int,
        minimumRecentMessages: Int = 4,
    ): Int {
        if (messages.size <= 1) return 0

        val minimumStart = (messages.size - minimumRecentMessages.coerceAtLeast(1))
            .coerceAtLeast(0)
        var start = messages.lastIndex
        var used = 0L
        while (start >= 0) {
            val next = estimateMessageTokens(messages[start])
            if (start < minimumStart && used + next > targetTokens) break
            used += next
            start--
        }
        return (start + 1).coerceIn(1, messages.lastIndex)
    }

    @Suppress("DEPRECATION")
    private fun estimatePartTokens(part: UIMessagePart): Long = when (part) {
        is UIMessagePart.Text -> estimateTextTokens(part.text)
        is UIMessagePart.Reasoning -> estimateTextTokens(part.reasoning)
        is UIMessagePart.Tool -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.input) + part.output.sumOf(::estimatePartTokens)
        is UIMessagePart.ToolCall -> estimateTextTokens(part.toolName) + estimateTextTokens(part.arguments)
        is UIMessagePart.ToolResult -> estimateTextTokens(part.toolName) +
            estimateTextTokens(part.arguments.toString()) + estimateTextTokens(part.content.toString())
        is UIMessagePart.Document -> MEDIA_PART_TOKENS + estimateTextTokens(part.fileName)
        is UIMessagePart.Image,
        is UIMessagePart.Video,
        is UIMessagePart.Audio,
        UIMessagePart.Search,
            -> MEDIA_PART_TOKENS.toLong()
    }

    private fun estimateTextTokens(text: String): Long =
        ((text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN).toLong()
}
