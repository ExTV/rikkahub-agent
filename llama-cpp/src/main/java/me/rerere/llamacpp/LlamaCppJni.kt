package me.rerere.llamacpp

/**
 * The entire JNI surface for llama.cpp. Nothing else in the codebase calls native code
 * directly, so every native contract change is visible in this one file.
 */
object LlamaCppJni {

    init {
        System.loadLibrary("llamajni")
    }

    /** Which CPU features the loaded build detected, for diagnostics and bug reports. */
    external fun nativeSystemInfo(): String

    fun systemInfo(): String = nativeSystemInfo()

    /**
     * Loads a GGUF and returns an opaque handle. Throws RuntimeException on failure.
     * The handle is only valid until [nativeFreeModel] releases it: passing it to any
     * function here afterward, or freeing it twice, is undefined behaviour, since nothing
     * here tracks live handles to turn that into a checked error.
     */
    external fun nativeLoadModel(path: String): Long

    /** Releases a handle from [nativeLoadModel]. Safe to call with 0; see its doc for the freed-handle caveat. */
    external fun nativeFreeModel(handle: Long)

    /** Model metadata as a JSON object, shaped for [GgufModelInfo]. */
    external fun nativeModelInfo(handle: Long): String

    /** The model's own Jinja chat template, or null when it declares none. */
    external fun nativeChatTemplate(handle: Long): String?

    /**
     * Renders an OpenAI-shaped request through the model's own chat template.
     * Returns prompt, grammar and stop conditions as JSON.
     *
     * Bytes, not a String: request or rendered prompt text containing a supplementary-plane
     * character (an emoji, for example) is not valid Modified UTF-8, which is what a jstring
     * would require crossing this boundary. [applyTemplate] carries the standard-UTF-8
     * conversion for callers that just want a String in and a String out.
     */
    external fun nativeApplyTemplate(modelHandle: Long, requestJson: ByteArray): ByteArray

    fun applyTemplate(modelHandle: Long, requestJson: String): String =
        String(nativeApplyTemplate(modelHandle, requestJson.toByteArray(Charsets.UTF_8)), Charsets.UTF_8)

    /**
     * Creates the inference context for a loaded model, sized exactly as planned.
     *
     * The handle is not a raw `llama_context`: it also owns the cancellation flag
     * [nativeCancelGeneration] sets. Release it with [nativeFreeContext], never with
     * [nativeFreeModel], and free it before the model it was created from.
     */
    external fun nativeCreateContext(
        modelHandle: Long,
        nCtx: Int,
        nBatch: Int,
        nUBatch: Int,
        cacheTypeK: String,
        cacheTypeV: String,
        nThreads: Int,
    ): Long

    /** Releases a handle from [nativeCreateContext]. Safe to call with 0. */
    external fun nativeFreeContext(handle: Long)

    /**
     * Asks the generation running on this context to stop, from any thread.
     *
     * This is the only call here that may be made while [nativeGenerate] is running, and the
     * only reason it exists: returning false from a [TokenSink] can only stop generation
     * between tokens, so it cannot interrupt a long prefill, during which no token is
     * produced at all. Setting the flag when nothing is running is harmless, since each
     * generation clears it before starting.
     *
     * The caller must not free the context concurrently with a generation or with this call.
     */
    external fun nativeCancelGeneration(handle: Long)

    /** Receives generated text. Return false to stop generation. */
    interface TokenSink {
        /**
         * One slice of generated text as UTF-8 bytes, guaranteed to end on a character
         * boundary. Bytes rather than a String because a token can carry part of a character:
         * a byte-level vocabulary emits one token per byte for any character it has no whole
         * token for, and decoding half a character on its own turns it permanently into a
         * replacement character.
         */
        fun onToken(pieceUtf8: ByteArray): Boolean
    }

    /**
     * Streams a completion for a prompt already rendered by [nativeApplyTemplate].
     *
     * [appliedTemplateJson] is the bytes that call returned, passed through unchanged. It
     * carries the prompt, the grammar and the grammar's triggers and preserved tokens
     * together, so a caller cannot forward the grammar while dropping the triggers it needs
     * in order to ever activate.
     *
     * CONTRACT: the blob is strictly opaque to Kotlin. Do not parse it, rebuild it, filter it,
     * re-serialise it, or pass a subset of it. Turning it into a typed data class is the
     * obvious-looking improvement and it is precisely what this shape exists to prevent: every
     * field in it is load-bearing for either generation or parsing, several in ways that fail
     * silently rather than loudly, and a hand-maintained mirror of it will drift. Hold the
     * bytes, hand them back.
     */
    external fun nativeGenerate(
        ctxHandle: Long,
        modelHandle: Long,
        appliedTemplateJson: ByteArray,
        maxTokens: Int,
        sink: TokenSink,
    )

    /**
     * Parses generated text into an OpenAI-shaped message. Safe on partial text.
     *
     * Takes the same [appliedTemplateJson] blob as [nativeGenerate], under the same opacity
     * contract: reading a response requires the parser the template layer built for that exact
     * request, which no amount of format name alone can reconstruct. Passing a blob with no
     * parser is refused rather than quietly parsed as plain content, since that silently
     * discards every tool call.
     */
    external fun nativeParseChat(
        textUtf8: ByteArray,
        isPartial: Boolean,
        appliedTemplateJson: ByteArray,
    ): ByteArray

    /**
     * [nativeGenerate] with String in and out. Decoding each piece separately is safe because
     * native only ever emits whole characters.
     */
    fun generate(
        ctxHandle: Long,
        modelHandle: Long,
        appliedTemplateJson: String,
        maxTokens: Int,
        onPiece: (String) -> Boolean,
    ) = nativeGenerate(
        ctxHandle,
        modelHandle,
        appliedTemplateJson.toByteArray(Charsets.UTF_8),
        maxTokens,
        object : TokenSink {
            override fun onToken(pieceUtf8: ByteArray): Boolean =
                onPiece(String(pieceUtf8, Charsets.UTF_8))
        },
    )

    /** [nativeParseChat] with String in and out. */
    fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String =
        String(
            nativeParseChat(
                text.toByteArray(Charsets.UTF_8),
                isPartial,
                appliedTemplateJson.toByteArray(Charsets.UTF_8),
            ),
            Charsets.UTF_8,
        )
}
