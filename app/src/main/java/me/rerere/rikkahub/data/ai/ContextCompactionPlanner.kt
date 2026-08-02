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
    private const val TOOL_HISTORY_HEADER = "[Tool execution history — authoritative retained context]"
    private const val TOOL_HISTORY_FOOTER = "[End tool execution history]"
    private const val TOOL_RECORD_HEADER = "[Retained tool execution record]"
    private const val TOOL_RECORD_FOOTER = "[End retained tool execution record]"
    private val retainedToolRecordPattern = Regex(
        "${Regex.escape(TOOL_RECORD_HEADER)}\\s*(.*?)\\s*${Regex.escape(TOOL_RECORD_FOOTER)}",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )
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

    /**
     * This contract is appended by [ChatService] to every compression request, including when
     * the user has customized the normal compression prompt. A generic "preserve key facts"
     * instruction is too weak: models commonly retain the user's request but drop the concrete
     * result of the tools that actually carried out the work.
     */
    fun requiredToolRetentionInstructions(): String = """
        TOOL EXECUTION RETENTION IS MANDATORY:
        The conversation can contain [Completed tool execution record] blocks. Preserve every
        completed tool call in the resulting summary. Include the tool name, the meaningful
        arguments or target, and the factual outcome. Preserve errors, important returned values,
        file paths, URLs, IDs, and state changes. Use a clearly labelled "Tool execution history"
        section when any tool record is present. Do not replace these records with a vague phrase
        such as "tools were used". If an output is long, condense it faithfully instead of
        omitting its result.
    """.trimIndent()

    /**
     * A compression model may still omit tool results despite an explicit instruction. Keep a
     * compact, deterministic execution ledger alongside its prose summary so the next model
     * request always contains the factual result of each completed tool call.
     */
    fun mandatoryToolExecutionDigest(
        messages: List<UIMessage>,
        maxTokens: Int,
    ): String {
        if (maxTokens <= 0) return ""
        val records = messages.flatMap { message ->
            extractRetainedToolRecords(message) + message.parts.mapNotNull(::completedToolRecord)
        }
        if (records.isEmpty()) return ""

        val header = "$TOOL_HISTORY_HEADER\n"
        val remainingBudget = (maxTokens - estimateTokens(header)).coerceAtLeast(0)
        val perRecordBudget = remainingBudget / records.size
        return buildString {
            append(header)
            records.forEach { record ->
                appendLine(TOOL_RECORD_HEADER)
                appendLine(truncateToTokenBudget(record, perRecordBudget))
                appendLine(TOOL_RECORD_FOOTER)
            }
            appendLine(TOOL_HISTORY_FOOTER)
        }.trim()
    }

    /**
     * Selects the raw-message boundary that retains the latest [keepRecentToolCalls] executed
     * tool calls. A message may contain several calls, so the entire message is retained when it
     * contains the oldest call in the requested tail. Returning [messages.size] means compact the
     * whole active context because retaining the requested calls would leave nothing to summarize.
     */
    @Suppress("DEPRECATION")
    fun automaticTailStartIndex(
        messages: List<UIMessage>,
        rawTailStartIndex: Int,
        keepRecentToolCalls: Int,
    ): Int {
        require(rawTailStartIndex in 0..messages.size) { "Invalid raw tail start index" }
        if (keepRecentToolCalls <= 0) return messages.size

        var retainedToolCalls = 0
        for (index in messages.lastIndex downTo rawTailStartIndex) {
            retainedToolCalls += messages[index].parts.count { part ->
                when (part) {
                    is UIMessagePart.Tool -> !ContextCompactionPresentation.isDisplayTool(part)
                    is UIMessagePart.ToolCall,
                    is UIMessagePart.ToolResult
                        -> true
                    else -> false
                }
            }
            if (retainedToolCalls >= keepRecentToolCalls) {
                // The summary source must include at least one raw message. When the requested
                // tool tail begins at the current source boundary, the safe fallback is a full
                // compaction of the active summary and raw tail.
                return index.takeIf { it > rawTailStartIndex } ?: messages.size
            }
        }
        return messages.size
    }

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
    private fun completedToolRecord(part: UIMessagePart): String? = when (part) {
        is UIMessagePart.Tool -> {
            if (ContextCompactionPresentation.isDisplayTool(part) || part.output.isEmpty()) {
                null
            } else {
                buildString {
                    appendLine("- Tool: ${part.toolName}")
                    appendLine("  Result:")
                    part.output.forEach { appendPartForSummary(it) }
                    appendLine("  Input: ${part.input}")
                }.trim()
            }
        }
        is UIMessagePart.ToolResult -> buildString {
            appendLine("- Tool: ${part.toolName}")
            appendLine("  Result: ${part.content}")
            appendLine("  Input: ${part.arguments}")
        }.trim()
        else -> null
    }

    private fun truncateToTokenBudget(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return "[tool record omitted: summary budget exhausted]"
        if (estimateTokens(text) <= maxTokens) return text

        var asciiChars = 0L
        var nonAsciiChars = 0L
        var end = 0
        while (end < text.length) {
            if (text[end].code <= 0x7F) asciiChars++ else nonAsciiChars++
            val tokens = nonAsciiChars + (asciiChars + 2L) / 3L
            if (tokens > maxTokens) break
            end++
        }
        return text.substring(0, end).trimEnd() + " …[truncated]"
    }

    /**
     * A later compaction receives the previous summary as a plain user message. Re-read the
     * deterministic ledger from that message so another model summary cannot erase old tool
     * outcomes during a second or third compaction pass.
     *
     * The fallback recognises the ledger format written by builds before record delimiters were
     * introduced. Those ledgers were appended at the end of the summary, so their remaining text
     * can safely be split into individual tool records.
     */
    private fun extractRetainedToolRecords(message: UIMessage): List<String> = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .flatMap { part ->
            val delimitedRecords = retainedToolRecordPattern.findAll(part.text)
                .map { match -> match.groupValues[1].trim() }
                .filter(String::isNotBlank)
                .toList()
            if (delimitedRecords.isNotEmpty()) {
                delimitedRecords
            } else {
                extractLegacyRetainedToolRecords(part.text)
            }
        }

    private fun extractLegacyRetainedToolRecords(text: String): List<String> {
        val historyStart = text.indexOf(TOOL_HISTORY_HEADER)
        if (historyStart < 0) return emptyList()
        val historyEnd = text.indexOf(TOOL_HISTORY_FOOTER, startIndex = historyStart)
            .takeIf { it >= 0 }
            ?: text.length
        return text.substring(historyStart + TOOL_HISTORY_HEADER.length, historyEnd)
            .split(Regex("(?m)(?=^- Tool:)"))
            .map(String::trim)
            .filter(String::isNotBlank)
    }

    @Suppress("DEPRECATION")
    private fun StringBuilder.appendPartForSummary(part: UIMessagePart) {
        if (ContextCompactionPresentation.isDisplayTool(part)) return
        when (part) {
            is UIMessagePart.Text -> appendLine(part.text)
            is UIMessagePart.Reasoning -> appendLine(part.reasoning)
            is UIMessagePart.Tool -> {
                appendLine("[Completed tool execution record — must be retained in summary]")
                appendLine("Tool: ${part.toolName}")
                appendLine("Input: ${part.input}")
                appendLine("Output:")
                part.output.forEach { output -> appendPartForSummary(output) }
                appendLine("[End completed tool execution record]")
            }
            is UIMessagePart.ToolCall -> {
                appendLine("[Tool call requested but no recorded result: ${part.toolName}]")
                appendLine("Arguments: ${part.arguments}")
            }
            is UIMessagePart.ToolResult -> {
                appendLine("[Completed tool execution record — must be retained in summary]")
                appendLine("Tool: ${part.toolName}")
                appendLine("Arguments: ${part.arguments}")
                appendLine("Content: ${part.content}")
                appendLine("[End completed tool execution record]")
            }
            is UIMessagePart.Document -> appendLine("[Document: ${part.fileName}]")
            is UIMessagePart.Image -> appendLine("[Image]")
            is UIMessagePart.Video -> appendLine("[Video]")
            is UIMessagePart.Audio -> appendLine("[Audio]")
            UIMessagePart.Search -> appendLine("[Search]")
        }
    }
}
