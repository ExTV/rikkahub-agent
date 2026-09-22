package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Modality
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.OpenRouterReasoningMetadata
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatCompletionsAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 */
class ChatCompletionsRequestMessageTest {

    private lateinit var api: ChatCompletionsAPI

    @Before
    fun setUp() {
        api = ChatCompletionsAPI(OkHttpClient(), KeyRoulette.default())
    }

    // Helper to invoke private buildMessages method via reflection
    private fun invokeBuildMessages(
        messages: List<UIMessage>,
        includeHistoryReasoning: Boolean = true,
        includeOpenRouterReasoningDetails: Boolean = false,
        supportInputModalities: List<Modality> = listOf(Modality.TEXT, Modality.IMAGE),
    ): JsonArray {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "buildMessages",
            List::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(
            api,
            messages,
            includeHistoryReasoning,
            false,
            includeOpenRouterReasoningDetails,
            supportInputModalities
        ) as JsonArray
    }

    private fun invokeParseMessage(message: JsonObject): UIMessage {
        val method = ChatCompletionsAPI::class.java.getDeclaredMethod(
            "parseMessage",
            JsonObject::class.java,
        )
        method.isAccessible = true
        return method.invoke(api, message) as UIMessage
    }

    @Test
    fun `multi-round reasoning and tool calls should be correctly ordered`() {
        // Scenario: Assistant message with multiple rounds of reasoning and tool calls
        // [Reasoning1, Text1, Tool1(executed), Reasoning2, Text2, Tool2(executed), Text3]
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Let me think about this..."),
                UIMessagePart.Text("I'll search for information"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result 1"),
                UIMessagePart.Reasoning(reasoning = "Now I need to calculate..."),
                UIMessagePart.Text("Let me calculate that"),
                createExecutedTool("call_2", "calculate", """{"expr": "1+1"}""", "2"),
                UIMessagePart.Text("The final answer is 2")
            )
        )

        val messages = listOf(
            UIMessage.user("What is 1+1?"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Result should contain:
        // 1. User message
        // 2. Assistant message with reasoning_content, content, and tool_calls for search
        // 3. Tool result for search
        // 4. Assistant message with reasoning_content, content, and tool_calls for calculate
        // 5. Tool result for calculate
        // 6. Assistant message with final text

        assertTrue("Should have at least 6 messages", result.size >= 6)

        // Verify user message
        val userMsg = result[0].jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)

        // Verify first assistant message (with first tool call)
        val assistant1 = result[1].jsonObject
        assertEquals("assistant", assistant1["role"]?.jsonPrimitive?.content)
        assertTrue("First assistant message should have tool_calls", assistant1.containsKey("tool_calls"))
        val toolCalls1 = assistant1["tool_calls"]?.jsonArray
        assertEquals(1, toolCalls1?.size)
        assertEquals("search", toolCalls1?.get(0)?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        // Verify first tool result
        val toolResult1 = result[2].jsonObject
        assertEquals("tool", toolResult1["role"]?.jsonPrimitive?.content)
        assertEquals("call_1", toolResult1["tool_call_id"]?.jsonPrimitive?.content)

        // Verify second assistant message (with second tool call)
        val assistant2 = result[3].jsonObject
        assertEquals("assistant", assistant2["role"]?.jsonPrimitive?.content)
        assertTrue("Second assistant message should have tool_calls", assistant2.containsKey("tool_calls"))
        val toolCalls2 = assistant2["tool_calls"]?.jsonArray
        assertEquals(1, toolCalls2?.size)
        assertEquals("calculate", toolCalls2?.get(0)?.jsonObject?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.content)

        // Verify second tool result
        val toolResult2 = result[4].jsonObject
        assertEquals("tool", toolResult2["role"]?.jsonPrimitive?.content)
        assertEquals("call_2", toolResult2["tool_call_id"]?.jsonPrimitive?.content)

        // Verify final assistant message
        val assistant3 = result[5].jsonObject
        assertEquals("assistant", assistant3["role"]?.jsonPrimitive?.content)
        val content = assistant3["content"]
        assertTrue("Final assistant content should contain 'final answer'",
            content?.jsonPrimitive?.content?.contains("final answer") == true ||
            (content is JsonArray && content.any { it.jsonObject["text"]?.jsonPrimitive?.content?.contains("final answer") == true })
        )
    }

    @Test
    fun `parallel tool calls should be grouped together`() {
        // Scenario: Multiple tools called in parallel
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search multiple sources"),
                createExecutedTool("call_1", "search_web", """{"query": "test1"}""", "Result 1"),
                createExecutedTool("call_2", "search_docs", """{"query": "test2"}""", "Result 2"),
                createExecutedTool("call_3", "search_wiki", """{"query": "test3"}""", "Result 3"),
                UIMessagePart.Text("Combined results show...")
            )
        )

