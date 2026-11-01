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
        assertEquals(1, result!!.items.size)
        assertEquals("/plan", result.items[0].label)

        // Typing "/p"
        val pResult = provider.complete(ChatCompletionContext("/p", TextRange(2)))
        assertNotNull(pResult)
        assertEquals("/plan", pResult!!.items[0].label)

        // Typing "/b" in Build mode returns nothing (/build is never offered)
        val bResult = provider.complete(ChatCompletionContext("/b", TextRange(2)))
        assertNull(bResult)
    }

    @Test
    fun inPlanMode_onlyBuildIsSuggested() = runBlocking {
        var currentMode = AgentMode.PLAN
        val provider = SlashCommandCompletionProvider(currentMode = { currentMode })

        // Typing "/"
        val result = provider.complete(ChatCompletionContext("/", TextRange(1)))
        assertNotNull(result)
        assertEquals(1, result!!.items.size)
        assertEquals("/build", result.items[0].label)

        // Typing "/b"
        val bResult = provider.complete(ChatCompletionContext("/b", TextRange(2)))
        assertNotNull(bResult)
        assertEquals("/build", bResult!!.items[0].label)

        // Typing "/p" in Plan mode returns nothing (/plan is never offered)
        val pResult = provider.complete(ChatCompletionContext("/p", TextRange(2)))
        assertNull(pResult)
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
