package me.rerere.llamacpp

import kotlinx.coroutines.runBlocking
import me.rerere.locallm.MemoryGuard
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRefusalTest {

    @Test
    fun `a model that cannot fit is refused before the context is created`() {
        // MemoryGuard.decide is the same gate LiteRT uses; reuse it rather than
        // inventing a second memory policy.
        val decision = MemoryGuard.decide(
            modelFileBytes = 6_000_000_000L,
            availMemBytes = 2_000_000_000L,
        )
        assertTrue("a 6GB model on a 2GB budget must be refused", decision is MemoryGuard.Decision.TooLarge)
    }

    @Test
    fun `load throws ModelTooLargeException rather than letting the OS kill us`() = runBlocking {
        val native = object : LlamaCppNative by NoopNative() {
            override fun modelInfo(handle: Long): String = """
                {"n_layers":48,"n_embd":4096,"n_head_kv":8,"n_embd_head_k":128,
                 "n_embd_head_v":128,"n_vocab":262144,"n_ctx_train":32768,
                 "sliding_window":0,"weights_bytes":9000000000}
            """.trimIndent()
        }
        val runtime = LlamaCppRuntime(native)
        val error = runCatching {
            runtime.load("/tmp/huge.gguf", emptyList(), 0, availableRamBytes = 2_000_000_000L)
        }.exceptionOrNull()
        assertTrue("expected ModelTooLargeException, got $error", error is ModelTooLargeException)
    }

    @Test
    fun `a model reporting 0 weights_bytes is refused rather than waved through`() = runBlocking {
        // MemoryGuard.decide(0, anyBudget) always returns Ok, since 0 fits any budget.
        // load() must catch this before calling MemoryGuard rather than trust it.
        val native = object : LlamaCppNative by NoopNative() {
            override fun modelInfo(handle: Long): String = """
                {"n_layers":48,"n_embd":4096,"n_head_kv":8,"n_embd_head_k":128,
                 "n_embd_head_v":128,"n_vocab":262144,"n_ctx_train":32768,
                 "sliding_window":0,"weights_bytes":0}
            """.trimIndent()
        }
        val runtime = LlamaCppRuntime(native)
        val error = runCatching {
            runtime.load("/tmp/unmeasurable.gguf", emptyList(), 0, availableRamBytes = 2_000_000_000L)
        }.exceptionOrNull()
        assertTrue("expected ModelTooLargeException, got $error", error is ModelTooLargeException)
    }

    @Test
    fun `a model whose weights fit the budget is accepted even though the full estimate would not`() = runBlocking {
        // 3 GB of weights against 5 GB of RAM: comfortably under MemoryGuard's 0.7 budget
        // (3.5 GB) on their own. But ContextPlanner.estimateBytes adds the KV cache, the
        // compute buffer and its own 1.1x headroom on top of the weights, which pushes the
        // full estimate for this shape past 3.5 GB. Passing that total into MemoryGuard
        // (rather than the weights alone) would refuse a load that actually fits.
        val native = object : LlamaCppNative by NoopNative() {
            override fun modelInfo(handle: Long): String = """
                {"n_layers":32,"n_embd":4096,"n_head_kv":8,"n_embd_head_k":128,
                 "n_embd_head_v":128,"n_vocab":128000,"n_ctx_train":4096,
                 "sliding_window":0,"weights_bytes":3000000000}
            """.trimIndent()
        }
        val runtime = LlamaCppRuntime(native)
        val plan = runtime.load("/tmp/fits.gguf", emptyList(), 0, availableRamBytes = 5_000_000_000L)

        assertTrue(
            "test setup must actually exercise the double-count: the full estimate " +
                "(${plan.estimatedBytes}) must exceed MemoryGuard's 0.7 budget of RAM (3_500_000_000)",
            plan.estimatedBytes > 3_500_000_000L,
        )

        runtime.unload()
    }
}
