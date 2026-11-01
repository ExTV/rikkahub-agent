package me.rerere.llamacpp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.locallm.MemoryGuard
import org.json.JSONObject

/**
 * Seam over [LlamaCppJni] so the runtime's state machine is testable on the JVM without
 * loading native code. Mirrors [LlamaCppJni]'s String-in/String-out convenience wrappers
 * rather than the raw byte-array externs. [LlamaCppJni.nativeChatTemplate] has no caller
 * in the runtime, so it is not mirrored here.
 */
interface LlamaCppNative {
    fun loadModel(path: String): Long
    fun freeModel(handle: Long)
    fun modelInfo(handle: Long): String
    fun createContext(
        modelHandle: Long,
        nCtx: Int,
        nBatch: Int,
        nUBatch: Int,
        cacheTypeK: String,
        cacheTypeV: String,
        nThreads: Int,
    ): Long

    fun freeContext(handle: Long)

    /** Mirrors [LlamaCppJni.nativeCancelGeneration]: safe from any thread, harmless when
     *  nothing is running. */
    fun cancelGeneration(handle: Long)

    fun applyTemplate(modelHandle: Long, requestJson: String): String

    /**
     * Mirrors [LlamaCppJni.generate]. [appliedTemplateJson] is the opaque blob
     * [LlamaCppJni.nativeApplyTemplate] returned, carrying the prompt, the grammar and
     * the grammar's triggers together; it must be passed through unchanged.
     */
    fun generate(
        ctxHandle: Long,
        modelHandle: Long,
        appliedTemplateJson: String,
        maxTokens: Int,
        onPiece: (String) -> Boolean,
    )

    fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String
}

/** Delegates straight to [LlamaCppJni]'s String-in/String-out wrappers. */
object RealLlamaCppNative : LlamaCppNative {
    override fun loadModel(path: String) = LlamaCppJni.nativeLoadModel(path)
    override fun freeModel(handle: Long) = LlamaCppJni.nativeFreeModel(handle)
    override fun modelInfo(handle: Long): String = LlamaCppJni.nativeModelInfo(handle)
    override fun createContext(
        modelHandle: Long,
        nCtx: Int,
        nBatch: Int,
        nUBatch: Int,
        cacheTypeK: String,
        cacheTypeV: String,
        nThreads: Int,
    ) = LlamaCppJni.nativeCreateContext(
        modelHandle, nCtx, nBatch, nUBatch, cacheTypeK, cacheTypeV, nThreads,
    )

    override fun freeContext(handle: Long) = LlamaCppJni.nativeFreeContext(handle)
    override fun cancelGeneration(handle: Long) = LlamaCppJni.nativeCancelGeneration(handle)
    override fun applyTemplate(modelHandle: Long, requestJson: String) =
        LlamaCppJni.applyTemplate(modelHandle, requestJson)

    override fun generate(
        ctxHandle: Long,
        modelHandle: Long,
        appliedTemplateJson: String,
        maxTokens: Int,
        onPiece: (String) -> Boolean,
    ) = LlamaCppJni.generate(ctxHandle, modelHandle, appliedTemplateJson, maxTokens, onPiece)

    override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String) =
        LlamaCppJni.parseChat(text, isPartial, appliedTemplateJson)
}

/** Thrown when the planned configuration cannot fit in the memory the device has free. */
class ModelTooLargeException(message: String) : RuntimeException(message)

/**
 * Owns one loaded model and its context.
 *
 * `llama_context` is not thread-safe, so [generate] holds [mutex] for its whole duration,
 * and a second call made while one is running is refused rather than queued: the caller
 * asked to run inference concurrently on a context that cannot support it, which is a bug
 * at the call site, not something to paper over. [load] and [unload] take the same
 * [mutex], so neither can touch the handles while a generation is inside native code -
 * the use-after-free [LlamaCppJni.nativeCancelGeneration]'s doc warns about. [applyTemplate]
 * takes it too, for the same reason: it dereferences [modelHandle] in native code just like
 * [generate] does.
 *
 * [cancelGeneration] deliberately does not take [mutex]: it is the only way to interrupt
 * a prefill (a [LlamaCppJni.TokenSink] can only refuse a token between tokens, and no
 * token is produced during a prefill at all), so it must work from another thread while
 * [generate] holds the lock. It still cannot be allowed to race the context being freed -
 * that exact pairing is what nativeCancelGeneration's contract forbids - so it and the
 * context-free step share the narrower [cancelFreeLock] instead.
 */
