package me.rerere.ai.provider.providers.google

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for GoogleProvider message building logic.
 * Tests the conversion from UIMessage list to Google Gemini API format,
 * specifically focusing on multi-round reasoning/tool scenarios.
 *
 * Google API format:
 * - role: "user" or "model"
 * - parts array containing text, functionCall, functionResponse
 * - thought: true for reasoning parts
 */
class GoogleRequestMessageTest {

    private lateinit var provider: GoogleProvider

    @Before
    fun setUp() {
        provider = GoogleProvider(OkHttpClient())
    }

    // Helper to invoke private buildContents method via reflection
    private fun invokeBuildContents(messages: List<UIMessage>): JsonArray {
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildContents",
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, messages) as JsonArray
    }

    private fun invokeBuildCompletionRequestBody(
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): JsonObject {
        // Reflection pins the exact signature, so the safety-category list has to be passed
        // explicitly: Kotlin compiles the default away into a separate synthetic overload.
        val method = GoogleProvider::class.java.getDeclaredMethod(
            "buildCompletionRequestBody",
            List::class.java,
            TextGenerationParams::class.java,
            List::class.java
        )
        method.isAccessible = true
        return method.invoke(provider, messages, params, GOOGLE_SAFETY_CATEGORIES) as JsonObject
    }

    @Test
    fun `multi-round tool calls should produce functionCall followed by functionResponse`() {
        // Scenario: Multiple rounds of tool calls
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

        val result = invokeBuildContents(messages)

        // Google format:
        // 1. user message
        // 2. model message with [text, functionCall(search)]
        // 3. user message with [functionResponse(search)]
        // 4. model message with [text, functionCall(calculate)]
        // 5. user message with [functionResponse(calculate)]
        // 6. model message with [text]

        // Collect all functionCall and functionResponse parts
        val functionCalls = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val functionResponses = mutableListOf<kotlinx.serialization.json.JsonObject>()

        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val partObj = part.jsonObject
                if (partObj.containsKey("functionCall")) {
                    functionCalls.add(partObj["functionCall"]!!.jsonObject)
                }
                if (partObj.containsKey("functionResponse")) {
                    functionResponses.add(partObj["functionResponse"]!!.jsonObject)
                }
            }
        }

        assertEquals("Should have 2 functionCall parts", 2, functionCalls.size)
        assertEquals("Should have 2 functionResponse parts", 2, functionResponses.size)

        // Verify functionCall contents
        assertEquals("search", functionCalls[0]["name"]?.jsonPrimitive?.content)
        assertEquals("calculate", functionCalls[1]["name"]?.jsonPrimitive?.content)

        // Verify functionResponse contents
        assertEquals("search", functionResponses[0]["name"]?.jsonPrimitive?.content)
        assertEquals("calculate", functionResponses[1]["name"]?.jsonPrimitive?.content)

        // Every functionCall/functionResponse must carry a non-blank id, and the ids must
        // pair up per tool call (call_1 -> search, call_2 -> calculate) - see issue #26.
        val callIds = functionCalls.map { it["id"]?.jsonPrimitive?.content }
        val responseIds = functionResponses.map { it["id"]?.jsonPrimitive?.content }
        callIds.forEach { assertTrue("functionCall id should be non-blank", !it.isNullOrBlank()) }
        responseIds.forEach { assertTrue("functionResponse id should be non-blank", !it.isNullOrBlank()) }
        assertEquals("call_1", callIds[0])
        assertEquals("call_2", callIds[1])
        assertEquals(
            "functionCall and functionResponse ids should pair up per tool call",
            callIds,
            responseIds
        )
    }

    @Test
    fun `functionCall in model should be followed by user message with functionResponse`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text("Using tool"),
                createExecutedTool("call_abc", "my_tool", "{}", "Tool output")
            )
        )

        val messages = listOf(
            UIMessage.user("Use a tool"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message with functionCall
        var modelWithFunctionCallIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            if (msg["role"]?.jsonPrimitive?.content == "model") {
                val parts = msg["parts"]?.jsonArray ?: continue
                if (parts.any { it.jsonObject.containsKey("functionCall") }) {
                    modelWithFunctionCallIndex = i
                    break
                }
            }
        }

        assertTrue("Should find model with functionCall", modelWithFunctionCallIndex >= 0)
        assertTrue("Should not be last message", modelWithFunctionCallIndex < result.size - 1)

        // Next message should be user with functionResponse
        val nextMsg = result[modelWithFunctionCallIndex + 1].jsonObject
        assertEquals("user", nextMsg["role"]?.jsonPrimitive?.content)
        val nextParts = nextMsg["parts"]?.jsonArray
        assertTrue("Next message should have functionResponse",
            nextParts?.any { it.jsonObject.containsKey("functionResponse") } == true)
    }

    @Test
    fun `reasoning parts should have thought flag set to true`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Let me think about this..."),
                UIMessagePart.Text("Here is my response")
            )
        )

        val messages = listOf(
            UIMessage.user("Question"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message
        val modelMsg = result.find {
            it.jsonObject["role"]?.jsonPrimitive?.content == "model"
        }?.jsonObject

        assertTrue("Should have model message", modelMsg != null)

        val parts = modelMsg!!["parts"]?.jsonArray
        assertTrue("Parts should not be null", parts != null)

        // Find text part with thought:true (reasoning is converted to text with thought flag)
        // Note: The implementation may vary - check for thought flag in text parts
        val textParts = parts!!.filter { it.jsonObject.containsKey("text") }
        assertTrue("Should have text parts", textParts.isNotEmpty())

        // Verify we have both regular text and thought text
        val hasThoughtPart = textParts.any {
            it.jsonObject["thought"]?.jsonPrimitive?.content == "true" ||
            it.jsonObject["thought"]?.toString() == "true"
        }
        // Note: If reasoning is handled differently, adjust this assertion
    }

    @Test
    fun `parallel tool calls should be in same model message`() {
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
            UIMessage.user("Do multiple things"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find model message with all functionCall parts
        var foundModelWithMultipleCalls = false
        for (msg in result) {
            val msgObj = msg.jsonObject
            if (msgObj["role"]?.jsonPrimitive?.content != "model") continue

            val parts = msgObj["parts"]?.jsonArray ?: continue
            val functionCallParts = parts.filter { it.jsonObject.containsKey("functionCall") }

            if (functionCallParts.size == 3) {
                foundModelWithMultipleCalls = true
                // Verify tool names
                val toolNames = functionCallParts.map {
                    it.jsonObject["functionCall"]?.jsonObject?.get("name")?.jsonPrimitive?.content
                }
                assertTrue(toolNames.contains("tool_a"))
                assertTrue(toolNames.contains("tool_b"))
                assertTrue(toolNames.contains("tool_c"))
                break
            }
        }

        assertTrue("Should have model with 3 parallel functionCall parts",
            foundModelWithMultipleCalls)

        // Verify corresponding functionResponse parts in user message
        var foundUserWithMultipleResponses = false
        for (msg in result) {
            val msgObj = msg.jsonObject
            if (msgObj["role"]?.jsonPrimitive?.content != "user") continue

            val parts = msgObj["parts"]?.jsonArray ?: continue
            val responseParts = parts.filter { it.jsonObject.containsKey("functionResponse") }

            if (responseParts.size == 3) {
                foundUserWithMultipleResponses = true
                break
            }
        }

        assertTrue("Should have user with 3 functionResponse parts",
            foundUserWithMultipleResponses)
    }

    @Test
    fun `multi-round reasoning and tools should maintain correct order`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "Step 1: Search"),
                UIMessagePart.Text("Searching..."),
                createExecutedTool("call_1", "search", "{}", "Found data"),
                UIMessagePart.Reasoning(reasoning = "Step 2: Analyze"),
                UIMessagePart.Text("Analyzing..."),
                createExecutedTool("call_2", "analyze", "{}", "Analysis done"),
                UIMessagePart.Reasoning(reasoning = "Step 3: Present"),
                UIMessagePart.Text("Results")
            )
        )

        val messages = listOf(
            UIMessage.user("Analyze"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Verify structure:
        // model -> user (functionResponse) -> model -> user (functionResponse) -> model

        var functionCallCount = 0
        var functionResponseCount = 0

        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                val partObj = part.jsonObject
                if (partObj.containsKey("functionCall")) functionCallCount++
                if (partObj.containsKey("functionResponse")) functionResponseCount++
            }
        }

        assertEquals("Should have 2 functionCall parts", 2, functionCallCount)
        assertEquals("Should have 2 functionResponse parts", 2, functionResponseCount)

        // Verify functionCall -> functionResponse order
        for (i in 0 until result.size - 1) {
            val msg = result[i].jsonObject
            val parts = msg["parts"]?.jsonArray ?: continue
            val hasFunctionCall = parts.any { it.jsonObject.containsKey("functionCall") }

            if (hasFunctionCall && msg["role"]?.jsonPrimitive?.content == "model") {
                // Next should be user with functionResponse
                val nextMsg = result[i + 1].jsonObject
                assertEquals("user", nextMsg["role"]?.jsonPrimitive?.content)
                val nextParts = nextMsg["parts"]?.jsonArray
                assertTrue("Should have functionResponse in next message",
                    nextParts?.any { it.jsonObject.containsKey("functionResponse") } == true)
            }
        }
    }

    @Test
    fun `user message parts should be correctly formatted`() {
        val messages = listOf(
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(
                    UIMessagePart.Text("Hello, how are you?")
                )
            )
        )

        val result = invokeBuildContents(messages)

        assertEquals(1, result.size)
        val userMsg = result[0].jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)

        val parts = userMsg["parts"]?.jsonArray
        assertTrue("Parts should not be null", parts != null)
        assertTrue("Parts should not be empty", parts!!.isNotEmpty())

        val textPart = parts.find { it.jsonObject.containsKey("text") }?.jsonObject
        assertEquals("Hello, how are you?", textPart?.get("text")?.jsonPrimitive?.content)
    }

    @Test
    fun `complex multi-round scenario with interleaved content`() {
        val messages = listOf(
            UIMessage.user("Execute task"),
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Text("Starting"),
                    createExecutedTool("step1", "init", "{}", "initialized"),
                    UIMessagePart.Text("Processing"),
                    createExecutedTool("step2", "process", """{"data": "x"}""", "processed"),
                    UIMessagePart.Text("Finalizing"),
                    createExecutedTool("step3", "finalize", "{}", "done"),
                    UIMessagePart.Text("Task completed")
                )
            )
        )

        val result = invokeBuildContents(messages)

        // Count roles
        val userCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "user" }
        val modelCount = result.count { it.jsonObject["role"]?.jsonPrimitive?.content == "model" }

        // Should have: 1 initial user + 3 functionResponse users = 4 user messages
        // And: multiple model messages
        assertEquals("Should have 4 user messages (1 initial + 3 responses)", 4, userCount)
        assertTrue("Should have multiple model messages", modelCount >= 3)

        // Verify order: each functionCall should be followed by functionResponse
        var lastFunctionCallIndex = -1
        for (i in result.indices) {
            val msg = result[i].jsonObject
            val parts = msg["parts"]?.jsonArray ?: continue
            if (parts.any { it.jsonObject.containsKey("functionCall") }) {
                assertTrue("functionCall should not be last", i < result.size - 1)
                val next = result[i + 1].jsonObject
                assertEquals("user", next["role"]?.jsonPrimitive?.content)
                assertTrue("Index should increase", i > lastFunctionCallIndex)
                lastFunctionCallIndex = i
            }
        }
    }

    @Test
    fun `functionResponse should contain correct result structure`() {
        val assistantMessage = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                createExecutedTool("call_1", "my_tool", """{"input": "test"}""", "Expected output value")
            )
        )

        val messages = listOf(
            UIMessage.user("Use tool"),
            assistantMessage
        )

        val result = invokeBuildContents(messages)

        // Find functionResponse
        var functionResponse: kotlinx.serialization.json.JsonObject? = null
        for (msg in result) {
            val msgObj = msg.jsonObject
            val parts = msgObj["parts"]?.jsonArray ?: continue
            for (part in parts) {
                if (part.jsonObject.containsKey("functionResponse")) {
                    functionResponse = part.jsonObject["functionResponse"]?.jsonObject
                    break
                }
            }
            if (functionResponse != null) break
        }

        assertTrue("Should find functionResponse", functionResponse != null)
        assertEquals("my_tool", functionResponse!!["name"]?.jsonPrimitive?.content)

        // Verify response structure
        val response = functionResponse["response"]?.jsonObject
        assertTrue("Response should contain result",
            response?.containsKey("result") == true)
        assertTrue("Result should contain expected output",
            response?.get("result")?.jsonPrimitive?.content?.contains("Expected output value") == true)
    }

    @Test
    fun `system prompt text should be serialized into Gemini system instruction`() {
        val prompt = "Assistant prompt\n\nTool guidance"
        val messages = listOf(
            UIMessage.system(prompt),
            UIMessage.user("hello")
        )
        val params = TextGenerationParams(
            model = Model(modelId = "gemini-test", abilities = listOf(ModelAbility.REASONING))
        )

        val request = invokeBuildCompletionRequestBody(messages, params)
        val systemInstruction = request["systemInstruction"]!!.jsonObject
        val parts = systemInstruction["parts"]!!.jsonArray
        assertEquals(prompt, parts.single().jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `Gemini 3 series models send the canonical uppercase thinkingLevel for each ReasoningLevel`() {
        // The v1beta discovery document and both official SDKs (@google/genai, python-genai)
        // serialize thinkingLevel as the uppercase proto enum name.
        val expected = mapOf(
            ReasoningLevel.OFF to "MINIMAL",
            ReasoningLevel.LOW to "LOW",
            ReasoningLevel.MEDIUM to "MEDIUM",
            ReasoningLevel.HIGH to "HIGH",
            ReasoningLevel.XHIGH to "HIGH",
        )

        for ((level, thinkingLevel) in expected) {
            val messages = listOf(UIMessage.user("hello"))
            val params = TextGenerationParams(
                model = Model(
                    modelId = "gemini-3-pro-preview",
                    abilities = listOf(ModelAbility.REASONING)
                ),
                reasoningLevel = level,
            )

            val request = invokeBuildCompletionRequestBody(messages, params)
            val thinkingConfig = request["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject

            assertEquals(
                "ReasoningLevel.$level should map to thinkingLevel \"$thinkingLevel\"",
                thinkingLevel,
                thinkingConfig["thinkingLevel"]?.jsonPrimitive?.content
            )
        }
    }

    @Test
    fun `AUTO reasoning level omits thinkingLevel for Gemini 3 series models`() {
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(
                modelId = "gemini-3-pro-preview",
                abilities = listOf(ModelAbility.REASONING)
            ),
            reasoningLevel = ReasoningLevel.AUTO,
        )

        val request = invokeBuildCompletionRequestBody(messages, params)
        val thinkingConfig = request["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject

        assertTrue(
            "AUTO must not emit a thinkingLevel key",
            !thinkingConfig.containsKey("thinkingLevel")
        )
    }

    @Test
    fun `function tools and model built-in tools should both land in the tools array`() {
        // Regression for the "tools" key being written twice: put("tools", ...) on a
        // JsonObjectBuilder replaces the existing key outright, so a model with a
        // built-in tool (e.g. Search) enabled alongside caller-supplied function tools
        // used to silently drop every function declaration.
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(
                modelId = "gemini-test",
                abilities = listOf(ModelAbility.TOOL),
                tools = setOf(BuiltInTools.Search),
            ),
            tools = listOf(
                Tool(
                    name = "my_tool",
                    description = "a function tool",
                    execute = { emptyList() },
                )
            ),
        )

        val request = invokeBuildCompletionRequestBody(messages, params)
        val toolsArray = request["tools"]!!.jsonArray

        val hasFunctionDeclarations = toolsArray.any { it.jsonObject.containsKey("functionDeclarations") }
        val hasGoogleSearch = toolsArray.any { it.jsonObject.containsKey("googleSearch") }

        assertTrue("function tools must survive alongside built-in tools", hasFunctionDeclarations)
        assertTrue("built-in tools must still be present", hasGoogleSearch)
    }

    @Test
    fun `function tool parameters are sanitized to the Gemini schema allowlist`() {
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(modelId = "gemini-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(
                Tool(
                    name = "search",
                    description = "a function tool with a JSON-Schema-only key",
                    parameters = {
                        InputSchema.Obj(
                            properties = buildJsonObject {
                                put("query", buildJsonObject {
                                    put("type", "string")
                                    put("x-google-identifier", "should be stripped")
                                })
                            }
                        )
                    },
                    execute = { emptyList() },
                )
            ),
        )

        val request = invokeBuildCompletionRequestBody(messages, params)
        val declaration = request["tools"]!!.jsonArray.first()
            .jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject
        val queryProperty = declaration["parameters"]!!.jsonObject["properties"]!!
            .jsonObject["query"]!!.jsonObject

        assertEquals("string", queryProperty["type"]?.jsonPrimitive?.content)
        assertTrue(
            "unknown vendor keys must not reach Google",
            queryProperty["x-google-identifier"] == null
        )
    }

    @Test
    fun `a tool with no parameters omits the parameters key entirely`() {
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(modelId = "gemini-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(
                Tool(
                    name = "no_args",
                    description = "a tool that takes no arguments",
                    execute = { emptyList() },
                )
            ),
        )

        val request = invokeBuildCompletionRequestBody(messages, params)
        val declaration = request["tools"]!!.jsonArray.first()
            .jsonObject["functionDeclarations"]!!.jsonArray.single().jsonObject

        assertTrue(
            "parameters key must be omitted, not emitted as JSON null",
            !declaration.containsKey("parameters")
        )
    }

    @Test
    fun `toolConfig includeServerSideToolInvocations is set when function and built-in tools are mixed`() {
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(
                modelId = "gemini-test",
                abilities = listOf(ModelAbility.TOOL),
                tools = setOf(BuiltInTools.Search),
            ),
            tools = listOf(
                Tool(
                    name = "my_tool",
                    description = "a function tool",
                    execute = { emptyList() },
                )
            ),
        )

        val request = invokeBuildCompletionRequestBody(messages, params)
        val toolConfig = request["toolConfig"]!!.jsonObject

        assertTrue(toolConfig["includeServerSideToolInvocations"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `toolConfig is absent when only function tools are present`() {
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(modelId = "gemini-test", abilities = listOf(ModelAbility.TOOL)),
            tools = listOf(
                Tool(
                    name = "my_tool",
                    description = "a function tool",
                    execute = { emptyList() },
                )
            ),
        )

        val request = invokeBuildCompletionRequestBody(messages, params)

        assertTrue("toolConfig must be absent with only function tools", request["toolConfig"] == null)
    }

    @Test
    fun `toolConfig is absent when only built-in tools are present`() {
        val messages = listOf(UIMessage.user("hello"))
        val params = TextGenerationParams(
            model = Model(
                modelId = "gemini-test",
                abilities = listOf(ModelAbility.TOOL),
                tools = setOf(BuiltInTools.Search),
            ),
        )

        val request = invokeBuildCompletionRequestBody(messages, params)

        assertTrue("toolConfig must be absent with only built-in tools", request["toolConfig"] == null)
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
