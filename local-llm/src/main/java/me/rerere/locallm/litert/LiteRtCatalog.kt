package me.rerere.locallm.litert

/** A single curated entry the Settings → Local · LiteRT picker shows. Joins the
 *  download identity (HuggingFace repo + file) with the config defaults so picking
 *  an entry is one-shot: download + config in one user action. */
data class LiteRtCatalogEntry(
    val displayName: String,         // e.g. "Gemma 4 E2B"
    val modelId: String,             // HuggingFace repo path, e.g. "litert-community/gemma-4-E2B-it-litert-lm"
    val modelFile: String,           // File inside the repo, e.g. "gemma-4-E2B-it.litertlm"
    val description: String,         // Markdown-friendly one-liner
    val sizeBytes: Long,
    val minDeviceMemoryGb: Int,
    val recommended: Boolean = false, // Marks a "good default pick" for its size class
    val tags: List<String> = emptyList(), // ["multimodal", "thinking"], for chips in UI
) {
    /** Pre-built download URL on HuggingFace's `resolve` path. Same format ModelInstall already validates. */
    fun resolveUrl(): String = "https://huggingface.co/$modelId/resolve/main/$modelFile"

    /** Lookup the matching config defaults. */
    fun config(): LiteRtModelConfig = LiteRtModelDefaults.forModelFile(modelFile)
}

