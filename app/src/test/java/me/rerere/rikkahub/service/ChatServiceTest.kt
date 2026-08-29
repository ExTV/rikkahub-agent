package me.rerere.rikkahub.service

import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.AutoCompactionThresholdMode
import me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.uuid.Uuid

class ChatServiceTest {
    @Test
    fun `fork conversation inherits folder and workspace context`() {
        val source = Conversation(
            assistantId = Uuid.random(),
            title = "Source conversation",
            messageNodes = emptyList(),
            workspaceCwd = "/workspace/project",
            folderId = Uuid.random(),
        )

        val fork = createForkConversation(source, emptyList())

        assertNotEquals(source.id, fork.id)
        assertEquals(source.assistantId, fork.assistantId)
        assertEquals(source.workspaceCwd, fork.workspaceCwd)
        assertEquals(source.folderId, fork.folderId)
        assertEquals("", fork.title)
        assertFalse(fork.isPinned)
    }

    @Test
    fun `background generation params include model custom request configuration`() {
        val headers = listOf(CustomHeader(name = "X-Gateway-Token", value = "test-token"))
        val bodies = listOf(CustomBody(key = "gateway_mode", value = JsonPrimitive("strict")))
        val model = Model(
            modelId = "custom-chat-model",
            customHeaders = headers,
            customBodies = bodies,
        )

        val params = backgroundTextGenerationParams(model)

        assertEquals(model, params.model)
        assertEquals(ReasoningLevel.OFF, params.reasoningLevel)
        assertEquals(headers, params.customHeaders)
        assertEquals(bodies, params.customBody)
    }

    @Test
    fun `token threshold is used as explicit compression context ceiling`() {
        val model = Model(modelId = "codex-model", contextLength = null)
        val settings = Settings(
            autoCompactionThresholdMode = AutoCompactionThresholdMode.TOKENS,
            autoCompactionThresholdTokensK = 372,
        )

        assertEquals(372_000, compactionContextLength(settings, model))
    }

    @Test
    fun `percent threshold keeps advertised compression context`() {
        val model = Model(modelId = "model", contextLength = 128_000)
        val settings = Settings(
            autoCompactionThresholdMode = AutoCompactionThresholdMode.PERCENT,
            autoCompactionThresholdTokensK = 372,
        )

        assertEquals(128_000, compactionContextLength(settings, model))
    }

