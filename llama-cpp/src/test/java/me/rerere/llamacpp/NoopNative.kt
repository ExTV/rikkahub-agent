package me.rerere.llamacpp

/**
 * Reusable [LlamaCppNative] stub with a harmless default for every member, so a test can
 * do `object : LlamaCppNative by NoopNative() { override fun ... }` and override only the
 * calls it actually cares about.
 */
class NoopNative : LlamaCppNative {
    override fun loadModel(path: String): Long = 1L
    override fun freeModel(handle: Long) {}
    override fun modelInfo(handle: Long): String = "{}"
    override fun createContext(
        modelHandle: Long,
        nCtx: Int,
        nBatch: Int,
        nUBatch: Int,
        cacheTypeK: String,
        cacheTypeV: String,
        nThreads: Int,
    ): Long = 2L

    override fun freeContext(handle: Long) {}
    override fun cancelGeneration(handle: Long) {}
    override fun applyTemplate(modelHandle: Long, requestJson: String): String = "{}"
    override fun generate(
        ctxHandle: Long,
        modelHandle: Long,
        appliedTemplateJson: String,
        maxTokens: Int,
        onPiece: (String) -> Boolean,
    ) {
    }

    override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String = "{}"
}
