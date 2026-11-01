package me.rerere.llamacpp

/**
 * A curated GGUF the Settings -> Local · llama.cpp picker offers. Mirrors
 * `me.rerere.locallm.litert.LiteRtCatalogEntry`'s shape, minus its `config()`, which returns a
 * LiteRT-specific config type llama.cpp has no equivalent for.
 */
data class LlamaCppCatalogEntry(
    val displayName: String, // e.g. "Qwen3 4B"
    val repo: String,        // HuggingFace repo path, e.g. "Qwen/Qwen3-4B-GGUF"
    val file: String,        // File inside the repo, e.g. "Qwen3-4B-Q4_K_M.gguf"
    val sizeBytes: Long,
    val minMemGb: Int,
    val tags: List<String> = emptyList(),
) {
    /** Pre-built download URL on HuggingFace's `resolve` path. Same format `ModelInstall`
     *  already normalises and validates. */
    fun resolveUrl(): String = "https://huggingface.co/$repo/resolve/main/$file"
}

object LlamaCppCatalog {
    /**
     * Curated picker list. Entry criteria are set by the spec, not taste: the model must ship
     * a chat template that declares tool support, must fit a mid-range phone at the planner's
     * smallest ladder rung with a real tool set attached, and must be an ungated public repo.
     * Quantisation defaults to a 4-bit variant unless a model is small enough that a
     * higher-precision quant fits.
     *
     * Every repo, file name and byte size below was verified against the live HuggingFace API
     * on 2026-08-03. They are exact and must not be edited, guessed at, or extended without
     * re-verifying against the live API: a wrong repo id or file name 404s on first download,
     * which is worse than shipping nothing.
     *
     * All three are Qwen3, apache-2.0, public and ungated. Qwen3 ships a tool-declaring chat
     * template, satisfying the spec's first entry criterion, and supports an optional thinking
     * mode.
     *
     * The official `Qwen/Qwen3-0.6B-GGUF` and `Qwen/Qwen3-1.7B-GGUF` repos each publish a
     * single file, Q8_0, with no 4-bit variant. 0.6B at Q8_0 is small enough that the spec's
     * "unless a model is small enough that a higher-precision quant fits" escape clause covers
     * it. The 1.7B entry below instead uses `unsloth/Qwen3-1.7B-GGUF`'s Q4_K_M requant - a
     * deliberate choice, not an oversight: it is 727 MB smaller than the official Q8_0, and a
     * smaller weights file leaves more RAM for the KV cache.
     */
    val ENTRIES: List<LlamaCppCatalogEntry> = listOf(
        LlamaCppCatalogEntry(
            displayName = "Qwen3 0.6B",
            repo = "Qwen/Qwen3-0.6B-GGUF",
            file = "Qwen3-0.6B-Q8_0.gguf",
            sizeBytes = 639446688L,
            minMemGb = 4,
            tags = listOf("thinking", "tools"),
        ),
        LlamaCppCatalogEntry(
            displayName = "Qwen3 1.7B",
            // Community requant (see class doc above for why), not the official Qwen repo.
            repo = "unsloth/Qwen3-1.7B-GGUF",
            file = "Qwen3-1.7B-Q4_K_M.gguf",
            sizeBytes = 1107409472L,
            minMemGb = 4,
            tags = listOf("thinking", "tools"),
        ),
        LlamaCppCatalogEntry(
            displayName = "Qwen3 4B",
            repo = "Qwen/Qwen3-4B-GGUF",
            file = "Qwen3-4B-Q4_K_M.gguf",
            sizeBytes = 2497280256L,
            minMemGb = 8,
            tags = listOf("thinking", "tools"),
        ),
    )
}