class LlamaCppRuntime(private val native: LlamaCppNative = RealLlamaCppNative) {

    @Volatile
    private var modelHandle: Long = 0

    @Volatile
    private var contextHandle: Long = 0

    /** The plan [load] created the current context from. Written under [mutex] by [load]
     *  and [unloadLocked], read without it - see the class doc. */
    @Volatile
    private var plan: ContextPlan? = null

    private val mutex = Mutex()

    /** Guards [contextHandle] between [cancelGeneration] and the free step in
     *  [unloadLocked], since llama.cpp forbids freeing a context while a call to
     *  nativeCancelGeneration on it is in flight. */
    private val cancelFreeLock = Any()

    /**
     * Loads [path], plans a context size for it against [availableRamBytes] via
     * [ContextPlanner], and creates the engine with exactly that size. Any previously
     * loaded model is released first.
     */
    suspend fun load(
        path: String,
        tools: List<ToolDeclaration>,
        systemPromptBytes: Int,
        availableRamBytes: Long,
        threads: Int = defaultThreads(),
    ): ContextPlan = mutex.withLock {
        unloadLocked()

        val model = native.loadModel(path)
        val plan = try {
            val info = parseModelInfo(native.modelInfo(model))
            val planned = ContextPlanner.plan(info, availableRamBytes, tools, systemPromptBytes)

            // Refuse before creating the context. An over-budget load is killed by the OS
            // partway through, which looks like a crash rather than a decision.
            //
            // MemoryGuard's 0.7 budget already reserves the other 30% for the KV cache and
            // compute buffers, so it must see the model file alone (info.weightsBytes), not
            // planned.estimatedBytes - that total already includes KV cache and compute
            // buffer headroom of its own, and adding MemoryGuard's on top double-counts it.
            //
            // A weightsBytes of 0 is not a small model, it is unreadable metadata:
            // MemoryGuard.decide(0, ...) always returns Ok, so it must be refused here
            // rather than passed through, or an unmeasurable model would sail past the
            // guard it was supposed to be judged by.
            if (info.weightsBytes <= 0) {
                throw ModelTooLargeException(
                    "This model's file size could not be read, so it cannot be safely loaded."
                )
            }
            when (val decision = MemoryGuard.decide(info.weightsBytes, availableRamBytes)) {
                is MemoryGuard.Decision.TooLarge -> throw ModelTooLargeException(
                    "This model needs about ${decision.requiredFreeBytes / 1_000_000}MB free " +
                        "but only ${decision.availMemBytes / 1_000_000}MB is available. " +
                        "Close other apps or pick a smaller model."
                )
                MemoryGuard.Decision.Ok -> Unit
            }

            planned
        } catch (t: Throwable) {
            native.freeModel(model)
            throw t
        }

        val ctx = try {
            native.createContext(
                model, plan.nCtx, plan.nBatch, plan.nUBatch,
                plan.cacheTypeK.id, plan.cacheTypeV.id, threads,
            )
        } catch (t: Throwable) {
            native.freeModel(model)
            throw t
        }

        modelHandle = model
        contextHandle = ctx
        this.plan = plan
        plan
    }

    /** Releases the loaded model and context, if any. Waits for an in-flight [generate]
     *  to finish first, since [mutex] is shared with it. */
    suspend fun unload() = mutex.withLock { unloadLocked() }

    private fun unloadLocked() {
        synchronized(cancelFreeLock) {
            if (contextHandle != 0L) {
                native.freeContext(contextHandle)
                contextHandle = 0
            }
        }
        if (modelHandle != 0L) {
            native.freeModel(modelHandle)
            modelHandle = 0
        }
        plan = null
    }

