package me.rerere.llamacpp

import kotlin.math.roundToLong

/**
 * Decides the context size and KV cache types for a model on this device.
 *
 * The single invariant this exists to hold: the context returned here is both what the
 * engine is created with and what every prompt budget is computed from. LiteRT budgeted
 * the prompt against the model file's ceiling while configuring the engine with the
 * catalog's smaller allocation, and an oversized prefill was the result.
 */
object ContextPlanner {

    /** Candidate context sizes, smallest first. */
    val LADDER = listOf(4096, 8192, 16384, 32768)

    /** Rough bytes of prompt text per token. JSON tool schemas run denser than prose. */
    const val BYTES_PER_TOKEN = 4

    /** Fraction of the context reserved for the response. */
    private const val RESPONSE_RESERVE = 0.5

    /** Tokens kept free for conversation history growth. */
    private const val HISTORY_HEADROOM_TOKENS = 750

    private const val N_BATCH = 512
    private const val N_UBATCH = 512

    /** Multiplier over the computed total, for allocator overhead. */
    private const val HEADROOM_WITH_METADATA = 1.1
    private const val HEADROOM_WITHOUT_METADATA = 1.2

    /** Never plan to use more than this share of what the device has free. */
    private const val RAM_SAFETY_FRACTION = 0.8

    fun plan(
        info: GgufModelInfo,
        availableRamBytes: Long,
        tools: List<ToolDeclaration>,
        systemPromptBytes: Int,
    ): ContextPlan {
        val budget = (availableRamBytes * RAM_SAFETY_FRACTION).roundToLong()

        // Metadata we cannot trust means we cannot compute a KV cache size. Take the
        // smallest rung and a coarse whole-file estimate instead of guessing.
        if (!info.isComplete) {
            val fallbackCtx = LADDER.first()
            val request = replanForRequest(fallbackCtx, tools, systemPromptBytes)
            return ContextPlan(
                nCtx = fallbackCtx,
                cacheTypeK = KvCacheType.F16,
                cacheTypeV = KvCacheType.F16,
                nBatch = N_BATCH,
                nUBatch = N_UBATCH,
                droppedToolNames = request.droppedToolNames,
                estimatedBytes = (info.weightsBytes * HEADROOM_WITHOUT_METADATA).roundToLong(),
                reservedInputBytes = request.reservedInputBytes,
            )
        }

        val rungs = LADDER.filter { it <= info.nCtxTrain }.ifEmpty { listOf(info.nCtxTrain) }

        // Prefer a bigger context over a higher-precision cache: quantising the cache
        // costs a little quality, while a context too small to hold the tools costs the
        // agent its capabilities outright.
        for (ctx in rungs.reversed()) {
            for (cache in listOf(KvCacheType.F16, KvCacheType.Q8_0)) {
                val bytes = estimateBytes(info, ctx, cache)
                if (bytes <= budget) {
                    val request = replanForRequest(ctx, tools, systemPromptBytes)
                    return ContextPlan(
                        nCtx = ctx,
                        cacheTypeK = cache,
                        cacheTypeV = cache,
                        nBatch = N_BATCH,
                        nUBatch = N_UBATCH,
                        droppedToolNames = request.droppedToolNames,
                        estimatedBytes = bytes,
                        reservedInputBytes = request.reservedInputBytes,
                    )
                }
            }
        }

        // Nothing fit. Take the smallest rung with the smallest cache and let
        // MemoryGuard refuse the load with an actionable message.
        val smallest = rungs.first()
        val request = replanForRequest(smallest, tools, systemPromptBytes)
        return ContextPlan(
            nCtx = smallest,
            cacheTypeK = KvCacheType.Q8_0,
            cacheTypeV = KvCacheType.Q8_0,
            nBatch = N_BATCH,
            nUBatch = N_UBATCH,
            droppedToolNames = request.droppedToolNames,
            estimatedBytes = estimateBytes(info, smallest, KvCacheType.Q8_0),
            reservedInputBytes = request.reservedInputBytes,
        )
    }

