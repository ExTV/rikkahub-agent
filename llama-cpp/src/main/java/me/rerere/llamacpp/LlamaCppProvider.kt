package me.rerere.llamacpp

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.StreamChunkHandler
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.locallm.LocalRuntime
import me.rerere.locallm.LocalRuntimePreferences
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns one [ChatDeltaTracker] channel's deltas into the safe suffix to forward downstream,
 * given that the shared chunk protocol (UIMessage's delta-append fold) can only ever append,
 * never retract, what it has already been sent.
 *
 * A non-reset delta is already a safe suffix and is forwarded unchanged. A reset delta
 * carries the channel's full corrected value instead of a suffix (see [ChatDelta]'s doc); what
 * has already been forwarded downstream cannot be un-sent, so re-sending the corrected value
 * in full would duplicate whatever of it was already shown rather than fix it. Mid-stream
 * ([isFinal] false), forwarding nothing and silently resyncing to the corrected value is the
 * right call: a later, non-reset delta will extend past the corrected value and its suffix
 * carries the correction downstream, so nothing is lost - keeping the stale watermark instead
 * would make every later delta fail its "is this an extension of what's shown" check forever,
 * since the true text no longer contains the stale prefix at all once it has diverged.
 *
 * On the terminal delta ([isFinal] true - the final `isPartial = false` parse, see
 * [ChatDeltaTracker.consume]) there is no later delta to rely on: generation has already
 * stopped, so swallowing a reset there would drop the model's real final text permanently
 * instead of merely delaying its correction. [advance] forwards the full corrected value in
 * that one case instead, accepting a possible duplicate suffix in exchange for never silently
 * losing content the model actually produced.
 */
private class ChannelWatermark {
    private var shown = ""

    fun advance(deltaValue: String, reset: Boolean, isFinal: Boolean): String {
        if (reset) {
            shown = deltaValue
            return if (isFinal) deltaValue else ""
        }
        shown += deltaValue
        return deltaValue
    }
}

/**
 * Streams generation from a locally loaded GGUF through [LlamaCppRuntime]. The prompt, the
 * grammar that constrains tool-call syntax, and the rules for parsing the reply all come from
 * the applied-template blob [LlamaCppRuntime.applyTemplate] returns; this provider treats that
 * blob as opaque, only ever handing it back to [LlamaCppRuntime.generate] and
 * [LlamaCppRuntime.parse], never parsing or rebuilding it.
 *
 * [streamText] resolves [TextGenerationParams.model]'s `modelId` - a bare file name, never a
 * path (see [LocalRuntimePreferences.installedModels]) - to the GGUF's absolute path, loads it
 * via [LlamaCppRuntime.load], and only then templates and generates. [loadedPath] tracks which
 * path is currently loaded so a repeat call for the same model is a no-op: reloading a
 * multi-GB model on every turn would make every message pay the load cost again.
 *
 * [LlamaCppRuntime.generate] blocks the calling thread for the whole generation, so it runs on
 * a child coroutine on [Dispatchers.IO] rather than inline in the flow body: cancelling a
 * coroutine only takes effect at a suspension point, and a blocking native call has none, so a
 * cancelled collector could not otherwise interrupt it. [awaitClose] runs concurrently with
 * that child and calls [LlamaCppRuntime.cancelGeneration] - the only way to interrupt a prefill,
 * per its doc - when the flow is cancelled before the generation finished on its own.
 */
