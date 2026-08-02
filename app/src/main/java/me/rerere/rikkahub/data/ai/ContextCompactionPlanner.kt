package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * Plans requests made to the compression model.
 *
 * The compression model's context window is independent from the chat model's one.  Splitting
 * by message count lets a handful of long tool results overflow that window, so every request is
 * instead bounded by a conservative token estimate.  The returned source segments preserve every
 * character; the caller can recursively reduce their summaries into one final summary.
 */
internal object ContextCompactionPlanner {
    private const val CHARS_PER_TOKEN = 3
    private const val DEFAULT_CONTEXT_LENGTH = 8_192
    private const val PROMPT_OVERHEAD_TOKENS = 768
    private const val MIN_INPUT_BUDGET_TOKENS = 512

    fun inputBudgetTokens(
        contextLength: Int?,
        targetTokens: Int,
    ): Int {
        val availableContext = (contextLength?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_LENGTH)
            .coerceAtLeast(MIN_INPUT_BUDGET_TOKENS + PROMPT_OVERHEAD_TOKENS)
        val outputReserve = targetTokens
            .coerceAtLeast(256)
            .coerceAtMost(availableContext / 2)
        val remaining = (availableContext - outputReserve - PROMPT_OVERHEAD_TOKENS)
            .coerceAtLeast(MIN_INPUT_BUDGET_TOKENS)
        return minOf(remaining, availableContext * 3 / 4)
            .coerceAtLeast(MIN_INPUT_BUDGET_TOKENS)
    }

    /**
     * Splits source text into request-sized groups. A single long message is split at whitespace
     * where possible, rather than allowing it to make one compression request overflow.
     */
    fun partitionSources(
        sources: List<String>,
        maxInputTokens: Int,
    ): List<List<String>> {
        require(maxInputTokens > 0) { "maxInputTokens must be positive" }

        val maxChars = maxInputTokens.toLong()
            .times(CHARS_PER_TOKEN)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
        val pieces = sources
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { splitSource(it, maxChars).asSequence() }
            .toList()
        if (pieces.isEmpty()) return emptyList()

        val groups = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        var currentTokens = 0
        val separatorTokens = estimateTokens("\n\n")
        pieces.forEach { piece ->
            val pieceTokens = estimateTokens(piece)
            val nextTokens = pieceTokens + if (current.isEmpty()) 0 else separatorTokens
            if (current.isNotEmpty() && currentTokens + nextTokens > maxInputTokens) {
                groups += current.toList()
                current.clear()
                currentTokens = 0
            }
            current += piece
            currentTokens += pieceTokens + if (current.size == 1) 0 else separatorTokens
        }
        if (current.isNotEmpty()) groups += current.toList()
        return groups
    }

    /**
     * Unlike [UIMessage.toText], this also retains tool inputs and outputs. Tool results are
     * often the largest part of an agent turn and must be available to the summarizer before the
     * original context is replaced by the request-only summary.
     */
    fun sourceText(message: UIMessage): String = buildString {
        append('[')
        append(message.role.name)
        append("]:\n")
        message.parts.forEach { appendPartForSummary(it) }
    }.trim()

    /** A short intermediate summary prevents a large map phase from inflating the reduce phase. */
    fun intermediateTargetTokens(
        finalTargetTokens: Int,
        inputBudgetTokens: Int,
    ): Int = minOf(
        finalTargetTokens.coerceAtLeast(1),
        (inputBudgetTokens / 4).coerceAtLeast(256),
    )

    internal fun estimateTokens(text: String): Int = ((text.length + CHARS_PER_TOKEN - 1) /
        CHARS_PER_TOKEN).coerceAtLeast(1)

    private fun splitSource(source: String, maxChars: Int): List<String> {
        if (source.length <= maxChars) return listOf(source)

        val pieces = mutableListOf<String>()
        var start = 0
        while (start < source.length) {
            val limit = minOf(start + maxChars, source.length)
            val boundary = if (limit == source.length) {
                limit
            } else {
                source.lastIndexOfAny(charArrayOf('\n', ' ', '\t'), startIndex = limit - 1)
                    .takeIf { it > start + maxChars / 2 }
                    ?: limit
            }
            val end = if (boundary == start) limit else boundary
            pieces += source.substring(start, end)
            start = end
            while (start < source.length && source[start].isWhitespace()) start++
        }
        return pieces
    }

    @Suppress("DEPRECATION")
    private fun StringBuilder.appendPartForSummary(part: UIMessagePart) {
        when (part) {
            is UIMessagePart.Text -> appendLine(part.text)
            is UIMessagePart.Reasoning -> appendLine(part.reasoning)
            is UIMessagePart.Tool -> {
                appendLine("[Tool: ${part.toolName}]")
                appendLine("Input: ${part.input}")
                appendLine("Output:")
                part.output.forEach { output -> appendPartForSummary(output) }
            }
            is UIMessagePart.ToolCall -> {
                appendLine("[Tool call: ${part.toolName}]")
                appendLine("Arguments: ${part.arguments}")
            }
            is UIMessagePart.ToolResult -> {
                appendLine("[Tool result: ${part.toolName}]")
                appendLine("Arguments: ${part.arguments}")
                appendLine("Content: ${part.content}")
            }
            is UIMessagePart.Document -> appendLine("[Document: ${part.fileName}]")
            is UIMessagePart.Image -> appendLine("[Image]")
            is UIMessagePart.Video -> appendLine("[Video]")
            is UIMessagePart.Audio -> appendLine("[Audio]")
            UIMessagePart.Search -> appendLine("[Search]")
        }
    }
}