        val messages = listOf(
            UIMessage.user("Search everything"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify parallel tools are in same assistant message
        var foundAssistantWithMultipleTools = false
        for (element in result) {
            val msg = element.jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant") {
                val toolCalls = msg["tool_calls"]?.jsonArray
                if (toolCalls != null && toolCalls.size == 3) {
                    foundAssistantWithMultipleTools = true
                    // Verify all three tool calls are present
                    val toolNames = toolCalls.map {
                        it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                    }
                    assertTrue(toolNames.contains("search_web"))
                    assertTrue(toolNames.contains("search_docs"))
                    assertTrue(toolNames.contains("search_wiki"))
                    break
                }
            }
        }
        assertTrue("Should have assistant message with 3 parallel tool calls", foundAssistantWithMultipleTools)

        // Verify 3 separate tool result messages
        val toolResults = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "tool"
        }
        assertEquals(3, toolResults.size)
    }

    @Test
    fun `reasoning should be included for all assistant messages when history reasoning enabled`() {
        val messages = createMultiRoundReasoningMessages()

        val result = invokeBuildMessages(messages, includeHistoryReasoning = true)

        val assistantMessages = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertEquals(2, assistantMessages.size)
        assertEquals("Initial thinking",
            assistantMessages[0].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("Final thinking",
            assistantMessages[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `reasoning should be excluded from all assistant messages when history reasoning disabled`() {
        val messages = createMultiRoundReasoningMessages()

        val result = invokeBuildMessages(messages, includeHistoryReasoning = false)

        val assistantMessages = result.filter {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertEquals(2, assistantMessages.size)
        assistantMessages.forEach { msg ->
            assertFalse("Assistant should not have reasoning_content",
                msg.jsonObject.containsKey("reasoning_content"))
        }
    }

    private fun createMultiRoundReasoningMessages(): List<UIMessage> {
        val assistant1 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Initial thinking"),
                UIMessagePart.Text("Initial response")
            )
        )
        val assistant2 = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Final thinking"),
                UIMessagePart.Text("Final response")
            )
        )
        return listOf(
            UIMessage.user("First question"),
            assistant1,
            UIMessage.user("Second question"),
            assistant2
        )
    }

    @Test
    fun `tool_call followed by tool result should maintain correct order`() {
        // Verify the pattern: assistant (with tool_calls) -> tool (result)
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Calling tool"),
                createExecutedTool("call_abc", "my_tool", """{"param": "value"}""", "Tool output")
            )
        )

        val messages = listOf(
            UIMessage.user("Use a tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find the assistant message with tool_calls
        var assistantIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assistantIndex = i
                break
            }
        }

        assertTrue("Should find assistant with tool_calls", assistantIndex >= 0)

        // The next message should be the tool result
        val nextMsg = result[assistantIndex + 1].jsonObject
        assertEquals("tool", nextMsg["role"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextMsg["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("my_tool", nextMsg["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `complex multi-round conversation with interleaved reasoning and tools`() {
        // Complex scenario simulating agent conversation
        val messages = listOf(
            UIMessage.user("Plan and execute a task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "Step 1: Analyze the task"),
                    UIMessagePart.Text("First, I'll gather information"),
                    createExecutedTool("call_1", "gather_info", "{}", "Info gathered"),
                    UIMessagePart.Reasoning(reasoning = "Step 2: Process the information"),
                    UIMessagePart.Text("Now processing..."),
                    createExecutedTool("call_2", "process", "{}", "Processed"),
                    UIMessagePart.Reasoning(reasoning = "Step 3: Generate output"),
                    UIMessagePart.Text("Here is the result")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Verify structure:
        // 1. user message
        // 2. assistant (reasoning + text + tool_calls)
        // 3. tool result
        // 4. assistant (reasoning + text + tool_calls)
        // 5. tool result
        // 6. assistant (reasoning + text)

        // Count message types
        val userCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val assistantCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" }
        val toolCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }

        assertEquals("Should have 1 user message", 1, userCount)
        assertEquals("Should have 2 tool results", 2, toolCount)
        assertTrue("Should have at least 3 assistant messages", assistantCount >= 3)

        // Verify order: each tool_calls should be immediately followed by tool result
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assertTrue("Index should not be last", i < result.size - 1)
                val nextMsg = result[i + 1].jsonObject
                assertEquals("Tool result should follow tool_calls",
                    "tool", nextMsg["role"]?.jsonPrimitive?.content)
            }
        }
    }

    @Test
    fun `assistant with only reasoning and empty text is dropped when includeHistoryReasoning is false`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage.user("Question 2")
        )

        val result = invokeBuildMessages(messages, includeHistoryReasoning = false)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("user", result[1].jsonObject["role"]?.jsonPrimitive?.content)
    }

    @Test
    fun `assistant with only reasoning and empty text should be kept when history reasoning enabled`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            ),
            UIMessage.user("Question 2")
        )

        val result = invokeBuildMessages(messages, includeHistoryReasoning = true)

        assertEquals(3, result.size)
        assertEquals("assistant", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("thinking", result[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `latest assistant with reasoning and empty text should keep reasoning content`() {
        val messages = listOf(
            UIMessage.user("Question 1"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = "thinking"),
                    UIMessagePart.Text("")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        assertEquals(2, result.size)
        assertEquals("user", result[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("assistant", result[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("thinking", result[1].jsonObject["reasoning_content"]?.jsonPrimitive?.content)
        assertEquals("", result[1].jsonObject["content"]?.jsonPrimitive?.content)
    }

    // ==================== Vision gate tests ====================
    // Regression coverage for the DeepSeek crash: a text-only model must never see
    // an `image_url` content block, no matter which of the three emission sites
    // (tool image-lift, assistant content image, user content image) produced it.

    private val imagePlaceholder = "[Image output omitted: current model does not support image input]"

    private fun createHistoryWithImages(): List<UIMessage> {
        val assistantWithToolImage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me take a screenshot"),
                createExecutedToolWithImage("call_shot", "take_screenshot", "{}"),
            )
        )
        val assistantWithImageContent = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Here is a generated image"),
                UIMessagePart.Image("data:image/png;base64,QUJD"),
            )
        )
        val userWithImage = UIMessage(
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text("What's in this image?"),
                UIMessagePart.Image("data:image/png;base64,WFla"),
            )
        )
        return listOf(
            UIMessage.user("Take a screenshot"),
            assistantWithToolImage,
            UIMessage.user("Now generate an image"),
            assistantWithImageContent,
            userWithImage,
        )
    }

    private fun createExecutedToolWithImage(callId: String, name: String, input: String): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Image("data:image/png;base64,AAAA")),
        )
    }

    @Test
    fun `text-only model emits zero image_url and a placeholder at every site`() {
        val result = invokeBuildMessages(
            createHistoryWithImages(),
            supportInputModalities = listOf(Modality.TEXT),
        )
        val serialized = result.toString()

        assertFalse(
            "text-only model must not emit image_url anywhere",
            serialized.contains("\"image_url\"")
        )
        val placeholderCount = Regex(Regex.escape(imagePlaceholder)).findAll(serialized).count()
        assertEquals(
            "expected a placeholder for the tool-output image, the assistant content image, " +
                "and the user content image",
            3,
            placeholderCount
        )
    }

    @Test
    fun `vision model still emits image_url unchanged`() {
        val result = invokeBuildMessages(
            createHistoryWithImages(),
            supportInputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        )
        val serialized = result.toString()

        assertTrue("vision model should still emit image_url", serialized.contains("\"image_url\""))
        assertFalse(
            "vision model should not fall back to the text placeholder",
            serialized.contains(imagePlaceholder)
        )
    }

    // ==================== Image lift ordering tests (issue #104) ====================
    // A parallel tool batch where a non-last result carries an image used to lift an
    // extra role:"user" message in between the tool results, which providers reject as
    // "no tool output found for tool call". These tests guard that all tool results of a
    // batch stay contiguous and the lift, if any, comes after all of them.

    @Test
    fun `parallel tool batch with an early image keeps tool results contiguous and lifts once at the end`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running three tools"),
                createExecutedToolWithImage("call_1", "take_screenshot", "{}"),
                createExecutedTool("call_2", "search_docs", """{"query": "test2"}""", "Result 2"),
                createExecutedTool("call_3", "search_wiki", """{"query": "test3"}""", "Result 3"),
            )
        )

        val messages = listOf(UIMessage.user("Do three things"), assistantMessage)

        val result = invokeBuildMessages(messages)

        var assistantIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "assistant" && msg.containsKey("tool_calls")) {
                assistantIndex = i
                break
            }
        }
        assertTrue("Should find assistant with tool_calls", assistantIndex >= 0)

        // The three tool results must immediately follow, in call order, no foreign
        // message (like the old inline lift) in between.
        val expectedCallIds = listOf("call_1", "call_2", "call_3")
        val toolMessages = (1..3).map { offset -> result[assistantIndex + offset].jsonObject }
        toolMessages.forEachIndexed { i, msg ->
            assertEquals("tool", msg["role"]?.jsonPrimitive?.content)
            assertEquals(expectedCallIds[i], msg["tool_call_id"]?.jsonPrimitive?.content)
            assertTrue("tool content must always be a string", msg["content"] is JsonPrimitive)
        }
        assertEquals(
            "[Image output attached in the following user message]",
            toolMessages[0]["content"]?.jsonPrimitive?.content
        )

        // Exactly one lift message, placed right after the last tool result of the batch.
        val liftMessage = result[assistantIndex + 4].jsonObject
        assertEquals("user", liftMessage["role"]?.jsonPrimitive?.content)
        val liftContent = liftMessage["content"]!!.jsonArray
        assertTrue(
            "lift message should carry the image",
            liftContent.any { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }
        )

        // No tool message anywhere should carry array content, and there should be no
        // other lift message for this batch.
        assertEquals(3, result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" })
        result.forEach { element ->
            if (element.jsonObject["role"]?.jsonPrimitive?.content == "tool") {
                assertTrue("tool content must never be an array", element.jsonObject["content"] is JsonPrimitive)
            }
        }
        val userLiftMessages = result.filter { element ->
            val content = element.jsonObject["content"]
            content is JsonArray && content.any { it.jsonObject["type"]?.jsonPrimitive?.content == "image_url" }
        }
        assertEquals(1, userLiftMessages.size)
    }

    @Test
    fun `parallel tool batch with an early image emits no lift for a text-only model`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedToolWithImage("call_1", "take_screenshot", "{}"),
                createExecutedTool("call_2", "search_docs", """{"query": "test2"}""", "Result 2"),
            )
        )

        val messages = listOf(UIMessage.user("Do two things"), assistantMessage)

        val result = invokeBuildMessages(messages, supportInputModalities = listOf(Modality.TEXT))

        val userMessages = result.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        assertEquals("no lift message should be emitted for a text-only model", 1, userMessages.size)

        val toolMessages = result.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }
        assertEquals(2, toolMessages.size)
        val imageToolMessage = toolMessages.first {
            it.jsonObject["tool_call_id"]?.jsonPrimitive?.content == "call_1"
        }
        assertEquals(
            imagePlaceholder,
            imageToolMessage.jsonObject["content"]?.jsonPrimitive?.content
        )
        assertTrue("tool content must always be a string", imageToolMessage.jsonObject["content"] is JsonPrimitive)
    }

    @Test
    fun `assistant should preserve OpenRouter reasoning details`() {
        val reasoningDetails = buildJsonArray {
            add(buildJsonObject {
                put("type", "reasoning.text")
                put("text", "thinking")
                put("format", "unknown")
                put("index", 0)
            })
        }
        val messages = listOf(
            UIMessage.user("Question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning(
                    reasoning = "thinking",
                    metadata = OpenRouterReasoningMetadata(reasoningDetails).toMetadata(),
                )),
            ),
        )

        val assistant = invokeBuildMessages(
            messages,
            includeOpenRouterReasoningDetails = true,
        )[1].jsonObject

        assertEquals(reasoningDetails, assistant["reasoning_details"])
        assertFalse(assistant.containsKey("reasoning_content"))
    }

    @Test
    fun `non streaming OpenRouter response should preserve reasoning details for tool continuation`() {
        val reasoningDetails = buildJsonArray {
            add(buildJsonObject {
                put("type", "reasoning.text")
                put("text", "thinking")
                put("signature", "signature")
                put("format", "anthropic-claude-v1")
                put("index", 0)
            })
        }
        val parsed = invokeParseMessage(buildJsonObject {
            put("role", "assistant")
            put("content", "")
            put("reasoning", "thinking")
            put("reasoning_details", reasoningDetails)
            put("tool_calls", buildJsonArray {
                add(buildJsonObject {
                    put("id", "call_1")
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "search")
                        put("arguments", "{}")
                    })
                })
            })
        })

        val reasoning = parsed.parts.filterIsInstance<UIMessagePart.Reasoning>().single()
        assertEquals(
            reasoningDetails,
            reasoning.metadataAs<OpenRouterReasoningMetadata>()?.reasoningDetails,
        )

        val executed = parsed.copy(parts = parsed.parts.map { part ->
            if (part is UIMessagePart.Tool) {
                part.copy(output = listOf(UIMessagePart.Text("result")))
            } else {
                part
            }
        })
        val assistant = invokeBuildMessages(
            messages = listOf(UIMessage.user("Question"), executed),
            includeOpenRouterReasoningDetails = true,
        )[1].jsonObject

        assertEquals(reasoningDetails, assistant["reasoning_details"])
        assertFalse(assistant.containsKey("reasoning_content"))
    }

    @Test
    fun `assistant should not send OpenRouter reasoning details to other hosts`() {
        val reasoningDetails = buildJsonArray {
            add(buildJsonObject {
                put("type", "reasoning.text")
                put("text", "thinking")
                put("signature", "signature")
            })
        }
        val messages = listOf(
            UIMessage.user("Question"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning(
                    reasoning = "thinking",
                    metadata = OpenRouterReasoningMetadata(reasoningDetails).toMetadata(),
                )),
            ),
        )

        val assistant = invokeBuildMessages(messages)[1].jsonObject

        assertFalse(assistant.containsKey("reasoning_details"))
        assertEquals("thinking", assistant["reasoning_content"]?.jsonPrimitive?.content)
    }

    // ==================== Helper Functions ====================

    private fun createExecutedTool(
        callId: String,
        name: String,
        input: String,
        output: String
    ): UIMessagePart.Tool {
        return UIMessagePart.Tool(
            toolCallId = callId,
            toolName = name,
            input = input,
            output = listOf(UIMessagePart.Text(output))
        )
    }
}
