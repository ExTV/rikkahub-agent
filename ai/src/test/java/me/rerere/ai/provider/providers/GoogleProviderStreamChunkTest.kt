package me.rerere.ai.provider.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.providers.google.GoogleProvider
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Covers [GoogleProvider.parseStreamCandidates], which was extracted out of the SSE listener so
 * the Cloud Code Assist transport could reuse it. The extraction has to preserve the listener's
 * old behaviour exactly, so the "no candidates" and "empty candidates" cases are asserted as
 * hard nulls: the listener returns early on both, and a chunk emitted there would surface as a
 * spurious empty delta in the transcript.
 */
class GoogleProviderStreamChunkTest {

    private lateinit var provider: GoogleProvider
    private val model = Model(modelId = "gemini-2.5-pro", displayName = "Gemini 2.5 Pro")

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    private fun parse(payload: String) =
        provider.parseStreamCandidates(Json.parseToJsonElement(payload).jsonObject, model)

    @Test
    fun `payload without candidates yields no chunk`() {
        assertNull(parse("""{"usageMetadata":{"promptTokenCount":10}}"""))
    }

    @Test
    fun `payload with an empty candidate list yields no chunk`() {
        assertNull(parse("""{"candidates":[]}"""))
    }

    @Test
    fun `text delta is carried through as an assistant part`() {
        val chunk = parse(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"hello"}]}}]}"""
        )
        requireNotNull(chunk)
        assertEquals("gemini-2.5-pro", chunk.model)
        assertEquals(1, chunk.choices.size)
        val delta = requireNotNull(chunk.choices[0].delta)
        assertEquals("hello", delta.parts.filterIsInstance<UIMessagePart.Text>().single().text)
        assertNull(chunk.choices[0].message)
    }

    @Test
    fun `finish reason is reported on the choice`() {
        val chunk = parse(
            """{"candidates":[{"content":{"role":"model","parts":[{"text":"x"}]},"finishReason":"STOP"}]}"""
        )
        assertEquals("STOP", requireNotNull(chunk).choices[0].finishReason)
    }

    @Test
    fun `usage metadata folds thoughts into completion tokens and keeps the cache read`() {
        val chunk = parse(
            """
            {
              "candidates":[{"content":{"role":"model","parts":[{"text":"x"}]}}],
              "usageMetadata":{
                "promptTokenCount":100,
                "candidatesTokenCount":20,
                "thoughtsTokenCount":5,
                "cachedContentTokenCount":80,
                "totalTokenCount":125
              }
            }
            """.trimIndent()
        )
        val usage = requireNotNull(requireNotNull(chunk).usage)
        assertEquals(100, usage.promptTokens)
        assertEquals(25, usage.completionTokens)
        assertEquals(80, usage.cachedTokens)
        assertEquals(125, usage.totalTokens)
    }

    @Test
    fun `a candidate with no content still produces a chunk with a null delta`() {
        val chunk = parse("""{"candidates":[{"finishReason":"SAFETY"}]}""")
        requireNotNull(chunk)
        assertNull(chunk.choices[0].delta)
        assertEquals("SAFETY", chunk.choices[0].finishReason)
    }

    @Test
    fun `a signature-only thought part with no text is skipped without throwing`() {
        val chunk = parse(
            """{"candidates":[{"content":{"role":"model","parts":[
                {"thought":true,"thoughtSignature":"sig-1"}
            ]}}]}"""
        )
        val delta = requireNotNull(requireNotNull(chunk).choices[0].delta)
        assertEquals(0, delta.parts.size)
    }

    @Test
    fun `a chunk whose parts are all unrecognized parses to an empty part list without throwing`() {
        val chunk = parse(
            """{"candidates":[{"content":{"role":"model","parts":[
                {"executableCode":{"language":"PYTHON","code":"print(1)"}},
                {"codeExecutionResult":{"outcome":"OK","output":"1"}}
            ]}}]}"""
        )
        val delta = requireNotNull(requireNotNull(chunk).choices[0].delta)
        assertEquals(0, delta.parts.size)
    }

    @Test
    fun `an unrecognized part is dropped while a thought text part in the same chunk survives`() {
        val chunk = parse(
            """{"candidates":[{"content":{"role":"model","parts":[
                {"thought":true,"text":"pondering"},
                {"executableCode":{"language":"PYTHON","code":"print(1)"}}
            ]}}]}"""
        )
        val delta = requireNotNull(requireNotNull(chunk).choices[0].delta)
        assertEquals(1, delta.parts.size)
        val reasoning = delta.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals("pondering", reasoning.reasoning)
    }
}