object LiteRtCatalog {
    /**
     * Curated picker list, ordered by HuggingFace download count (most-downloaded first).
     *
     * Sourced from the `litert-community` org (281 repos as of 2026-07-29), filtered to the
     * ten most-downloaded models that satisfy all three requirements:
     *
     *  1. **Tool-calling capable.** RikkaHub is an agent: a model that cannot call tools is
     *     close to useless here. Verified objectively by reading each *base* model's chat
     *     template and confirming it accepts a `tools` argument and emits tool calls, rather
     *     than trusting marketing copy. This is what excluded
     *     `DeepSeek-R1-Distill-Qwen-1.5B` (77k downloads, a reasoning distillation with no
     *     `tools` handling at all) and both `SmolLM2-*-Instruct` sizes (19k + 6k).
     *  2. **Installable.** The repo must be ungated: the downloader is a plain HTTP client
     *     with no HuggingFace token, so a gated repo returns 401 and the install fails.
     *     This excluded `Gemma3-1B-IT`, `gemma-3-270m-it`, and
     *     `functiongemma-270m-ft-mobile-actions`, all `gated: auto`. FunctionGemma is a
     *     purpose-built function-calling model and is the first thing to add here if we ever
     *     support HuggingFace tokens.
     *  3. **Ships a `.litertlm` asset.** `Qwen2.5-0.5B-Instruct` and `TinyLlama-1.1B-Chat`
     *     publish only the older `.task` format.
     *
     * Sizes are the real byte sizes of the referenced file, read from the HuggingFace tree
     * API, so the pre-download memory check compares against the truth.
     */
    val ENTRIES: List<LiteRtCatalogEntry> = listOf(
        LiteRtCatalogEntry(
            displayName = "Gemma 4 E2B",
            modelId = "litert-community/gemma-4-E2B-it-litert-lm",
            modelFile = "gemma-4-E2B-it.litertlm",
            description = "Google's on-device flagship. Understands images and audio, thinks before answering, and supports up to 32K context. The best all-round pick if your device has the memory.",
            sizeBytes = 2_588_147_712L,
            minDeviceMemoryGb = 8,
            recommended = true,
            tags = listOf("multimodal", "thinking", "tools"),
        ),
        LiteRtCatalogEntry(
            displayName = "Gemma 4 E4B",
            modelId = "litert-community/gemma-4-E4B-it-litert-lm",
            modelFile = "gemma-4-E4B-it.litertlm",
            description = "The larger Gemma 4. Same image, audio and thinking support as E2B with noticeably better answers, for devices with memory to spare.",
            sizeBytes = 3_659_530_240L,
            minDeviceMemoryGb = 12,
            tags = listOf("multimodal", "thinking", "tools"),
        ),
        LiteRtCatalogEntry(
            displayName = "Qwen2.5 1.5B Instruct",
            modelId = "litert-community/Qwen2.5-1.5B-Instruct",
            modelFile = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            description = "Small, fast and dependable. Text only, 4K context, and the most reliable choice on devices where the Gemma vision encoder will not start.",
            sizeBytes = 1_597_931_520L,
            minDeviceMemoryGb = 6,
            recommended = true,
            tags = listOf("tools"),
        ),
        LiteRtCatalogEntry(
            displayName = "Qwen3 0.6B",
            modelId = "litert-community/Qwen3-0.6B",
            modelFile = "qwen3_0_6b_mixed_int4.litertlm",
            description = "Tiny but tool-capable, with an optional thinking mode. Runs comfortably on modest hardware.",
            sizeBytes = 498_000_000L,
            minDeviceMemoryGb = 4,
            recommended = true,
            tags = listOf("thinking", "tools"),
        ),
        LiteRtCatalogEntry(
            displayName = "Phi-4 mini Instruct",
            modelId = "litert-community/Phi-4-mini-instruct",
            modelFile = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            description = "Microsoft's compact instruct model, strong at reasoning and code for its size. Text only, 4K context.",
            sizeBytes = 3_910_000_000L,
            minDeviceMemoryGb = 12,
            tags = listOf("tools"),
        ),
        LiteRtCatalogEntry(
            displayName = "Gemma 4 12B",
            modelId = "litert-community/gemma-4-12B-it-litert-lm",
            modelFile = "gemma-4-12B-it.litertlm",
            description = "Desktop-class Gemma 4. Far beyond what a phone can hold, listed for tablets and high-memory devices only.",
            sizeBytes = 6_548_000_000L,
            minDeviceMemoryGb = 16,
            tags = listOf("multimodal", "thinking", "tools"),
        ),
        LiteRtCatalogEntry(
            displayName = "Qwen3 4B",
            modelId = "litert-community/Qwen3-4B",
            modelFile = "qwen3_4b_mixed_int4.litertlm",
            description = "The strongest text-only model here that still fits a phone. Thinking mode and solid tool use.",
            sizeBytes = 2_659_000_000L,
            minDeviceMemoryGb = 8,
            tags = listOf("thinking", "tools"),
        ),
        LiteRtCatalogEntry(
            displayName = "Qwen3 1.7B",
            modelId = "litert-community/Qwen3-1.7B",
            modelFile = "Qwen3_1.7B.litertlm",
            description = "A middle-ground Qwen3: better answers than 0.6B, lighter than 4B.",
            sizeBytes = 2_057_000_000L,
            minDeviceMemoryGb = 6,
            tags = listOf("thinking", "tools"),
        ),
        LiteRtCatalogEntry(
            displayName = "SmolLM3 3B",
            modelId = "litert-community/SmolLM3-3B",
            modelFile = "SmolLM3-3B_q4_block32_ekv4096.litertlm",
            description = "HuggingFace's own 3B, built for reasoning and tool use, quantised to about 2GB.",
            sizeBytes = 2_002_000_000L,
            minDeviceMemoryGb = 6,
            tags = listOf("thinking", "tools"),
        ),
        LiteRtCatalogEntry(
            displayName = "Qwen3 0.6B (int4)",
            modelId = "litert-community/Qwen3-0.6B-int4",
            modelFile = "qwen3_0.6b_q4_block32_ekv1280.litertlm",
            description = "The smallest model here at about 350MB. Short 1.3K context, but it runs almost anywhere.",
            sizeBytes = 347_000_000L,
            minDeviceMemoryGb = 3,
            tags = listOf("thinking", "tools"),
        ),
    )

    /** Find an entry by modelFile (matches what's stored in our provider config). Useful for
     *  rendering "you have <X> installed" in the UI. */
    fun findByModelFile(modelFile: String): LiteRtCatalogEntry? =
        ENTRIES.firstOrNull { it.modelFile == modelFile }

    /** The catalog's display name for [modelFile], falling back to the bare filename for a
     *  model the user installed from a pasted URL. Keeps the chat model picker readable
     *  instead of showing `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm`. */
    fun displayNameFor(modelFile: String): String =
        findByModelFile(modelFile)?.displayName ?: modelFile.substringBeforeLast(".litertlm")
}
