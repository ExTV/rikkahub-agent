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
    private const val DEFAULT_CONTEXT_LENGTH = 8_192
    private const val PROMPT_OVERHEAD_TOKENS = 768
    private const val MIN_INPUT_BUDGET_TOKENS = 512
    /**
     * Keep map requests bounded even when the selected compression model advertises a very large
     * context window. A 400k-token conversation therefore becomes roughly four independent
     * 100k-token summaries before the reduce pass, instead of one expensive request.
     */
    private const val MAX_MAP_INPUT_TOKENS = 100_000

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
     * Returns the maximum source size for one map request. A source that already fits this
     * budget remains a single request; only oversized histories are split.
     */
    fun mapInputBudgetTokens(inputBudgetTokens: Int): Int {
        require(inputBudgetTokens > 0) { "inputBudgetTokens must be positive" }
        return inputBudgetTokens.coerceAtMost(MAX_MAP_INPUT_TOKENS)
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

        val pieces = sources
            .asSequence()
            .filter { it.isNotBlank() }
            .flatMap { splitSource(it, maxInputTokens).asSequence() }
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

    /**
     * A character-count-only estimate badly undercounts Chinese, Japanese, Korean, emoji and
     * other non-ASCII content. ASCII text is estimated at three chars/token while non-ASCII
     * chars consume one token each. This intentionally overestimates mixed text to keep every
     * compression prompt safely below its planned budget.
     */
    internal fun estimateTokens(text: String): Int = estimateTokens(text, 0, text.length)
        .coerceAtLeast(1)

    private fun estimateTokens(text: String, startIndex: Int, endIndex: Int): Int {
        var asciiChars = 0L
        var nonAsciiChars = 0L
        for (index in startIndex until endIndex) {
            if (text[index].code <= 0x7F) asciiChars++ else nonAsciiChars++
        }
        return (nonAsciiChars + (asciiChars + 2L) / 3L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    /**
     * Splits in one linear scan, so a single giant tool result or article also obeys the token
     * budget. Prefer a nearby whitespace boundary, but never let an unbroken word bypass it.
     */
    private fun splitSource(source: String, maxInputTokens: Int): List<String> {
        if (estimateTokens(source) <= maxInputTokens) return listOf(source)

        val pieces = mutableListOf<String>()
        var start = 0
        var index = start
        var asciiChars = 0L
        var nonAsciiChars = 0L
        var lastWhitespaceEnd = -1

        while (index < source.length) {
            val char = source[index]
            if (char.code <= 0x7F) asciiChars++ else nonAsciiChars++
            val estimatedTokens = nonAsciiChars + (asciiChars + 2L) / 3L
            if (estimatedTokens <= maxInputTokens) {
                if (char.isWhitespace()) lastWhitespaceEnd = index + 1
                index++
                continue
            }

            val end = lastWhitespaceEnd
                .takeIf { it > start + (index - start) / 2 }
                ?: index
            pieces += source.substring(start, end)
            start = end
            index = start
            asciiChars = 0L
            nonAsciiChars = 0L
            lastWhitespaceEnd = -1
        }
        if (start < source.length) pieces += source.substring(start)
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