    @Test
    fun `findToolCallPart locates a tool call in the currently selected branch`() {
        val target = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "search_web",
            input = "{}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val node = MessageNode(
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(target)))
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        val found = findToolCallPart(conversation, "call-1")

        assertEquals(target, found)
    }

    @Test
    fun `findToolCallPart locates a tool call in a non-selected branch`() {
        val target = UIMessagePart.Tool(
            toolCallId = "call-2",
            toolName = "search_web",
            input = "{}",
            output = listOf(UIMessagePart.Text("result")),
        )
        val node = MessageNode(
            messages = listOf(
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(target)),
                UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("regenerated"))),
            ),
            selectIndex = 1, // the branch containing "call-2" is NOT the selected one
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        val found = findToolCallPart(conversation, "call-2")

        assertEquals(target, found)
    }

    @Test
    fun `findToolCallPart returns null when no tool call matches`() {
        val node = MessageNode(
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi"))))
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        assertNull(findToolCallPart(conversation, "missing-call"))
    }

    @Test
    fun `replaceToolCallPart replaces only the matching part and leaves the rest untouched`() {
        val original = UIMessagePart.Tool(
            toolCallId = "call-3",
            toolName = "search_web",
            input = "{\"q\":\"weather\"}",
            output = listOf(UIMessagePart.Text("old result")),
            executionStartedAt = 1_000L,
        )
        val otherText = UIMessagePart.Text("keep me")
        val node = MessageNode(
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(otherText, original)))
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        val updated = replaceToolCallPart(conversation, "call-3") {
            it.copy(output = listOf(UIMessagePart.Text("new result")), executionStartedAt = 2_000L)
        }

        val updatedTool = updated.messageNodes.single().messages.single().parts
            .filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("new result", (updatedTool.output.single() as UIMessagePart.Text).text)
        assertEquals(2_000L, updatedTool.executionStartedAt)
        // Unrelated parts are unaffected.
        assertTrue(updated.messageNodes.single().messages.single().parts.contains(otherText))
    }

    @Test
    fun `replaceToolCallPart is a no-op when the tool call is not found`() {
        val node = MessageNode(
            messages = listOf(UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hi"))))
        )
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = listOf(node))

        val result = replaceToolCallPart(conversation, "missing-call") { it }

        assertSame(conversation, result)
    }

    @Test
    fun `isStalledTurn is true on failure regardless of the last message`() {
        assertTrue(isStalledTurn(succeeded = false, lastMessage = null))
    }

    @Test
    fun `isStalledTurn is true on failure with an empty message list`() {
        // Mirrors handleMessageComplete calling this against currentMessages.lastOrNull()
        // when the conversation has no assistant message at all.
        val conversation = Conversation(assistantId = Uuid.random(), messageNodes = emptyList())

        assertTrue(isStalledTurn(succeeded = false, lastMessage = conversation.currentMessages.lastOrNull()))
    }

    @Test
    fun `isStalledTurn is false on success when the last assistant message has text`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("hello there")))

        assertTrue(!isStalledTurn(succeeded = true, lastMessage = message))
    }

    @Test
    fun `isStalledTurn is true on success when the last message is reasoning and tool parts only`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Reasoning(reasoning = "thinking..."),
                UIMessagePart.Tool(
                    toolCallId = "call-4",
                    toolName = "search_web",
                    input = "{}",
                    output = listOf(UIMessagePart.Text("result")),
                ),
            ),
        )

        assertTrue(isStalledTurn(succeeded = true, lastMessage = message))
    }

    @Test
    fun `isStalledTurn is true on success when the only text part is blank`() {
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("   ")))

        assertTrue(isStalledTurn(succeeded = true, lastMessage = message))
    }

    @Test
    fun `external web search is disabled when assistant preference is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model()

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `external web search is enabled when assistant preference is enabled`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model()

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search suppresses enabled external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `built-in search remains exclusive when external web search is disabled`() {
        val assistant = Assistant(enableWebSearch = false)
        val model = Model(tools = setOf(BuiltInTools.Search))

        assertFalse(shouldUseExternalWebSearch(assistant, model))
    }

    @Test
    fun `unrelated built-in tools do not suppress external web search`() {
        val assistant = Assistant(enableWebSearch = true)
        val model = Model(tools = setOf(BuiltInTools.UrlContext))

        assertTrue(shouldUseExternalWebSearch(assistant, model))
    }

    // --- resolveCompressionModel: Compress Model "Auto" resolving to a disabled provider -----

    @Test
    fun `auto with the built-in provider disabled resolves to the current chat model`() {
        val chatModel = Model(modelId = "gpt-5")
        val autoModel = Model(id = DEFAULT_AUTO_MODEL_ID, modelId = "auto")
        val rikkaHub = ProviderSetting.OpenAI(enabled = false, name = "RikkaHub", models = listOf(autoModel))
        val chatProvider = ProviderSetting.OpenAI(enabled = true, name = "OpenAI", models = listOf(chatModel))
        val settings = Settings(
            compressModelId = autoModel.id,
            chatModelId = chatModel.id,
            providers = listOf(rikkaHub, chatProvider),
        )

        assertEquals(chatModel, resolveCompressionModel(settings))
    }

    @Test
    fun `auto with the current chat model's provider also disabled resolves to a model from an enabled provider`() {
        val chatModel = Model(modelId = "gpt-5")
        val autoModel = Model(id = DEFAULT_AUTO_MODEL_ID, modelId = "auto")
        val fallbackModel = Model(modelId = "fallback-model")
        val rikkaHub = ProviderSetting.OpenAI(enabled = false, name = "RikkaHub", models = listOf(autoModel))
        val chatProvider = ProviderSetting.OpenAI(enabled = false, name = "OpenAI", models = listOf(chatModel))
        val enabledProvider = ProviderSetting.OpenAI(enabled = true, name = "Groq", models = listOf(fallbackModel))
        val settings = Settings(
            compressModelId = autoModel.id,
            chatModelId = chatModel.id,
            providers = listOf(rikkaHub, chatProvider, enabledProvider),
        )

        assertEquals(fallbackModel, resolveCompressionModel(settings))
    }

    @Test
    fun `no enabled provider at all resolves to null`() {
        val chatModel = Model(modelId = "gpt-5")
        val autoModel = Model(id = DEFAULT_AUTO_MODEL_ID, modelId = "auto")
        val rikkaHub = ProviderSetting.OpenAI(enabled = false, name = "RikkaHub", models = listOf(autoModel))
        val chatProvider = ProviderSetting.OpenAI(enabled = false, name = "OpenAI", models = listOf(chatModel))
        val settings = Settings(
            compressModelId = autoModel.id,
            chatModelId = chatModel.id,
            providers = listOf(rikkaHub, chatProvider),
        )

        assertNull(resolveCompressionModel(settings))
        // generateAndStoreCompaction throws this exact message when resolution fails, and it
        // names the setting to change so the resulting error card is actionable.
        assertTrue(compressionModelUnavailableMessage().contains("Compress Model"))
    }

    @Test
    fun `a configured non-auto model on an enabled provider is used as-is`() {
        val configured = Model(modelId = "claude")
        val provider = ProviderSetting.OpenAI(enabled = true, name = "Anthropic", models = listOf(configured))
        val settings = Settings(
            compressModelId = configured.id,
            providers = listOf(provider),
        )

        assertEquals(configured, resolveCompressionModel(settings))
    }

    // --- automaticCompactionNoOpResult: a non-advancing boundary must not abort the turn -----

    @Test
    fun `a non-advancing boundary with an existing compaction returns that compaction`() {
        val existing = ConversationCompaction(
            conversationId = Uuid.random(),
            summary = "prior summary",
            tailStartNodeId = Uuid.random(),
            sourceEndNodeId = Uuid.random(),
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 500,
            createdAt = Instant.EPOCH,
        )

        assertSame(existing, automaticCompactionNoOpResult(existing))
    }

    @Test
    fun `a non-advancing boundary with no existing compaction still throws`() {
        assertThrows(IllegalStateException::class.java) {
            automaticCompactionNoOpResult(null)
        }
    }

    // --- recordAutoCompactionIfCreated: a no-op must not surface as newlyCreatedAutoCompaction

    @Test
    fun `a no-op compaction result is not recorded as newly created`() {
        var recorded: ConversationCompaction? = null

        val result = recordAutoCompactionIfCreated(null) { recorded = it }

        assertNull(result)
        assertNull(recorded)
    }

    @Test
    fun `a freshly created compaction result is recorded as newly created`() {
        val fresh = ConversationCompaction(
            conversationId = Uuid.random(),
            summary = "new summary",
            tailStartNodeId = Uuid.random(),
            sourceEndNodeId = Uuid.random(),
            summaryModelId = Uuid.random(),
            isAuto = true,
            sourceTokenEstimate = 500,
            createdAt = Instant.EPOCH,
        )
        var recorded: ConversationCompaction? = null

        val result = recordAutoCompactionIfCreated(fresh) { recorded = it }

        assertSame(fresh, result)
        assertSame(fresh, recorded)
    }
}
