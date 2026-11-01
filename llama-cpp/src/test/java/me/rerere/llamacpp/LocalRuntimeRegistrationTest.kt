package me.rerere.llamacpp

import me.rerere.locallm.LocalRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRuntimeRegistrationTest {

    @Test
    fun `the llama cpp runtime is registered with the gguf extension`() {
        assertEquals("gguf", LocalRuntime.LlamaCpp.fileExtension)
        assertTrue(LocalRuntime.LlamaCpp.displayName.isNotBlank())
    }

    @Test
    fun `runtime display names are distinct so preference keys cannot collide`() {
        // Preferences are keyed on displayName; a duplicate would silently share
        // accelerator and installed-model state between runtimes.
        val names = listOf(LocalRuntime.LiteRT.displayName, LocalRuntime.LlamaCpp.displayName)
        assertEquals(names.size, names.toSet().size)
    }
}
