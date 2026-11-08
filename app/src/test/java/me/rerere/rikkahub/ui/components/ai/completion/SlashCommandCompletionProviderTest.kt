package me.rerere.rikkahub.ui.components.ai.completion

import androidx.compose.ui.text.TextRange
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.AgentMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SlashCommandCompletionProviderTest {

    @Test
    fun inBuildMode_onlyPlanIsSuggested() = runBlocking {
        var currentMode = AgentMode.BUILD
        val provider = SlashCommandCompletionProvider(currentMode = { currentMode })

        // Typing "/"
        val result = provider.complete(ChatCompletionContext("/", TextRange(1)))
        assertNotNull(result)
        // Must contain /plan, must NOT contain /build
        assert(result!!.items.any { it.label == "/plan" })
        assert(result.items.none { it.label == "/build" })

        // Typing "/p"
        val pResult = provider.complete(ChatCompletionContext("/p", TextRange(2)))
        assertNotNull(pResult)
        assertEquals("/plan", pResult!!.items[0].label)

        // Typing "/bu" in Build mode returns no /build
        val bResult = provider.complete(ChatCompletionContext("/bu", TextRange(3)))
        assert(bResult == null || bResult.items.none { it.label == "/build" })
    }

    @Test
    fun inPlanMode_onlyBuildIsSuggested() = runBlocking {
        var currentMode = AgentMode.PLAN
        val provider = SlashCommandCompletionProvider(currentMode = { currentMode })

        // Typing "/"
        val result = provider.complete(ChatCompletionContext("/", TextRange(1)))
        assertNotNull(result)
        // Must contain /build, must NOT contain /plan
        assert(result!!.items.any { it.label == "/build" })
        assert(result.items.none { it.label == "/plan" })

        // Typing "/b" suggests /build (and /browser)
        val bResult = provider.complete(ChatCompletionContext("/b", TextRange(2)))
        assertNotNull(bResult)
        assert(bResult!!.items.any { it.label == "/build" })

        // Typing "/pl" in Plan mode returns no /plan
        val pResult = provider.complete(ChatCompletionContext("/pl", TextRange(3)))
        assert(pResult == null || pResult.items.none { it.label == "/plan" })
    }

    @Test
    fun nonSlashInput_returnsNull() = runBlocking {
        val provider = SlashCommandCompletionProvider(currentMode = { AgentMode.BUILD })

        // Normal text
        assertNull(provider.complete(ChatCompletionContext("hello", TextRange(5))))
        // Slash inside a word
        assertNull(provider.complete(ChatCompletionContext("path/sub", TextRange(8))))
    }
}