    /**
     * Bytes of prompt the currently planned context can accept, excluding the response
     * reserve, the system prompt and the tools this request already committed to
     * ([ContextPlan.reservedInputBytes]). Backs [ChatRequestMapper.trimToBudget] so a long
     * conversation is trimmed to what this model's context can actually hold rather than
     * overflowing at generate time. Throws if no model is loaded, rather than returning 0 -
     * a silent 0 budget would trim a conversation down to nothing.
     *
     * Coerced to never go negative: a system prompt and tool set large enough to exceed the
     * whole input budget on their own must still leave [ChatRequestMapper.trimToBudget]
     * something well-defined to compare against, rather than a budget that reads as "plenty
     * of room" because it wrapped negative into a huge Int.
     */
    fun inputBudgetBytes(): Int {
        val current = plan ?: error("no model is loaded")
        return (ContextPlanner.inputByteBudget(current.nCtx) - current.reservedInputBytes)
            .coerceAtLeast(0)
    }

    /**
     * Re-fits [tools] and [systemPromptBytes] against the model already loaded, without
     * picking a new context size or touching the native context - see [ContextPlanner]'s
     * class doc for why [ContextPlan.nCtx] must never drift from what the engine was
     * created with. [LlamaCppProvider.ensureLoaded] calls this on every request, including
     * one for a model that is already loaded, since the tools enabled and the system prompt
     * in use are per-request and can change between turns even when the model does not.
     */
    suspend fun replan(tools: List<ToolDeclaration>, systemPromptBytes: Int) = mutex.withLock {
        check(modelHandle != 0L) { "no model is loaded" }
        val current = plan ?: error("no model is loaded")
        val request = ContextPlanner.replanForRequest(current.nCtx, tools, systemPromptBytes)
        plan = current.copy(
            droppedToolNames = request.droppedToolNames,
            reservedInputBytes = request.reservedInputBytes,
        )
    }

    /**
     * Renders [requestJson] through the model's chat template. Takes [mutex], the same
     * guard [generate] uses: [LlamaCppJni.nativeCancelGeneration]'s doc says it is the only
     * native call allowed to overlap [LlamaCppJni.nativeGenerate], so calling this while a
     * generation or an unload is in flight would race the model handle the same way an
     * unmutexed [generate] would.
     */
    fun applyTemplate(requestJson: String): String {
        if (!mutex.tryLock()) {
            throw IllegalStateException("this model is busy with another operation (load, unload, or generate)")
        }
        try {
            val model = modelHandle
            check(model != 0L) { "no model is loaded" }
            return native.applyTemplate(model, requestJson)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Runs one generation, blocking the calling thread until it completes. Returning
     * false from [onPiece] stops it; so does [cancelGeneration] called from another
     * thread. Throws [IllegalStateException] if no model is loaded, or if another
     * generation is already running.
     */
    fun generate(
        appliedTemplateJson: String,
        maxTokens: Int,
        onPiece: (String) -> Boolean,
    ) {
        if (!mutex.tryLock()) {
            throw IllegalStateException("this model is busy with another operation (load, unload, or generate)")
        }
        try {
            val model = modelHandle
            val ctx = contextHandle
            check(model != 0L && ctx != 0L) { "no model is loaded" }
            native.generate(ctx, model, appliedTemplateJson, maxTokens, onPiece)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Asks the running generation to stop, if any. Safe to call with nothing loaded or
     * nothing running - see the class doc for why this does not take [mutex].
     */
    fun cancelGeneration() {
        synchronized(cancelFreeLock) {
            val ctx = contextHandle
            if (ctx != 0L) native.cancelGeneration(ctx)
        }
    }

    /** Parses generated text against the applied-template blob that produced it. Stateless
     *  with respect to the loaded model, so it carries no loaded-model check. */
    fun parse(text: String, isPartial: Boolean, appliedTemplateJson: String): String =
        native.parseChat(text, isPartial, appliedTemplateJson)

    private fun parseModelInfo(json: String): GgufModelInfo {
        val o = JSONObject(json)
        return GgufModelInfo(
            nLayers = o.getInt("n_layers"),
            nEmbd = o.getInt("n_embd"),
            nHeadKv = o.getInt("n_head_kv"),
            nEmbdHeadK = o.getInt("n_embd_head_k"),
            nEmbdHeadV = o.getInt("n_embd_head_v"),
            nVocab = o.getInt("n_vocab"),
            nCtxTrain = o.getInt("n_ctx_train"),
            slidingWindow = o.optInt("sliding_window", 0).takeIf { it > 0 },
            weightsBytes = o.getLong("weights_bytes"),
        )
    }

    private fun defaultThreads(): Int =
        (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 8)
}