    /**
     * Weights plus KV cache plus compute buffer, with headroom. Sliding-window models
     * cache only their window, which is why a 32k Gemma context is affordable.
     */
    fun estimateBytes(info: GgufModelInfo, nCtx: Int, cache: KvCacheType): Long {
        val effectiveCtx = info.slidingWindow?.let { minOf(nCtx, it) } ?: nCtx
        val perToken = info.nHeadKv.toDouble() *
            (info.nEmbdHeadK * cache.bytesPerElement + info.nEmbdHeadV * cache.bytesPerElement)
        val kvCache = info.nLayers.toDouble() * effectiveCtx * perToken
        val computeBuffer = (info.nVocab.toDouble() + info.nEmbd) * N_UBATCH * 4
        return ((info.weightsBytes + kvCache + computeBuffer) * HEADROOM_WITH_METADATA).roundToLong()
    }

    /**
     * Bytes available for prompt input (history, system prompt and tools combined) at
     * [nCtx]: half the context reserved for the response, minus [HISTORY_HEADROOM_TOKENS]
     * kept free for conversation growth. Shared by [fitTools] (which subtracts the system
     * prompt and tool schemas from it) and [LlamaCppRuntime.inputBudgetBytes] (which
     * subtracts [ContextPlan.reservedInputBytes] from it), so the two can never drift
     * apart into disagreeing about what the input half of the context actually holds.
     */
    fun inputByteBudget(nCtx: Int): Int {
        val inputTokens = (nCtx * (1.0 - RESPONSE_RESERVE)).toInt() - HISTORY_HEADROOM_TOKENS
        return inputTokens * BYTES_PER_TOKEN
    }

    /** The per-request outcome of fitting [tools] and the system prompt into [nCtx]'s input
     *  budget: which tools had to be dropped, and how many of the input budget's bytes the
     *  system prompt plus the surviving tools reserve - the two figures a request-specific
     *  replan (see [LlamaCppRuntime.replan]) needs without re-picking [nCtx] itself. */
    data class RequestPlan(val droppedToolNames: List<String>, val reservedInputBytes: Int)

    /**
     * Fits [tools] and [systemPromptBytes] into [nCtx]'s input budget without picking a new
     * context size - [nCtx] is a given here, not a candidate, since the native context
     * cannot be resized once created. Used both when [plan] first sizes a context and by
     * [LlamaCppRuntime.replan] to re-fit a later request's tools/system prompt against an
     * already-loaded model.
     */
    fun replanForRequest(nCtx: Int, tools: List<ToolDeclaration>, systemPromptBytes: Int): RequestPlan {
        val kept = fitTools(nCtx, tools, systemPromptBytes)
        return RequestPlan(
            droppedToolNames = kept.dropped,
            reservedInputBytes = systemPromptBytes + kept.keptBytes,
        )
    }

    private class ToolFit(val dropped: List<String>, val keptBytes: Int)

    /**
     * Keep tools until the input half of the context is spent. Drop order is the reverse
     * of the enabled order, so the tools the user enabled first survive. Dropping by
     * schema size instead would silently favour tools with small schemas regardless of
     * how much the user wants them.
     */
    private fun fitTools(
        nCtx: Int,
        tools: List<ToolDeclaration>,
        systemPromptBytes: Int,
    ): ToolFit {
        val availableBytes = inputByteBudget(nCtx) - systemPromptBytes
        if (availableBytes <= 0) {
            return ToolFit(dropped = tools.map { it.name }, keptBytes = 0)
        }

        var used = 0
        val dropped = mutableListOf<String>()
        var fitting = true
        for (tool in tools) {
            if (!fitting || used + tool.jsonBytes > availableBytes) {
                fitting = false
                dropped += tool.name
            } else {
                used += tool.jsonBytes
            }
        }
        return ToolFit(dropped = dropped, keptBytes = used)
    }
}
