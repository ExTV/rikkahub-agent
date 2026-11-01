package me.rerere.locallm.litert

/**
 * Per-model runtime defaults.
 *
 * Sampler values ([topK] / [topP] / [temperature]) are the ones each model's authors ship in
 * their `generation_config.json`, so a local model answers the way its publisher intended.
 */
data class LiteRtModelConfig(
    val modelFile: String,           // e.g. "gemma-4-E2B-it.litertlm" — match key
    val topK: Int = 64,
    val topP: Double = 0.95,
    val temperature: Double = 1.0,
    /**
     * Default value for `EngineConfig.maxNumTokens`, which is the engine's TOTAL token
     * budget (prompt + response), not an output-only cap. It sizes the KV cache, so raising
     * it costs memory. The user can override it in Settings, bounded by [maxContextLength].
     */
    val maxTokens: Int = 4096,
    /**
     * Hard ceiling on [maxTokens] for this file, or null when unknown. Files built with an
     * `ekvNNNN` marker cannot exceed NNNN regardless of what the base model supports: the
     * KV cache was baked at conversion time.
     */
    val maxContextLength: Int? = null,
    val preferredAccelerators: List<String> = listOf("gpu", "cpu"),  // first available wins
    val visionAccelerator: String? = null,                            // null when no image support
    val supportsImage: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsThinking: Boolean = false,
    val supportsSpeculativeDecoding: Boolean = false,
    val minDeviceMemoryGb: Int = 6,
    val sizeBytes: Long,
)

object LiteRtModelDefaults {
    /**
     * Look up by exact `modelFile` name. An unknown file (installed from a pasted URL) gets
     * conservative defaults rather than the SDK's silent internal ones, with its context
     * ceiling recovered from the filename where the packager encoded one.
     */
    fun forModelFile(modelFile: String): LiteRtModelConfig =
        BUILT_IN.firstOrNull { it.modelFile == modelFile }
            ?: fallbackFor(modelFile)

    /**
     * The `ekvNNNN` marker LiteRT-LM's conversion tooling puts in a filename records the KV
     * cache size the file was built with (`..._q8_ekv4096.litertlm` -> 4096). It is a hard
     * ceiling: asking the engine for more tokens than the cache holds fails at load time
     * with an opaque native error. Recovering it from the name means a URL-pasted model gets
     * a correct ceiling instead of a guess.
     */
    internal fun contextCeilingFromFileName(modelFile: String): Int? =
        Regex("""ekv(\d+)""", RegexOption.IGNORE_CASE)
            .find(modelFile)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

    private fun fallbackFor(modelFile: String): LiteRtModelConfig {
        val ceiling = contextCeilingFromFileName(modelFile)
        return LiteRtModelConfig(
            modelFile = modelFile,
            maxTokens = ceiling?.coerceAtMost(4096) ?: 4096,
            maxContextLength = ceiling,
            sizeBytes = 0L,
        )
    }

