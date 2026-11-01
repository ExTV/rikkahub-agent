package me.rerere.llamacpp

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class LlamaCppProviderTest {

    /** Streams a fixed reply so the provider's chunking is testable without native code. */
    private class ScriptedNative(private val pieces: List<String>) : LlamaCppNative {
        override fun loadModel(path: String) = 1L
        override fun freeModel(handle: Long) {}
        override fun modelInfo(handle: Long): String = """
            {"n_layers":26,"n_embd":2048,"n_head_kv":4,"n_embd_head_k":256,
             "n_embd_head_v":256,"n_vocab":262144,"n_ctx_train":32768,
             "sliding_window":0,"weights_bytes":2600000000}
        """.trimIndent()
        override fun createContext(
            modelHandle: Long, nCtx: Int, nBatch: Int, nUBatch: Int,
            cacheTypeK: String, cacheTypeV: String, nThreads: Int,
        ) = 2L
        override fun freeContext(handle: Long) {}
        override fun cancelGeneration(handle: Long) {}
        override fun applyTemplate(modelHandle: Long, requestJson: String): String = "{}"
        override fun generate(
            ctxHandle: Long, modelHandle: Long, appliedTemplateJson: String,
            maxTokens: Int, onPiece: (String) -> Boolean,
        ) {
            for (piece in pieces) {
                if (!onPiece(piece)) return
            }
        }
        // Mirrors llama.cpp: each parse restates the whole message so far.
        override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String =
            """{"role":"assistant","content":"$text","reasoning_content":"","tool_calls":[]}"""
    }

    /** Like [ScriptedNative], but counts [loadModel] calls so a test can prove a repeat
     *  [LlamaCppProvider.ensureLoaded] for the same path does not touch native code again. */
    private class CountingNative : LlamaCppNative {
        var loadModelCallCount = 0
            private set

        override fun loadModel(path: String): Long {
            loadModelCallCount++
            return 1L
        }
        override fun freeModel(handle: Long) {}
        override fun modelInfo(handle: Long): String = """
            {"n_layers":26,"n_embd":2048,"n_head_kv":4,"n_embd_head_k":256,
             "n_embd_head_v":256,"n_vocab":262144,"n_ctx_train":32768,
             "sliding_window":0,"weights_bytes":2600000000}
        """.trimIndent()
        override fun createContext(
            modelHandle: Long, nCtx: Int, nBatch: Int, nUBatch: Int,
            cacheTypeK: String, cacheTypeV: String, nThreads: Int,
        ) = 2L
        override fun freeContext(handle: Long) {}
        override fun cancelGeneration(handle: Long) {}
        override fun applyTemplate(modelHandle: Long, requestJson: String): String = "{}"
        override fun generate(
            ctxHandle: Long, modelHandle: Long, appliedTemplateJson: String,
            maxTokens: Int, onPiece: (String) -> Boolean,
        ) {
            onPiece("ok")
        }
        override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String =
            """{"role":"assistant","content":"$text","reasoning_content":"","tool_calls":[]}"""
    }

    @Test
    fun `streams incremental chunks for a plain reply`() = runBlocking {
        val runtime = LlamaCppRuntime(ScriptedNative(listOf("Hel", "lo", " world")))
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        val chunks = LlamaCppProvider.streamFromLoadedModel(
            runtime = runtime,
            messages = listOf(UIMessage.user("hi")),
            params = TextGenerationParams(model = Model(modelId = "test.gguf"), maxTokens = 64),
        ).toList()

        val combined = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }
        assertTrue("expected the full reply, got '$combined'", combined.contains("Hello world"))

        runtime.unload()
    }

    @Test
    fun `generateText returns the full reply, not just the last streamed chunk`() = runBlocking {
        val runtime = LlamaCppRuntime(ScriptedNative(listOf("Hel", "lo", " world")))
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        val result = LlamaCppProvider.generateFromLoadedModel(
            runtime = runtime,
            messages = listOf(UIMessage.user("hi")),
            params = TextGenerationParams(model = Model(modelId = "test.gguf"), maxTokens = 64),
        )

        val text = result.message.toText()
        assertTrue("expected the full reply, got '$text'", text.contains("Hello world"))

        runtime.unload()
    }

    @Test
    fun `streaming without a loaded model fails loudly`() = runBlocking {
        val runtime = LlamaCppRuntime(ScriptedNative(emptyList()))

        val error = runCatching {
            LlamaCppProvider.streamFromLoadedModel(
                runtime = runtime,
                messages = listOf(UIMessage.user("hi")),
                params = TextGenerationParams(model = Model(modelId = "test.gguf")),
            ).toList()
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
    }

    /** Like [ScriptedNative], but records the request JSON handed to [applyTemplate], and
     *  how many times [loadModel] actually ran, so a test can check what the provider sent
     *  and whether it reloaded, without depending on native code. */
    private class CapturingNative(private val nCtxTrain: Int) : LlamaCppNative {
        var capturedRequestJson: String? = null
            private set
        var loadModelCallCount = 0
            private set

        override fun loadModel(path: String): Long {
            loadModelCallCount++
            return 1L
        }
        override fun freeModel(handle: Long) {}
        override fun modelInfo(handle: Long): String = """
            {"n_layers":26,"n_embd":2048,"n_head_kv":4,"n_embd_head_k":256,
             "n_embd_head_v":256,"n_vocab":262144,"n_ctx_train":$nCtxTrain,
             "sliding_window":0,"weights_bytes":2600000000}
        """.trimIndent()
        override fun createContext(
            modelHandle: Long, nCtx: Int, nBatch: Int, nUBatch: Int,
            cacheTypeK: String, cacheTypeV: String, nThreads: Int,
        ) = 2L
        override fun freeContext(handle: Long) {}
        override fun cancelGeneration(handle: Long) {}
        override fun applyTemplate(modelHandle: Long, requestJson: String): String {
            capturedRequestJson = requestJson
            return "{}"
        }
        override fun generate(
            ctxHandle: Long, modelHandle: Long, appliedTemplateJson: String,
            maxTokens: Int, onPiece: (String) -> Boolean,
        ) {
            onPiece("ok")
        }
        override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String =
            """{"role":"assistant","content":"$text","reasoning_content":"","tool_calls":[]}"""
    }

    @Test
    fun `streamText trims history to the runtime's input budget before templating`() = runBlocking {
        // n_ctx_train pins the plan to the smallest ladder rung (4096), which caps
        // inputBudgetBytes() at (4096 / 2) * 4 = 8192 bytes - too small for the 200-turn
        // history below, so trimming must actually happen for the request to fit.
        val native = CapturingNative(nCtxTrain = 4096)
        val runtime = LlamaCppRuntime(native)
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        val longHistory = (1..200).map {
            UIMessage.user("turn number $it padded with extra text so each message has real weight")
        }
        val untrimmedBytes = ChatRequestMapper.toRequestJson(longHistory, emptyList()).toByteArray().size

        LlamaCppProvider.streamFromLoadedModel(
            runtime = runtime,
            messages = longHistory,
            params = TextGenerationParams(model = Model(modelId = "test.gguf"), maxTokens = 64),
        ).toList()

        val sentJson = native.capturedRequestJson
        assertTrue("applyTemplate must have been called", sentJson != null)
        val sentMessages = JSONObject(sentJson!!).getJSONArray("messages")

        assertTrue(
            "expected the request actually sent (${sentJson.length} bytes) to be smaller " +
                "than the untrimmed request ($untrimmedBytes bytes)",
            sentJson.length < untrimmedBytes,
        )
        assertTrue(
            "expected fewer messages to reach applyTemplate than were passed in",
            sentMessages.length() < longHistory.size,
        )
        assertEquals(
            "the newest turn must survive trimming",
            "turn number 200 padded with extra text so each message has real weight",
            sentMessages.getJSONObject(sentMessages.length() - 1).getString("content"),
        )

        runtime.unload()
    }

    // resolveModelPath -----------------------------------------------------------------

    @Test
    fun `resolveModelPath throws a named error when the model is not installed`() {
        val error = runCatching {
            LlamaCppProvider.resolveModelPath(emptyMap(), "missing.gguf")
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(
            "expected the error to name the model, got '${error?.message}'",
            error?.message?.contains("missing.gguf") == true,
        )
        assertFalse(
            "must not be the generic runtime error a raw applyTemplate call would throw",
            error?.message?.contains("no model is loaded") == true,
        )
    }

    @Test
    fun `resolveModelPath throws when the registered file no longer exists on disk`() {
        val goneePath = "/tmp/definitely-not-here-${System.nanoTime()}.gguf"

        val error = runCatching {
            LlamaCppProvider.resolveModelPath(mapOf("gone.gguf" to goneePath), "gone.gguf")
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertTrue(error?.message?.contains("gone.gguf") == true)
    }

    @Test
    fun `resolveModelPath returns the installed path when the file exists`() {
        val tmp = File.createTempFile("model", ".gguf")
        tmp.deleteOnExit()

        val resolved = LlamaCppProvider.resolveModelPath(mapOf("model.gguf" to tmp.absolutePath), "model.gguf")

        assertEquals(tmp.absolutePath, resolved)
    }

    // ensureLoaded -----------------------------------------------------------------------

    @Test
    fun `ensureLoaded does not reload the same path twice`() = runBlocking {
        val native = CountingNative()
        val runtime = LlamaCppRuntime(native)
        val loadedPath = AtomicReference<String?>(null)

        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)
        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        assertEquals(
            "reloading the already-loaded path must not touch native code again",
            1,
            native.loadModelCallCount,
        )

        runtime.unload()
    }

    @Test
    fun `ensureLoaded reloads when the resolved path changes`() = runBlocking {
        val native = CountingNative()
        val runtime = LlamaCppRuntime(native)
        val loadedPath = AtomicReference<String?>(null)

        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/a.gguf", emptyList(), 0, 12_000_000_000L)
        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/b.gguf", emptyList(), 0, 12_000_000_000L)

        assertEquals(2, native.loadModelCallCount)

        runtime.unload()
    }

    /** Like [CountingNative], but reports an oversized weights_bytes for one specific path
     *  so switching to it makes [LlamaCppRuntime.load] throw a real [ModelTooLargeException]
     *  partway through - after it has already unloaded whatever was loaded before. */
    private class OneModelTooLargeNative(private val tooLargePath: String) : LlamaCppNative {
        var loadModelCallCount = 0
            private set
        private var lastLoadedPath: String? = null

        override fun loadModel(path: String): Long {
            loadModelCallCount++
            lastLoadedPath = path
            return 1L
        }
        override fun freeModel(handle: Long) {}
        override fun modelInfo(handle: Long): String {
            val weightsBytes = if (lastLoadedPath == tooLargePath) 999_000_000_000L else 2_600_000_000L
            return """
                {"n_layers":26,"n_embd":2048,"n_head_kv":4,"n_embd_head_k":256,
                 "n_embd_head_v":256,"n_vocab":262144,"n_ctx_train":32768,
                 "sliding_window":0,"weights_bytes":$weightsBytes}
            """.trimIndent()
        }
        override fun createContext(
            modelHandle: Long, nCtx: Int, nBatch: Int, nUBatch: Int,
            cacheTypeK: String, cacheTypeV: String, nThreads: Int,
        ) = 2L
        override fun freeContext(handle: Long) {}
        override fun cancelGeneration(handle: Long) {}
        override fun applyTemplate(modelHandle: Long, requestJson: String): String = "{}"
        override fun generate(
            ctxHandle: Long, modelHandle: Long, appliedTemplateJson: String,
            maxTokens: Int, onPiece: (String) -> Boolean,
        ) {
            onPiece("ok")
        }
        override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String =
            """{"role":"assistant","content":"$text","reasoning_content":"","tool_calls":[]}"""
    }

    @Test
    fun `ensureLoaded retries a still-installed model after a failed switch, instead of skipping it`() = runBlocking {
        val native = OneModelTooLargeNative(tooLargePath = "/tmp/big.gguf")
        val runtime = LlamaCppRuntime(native)
        val loadedPath = AtomicReference<String?>(null)

        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/small.gguf", emptyList(), 0, 12_000_000_000L)
        assertEquals(1, native.loadModelCallCount)

        // load() unloads the small model as its first step, then fails on the oversized one.
        val error = runCatching {
            LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/big.gguf", emptyList(), 0, 12_000_000_000L)
        }.exceptionOrNull()
        assertTrue("expected ModelTooLargeException, got $error", error is ModelTooLargeException)

        // The runtime now has nothing loaded, so switching back to the still-installed small
        // model must trigger a real reload rather than being skipped as "already loaded".
        LlamaCppProvider.ensureLoaded(runtime, loadedPath, "/tmp/small.gguf", emptyList(), 0, 12_000_000_000L)
        assertEquals(
            "the previously-working model must be reloaded after a failed switch, not skipped",
            3,
            native.loadModelCallCount,
        )

        runtime.unload()
    }

    // inputBudgetBytes / replan ----------------------------------------------------------

    @Test
    fun `inputBudgetBytes reserves bytes for the system prompt and the tools that survived`() = runBlocking {
        // n_ctx_train pins the plan to nCtx 4096, so ContextPlanner.inputByteBudget(4096) is
        // a fixed, known figure to check the reservation against.
        val native = CapturingNative(nCtxTrain = 4096)
        val runtime = LlamaCppRuntime(native)
        val tools = listOf(ToolDeclaration("get_weather", jsonBytes = 300))

        runtime.load("/tmp/m.gguf", tools, systemPromptBytes = 500, availableRamBytes = 12_000_000_000L)

        val expected = ContextPlanner.inputByteBudget(4096) - 500 - 300
        assertEquals(expected, runtime.inputBudgetBytes())
        assertTrue(
            "the old (nCtx / 2) * BYTES_PER_TOKEN formula ignored system+tool bytes and " +
                "must no longer be what this returns",
            runtime.inputBudgetBytes() < (4096 / 2) * ContextPlanner.BYTES_PER_TOKEN,
        )

        runtime.unload()
    }

    @Test
    fun `ensureLoaded re-evaluates the per-request plan on a path match instead of returning early`() = runBlocking {
        val native = CapturingNative(nCtxTrain = 4096)
        val runtime = LlamaCppRuntime(native)
        val loadedPath = AtomicReference<String?>(null)
        val manyTools = (1..40).map { ToolDeclaration("tool_$it", jsonBytes = 700) }

        LlamaCppProvider.ensureLoaded(
            runtime, loadedPath, "/tmp/m.gguf", manyTools, systemPromptBytes = 500, availableRamBytes = 12_000_000_000L,
        )
        val budgetWithManyTools = runtime.inputBudgetBytes()

        // Same path - no reload - but a much smaller tool set for this request. Returning
        // early on the path match would leave the first call's dropped-tools/reserved-bytes
        // plan in effect here too.
        LlamaCppProvider.ensureLoaded(
            runtime, loadedPath, "/tmp/m.gguf", emptyList(), systemPromptBytes = 500, availableRamBytes = 12_000_000_000L,
        )
        val budgetWithNoTools = runtime.inputBudgetBytes()

        assertEquals("a path match must not reload the model", 1, native.loadModelCallCount)
        assertTrue(
            "the input budget must grow once the tool set shrinks, proving the per-request " +
                "plan was re-evaluated rather than reused from the first call",
            budgetWithNoTools > budgetWithManyTools,
        )

        runtime.unload()
    }

    // sendDelta / textReset / reasoningReset ----------------------------------------------

    /** Reports a fixed sequence of whole-message parses regardless of what [generate] fed
     *  it, so a test can engineer a specific reset scenario deterministically. [generate]
     *  delivers one "piece" per parse before the last - the last is delivered by the
     *  provider's own final (isPartial = false) parse. */
    private class ScriptedParseNative(private val parses: List<Pair<String, String>>) : LlamaCppNative {
        private var call = 0
        override fun loadModel(path: String) = 1L
        override fun freeModel(handle: Long) {}
        override fun modelInfo(handle: Long): String = """
            {"n_layers":26,"n_embd":2048,"n_head_kv":4,"n_embd_head_k":256,
             "n_embd_head_v":256,"n_vocab":262144,"n_ctx_train":32768,
             "sliding_window":0,"weights_bytes":2600000000}
        """.trimIndent()
        override fun createContext(
            modelHandle: Long, nCtx: Int, nBatch: Int, nUBatch: Int,
            cacheTypeK: String, cacheTypeV: String, nThreads: Int,
        ) = 2L
        override fun freeContext(handle: Long) {}
        override fun cancelGeneration(handle: Long) {}
        override fun applyTemplate(modelHandle: Long, requestJson: String): String = "{}"
        override fun generate(
            ctxHandle: Long, modelHandle: Long, appliedTemplateJson: String,
            maxTokens: Int, onPiece: (String) -> Boolean,
        ) {
            for (i in 0 until parses.size - 1) if (!onPiece("piece")) return
        }
        override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String {
            val (content, reasoning) = parses[call.coerceAtMost(parses.size - 1)]
            call++
            return """{"role":"assistant","content":"$content","reasoning_content":"$reasoning","tool_calls":[]}"""
        }
    }

    @Test
    fun `a mid-stream reset does not resend already-streamed text before the next delta catches up`() =
        runBlocking {
            // The second (still-partial) parse reformats what was already sent ("Hello") with a
            // leading space instead of extending it - ChatDeltaTracker flags this as textReset.
            // A later delta still extends past the corrected value, so the reset here is not
            // terminal: naively forwarding the reset delta in full would resend "Hello" a second
            // time on top of what streamed already, producing "Hello Hello!".
            val native = ScriptedParseNative(
                listOf(
                    "Hello" to "",
                    " Hello!" to "",
                    " Hello! Nice to meet you" to "",
                )
            )
            val runtime = LlamaCppRuntime(native)
            runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

            val chunks = LlamaCppProvider.streamFromLoadedModel(
                runtime = runtime,
                messages = listOf(UIMessage.user("hi")),
                params = TextGenerationParams(model = Model(modelId = "test.gguf"), maxTokens = 64),
            ).toList()

            val combined = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }

            assertEquals("Hello Nice to meet you", combined)

            runtime.unload()
        }

    @Test
    fun `a reset on the terminal parse forwards the corrected text instead of losing it`() = runBlocking {
        // The third parse - the final, isPartial = false one, with no delta after it - reformats
        // what was already sent ("Hello world") with a leading space instead of extending it.
        // There is no later delta to catch up, so swallowing this reset the way a mid-stream one
        // is swallowed would silently drop the model's real final text. The correction is
        // forwarded in full instead, even though it duplicates the "Hello world" already shown.
        val native = ScriptedParseNative(
            listOf(
                "Hello" to "",
                "Hello world" to "",
                " Hello world!" to "",
            )
        )
        val runtime = LlamaCppRuntime(native)
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        val chunks = LlamaCppProvider.streamFromLoadedModel(
            runtime = runtime,
            messages = listOf(UIMessage.user("hi")),
            params = TextGenerationParams(model = Model(modelId = "test.gguf"), maxTokens = 64),
        ).toList()

        val combined = chunks.filterIsInstance<StreamChunk.TextDelta>().joinToString("") { it.text }

        assertEquals("Hello world Hello world!", combined)

        runtime.unload()
    }

    @Test
    fun `a mid-stream reasoning reset does not resend already-streamed reasoning before the next delta catches up`() =
        runBlocking {
            // The reasoning channel goes through the exact same non-extending reformat as the
            // text case above, followed by a further extension - must not duplicate either.
            val native = ScriptedParseNative(
                listOf(
                    "" to "Thinking",
                    "" to " Thinking!",
                    "answer" to " Thinking! Almost there",
                )
            )
            val runtime = LlamaCppRuntime(native)
            runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

            val chunks = LlamaCppProvider.streamFromLoadedModel(
                runtime = runtime,
                messages = listOf(UIMessage.user("hi")),
                params = TextGenerationParams(model = Model(modelId = "test.gguf"), maxTokens = 64),
            ).toList()

            val combinedReasoning = chunks.filterIsInstance<StreamChunk.ReasoningDelta>()
                .joinToString("") { it.text }

            assertEquals("Thinking Almost there", combinedReasoning)

            runtime.unload()
        }

    @Test
    fun `a reasoning reset on the terminal parse forwards the corrected reasoning instead of losing it`() =
        runBlocking {
            // The reasoning channel's terminal reset must not be silently dropped either -
            // reasoning is user-visible content just like the text channel.
            val native = ScriptedParseNative(
                listOf(
                    "" to "Thinking",
                    "" to "Thinking about it",
                    "answer" to " Thinking about it!",
                )
            )
            val runtime = LlamaCppRuntime(native)
            runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

            val chunks = LlamaCppProvider.streamFromLoadedModel(
                runtime = runtime,
                messages = listOf(UIMessage.user("hi")),
                params = TextGenerationParams(model = Model(modelId = "test.gguf"), maxTokens = 64),
            ).toList()

            val combinedReasoning = chunks.filterIsInstance<StreamChunk.ReasoningDelta>()
                .joinToString("") { it.text }

            assertEquals("Thinking about it Thinking about it!", combinedReasoning)

            runtime.unload()
        }
}
