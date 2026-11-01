package me.rerere.llamacpp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class LlamaCppGenerateTest {

    private val fixture = File("/data/local/tmp/llamacpp-test.gguf")

    /**
     * 512 tokens, not the 4096 a real deployment would plan. The KV cache is the dominant
     * allocation in this whole suite and it scales linearly with the context: this fixture has
     * 28 layers, 8 KV heads and 128-dimensional heads, so an f16 cache costs 28 * 8 * 128 * 2 *
     * 2 = 112 KB per token, which is 470 MB at 4096 against 59 MB at 512.
     *
     * That is not a tidiness point. At 4096 a single test peaked at 1069 MB resident on top of
     * the 428 MB model, and Android's low-memory killer takes the whole instrumentation process
     * when the device is under pressure. That surfaces as "Test run failed to complete due to
     * Process crashed" with no tombstone and nothing in the crash buffer, attributed to
     * whichever test happened to be running, which is what made it look like a native fault.
     *
     * 512 is above what any test here needs: the largest is the tool round trip, at roughly a
     * 200-token prompt plus a 128-token budget.
     */
    private fun withModelAndContext(block: (model: Long, ctx: Long) -> Unit) {
        val model = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        val ctx = LlamaCppJni.nativeCreateContext(model, 512, 512, 512, "f16", "f16", 4)
        try {
            block(model, ctx)
        } finally {
            LlamaCppJni.nativeFreeContext(ctx)
            LlamaCppJni.nativeFreeModel(model)
        }
    }

    private fun applied(
        model: Long,
        userText: String,
        toolsJson: String = "",
        toolChoice: String = "",
        enableThinking: Boolean? = null,
    ): String =
        LlamaCppJni.applyTemplate(
            model,
            JSONObject()
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", userText)),
                )
                .apply {
                    if (toolsJson.isNotEmpty()) put("tools", JSONArray(toolsJson))
                    if (toolChoice.isNotEmpty()) put("tool_choice", toolChoice)
                    if (enableThinking != null) put("enable_thinking", enableThinking)
                }
                .toString(),
        )

    /**
     * Asserts the native guarantee that a piece never ends mid-character, on the piece itself
     * rather than on the joined result. A strict decoder is the point: the default decoder
     * substitutes a replacement character and returns successfully, which is exactly the
     * silent corruption this is here to catch.
     */
    private fun assertWholeCharacters(piece: ByteArray) {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        try {
            decoder.decode(ByteBuffer.wrap(piece))
        } catch (e: Exception) {
            throw AssertionError("a streamed piece is not whole UTF-8: ${piece.toList()}", e)
        }
    }

    private fun collect(
        ctx: Long,
        model: Long,
        appliedJson: String,
        maxTokens: Int,
        stopAfter: Int = Int.MAX_VALUE,
    ): List<ByteArray> {
        val pieces = mutableListOf<ByteArray>()
        LlamaCppJni.nativeGenerate(
            ctxHandle = ctx,
            modelHandle = model,
            appliedTemplateJson = appliedJson.toByteArray(Charsets.UTF_8),
            maxTokens = maxTokens,
            sink = object : LlamaCppJni.TokenSink {
                override fun onToken(pieceUtf8: ByteArray): Boolean {
                    assertWholeCharacters(pieceUtf8)
                    pieces += pieceUtf8
                    return pieces.size < stopAfter
                }
            },
        )
        return pieces
    }

    @Test
    fun streamsTokensForAPrompt() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        withModelAndContext { model, ctx ->
            val pieces = collect(ctx, model, applied(model, "Count: 1 2 3"), maxTokens = 24)
            assertTrue("expected streamed tokens", pieces.isNotEmpty())
            val text = pieces.joinToString("") { String(it, Charsets.UTF_8) }
            assertTrue("expected non-empty text, got '$text'", text.isNotBlank())
        }
    }

    @Test
    fun aGrammarConstrainsWhatIsGenerated() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        withModelAndContext { model, ctx ->
            // Forcing the output with a grammar is what makes this deterministic, and it tests
            // two things at once. That the grammar reaches the sampler at all: without it a
            // 0.6B model asked to say this would not, so an exact match cannot happen by
            // chance. And that multi-byte characters survive the stream: none of these three
            // are single tokens in a byte-level BPE vocabulary, so each is emitted as several
            // byte-sized tokens, every one of which is half a character on its own.
            val forced = "Ὁᚦ🔂"
            val blob = JSONObject(applied(model, "Say anything."))
                .put("grammar", "root ::= \"$forced\"")
                .put("grammar_lazy", false)
                .put("grammar_triggers", JSONArray())
                // Cleared because a non-lazy tool-call grammar is otherwise advanced past the
                // generation prompt already in the prompt, and this synthetic grammar does not
                // accept those tokens.
                .put("generation_prompt", "")
                .toString()

            val pieces = collect(ctx, model, blob, maxTokens = 32)
            val text = pieces.joinToString("") { String(it, Charsets.UTF_8) }
            assertEquals("the grammar must decide the output exactly", forced, text)
            // Measured on the fixture: the model emits these ten bytes as ten byte-sized
            // tokens, and they arrive as three pieces, one whole character each. Disabling the
            // hold-back in incompleteUtf8Tail makes the first piece the bare byte 0xE1 and
            // fails assertWholeCharacters above, so this is a live check, not a lucky one.
            assertEquals("each character must arrive whole, not byte by byte", 3, pieces.size)
        }
    }

    /**
     * Around 150 tokens once templated. The size is picked against a hard floor: llama.cpp
     * rounds a context up with GGML_PAD(n_ctx, 256), so 256 is the smallest context that can
     * exist and anything asserting about a full context has to be over half of that. It is no
     * larger than it needs to be, because this suite builds llama.cpp unoptimised and the
     * device works through a prompt at roughly a token per second.
     */
    private val filler = (1..14).joinToString(" ") { "Fact $it: the number $it is a number." }

    @Test
    fun aPromptLongerThanOneBatchStillGenerates() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        // Handing llama_decode more tokens than n_batch is not an error return: it is
        // GGML_ASSERT(n_tokens_all <= cparams.n_batch) inside llama_context::decode, which
        // aborts the process. No exception, no Java stack trace, the test run simply dies. Any
        // real chat prompt carrying a system prompt and a few tool schemas is past a normal
        // batch, so the prefill has to be sliced.
        //
        // The batch is 8 rather than the planner's 512 so that even a two-word prompt is
        // several batches long. Making the prompt long enough to span a realistic batch is the
        // obvious alternative and it is the wrong trade: at this suite's speed it costs minutes
        // per run, and the slicing loop cannot tell how big the slices are.
        val model = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        val ctx = LlamaCppJni.nativeCreateContext(model, 256, 8, 8, "f16", "f16", 4)
        try {
            val pieces = collect(ctx, model, applied(model, "Say hi"), maxTokens = 2)
            assertTrue("a multi-batch prompt must still produce output", pieces.isNotEmpty())
        } finally {
            LlamaCppJni.nativeFreeContext(ctx)
            LlamaCppJni.nativeFreeModel(model)
        }
    }

    @Test
    fun aSecondGenerationDoesNotInheritTheFirst() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        // llama_batch_get_one continues from wherever the previous decode left off, so unless
        // the cache is cleared each time, the second generation's prompt is appended to the
        // first conversation instead of replacing it. The prompt already contains the whole
        // history, so that silently duplicates every earlier turn and exhausts the context
        // within a few turns.
        //
        // Context overflow is the discriminator. The prompt is comfortably over half of the 256
        // the context ends up with and comfortably under all of it, so one generation fits and
        // two concatenated do not: without the clear the second call fails with "prompt does
        // not fit the context". Verified by removing the clear and watching this fail on 186
        // tokens, which matters because two earlier sizings of this test passed either way:
        // a context asked for below 256 is silently rounded up to it.
        val model = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        val ctx = LlamaCppJni.nativeCreateContext(model, 256, 256, 256, "f16", "f16", 4)
        try {
            val blob = applied(model, filler)
            assertTrue("the first generation must produce output", collect(ctx, model, blob, 2).isNotEmpty())
            assertTrue("the second generation must produce output", collect(ctx, model, blob, 2).isNotEmpty())
        } finally {
            LlamaCppJni.nativeFreeContext(ctx)
            LlamaCppJni.nativeFreeModel(model)
        }
    }

    @Test
    fun returningFalseFromTheSinkStopsGenerationPromptly() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        withModelAndContext { model, ctx ->
            var count = 0
            val elapsed = measureTimeMillis {
                LlamaCppJni.nativeGenerate(
                    ctxHandle = ctx,
                    modelHandle = model,
                    appliedTemplateJson = applied(model, "Write a long essay.")
                        .toByteArray(Charsets.UTF_8),
                    maxTokens = 4096,
                    sink = object : LlamaCppJni.TokenSink {
                        override fun onToken(pieceUtf8: ByteArray): Boolean {
                            count++
                            return count < 5 // stop after five
                        }
                    },
                )
            }
            // Exactly five, not a range: the sink is not called again after it returns false,
            // so anything else means generation ran on past the stop.
            assertEquals("must stop on the token the sink refused", 5, count)
            // LiteRT shipped a bug where cancelling left native generation running.
            assertTrue("stopping must be prompt, took ${elapsed}ms", elapsed < 60_000)
        }
    }

    @Test
    fun cancellingFromAnotherThreadStopsGeneration() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        // Deliberately not using withModelAndContext: this is the one test with native work on
        // another thread, so it has to own the order in which things are released. Freeing a
        // context while a worker is still inside nativeGenerate is a use-after-free that takes
        // the whole process down, and a helper whose finally block runs on the way out of a
        // failed assertion would do exactly that.
        val model = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        val ctx = LlamaCppJni.nativeCreateContext(model, 512, 512, 512, "f16", "f16", 4)
        val budget = 256

        // The case a sink cannot cover: stopping generation from outside it. This is what a
        // cancelled coroutine does, and the sink is not called during a prefill at all, so
        // between-token polling could never reach it.
        val blob = applied(model, "Write a very long essay about the sea.")
            .toByteArray(Charsets.UTF_8)
        val firstPiece = CountDownLatch(1)
        var count = 0
        val failure = AtomicReference<Throwable>()

        val worker = thread(name = "llamacpp-generate") {
            try {
                LlamaCppJni.nativeGenerate(
                    ctxHandle = ctx,
                    modelHandle = model,
                    appliedTemplateJson = blob,
                    maxTokens = budget,
                    sink = object : LlamaCppJni.TokenSink {
                        override fun onToken(pieceUtf8: ByteArray): Boolean {
                            count++
                            firstPiece.countDown()
                            return true // never stops on its own
                        }
                    },
                )
            } catch (t: Throwable) {
                failure.set(t)
            }
        }

        var startedStreaming = false
        try {
            startedStreaming = firstPiece.await(120, TimeUnit.SECONDS)
        } finally {
            // Unconditional, and before any assertion can unwind: whatever happened above, the
            // worker must be out of native code before anything is released.
            LlamaCppJni.nativeCancelGeneration(ctx)
            worker.join(120_000)
            if (worker.isAlive) {
                // Nothing safe is left to do. Freeing now would corrupt the heap under a live
                // decode and crash a later, innocent test; leaking a context in a test process
                // that is about to exit costs nothing by comparison.
                throw AssertionError("generation ignored cancellation, leaking the context rather than freeing it under it")
            }
            LlamaCppJni.nativeFreeContext(ctx)
            LlamaCppJni.nativeFreeModel(model)
        }

        assertTrue("generation never produced a token", startedStreaming)
        assertTrue("cancellation must not fail: ${failure.get()}", failure.get() == null)
        // The sink asked for the full budget and never refused a token, so only the cancel can
        // have ended this. Anything near the budget means the flag was ignored.
        assertTrue("expected far fewer than the $budget requested, got $count", count < 200)
    }

    @Test
    fun generatingWithToolsProducesAToolCallThatParsesBack() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        withModelAndContext { model, ctx ->
            // The whole feature end to end: declare a tool, generate, parse what came back,
            // get the call out. Nothing else here proves the generate and parse halves agree.
            //
            // tool_choice "required" is what makes this deterministic. Under the default
            // "auto" the template builds a lazy grammar with min_calls of zero, so declining
            // to call anything is a legal completion and a 0.6B model takes that option: the
            // first attempt at this test generated the empty string and parsed to empty
            // content. "required" makes the grammar eager with min_calls of one, so a
            // syntactically valid call is the only thing the sampler will allow.
            val tools = """
                [{"type":"function","function":{"name":"get_time",
                  "description":"Get the current time",
                  "parameters":{"type":"object","properties":{"zone":{"type":"string"}},
                  "required":["zone"]}}}]
            """.trimIndent()
            val blob = applied(
                // Terse on purpose: the eager grammar still allows a free-text section before
                // the call, so anything inviting a chatty preamble is paid for token by token.
                model, "Use the tool. Zone Europe/Paris.", tools,
                toolChoice = "required",
                // Thinking off, or the budget goes on a reasoning block before the call is
                // ever reached: at 48 tokens the first attempt at this returned only "<think>".
                enableThinking = false,
            )
            val source = JSONObject(blob)
            assertTrue("the fixture must produce a tool grammar", source.getString("grammar").isNotBlank())
            assertTrue("a required tool choice must produce an eager grammar", !source.getBoolean("grammar_lazy"))

            val pieces = collect(ctx, model, blob, maxTokens = 128)
            val text = pieces.joinToString("") { String(it, Charsets.UTF_8) }

            val parsed = JSONObject(LlamaCppJni.parseChat(text, isPartial = false, appliedTemplateJson = blob))
            val calls = parsed.optJSONArray("tool_calls")
            assertTrue(
                "a generated tool call must survive the round trip, generated '$text', parsed $parsed",
                calls != null && calls.length() > 0,
            )
            assertEquals("get_time", calls!!.getJSONObject(0).getJSONObject("function").getString("name"))
        }
    }

    @Test
    fun parsingRecoversAToolCall() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val model = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            // Fixed text rather than generated text, because what is under test is the parse,
            // and a 0.6B model cannot be relied on to emit a tool call on demand. This is the
            // syntax this fixture's own template instructs the model to use.
            val tools = """
                [{"type":"function","function":{"name":"get_time",
                  "description":"Get the current time",
                  "parameters":{"type":"object","properties":{"zone":{"type":"string"}},
                  "required":["zone"]}}}]
            """.trimIndent()
            val blob = applied(model, "What time is it in Paris?", tools)
            val response = """
                <think>
                The user wants the time.
                </think>

                <tool_call>
                {"name": "get_time", "arguments": {"zone": "Europe/Paris"}}
                </tool_call>
            """.trimIndent()

            val parsed = JSONObject(LlamaCppJni.parseChat(response, isPartial = false, appliedTemplateJson = blob))
            val calls = parsed.optJSONArray("tool_calls")
            // The assertion that matters. Parsing with only the format name produces a
            // content-only parse that returns the raw text as content and no tool_calls at
            // all, without failing, so an absent array is the failure mode to catch.
            assertTrue("the tool call must be recovered, got: $parsed", calls != null && calls.length() == 1)
            val fn = calls!!.getJSONObject(0).getJSONObject("function")
            assertEquals("get_time", fn.getString("name"))
            assertTrue(
                "the arguments must survive, got: ${fn.getString("arguments")}",
                fn.getString("arguments").contains("Europe/Paris"),
            )
        } finally {
            LlamaCppJni.nativeFreeModel(model)
        }
    }

    @Test
    fun parsingSplitsThinkingOutOfTheAnswer() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val model = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            val blob = applied(model, "Is 7 prime?")
            val response = "<think>\n7 has no divisors but 1 and itself.\n</think>\n\nYes, 7 is prime."

            val parsed = JSONObject(LlamaCppJni.parseChat(response, isPartial = false, appliedTemplateJson = blob))
            // Reasoning extraction is decided when the template is applied, not here: the
            // parser is built to split thinking out only if reasoning_format was set then.
            // Left at its default the parse still succeeds and still returns content, with the
            // thinking silently folded into it, which is why both halves are asserted.
            assertTrue(
                "thinking must reach reasoning_content, got: $parsed",
                parsed.optString("reasoning_content").contains("no divisors"),
            )
            val content = parsed.optString("content")
            assertTrue("the answer must reach content, got: $content", content.contains("Yes, 7 is prime"))
            assertTrue("thinking must not also stay in content, got: $content", !content.contains("no divisors"))
        } finally {
            LlamaCppJni.nativeFreeModel(model)
        }
    }

    @Test
    fun parsingStreamedTextRecoversWhatWasGenerated() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        withModelAndContext { model, ctx ->
            // Joins the two halves: text this runtime actually generated, read back by this
            // runtime's parser, with the blob that produced it. Nothing else in this suite
            // proves the two agree on the same applied-template document.
            val blob = applied(model, "Reply with the single word: apple")
            val pieces = collect(ctx, model, blob, maxTokens = 24)
            val text = pieces.joinToString("") { String(it, Charsets.UTF_8) }

            val parsed = JSONObject(LlamaCppJni.parseChat(text, isPartial = true, appliedTemplateJson = blob))
            val recovered = parsed.optString("content") + parsed.optString("reasoning_content")
            assertTrue("the parse must return the generated text somewhere, got: $parsed", recovered.isNotBlank())
        }
    }

    @Test
    fun createContextOnAZeroHandleThrows() {
        val error = assertThrows(RuntimeException::class.java) {
            LlamaCppJni.nativeCreateContext(0L, 512, 128, 128, "f16", "f16", 2)
        }
        assertTrue("expected the null-handle message, got: ${error.message}", error.message == "model handle is null")
    }

    @Test
    fun createContextWithAnUnknownCacheTypeThrows() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val model = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            // Falling back to f16 here would hand back a context larger than the one
            // ContextPlanner sized and proved would fit, so the unknown id has to be refused.
            val error = assertThrows(RuntimeException::class.java) {
                LlamaCppJni.nativeCreateContext(model, 512, 128, 128, "q4_0", "f16", 2)
            }
            assertTrue(
                "expected an unknown cache type error, got: ${error.message}",
                error.message?.contains("unknown kv cache type") == true,
            )
        } finally {
            LlamaCppJni.nativeFreeModel(model)
        }
    }

    @Test
    fun generateOnAZeroHandleThrows() {
        val error = assertThrows(RuntimeException::class.java) {
            LlamaCppJni.generate(0L, 0L, """{"prompt":"hi"}""", 4) { true }
        }
        assertTrue(
            "expected the null-handle message, got: ${error.message}",
            error.message == "context or model handle is null",
        )
    }

    @Test
    fun aLazyGrammarWithNoTriggersIsRefused() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        withModelAndContext { model, ctx ->
            // Such a grammar never activates, so generation would run unconstrained while
            // every visible sign said otherwise. This is the shape a caller that forwarded
            // the grammar but dropped its triggers would produce.
            val blob = JSONObject(applied(model, "hi"))
                .put("grammar", "root ::= \"x\"")
                .put("grammar_lazy", true)
                .put("grammar_triggers", JSONArray())
                .toString()
            val error = assertThrows(RuntimeException::class.java) {
                LlamaCppJni.generate(ctx, model, blob, 4) { true }
            }
            assertTrue(
                "expected the inert-grammar error, got: ${error.message}",
                error.message?.contains("never activate") == true,
            )
        }
    }

    @Test
    fun parsingWithoutAParserIsRefused() {
        // An applied-template blob that lost its parser field parses as content-only without
        // failing, silently dropping every tool call, so the missing field must be an error.
        val error = assertThrows(RuntimeException::class.java) {
            LlamaCppJni.parseChat("hello", isPartial = false, appliedTemplateJson = """{"format":"peg-native"}""")
        }
        assertTrue(
            "expected the missing-parser error, got: ${error.message}",
            error.message?.contains("no parser") == true,
        )
    }
}