    private val BUILT_IN: List<LiteRtModelConfig> = listOf(
        // ---- Gemma 4 (multimodal, thinking, multi-token prediction) --------------------
        // Vision was confirmed working on Adreno 642L (Nothing Phone 1, Snapdragon 8 Gen 1)
        // on 2026-05-19; the runtime's text-only fallback covers devices whose GPU vision
        // encoder cannot initialise. The model card states support for up to 32K context.
        LiteRtModelConfig(
            modelFile = "gemma-4-E2B-it.litertlm",
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            // 16384, not 4096: an assistant with a normal enabled tool set spends roughly
            // 8k tokens on declarations alone, so a 4096-token allocation could not hold the
            // prompt at all. Half the file's 32768 ceiling keeps the KV cache affordable
            // while leaving the response about as much room as the prompt takes.
            maxTokens = 16384,
            maxContextLength = 32768,
            preferredAccelerators = listOf("gpu", "cpu"),
            visionAccelerator = "gpu",
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = true,
            supportsSpeculativeDecoding = true,
            minDeviceMemoryGb = 8,
            sizeBytes = 2_588_147_712L,
        ),
        LiteRtModelConfig(
            modelFile = "gemma-4-E4B-it.litertlm",
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            // See the E2B entry: 4096 cannot hold a real tool set's declarations.
            maxTokens = 16384,
            maxContextLength = 32768,
            preferredAccelerators = listOf("gpu", "cpu"),
            visionAccelerator = "gpu",
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = true,
            supportsSpeculativeDecoding = true,
            minDeviceMemoryGb = 12,
            sizeBytes = 3_659_530_240L,
        ),
        LiteRtModelConfig(
            modelFile = "gemma-4-12B-it.litertlm",
            topK = 64,
            topP = 0.95,
            temperature = 1.0,
            // See the E2B entry: 4096 cannot hold a real tool set's declarations.
            maxTokens = 16384,
            // 32003, not the 32768 the model card implies: this is what the file declares.
            maxContextLength = 32003,
            preferredAccelerators = listOf("gpu", "cpu"),
            visionAccelerator = "gpu",
            supportsImage = true,
            supportsAudio = true,
            supportsThinking = true,
            supportsSpeculativeDecoding = true,
            minDeviceMemoryGb = 16,
            sizeBytes = 6_548_000_000L,
        ),

        // ---- Qwen2.5 (text only) -------------------------------------------------------
        LiteRtModelConfig(
            modelFile = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            topK = 20,
            topP = 0.8,
            temperature = 0.7,
            maxTokens = 4096,
            maxContextLength = 4096,
            preferredAccelerators = listOf("gpu", "cpu"),
            minDeviceMemoryGb = 6,
            sizeBytes = 1_597_931_520L,
        ),

        // ---- Qwen3 (text only, thinking mode) ------------------------------------------
        // maxContextLength values below are the ceilings the files declare in their own
        // LlmMetadataProto, read out of the published artifacts rather than guessed. The
        // `mixed_int4` builds are 2048 despite Qwen3 supporting 32k as a base model: the
        // KV cache is baked at conversion time. Getting this wrong is not a quality
        // problem, it faults the native executor (see [LiteRtModelFile]).
        LiteRtModelConfig(
            modelFile = "qwen3_0_6b_mixed_int4.litertlm",
            topK = 20,
            topP = 0.95,
            temperature = 0.6,
            maxTokens = 2048,
            maxContextLength = 2048,
            preferredAccelerators = listOf("gpu", "cpu"),
            supportsThinking = true,
            minDeviceMemoryGb = 4,
            sizeBytes = 498_000_000L,
        ),
        LiteRtModelConfig(
            modelFile = "qwen3_0.6b_q4_block32_ekv1280.litertlm",
            topK = 20,
            topP = 0.95,
            temperature = 0.6,
            maxTokens = 1280,
            maxContextLength = 1280,
            preferredAccelerators = listOf("gpu", "cpu"),
            supportsThinking = true,
            minDeviceMemoryGb = 3,
            sizeBytes = 347_000_000L,
        ),
        LiteRtModelConfig(
            modelFile = "Qwen3_1.7B.litertlm",
            topK = 20,
            topP = 0.95,
            temperature = 0.6,
            maxTokens = 4096,
            maxContextLength = 4096,
            preferredAccelerators = listOf("gpu", "cpu"),
            supportsThinking = true,
            minDeviceMemoryGb = 6,
            sizeBytes = 2_057_000_000L,
        ),
        LiteRtModelConfig(
            modelFile = "qwen3_4b_mixed_int4.litertlm",
            topK = 20,
            topP = 0.95,
            temperature = 0.6,
            maxTokens = 2048,
            maxContextLength = 2048,
            preferredAccelerators = listOf("gpu", "cpu"),
            supportsThinking = true,
            minDeviceMemoryGb = 8,
            sizeBytes = 2_659_000_000L,
        ),

        // ---- Phi-4 mini (text only) ----------------------------------------------------
        // Phi-4-mini ships no sampler values in generation_config.json, so these are the
        // SDK-neutral defaults rather than publisher-specified ones.
        LiteRtModelConfig(
            modelFile = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            topK = 64,
            topP = 0.95,
            temperature = 0.8,
            maxTokens = 4096,
            maxContextLength = 4096,
            preferredAccelerators = listOf("gpu", "cpu"),
            minDeviceMemoryGb = 12,
            sizeBytes = 3_910_000_000L,
        ),

        // ---- SmolLM3 (text only, reasoning) --------------------------------------------
        LiteRtModelConfig(
            modelFile = "SmolLM3-3B_q4_block32_ekv4096.litertlm",
            topK = 64,
            topP = 0.95,
            temperature = 0.6,
            maxTokens = 4096,
            maxContextLength = 4096,
            preferredAccelerators = listOf("gpu", "cpu"),
            supportsThinking = true,
            minDeviceMemoryGb = 6,
            sizeBytes = 2_002_000_000L,
        ),
    )
}
