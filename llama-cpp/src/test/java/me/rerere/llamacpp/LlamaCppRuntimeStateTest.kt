package me.rerere.llamacpp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class LlamaCppRuntimeStateTest {

    /**
     * Fake with no native library involved: [generate] normally returns one fixed piece
     * immediately, but a test can install [blockOnGenerate] to hold it open on a
     * background thread, which is what the concurrency tests below need to observe state
     * while a generation is still "running".
     */
    private class FakeNative : LlamaCppNative {
        var loadedPath: String? = null
        var freedModel = false
        var freedContext = false
        var createdCtx: Int = 0
        var cancelledHandle: Long? = null
        var blockOnGenerate: (() -> Unit)? = null

        override fun loadModel(path: String): Long { loadedPath = path; return 1L }
        override fun freeModel(handle: Long) { freedModel = true }
        override fun modelInfo(handle: Long): String = """
            {"n_layers":26,"n_embd":2048,"n_head_kv":4,"n_embd_head_k":256,
             "n_embd_head_v":256,"n_vocab":262144,"n_ctx_train":32768,
             "sliding_window":0,"weights_bytes":2600000000}
        """.trimIndent()
        override fun createContext(
            modelHandle: Long, nCtx: Int, nBatch: Int, nUBatch: Int,
            cacheTypeK: String, cacheTypeV: String, nThreads: Int,
        ): Long { createdCtx = nCtx; return 2L }
        override fun freeContext(handle: Long) { freedContext = true }
        override fun cancelGeneration(handle: Long) { cancelledHandle = handle }
        override fun applyTemplate(modelHandle: Long, requestJson: String): String = "{}"
        override fun generate(
            ctxHandle: Long, modelHandle: Long, appliedTemplateJson: String,
            maxTokens: Int, onPiece: (String) -> Boolean,
        ) {
            blockOnGenerate?.invoke()
            onPiece("hi")
        }
        override fun parseChat(text: String, isPartial: Boolean, appliedTemplateJson: String): String = "{}"
    }

    @Test
    fun `the context is created with exactly the planned size`() = runBlocking {
        val native = FakeNative()
        val runtime = LlamaCppRuntime(native)
        val plan = runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)
        assertEquals(
            "the engine must be created with the planned context, not some other number",
            plan.nCtx, native.createdCtx,
        )
        runtime.unload()
    }

    @Test
    fun `unload releases both handles`() = runBlocking {
        val native = FakeNative()
        val runtime = LlamaCppRuntime(native)
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)
        runtime.unload()
        assertTrue("context must be freed", native.freedContext)
        assertTrue("model must be freed", native.freedModel)
    }

    @Test
    fun `generating without a loaded model fails loudly`() {
        val runtime = LlamaCppRuntime(FakeNative())
        val error = runCatching {
            runtime.generate("{}", 8) { true }
        }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
    }

    @Test
    fun `applying a template without a loaded model fails loudly`() {
        val runtime = LlamaCppRuntime(FakeNative())
        val error = runCatching { runtime.applyTemplate("{}") }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
    }

    @Test
    fun `cancelGeneration forwards to native on the loaded context handle`() = runBlocking {
        val native = FakeNative()
        val runtime = LlamaCppRuntime(native)
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)
        runtime.cancelGeneration()
        assertEquals(2L, native.cancelledHandle)
        runtime.unload()
    }

    @Test
    fun `cancelGeneration is a harmless no-op when nothing is loaded`() {
        val native = FakeNative()
        LlamaCppRuntime(native).cancelGeneration()
        assertTrue("nothing was loaded, so native must never see a cancel", native.cancelledHandle == null)
    }

    @Test
    fun `a second generation is refused while one is already running`() = runBlocking {
        val native = FakeNative()
        val runtime = LlamaCppRuntime(native)
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        native.blockOnGenerate = { started.countDown(); release.await() }

        val worker = thread(name = "first-generation") { runtime.generate("{}", 8) { true } }
        assertTrue("first generation must start", started.await(5, TimeUnit.SECONDS))

        val error = runCatching { runtime.generate("{}", 8) { true } }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)

        release.countDown()
        worker.join(5_000)
        runtime.unload()
    }

    @Test
    fun `applyTemplate is refused while a generation is already running`() = runBlocking {
        val native = FakeNative()
        val runtime = LlamaCppRuntime(native)
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        native.blockOnGenerate = { started.countDown(); release.await() }

        val worker = thread(name = "generation-under-apply-template") {
            runtime.generate("{}", 8) { true }
        }
        assertTrue("generation must start", started.await(5, TimeUnit.SECONDS))

        // applyTemplate must not be allowed to read the model handle while generate() is
        // inside native code, since a concurrent unload() would otherwise be free to run
        // right after and free the handle out from under it.
        val error = runCatching { runtime.applyTemplate("{}") }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)

        release.countDown()
        worker.join(5_000)
        runtime.unload()
    }

    @Test
    fun `unload does not free handles while a generation is still running`() = runBlocking {
        val native = FakeNative()
        val runtime = LlamaCppRuntime(native)
        runtime.load("/tmp/m.gguf", emptyList(), 0, 12_000_000_000L)

        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        native.blockOnGenerate = { started.countDown(); release.await() }

        val worker = thread(name = "generation-under-unload") { runtime.generate("{}", 8) { true } }
        assertTrue("generation must start", started.await(5, TimeUnit.SECONDS))

        val unloadFinished = CountDownLatch(1)
        val unloadJob = launch(Dispatchers.IO) {
            runtime.unload()
            unloadFinished.countDown()
        }

        // unload must not complete - and therefore must not free anything - while the
        // worker above is still inside generate().
        assertFalse(
            "unload must still be waiting on the running generation",
            unloadFinished.await(300, TimeUnit.MILLISECONDS),
        )
        assertFalse("context must not be freed while generation is still running", native.freedContext)
        assertFalse("model must not be freed while generation is still running", native.freedModel)

        release.countDown()
        worker.join(5_000)
        assertTrue("unload must complete once the generation is done", unloadFinished.await(5, TimeUnit.SECONDS))
        unloadJob.join()

        assertTrue(native.freedContext)
        assertTrue(native.freedModel)
    }
}
