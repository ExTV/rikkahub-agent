package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.toMetadata
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Unit tests for ResponseAPI message building logic.
 * Tests the conversion from UIMessage list to OpenAI Response API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * ResponseAPI uses a different format than ChatCompletionsAPI:
 * - function_call items for tool invocations
 * - function_call_output items for tool results
 */
class ResponseApiRequestMessageTest {

    private lateinit var api: ResponseAPI

    @Before
    fun setUp() {
        api = ResponseAPI(OkHttpClient())
    }

    // Helper to invoke buildMessages method
    private fun invokeBuildMessages(
        messages: List<UIMessage>,
        supportInputModalities: List<Modality> = listOf(Modality.TEXT, Modality.IMAGE),
    ): JsonArray {
        return api.buildMessages(messages, supportInputModalities)
    }

    private fun invokeBuildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage> = listOf(UIMessage.user("hello")),
        params: TextGenerationParams,
        stream: Boolean = false
    ): JsonObject {
        return api.buildRequestBody(providerSetting, messages, params, stream)
    }

    private fun createReasoningParams(reasoningLevel: ReasoningLevel = ReasoningLevel.OFF): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "test-model",
                displayName = "test-model",
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = reasoningLevel
        )
    }

    @Test
    fun `multi-round tool calls should produce correct function_call and function_call_output pairs`() {
        // Scenario: Multiple tool calls in sequence
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Let me search"),
                createExecutedTool("call_1", "search", """{"query": "test"}""", "Search result"),
                UIMessagePart.Text("Now calculating"),
                createExecutedTool("call_2", "calculate", """{"expr": "2+2"}""", "4"),
                UIMessagePart.Text("The answer is 4")
            )
        )

        val messages = listOf(
            UIMessage.user("Calculate something"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Verify structure for ResponseAPI:
        // 1. user message
        // 2. assistant content (text)
        // 3. function_call (search)
        // 4. function_call_output (search result)
        // 5. assistant content (text)
        // 6. function_call (calculate)
        // 7. function_call_output (calculate result)
        // 8. assistant content (final text)

        // Collect function_call items
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        assertEquals("Should have 2 function_call items", 2, functionCalls.size)

        // Collect function_call_output items
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }
        assertEquals("Should have 2 function_call_output items", 2, functionOutputs.size)

        // Verify first function_call
        val call1 = functionCalls[0].jsonObject
        assertEquals("call_1", call1["call_id"]?.jsonPrimitive?.content)
        assertEquals("search", call1["name"]?.jsonPrimitive?.content)

        // Verify first function_call_output
        val output1 = functionOutputs[0].jsonObject
        assertEquals("call_1", output1["call_id"]?.jsonPrimitive?.content)
        assertTrue(output1["output"]?.jsonPrimitive?.content?.contains("Search result") == true)

        // Verify second function_call
        val call2 = functionCalls[1].jsonObject
        assertEquals("call_2", call2["call_id"]?.jsonPrimitive?.content)
        assertEquals("calculate", call2["name"]?.jsonPrimitive?.content)

        // Verify second function_call_output
        val output2 = functionOutputs[1].jsonObject
        assertEquals("call_2", output2["call_id"]?.jsonPrimitive?.content)
        assertTrue(output2["output"]?.jsonPrimitive?.content?.contains("4") == true)
    }

    @Test
    fun `function_call should be immediately followed by function_call_output`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_abc", "my_tool", """{"x": 1}""", "result")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find function_call index
        var functionCallIndex = -1
        for (i in result.indices) {
            if (result[i].jsonObject["type"]?.jsonPrimitive?.content == "function_call") {
                functionCallIndex = i
                break
            }
        }

        assertTrue("Should find function_call", functionCallIndex >= 0)
        assertTrue("function_call_output should follow", functionCallIndex < result.size - 1)

        val nextItem = result[functionCallIndex + 1].jsonObject
        assertEquals("function_call_output", nextItem["type"]?.jsonPrimitive?.content)
        assertEquals("call_abc", nextItem["call_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parallel tool calls should emit all calls before their outputs`() {
        // Multiple tools called together
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running multiple tools"),
                createExecutedTool("call_1", "tool_a", "{}", "Result A"),
                createExecutedTool("call_2", "tool_b", "{}", "Result B"),
                createExecutedTool("call_3", "tool_c", "{}", "Result C"),
                UIMessagePart.Text("All done")
            )
        )

        val messages = listOf(
            UIMessage.user("Do things"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Should have 3 function_calls and 3 function_call_outputs
        val functionCalls = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals(3, functionCalls.size)
        assertEquals(3, functionOutputs.size)

        val callIds = listOf("call_1", "call_2", "call_3")
        assertEquals(
            callIds,
            functionCalls.map { it.jsonObject["call_id"]?.jsonPrimitive?.content },
        )
        assertEquals(
            callIds,
            functionOutputs.map { it.jsonObject["call_id"]?.jsonPrimitive?.content },
        )

        val toolItems = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content in setOf(
                "function_call",
                "function_call_output",
            )
        }
        assertEquals(
            listOf(
                "function_call",
                "function_call",
                "function_call",
                "function_call_output",
                "function_call_output",
                "function_call_output",
            ),
            toolItems.map { it.jsonObject["type"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `content with text should be properly formatted`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Hello world"),
                createExecutedTool("call_1", "test", "{}", "output"),
                UIMessagePart.Text("Goodbye")
            )
        )

        val messages = listOf(
            UIMessage.user("Hi"),
            assistantMessage
        )

        val result = invokeBuildMessages(messages)

        // Find assistant content messages
        val assistantContents = result.filter {
            val obj = it.jsonObject
            obj["role"]?.jsonPrimitive?.content == "assistant"
        }

        assertTrue("Should have assistant content messages", assistantContents.isNotEmpty())

        // First assistant message should have "Hello world"
        val firstAssistant = assistantContents[0].jsonObject
        val content = firstAssistant["content"]
        val hasHello = when {
            content is kotlinx.serialization.json.JsonPrimitive -> content.content.contains("Hello")
            content is JsonArray -> content.any {
                it.jsonObject["text"]?.jsonPrimitive?.content?.contains("Hello") == true
            }
            else -> false
        }
        assertTrue("First assistant should contain 'Hello'", hasHello)
    }

    @Test
    fun `complex multi-round scenario with text and tools interleaved`() {
        val messages = listOf(
            UIMessage.user("Execute a complex task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Starting task"),
                    createExecutedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing..."),
                    createExecutedTool("step2", "process", """{"data": "test"}""", "processed"),
                    UIMessagePart.Text("Finalizing..."),
                    createExecutedTool("step3", "finalize", "{}", "done"),
                    UIMessagePart.Text("Task completed successfully")
                )
            )
        )

        val result = invokeBuildMessages(messages)

        // Count items
        val userMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "user"
        }
        val assistantMessages = result.count {
            it.jsonObject["role"]?.jsonPrimitive?.content == "assistant"
        }
        val functionCalls = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call"
        }
        val functionOutputs = result.count {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output"
        }

        assertEquals("Should have 1 user message", 1, userMessages)
        assertEquals("Should have 3 function_calls", 3, functionCalls)
        assertEquals("Should have 3 function_call_outputs", 3, functionOutputs)
        assertTrue("Should have multiple assistant messages", assistantMessages >= 1)

        // Verify the order: each function_call immediately followed by function_call_output
        var lastCallIndex = -1
        for (i in result.indices) {
            val item = result[i].jsonObject
            if (item["type"]?.jsonPrimitive?.content == "function_call") {
                assertTrue("function_call should not be last", i < result.size - 1)
                val next = result[i + 1].jsonObject
                assertEquals("function_call_output should follow",
                    "function_call_output", next["type"]?.jsonPrimitive?.content)
                assertTrue("call_id should match",
                    item["call_id"]?.jsonPrimitive?.content == next["call_id"]?.jsonPrimitive?.content)
                assertTrue("Order should be maintained", i > lastCallIndex)
                lastCallIndex = i
            }
        }
    }

    @Test
    fun `encrypted reasoning should not replay plaintext content`() {
        val reasoningItem = invokeBuildMessages(listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning(
                    reasoning = "plaintext reasoning",
                    metadata = OpenAIReasoningMetadata(
                        reasoningId = "rs_1",
                        encryptedContent = "encrypted",
                    ).toMetadata(),
                    reasoningType = ReasoningType.REASONING_TEXT,
                )),
            ),
        )).single().jsonObject

        assertEquals("reasoning", reasoningItem["type"]?.jsonPrimitive?.content)
        assertEquals("rs_1", reasoningItem["id"]?.jsonPrimitive?.content)
        assertEquals("encrypted", reasoningItem["encrypted_content"]?.jsonPrimitive?.content)
        assertFalse(reasoningItem.containsKey("content"))
    }

    @Test
    fun `unencrypted reasoning should replay plaintext content`() {
        val reasoningItem = invokeBuildMessages(listOf(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Reasoning(
                    reasoning = "plaintext reasoning",
                    metadata = OpenAIReasoningMetadata(reasoningId = "rs_1").toMetadata(),
                    reasoningType = ReasoningType.REASONING_TEXT,
                )),
            ),
        )).single().jsonObject

        val content = reasoningItem["content"]?.jsonArray
        assertEquals(1, content?.size)
        assertEquals(
            "plaintext reasoning",
            content?.single()?.jsonObject?.get("text")?.jsonPrimitive?.content,
        )
        assertFalse(reasoningItem.containsKey("encrypted_content"))
    }

    @Test
    fun `volc response api should not include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user("hello")),
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertFalse("volc should not include reasoning.summary", reasoning!!.containsKey("summary"))
    }

    @Test
    fun `openai response api should include reasoning summary`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://api.openai.com/v1"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user("hello")),
            params = createReasoningParams()
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("auto", reasoning!!["summary"]?.jsonPrimitive?.content)
    }

    @Test
    fun `volc response api should keep reasoning effort when non auto`() {
        val providerSetting = ProviderSetting.OpenAI(
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3"
        )
        val requestBody = invokeBuildRequestBody(
            providerSetting = providerSetting,
            messages = listOf(UIMessage.user("hello")),
            params = createReasoningParams(reasoningLevel = ReasoningLevel.LOW)
        )

        val reasoning = requestBody["reasoning"]?.jsonObject
        assertTrue("reasoning should exist", reasoning != null)
        assertEquals("low", reasoning!!["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun `system prompt text should be serialized into Response API instructions`() {
        val prompt = "Assistant prompt\n\nTool guidance"
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(),
            messages = listOf(
                UIMessage.system(prompt),
                UIMessage.user("hello")
            ),
            params = createReasoningParams()
        )

        assertEquals(prompt, requestBody["instructions"]?.jsonPrimitive?.content)
    }

    @Test
    fun `response api should encode image input`() {
        val result = invokeBuildMessages(
            listOf(
                UIMessage(
                    role = MessageRole.USER,
                    parts = listOf(
                        UIMessagePart.Text("Describe this image"),
                        UIMessagePart.Image("data:image/png;base64,AA=="),
                    )
                )
            )
        )

        val content = result.single().jsonObject["content"]!!.jsonArray
        val image = content.first { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }
        assertEquals(
            "data:image/png;base64,AA==",
            image.jsonObject["image_url"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `response api should map all adjustable reasoning budgets`() {
        listOf(
            ReasoningLevel.LOW to "low",
            ReasoningLevel.MEDIUM to "medium",
            ReasoningLevel.HIGH to "high",
            ReasoningLevel.XHIGH to "xhigh",
        ).forEach { (level, expected) ->
            val requestBody = invokeBuildRequestBody(
                providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
                messages = emptyList(),
                params = createReasoningParams(reasoningLevel = level),
            )

            assertEquals(
                expected,
                requestBody["reasoning"]?.jsonObject?.get("effort")?.jsonPrimitive?.content,
            )
        }
    }

    // ==================== Vision gate tests =============
    // Regression coverage mirroring ChatCompletionsAPI: a text-only model must never
    // see an `input_image` item, no matter which of the three emission sites
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
    fun `text-only model emits zero input_image and a placeholder at every site`() {
        val result = invokeBuildMessages(
            createHistoryWithImages(),
            supportInputModalities = listOf(Modality.TEXT),
        )
        val serialized = result.toString()

        assertFalse(
            "text-only model must not emit input_image anywhere",
            serialized.contains("\"input_image\"")
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
    fun `vision model still emits input_image unchanged`() {
        val result = invokeBuildMessages(
            createHistoryWithImages(),
            supportInputModalities = listOf(Modality.TEXT, Modality.IMAGE),
        )
        val serialized = result.toString()

        assertTrue("vision model should still emit input_image", serialized.contains("\"input_image\""))
        assertFalse(
            "vision model should not fall back to the text placeholder",
            serialized.contains(imagePlaceholder)
        )
    }

    // ==================== Image lift removal tests (issue #104) ====================
    // The Responses API allows input_image inline inside function_call_output.output,
    // which is in-spec, so no extra role:"user" lift item should ever be emitted; that
    // used to split a batch's function_call/function_call_output pairing.

    @Test
    fun `parallel tool batch with an early image keeps the image inline with no lift item`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Running three tools"),
                createExecutedToolWithImage("call_1", "take_screenshot", "{}"),
                createExecutedTool("call_2", "search_docs", "{}", "Result 2"),
                createExecutedTool("call_3", "search_wiki", "{}", "Result 3"),
            )
        )

        val messages = listOf(UIMessage.user("Do three things"), assistantMessage)

        val result = invokeBuildMessages(messages)

        val output1 = result.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" &&
                it.jsonObject["call_id"]?.jsonPrimitive?.content == "call_1"
        }.jsonObject
        val outputContent = output1["output"]!!.jsonArray
        assertTrue(
            "function_call_output should carry the image inline",
            outputContent.any { it.jsonObject["type"]?.jsonPrimitive?.content == "input_image" }
        )

        // No lifted USER item beyond the original user turn.
        val userItems = result.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        assertEquals("no lifted user item should be emitted", 1, userItems.size)

        // function_call/function_call_output pairing must stay contiguous for the batch.
        val toolItems = result.filter {
            it.jsonObject["type"]?.jsonPrimitive?.content in setOf("function_call", "function_call_output")
        }
        assertEquals(
            listOf(
                "function_call", "function_call", "function_call",
                "function_call_output", "function_call_output", "function_call_output",
            ),
            toolItems.map { it.jsonObject["type"]?.jsonPrimitive?.content },
        )
    }

    @Test
    fun `parallel tool batch with an early image emits no lift for a text-only model`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedToolWithImage("call_1", "take_screenshot", "{}"),
                createExecutedTool("call_2", "search_docs", "{}", "Result 2"),
            )
        )

        val messages = listOf(UIMessage.user("Do two things"), assistantMessage)

        val result = invokeBuildMessages(messages, supportInputModalities = listOf(Modality.TEXT))

        val userItems = result.filter { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        assertEquals("no lift item should be emitted for a text-only model", 1, userItems.size)

        val output1 = result.first {
            it.jsonObject["type"]?.jsonPrimitive?.content == "function_call_output" &&
                it.jsonObject["call_id"]?.jsonPrimitive?.content == "call_1"
        }.jsonObject
        assertEquals(imagePlaceholder, output1["output"]?.jsonPrimitive?.content)
    }

    @Test
    fun `function tools and built-in tools should coexist in the same tools array`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createToolParams(
                tools = listOf(createFunctionTool("get_weather")),
                builtInTools = setOf(BuiltInTools.Search)
            )
        )

        val tools = requestBody["tools"]?.jsonArray
        assertTrue("tools should exist", tools != null)
        val types = tools!!.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertTrue("function tool should not be dropped", types.contains("function"))
        assertTrue("built-in web_search should be present", types.contains("web_search"))
        assertEquals(2, tools.size)
    }

    @Test
    fun `function tools should be sent when no built-in tools configured`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createToolParams(tools = listOf(createFunctionTool("get_weather")))
        )

        val tools = requestBody["tools"]?.jsonArray
        assertEquals(1, tools?.size)
        assertEquals("function", tools!![0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", tools[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `tools key should be absent when neither function nor built-in tools exist`() {
        val requestBody = invokeBuildRequestBody(
            providerSetting = ProviderSetting.OpenAI(baseUrl = "https://api.openai.com/v1"),
            params = createToolParams()
        )

        assertFalse("tools key should not be written", requestBody.containsKey("tools"))
    }

    @Test
    fun `response stream retry only replays failures without output`() {
        assertTrue(
            shouldRetryResponseStream(
                failure = ResponseStreamFailureException(
                    receivedMeaningfulOutput = false,
                    message = "closed before completion",
                ),
                retryAttempt = 0,
                maxRetries = 2,
            )
        )
        assertTrue(
            shouldRetryResponseStream(
                failure = IOException("stream reset"),
                retryAttempt = 1,
                maxRetries = 2,
            )
        )
        assertFalse(
            shouldRetryResponseStream(
                failure = ResponseStreamFailureException(
                    receivedMeaningfulOutput = true,
                    message = "stream reset after text",
                ),
                retryAttempt = 0,
                maxRetries = 2,
            )
        )
        assertFalse(
            shouldRetryResponseStream(
                failure = IOException("stream reset"),
                retryAttempt = 2,
                maxRetries = 2,
            )
        )
        assertTrue(
            shouldRetryResponseStream(
                failure = IllegalStateException("HTTP 401"),
                retryAttempt = 0,
                maxRetries = 2,
            )
        )
        assertFalse(
            shouldRetryResponseStream(
                failure = CancellationException("Generation cancelled by user"),
                retryAttempt = 0,
                maxRetries = 2,
            )
        )
    }

    @Test
    fun `response failed event exposes provider code and message and is not retried`() {
        val failure = parseResponseStreamError(
            payload = Json.parseToJsonElement(
                """{"type":"response.failed","response":{"error":{"code":"context_length_exceeded","message":"maximum context length is 300000 tokens"}}}"""
            ).jsonObject,
            eventType = "response.failed",
        )

        assertEquals("context_length_exceeded", failure.code)
        assertTrue(failure.message!!.contains("context_length_exceeded"))
        assertTrue(failure.message!!.contains("maximum context length"))
        assertFalse(shouldRetryResponseStream(failure, retryAttempt = 0, maxRetries = 10))
    }

    @Test
    fun `error event uses top level provider message`() {
        val failure = parseResponseStreamError(
            payload = Json.parseToJsonElement(
                """{"type":"error","code":"rate_limit_exceeded","message":"try again later"}"""
            ).jsonObject,
            eventType = "error",
        )

        assertEquals("rate_limit_exceeded", failure.code)
        assertTrue(failure.message!!.contains("try again later"))
        assertFalse(shouldRetryResponseStream(failure, retryAttempt = 0, maxRetries = 10))
    }

    @Test
    fun `context length errors returned as HTTP failures are not retried`() {
        assertFalse(
            shouldRetryResponseStream(
                failure = IOException("context_length_exceeded: maximum context length is 300000"),
                retryAttempt = 0,
                maxRetries = 10,
            )
        )
    }

    @Test
    fun `server tool should replay the original response item`() {
        val rawItem = buildJsonObject {
            put("type", "web_search_call")
            put("id", "ws_1")
            put("status", "completed")
            put("action", buildJsonObject { put("type", "search") })
        }
        val messages = listOf(
            UIMessage.user("search"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.ServerTool(
                    toolCallId = "ws_1",
                    toolName = "web_search",
                    status = ServerToolStatus.COMPLETED,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.OPENAI_RESPONSES,
                        call = rawItem,
                    ).toMetadata(),
                )),
            ),
        )

        val item = invokeBuildMessages(messages).last().jsonObject
        assertEquals(rawItem, item)
    }

    @Test
    fun `server tool should not replay Claude blocks into Responses input`() {
        val claudeCall = buildJsonObject {
            put("type", "server_tool_use")
            put("id", "srvtoolu_1")
            put("name", "web_search")
            put("input", buildJsonObject {})
        }
        val messages = listOf(
            UIMessage.user("search"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.ServerTool(
                    toolCallId = "srvtoolu_1",
                    toolName = "web_search",
                    status = ServerToolStatus.COMPLETED,
                    metadata = ServerToolMetadata(
                        protocol = ServerToolProtocol.ANTHROPIC_MESSAGES,
                        call = claudeCall,
                    ).toMetadata(),
                )),
            ),
        )

        val items = invokeBuildMessages(messages)

        assertEquals(1, items.size)
        assertEquals("user", items.single().jsonObject["role"]?.jsonPrimitive?.content)
    }

    // ==================== Helper Functions ====================

    private fun createToolParams(
        tools: List<Tool> = emptyList(),
        builtInTools: Set<BuiltInTools> = emptySet()
    ): TextGenerationParams {
        return TextGenerationParams(
            model = Model(
                modelId = "test-model",
                displayName = "test-model",
                abilities = listOf(ModelAbility.TOOL),
                tools = builtInTools
            ),
            tools = tools
        )
    }

    private fun createFunctionTool(name: String): Tool {
        return Tool(
            name = name,
            description = "test tool",
            parameters = { InputSchema.Obj(properties = JsonObject(emptyMap())) },
            execute = { emptyList() }
        )
    }

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
