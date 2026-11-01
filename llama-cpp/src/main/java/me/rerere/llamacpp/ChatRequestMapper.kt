package me.rerere.llamacpp

import kotlinx.serialization.json.Json
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts the app's messages and tools into the OpenAI-shaped request that
 * llama.cpp's chat template layer consumes. The shape is deliberately the same one the
 * app already builds for Chat Completions, so there is one wire format to reason about.
 */
object ChatRequestMapper {

    fun toRequestJson(messages: List<UIMessage>, tools: List<Tool>): String {
        val root = JSONObject()
        root.put("messages", messagesArray(messages))
        // Absent rather than empty: an empty array makes some templates emit a tool
        // preamble describing no tools at all.
        if (tools.isNotEmpty()) {
            root.put("tools", toolsArray(tools))
        }
        return root.toString()
    }

    /**
     * Drops the oldest turns until the rendered conversation fits [budgetBytes].
     *
     * The newest non-system turn is always kept, even if it alone exceeds the budget:
     * sending a conversation with no user turn breaks the chat template, and an
     * overflowing prompt that produces a readable error beats that. System messages are
     * kept too, since the template needs them and they are already capped by the
     * planner's budget.
     */
    fun trimToBudget(messages: List<UIMessage>, budgetBytes: Int): List<UIMessage> {
        if (messages.isEmpty()) return messages

        fun sizeOf(list: List<UIMessage>): Int =
            messagesArray(list).toString().toByteArray().size

        var working = messages
        // Stop once only one non-system turn remains: that survivor is always the
        // newest one, since each iteration only ever removes the oldest, and it must
        // never be dropped even if it alone pushes the request over budget.
        var droppableCount = working.count { it.role != MessageRole.SYSTEM }
        while (droppableCount > 1 && sizeOf(working) > budgetBytes) {
            // Drop the oldest non-system turn.
            val index = working.indexOfFirst { it.role != MessageRole.SYSTEM }
            if (index < 0) break
            working = working.toMutableList().apply { removeAt(index) }
            droppableCount--
        }
        return working
    }

    fun toolDeclarations(tools: List<Tool>): List<ToolDeclaration> {
        val array = toolsArray(tools)
        return tools.mapIndexed { index, tool ->
            ToolDeclaration(
                name = tool.name,
                jsonBytes = array.getJSONObject(index).toString().toByteArray().size,
            )
        }
    }

    private fun messagesArray(messages: List<UIMessage>): JSONArray {
        val array = JSONArray()
        for (message in messages) {
            if (message.role == MessageRole.ASSISTANT) {
                addAssistantMessages(array, message)
            } else {
                addPlainMessage(array, message)
            }
        }
        return array
    }

    private fun addPlainMessage(array: JSONArray, message: UIMessage) {
        val role = when (message.role) {
            MessageRole.USER -> "user"
            MessageRole.SYSTEM -> "system"
            MessageRole.TOOL -> "tool"
            MessageRole.ASSISTANT -> "assistant" // handled by addAssistantMessages
        }
        val text = message.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        array.put(JSONObject().put("role", role).put("content", text))
    }

    /**
     * Splits an assistant message on tool boundaries using the same grouping the app's
     * own Chat Completions provider uses, so a turn that talks, calls a tool, then talks
     * again becomes three messages (assistant, tool, assistant) with the tool result
     * sitting between the two assistant turns, instead of one message that glues both
     * texts together and announces the call before the result exists. Also guarantees
     * every entry in a group's tool_calls has a matching tool message, because
     * [groupPartsByToolBoundary] only ever groups executed tools into [PartGroup.Tools];
     * an unexecuted (pending-approval) tool part lands in [PartGroup.Content] instead and
     * is silently dropped, exactly like the reference does.
     */
    private fun addAssistantMessages(array: JSONArray, message: UIMessage) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    contentBuffer += group.parts
                }

                is PartGroup.Tools -> {
                    assistantMessage(contentBuffer, group.tools)?.let { array.put(it) }
                    contentBuffer.clear()
                    for (tool in group.tools) {
                        array.put(toolResultMessage(tool))
                    }
                }
            }
        }

        // Trailing text after the last tool call, or the whole message when it never
        // called a tool at all.
        assistantMessage(contentBuffer, emptyList())?.let { array.put(it) }
    }

    /** Null when there is nothing to say and nothing was called, matching the reference. */
    private fun assistantMessage(contentParts: List<UIMessagePart>, tools: List<UIMessagePart.Tool>): JSONObject? {
        val text = contentParts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        if (text.isBlank() && tools.isEmpty()) return null

        val obj = JSONObject().put("role", "assistant").put("content", text)
        if (tools.isNotEmpty()) {
            val calls = JSONArray()
            for (part in tools) {
                calls.put(
                    JSONObject()
                        .put("id", part.toolCallId)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", part.toolName)
                                // Normalized the same way ChatCompletionsAPI does, so a
                                // stream cut mid-argument never sends broken JSON.
                                .put("arguments", part.inputAsJson().toString()),
                        )
                )
            }
            obj.put("tool_calls", calls)
        }
        return obj
    }

    private fun toolResultMessage(tool: UIMessagePart.Tool): JSONObject {
        val output = tool.output
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString("") { it.text }
        return JSONObject()
            .put("role", "tool")
            .put("name", tool.toolName)
            .put("tool_call_id", tool.toolCallId)
            .put("content", output)
    }

    private fun toolsArray(tools: List<Tool>): JSONArray {
        val array = JSONArray()
        for (tool in tools) {
            // parameters is a lambda returning InputSchema?, and InputSchema is
            // @Serializable, so encode it with kotlinx and re-read it as org.json to
            // keep one JSON object model in this file.
            val schema = tool.parameters()
            val schemaJson = if (schema == null) {
                JSONObject().put("type", "object").put("properties", JSONObject())
            } else {
                JSONObject(Json.encodeToString(InputSchema.serializer(), schema))
            }
            array.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", schemaJson)
                    )
            )
        }
        return array
    }
}
