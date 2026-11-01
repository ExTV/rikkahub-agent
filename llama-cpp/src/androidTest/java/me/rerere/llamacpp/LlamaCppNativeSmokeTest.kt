package me.rerere.llamacpp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaCppNativeSmokeTest {

    @Test
    fun nativeLibraryLoadsAndReportsCpuFeatures() {
        val info = LlamaCppJni.systemInfo()
        assertTrue("system info should not be blank", info.isNotBlank())
        // Every Arm build reports its detected feature set; this proves the real
        // llama.cpp build is linked rather than a stub.
        assertTrue("expected CPU feature flags in: $info", info.contains("="))
    }
}
