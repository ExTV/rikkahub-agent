package me.rerere.llamacpp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LlamaCppModelLoadTest {

    /**
     * Push a small GGUF before running:
     *   adb push model.gguf /data/local/tmp/llamacpp-test.gguf
     * The test skips rather than fails when it is absent, so the suite stays runnable
     * on a machine without the fixture. The fixture used to write the assertions below
     * is Qwen3 0.6B Q4_0.
     */
    private val fixture = File("/data/local/tmp/llamacpp-test.gguf")

    @Test
    fun loadsAModelAndReportsUsableMetadata() {
        assumeTrue("fixture GGUF not present at ${fixture.path}", fixture.exists())

        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            assertTrue("handle must be non-zero", handle != 0L)

            val info = JSONObject(LlamaCppJni.nativeModelInfo(handle))
            // Values below are the fixture's own GGUF header, not just positivity checks.
            assertEquals(28, info.getInt("n_layers"))
            assertEquals(1024, info.getInt("n_embd"))
            assertEquals(8, info.getInt("n_head_kv"))
            assertEquals(128, info.getInt("n_embd_head_k"))
            assertEquals(128, info.getInt("n_embd_head_v"))
            assertEquals(40960, info.getInt("n_ctx_train"))
            // getInt, not optInt: the key must be present, not merely absent-and-defaulted.
            assertEquals(0, info.getInt("sliding_window"))
            assertTrue("n_vocab must be positive", info.getInt("n_vocab") > 0)
            // llama_model_size is the summed tensor size, not the 428,970,080-byte file
            // size, so a band rather than an exact match; still tight enough to catch a
            // units or truncation error.
            assertTrue(
                "weights_bytes should be in a plausible range for this fixture",
                info.getLong("weights_bytes") in 300_000_000L..500_000_000L,
            )

            // The planner must accept what the native side reports.
            val parsed = GgufModelInfo(
                nLayers = info.getInt("n_layers"),
                nEmbd = info.getInt("n_embd"),
                nHeadKv = info.getInt("n_head_kv"),
                nEmbdHeadK = info.getInt("n_embd_head_k"),
                nEmbdHeadV = info.getInt("n_embd_head_v"),
                nVocab = info.getInt("n_vocab"),
                nCtxTrain = info.getInt("n_ctx_train"),
                slidingWindow = info.optInt("sliding_window", 0).takeIf { it > 0 },
                weightsBytes = info.getLong("weights_bytes"),
            )
            assertTrue("native metadata must be complete enough to plan", parsed.isComplete)
            assertNull("fixture declares no sliding window", parsed.slidingWindow)
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }

    @Test
    fun anInstructModelExposesAChatTemplate() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            val template = LlamaCppJni.nativeChatTemplate(handle)
            assertNotNull("an instruct GGUF must carry a chat template", template)
            assertEquals(4116, template!!.length)
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }

    @Test(expected = RuntimeException::class)
    fun aMissingFileThrowsRatherThanCrashing() {
        LlamaCppJni.nativeLoadModel("/data/local/tmp/definitely-not-here.gguf")
    }

    @Test
    fun freeingAZeroHandleIsANoOp() {
        LlamaCppJni.nativeFreeModel(0L)
    }

    @Test(expected = RuntimeException::class)
    fun modelInfoOnAZeroHandleThrows() {
        LlamaCppJni.nativeModelInfo(0L)
    }

    @Test(expected = RuntimeException::class)
    fun chatTemplateOnAZeroHandleThrows() {
        LlamaCppJni.nativeChatTemplate(0L)
    }
}
