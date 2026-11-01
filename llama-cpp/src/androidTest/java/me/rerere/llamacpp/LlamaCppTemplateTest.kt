package me.rerere.llamacpp

import androidx.test.ext.junit.runners.AndroidJUnit4
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LlamaCppTemplateTest {

    private val fixture = File("/data/local/tmp/llamacpp-test.gguf")

    @Test
    fun rendersAPromptFromTheModelsOwnTemplate() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            val request = """
                {"messages":[{"role":"user","content":"Say hello 😀"}]}
            """.trimIndent()
            val result = JSONObject(LlamaCppJni.applyTemplate(handle, request))
            val prompt = result.getString("prompt")
            assertTrue("prompt must contain the user text", prompt.contains("Say hello"))
            assertTrue("prompt must be templated, not raw", prompt.length > "Say hello".length)
            // U+1F600 is supplementary-plane: as a jstring it would arrive as Modified UTF-8
            // (CESU-8) and fail nlohmann's strict UTF-8 parse. The prompt is handed back through
            // utf8ToByteArray, so this single assertion pins both directions of that fix; do not
            // delete it as redundant with the plain-text checks above, it is the only thing in
            // this suite that would catch a regression back to a jstring signature.
            assertTrue("supplementary-plane text must survive both directions", prompt.contains("😀"))
            // A plain chat request with no tools must not be constrained to tool-call syntax,
            // or the model could never answer in prose.
            assertTrue("a tool-less request must not carry a grammar", result.getString("grammar").isEmpty())
            // The serialized parser is what reading the response back actually runs on.
            // common_chat_parse falls back to a content-only parser when it is missing rather
            // than failing, so losing this field costs every tool call with nothing to show
            // for it; the "format" name alone cannot stand in for it.
            assertTrue("the response parser must be carried across", result.getString("parser").isNotBlank())
            assertTrue(
                "the generation prompt the parser matches on must be carried across",
                result.getString("generation_prompt").isNotBlank(),
            )
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }

    @Test
    fun declaringToolsProducesAConstrainingGrammar() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            val request = """
                {"messages":[{"role":"user","content":"What time is it?"}],
                 "tools":[{"type":"function","function":{"name":"get_time",
                   "description":"Get the current time",
                   "parameters":{"type":"object","properties":{},"required":[]}}}]}
            """.trimIndent()
            val result = JSONObject(LlamaCppJni.applyTemplate(handle, request))
            assertTrue(
                "a tool-capable template must emit a grammar so tool syntax is constrained",
                result.getString("grammar").isNotBlank(),
            )
            assertTrue("the tool name should reach the prompt", result.getString("prompt").contains("get_time"))
            // A lazy grammar only starts constraining once a trigger matches. An empty trigger
            // list would mean it never activates and tool calling silently degrades to
            // unconstrained free text, while this assertion alone would still pass.
            assertTrue("a tool grammar for this fixture is lazy", result.getBoolean("grammar_lazy"))
            val triggers = result.getJSONArray("grammar_triggers")
            assertTrue("a lazy grammar needs at least one trigger to ever activate", triggers.length() > 0)
            assertEquals("word", triggers.getJSONObject(0).getString("type"))
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }

    @Test
    fun aToolResultFromChatRequestMapperReachesThePrompt() {
        // Joins Task 7's mapper to this task's renderer. Neither side's own tests exercise the
        // shape ChatRequestMapper emits for a turn after a tool call: an assistant message
        // carrying tool_calls, followed by a role:"tool" message. This is the most important
        // path this runtime has, since it is exactly where a shape mismatch would surface as a
        // hard exception on the first turn after any tool call.
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            val history = listOf(
                UIMessage.user("What's the weather in Paris?"),
                UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(
                        UIMessagePart.Tool(
                            toolCallId = "1",
                            toolName = "get_weather",
                            input = "{}",
                            output = listOf(UIMessagePart.Text("sunny and 22C")),
                        ),
                    ),
                ),
                UIMessage.user("Thanks, what should I wear?"),
            )
            val request = ChatRequestMapper.toRequestJson(history, emptyList())
            val result = JSONObject(LlamaCppJni.applyTemplate(handle, request))
            val prompt = result.getString("prompt")
            assertTrue("the tool result text must reach the rendered prompt", prompt.contains("sunny and 22C"))
            // The result text alone proves the role:"tool" message rendered; also check the
            // call side, or a regression dropping the assistant's tool_calls entry while still
            // emitting the tool message would pass unnoticed.
            assertTrue("the tool call itself must also reach the rendered prompt", prompt.contains("get_weather"))
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }

    @Test
    fun applyTemplateOnAZeroHandleThrows() {
        val error = assertThrows(RuntimeException::class.java) {
            LlamaCppJni.applyTemplate(0L, "{}")
        }
        assertTrue("expected the null-handle message, got: ${error.message}", error.message == "model handle is null")
    }

    @Test
    fun malformedRequestJsonThrows() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            // @Test(expected=...) would scope the expectation to the whole method, so a throw
            // from nativeLoadModel above (fixture present but fails to load) would satisfy it
            // without ever reaching the parse this test exists to pin. assertThrows scopes the
            // expectation to this one statement instead.
            val error = assertThrows(RuntimeException::class.java) {
                LlamaCppJni.applyTemplate(handle, "not json")
            }
            assertTrue("expected a JSON parse error, got: ${error.message}", error.message?.contains("parse error") == true)
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }

    @Test
    fun requestMissingMessagesThrows() {
        assumeTrue("fixture GGUF not present", fixture.exists())
        val handle = LlamaCppJni.nativeLoadModel(fixture.absolutePath)
        try {
            val error = assertThrows(RuntimeException::class.java) {
                LlamaCppJni.applyTemplate(handle, "{}")
            }
            assertTrue(
                "expected a missing 'messages' key error, got: ${error.message}",
                error.message?.contains("messages") == true,
            )
        } finally {
            LlamaCppJni.nativeFreeModel(handle)
        }
    }
}