class LlamaCppProvider(
    private val context: Context,
    private val runtime: LlamaCppRuntime,
    private val prefs: LocalRuntimePreferences,
) : Provider<ProviderSetting.LlamaCppLocal> {

    /** Path [runtime] currently has loaded, or null before the first call. Read and set by
     *  [ensureLoaded] so a repeat [streamText] call for the same model skips the reload. */
    private val loadedPath = AtomicReference<String?>(null)

    /** Serializes each [generateText]/[streamText] call's load-then-generate sequence into one
     *  atomic step, held from [ensureModelReady]'s already-loaded check through the generation
     *  that consumes the loaded model. [runtime] holds at most one model at a time, so releasing
     *  the lock right after loading (as a mutex guarding only [ensureModelReady] would) leaves a
     *  window where a second concurrent call - e.g. a post-turn generateTitle and
     *  generateSuggestion firing right after each other, configurable to different model ids -
     *  swaps [runtime] to its own model before the first call's generation reads it, feeding a
     *  mismatched template/tokenizer/grammar into native code. Mirrors LiteRtRuntime.streamTurns,
     *  which holds its own mutex for the whole inference so concurrent callers queue up rather
     *  than racing the loaded model. */
    private val loadMutex = Mutex()

    override suspend fun listModels(providerSetting: ProviderSetting.LlamaCppLocal): List<Model> =
        providerSetting.models

    override suspend fun generateText(
        providerSetting: ProviderSetting.LlamaCppLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = loadMutex.withLock {
        ensureModelReady(messages, params)
        generateFromLoadedModel(runtime, messages, params)
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.LlamaCppLocal,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> = flow {
        // loadMutex is held across the whole load-then-generate sequence, not just the load -
        // see the mutex's doc comment for why the generation itself must stay inside the lock.
        loadMutex.withLock {
            ensureModelReady(messages, params)
            emitAll(streamFromLoadedModel(runtime, messages, params))
        }
    }

    /** Resolves [TextGenerationParams.model]'s modelId to a path and loads it into [runtime]
     *  (a no-op when it is already loaded - see [ensureLoaded]). Shared by [generateText] and
     *  [streamText] so the two agree on exactly one load path; both already hold [loadMutex]
     *  before calling this. */
    private suspend fun ensureModelReady(messages: List<UIMessage>, params: TextGenerationParams) {
        val installed = prefs.installedModels(LocalRuntime.LlamaCpp)
        val modelPath = resolveModelPath(installed, params.model.modelId)
        // The load blocks the calling thread on a multi-gigabyte mmap, metadata parse and
        // context allocation, so it runs on Dispatchers.IO rather than whatever dispatcher the
        // caller used - mirrors LiteRtRuntime's engine init (LiteRtRuntime.kt:758). Without this,
        // a Main-confined caller like ProviderConnectionTester's rememberCoroutineScope would
        // load the model on the UI thread and ANR.
        withContext(Dispatchers.IO) {
            ensureLoaded(
                runtime = runtime,
                loadedPath = loadedPath,
                path = modelPath,
                tools = ChatRequestMapper.toolDeclarations(params.tools),
                systemPromptBytes = systemPromptBytesOf(messages),
                availableRamBytes = readAvailableRamBytes(),
            )
        }
    }

    /** Bytes of SYSTEM-role text across [messages] - the figure [LlamaCppRuntime.load] feeds
     *  `ContextPlanner` so the system prompt is accounted for before tools are fit into the
     *  context. */
    private fun systemPromptBytesOf(messages: List<UIMessage>): Int =
        messages
            .filter { it.role == MessageRole.SYSTEM }
            .flatMap { it.parts }
            .filterIsInstance<UIMessagePart.Text>()
            .sumOf { it.text.toByteArray(Charsets.UTF_8).size }

    /** Free RAM to size the context against - the same `ActivityManager.MemoryInfo.availMem`
     *  source `MemoryGuard.canLoad` reads, so the planner and the load-time refusal agree on
     *  what "available" means. */
    private fun readAvailableRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getMemoryInfo(info)
        return info.availMem
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> = error("llama.cpp does not support image generation")

    companion object {
        private const val DEFAULT_MAX_TOKENS = 2048

        /**
         * Resolves [modelId] - a bare file name, never a path - against [installed], the
         * `filename -> absolutePath` index [LocalRuntimePreferences.installedModels] returns.
         * Throws a user-readable, model-named error rather than letting a missing or stale
         * entry fail opaquely later on - mirrors `LiteRtProvider.streamText`'s guard.
         */
        internal fun resolveModelPath(installed: Map<String, String>, modelId: String): String {
            val path = installed[modelId]
                ?: throw IllegalStateException("Model $modelId not installed")
            if (!File(path).exists()) {
                throw IllegalStateException(
                    "Model file for \"$modelId\" is no longer present on disk ($path). " +
                        "Delete the model entry in Settings → Providers → Local · llama.cpp " +
                        "and re-download it."
                )
            }
            return path
        }

        /**
         * Loads [path] into [runtime] unless it is already the loaded model. Reloading a
         * multi-GB model on every turn is the obvious wrong implementation, so [loadedPath]
         * is checked first and only set to [path] after a real load succeeds.
         *
         * [LlamaCppRuntime.load] unconditionally unloads whatever was loaded before it
         * attempts [path], so the moment it is called the runtime no longer has
         * [loadedPath]'s old value loaded even if the new load then fails (e.g. an
         * oversized model throwing [ModelTooLargeException], or a corrupt file failing
         * natively). [loadedPath] is cleared to null right before that call so a failed
         * load leaves it reflecting "nothing loaded" rather than a model the runtime has
         * already discarded - otherwise a later call for that still-installed model would
         * see it as already loaded, skip the reload, and hit "no model is loaded" on the
         * very generation call this method exists to prevent.
         *
         * A path match still calls [LlamaCppRuntime.replan]: [tools] and
         * [systemPromptBytes] are this request's, not the ones the model happened to be
         * loaded with, and they can change turn to turn (a different assistant/tool
         * config) even when the model itself does not. Skipping that would leave a stale
         * [ContextPlan.droppedToolNames]/[ContextPlan.reservedInputBytes] in effect for
         * every request after the first against a given model.
         */
        internal suspend fun ensureLoaded(
            runtime: LlamaCppRuntime,
            loadedPath: AtomicReference<String?>,
            path: String,
            tools: List<ToolDeclaration>,
            systemPromptBytes: Int,
            availableRamBytes: Long,
        ) {
            if (loadedPath.get() == path) {
                runtime.replan(tools, systemPromptBytes)
                return
            }
            loadedPath.set(null)
            runtime.load(path, tools, systemPromptBytes, availableRamBytes)
            loadedPath.set(path)
        }

        /**
         * [generateText]'s logic, given a [runtime] that already has the right model loaded
         * (see [ensureLoaded]). Every chunk [streamFromLoadedModel] emits carries an
         * incremental delta, not the full message, so the pieces are summed here rather than
         * just keeping the last one - mirrors LiteRtProvider.generateText. Kept as a function
         * of an explicit [runtime] parameter for the same testability reason as
         * [streamFromLoadedModel].
         */
        internal suspend fun generateFromLoadedModel(
            runtime: LlamaCppRuntime,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): TextGenerationResult {
            var collected = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = emptyList()))
            val handler = StreamChunkHandler(params.model)
            streamFromLoadedModel(runtime, messages, params).collect { chunk ->
                collected = handler.handle(collected, chunk)
            }
            return TextGenerationResult(
                id = "llamacpp-${System.currentTimeMillis()}",
                model = params.model.modelId,
                message = collected.last(),
            )
        }

        /**
         * The streaming/chunking logic, given a [runtime] that already has the right model
         * loaded (see [ensureLoaded]). Kept as a function of an explicit [runtime] parameter
         * rather than an instance method reading a provider's own field so it stays
         * unit-testable without a live [Context]/[LocalRuntimePreferences] - see
         * `LlamaCppProviderTest`.
         */
        internal fun streamFromLoadedModel(
            runtime: LlamaCppRuntime,
            messages: List<UIMessage>,
            params: TextGenerationParams,
        ): Flow<StreamChunk> = callbackFlow {
            val streamId = "llamacpp-${System.currentTimeMillis()}"
            // One stable id per channel for the whole turn, so StreamChunkHandler appends
            // every delta into a single Text / Reasoning part - mirrors the old positional
            // append-only merge.
            val textId = "$streamId-text"
            val reasoningId = "$streamId-reasoning"
            // Trim history to the input half of the planned context before templating, so a
            // long conversation drops its oldest turns instead of overflowing the prompt.
            val trimmedMessages = ChatRequestMapper.trimToBudget(messages, runtime.inputBudgetBytes())
            val appliedTemplateJson = runtime.applyTemplate(
                ChatRequestMapper.toRequestJson(trimmedMessages, params.tools)
            )
            val tracker = ChatDeltaTracker()
            val accumulated = StringBuilder()
            val finished = AtomicBoolean(false)
            // The chunk protocol downstream (UIMessage's delta-append fold) can only ever
            // append, never retract, so these mirror what has actually been forwarded for
            // each channel - see ChannelWatermark's doc for how that reconciles textReset/
            // reasoningReset.
            val textWatermark = ChannelWatermark()
            val reasoningWatermark = ChannelWatermark()

            // A plain captured lambda, not an extension function: it closes over this
            // callbackFlow's ProducerScope directly, so calling it from inside the doubly-nested
            // onPiece callback below needs no implicit-receiver resolution of its own. isFinal
            // marks the terminal (isPartial = false) call so a reset there can still be
            // forwarded - see ChannelWatermark's doc.
            // Tracks whether a ReasoningDelta has been sent that a ReasoningEnd has not yet
            // closed, so a later delta with text but no reasoning can close it - mirrors the
            // old merge logic's "a delta with no reasoning part closes any open reasoning part".
            var reasoningOpen = false

            val sendDelta: (ChatDelta, Boolean) -> Boolean = { delta, isFinal ->
                val textDelta = textWatermark.advance(delta.textDelta, delta.textReset, isFinal)
                val reasoningDelta = reasoningWatermark.advance(delta.reasoningDelta, delta.reasoningReset, isFinal)
                var sentAny = false
                var allOk = true
                if (reasoningDelta.isNotEmpty()) {
                    sentAny = true
                    reasoningOpen = true
                    allOk = trySend(StreamChunk.ReasoningDelta(reasoningId, reasoningDelta)).isSuccess && allOk
                } else if (reasoningOpen && textDelta.isNotEmpty()) {
                    reasoningOpen = false
                    allOk = trySend(StreamChunk.ReasoningEnd(reasoningId)).isSuccess && allOk
                }
                if (textDelta.isNotEmpty()) {
                    sentAny = true
                    allOk = trySend(StreamChunk.TextDelta(textId, textDelta)).isSuccess && allOk
                }
                delta.completedToolCalls.forEach { call ->
                    sentAny = true
                    allOk = trySend(StreamChunk.ToolCallStart(id = call.id, toolName = call.name)).isSuccess && allOk
                    allOk = trySend(StreamChunk.ToolCallDelta(id = call.id, inputDelta = call.arguments)).isSuccess && allOk
                }
                if (sentAny) allOk else !isClosedForSend
            }

            val worker = launch(Dispatchers.IO) {
                try {
                    runtime.generate(appliedTemplateJson, params.maxTokens ?: DEFAULT_MAX_TOKENS) { piece ->
                        accumulated.append(piece)
                        val parsed = runtime.parse(accumulated.toString(), true, appliedTemplateJson)
                        sendDelta(tracker.consume(parsed, isPartial = true), false)
                    }

                    // The final parse is authoritative: it flushes any tool call whose arguments
                    // were still settling when generation stopped (see ChatDeltaTracker.consume).
                    val finalParsed = runtime.parse(accumulated.toString(), false, appliedTemplateJson)
                    sendDelta(tracker.consume(finalParsed, isPartial = false), true)
                    trySend(StreamChunk.Finish())
                } finally {
                    finished.set(true)
                    close()
                }
            }

            awaitClose {
                if (!finished.get()) {
                    runtime.cancelGeneration()
                }
                worker.cancel()
            }
        }
    }
}
