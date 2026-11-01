package me.rerere.locallm.litert

import android.util.Log
import com.google.ai.edge.litertlm.OpenApiTool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool as RikkaTool

private const val TAG = "LiteRtToolDeclaration"

/**
 * Declares one Rikka tool to the LiteRT-LM runtime under its real name and real JSON
 * schema.
 *
 * The SDK builds its tool description from [getToolDescriptionJsonString]: it parses the
 * string, reads `name` as the registry key, and hands the whole object to the model's chat
 * template. The shape it expects is the standard function declaration
 * `{name, description, parameters:{type:"object", properties:{...}, required:[...]}}`,
 * the same shape `ReflectionTool` generates for `@Tool`-annotated methods, and the same
 * shape `ToolManager.getToolsDescription()` wraps as `{"type":"function","function":{...}}`.
 *
 * # Why declaration-only
 *
 * The conversation is created with `automaticToolCalling = false`, so the runtime never
 * executes tools itself: it surfaces the model's calls on `Message.toolCalls` and
 * [LiteRtProvider] republishes them as ordinary `UIMessagePart.Tool` parts. That puts local
 * models on exactly the same execution path as every cloud provider, so the HARDLINE floor,
 * the per-call approval prompt, the auto-approve allowlist, and the per-turn wall-clock
 * budget all apply.
 *
 * The previous implementation was a single `@Tool fun runTool(name, argsJson)` that looked
 * the tool up and called `execute()` inline. That ran with none of those protections, so a
 * local model could invoke a destructive tool with no prompt and no HARDLINE check, and its
 * calls never appeared in the transcript as tool steps.
 */
internal class LiteRtToolDeclaration(private val tool: RikkaTool) : OpenApiTool {

    /**
     * The rendered declaration. Built once: [LiteRtProvider] measures its length to fit the
     * tool set inside the model's context budget, and the SDK then asks for the same string.
     */
    val declarationJson: String by lazy {
        buildJsonObject {
            put("name", tool.name)
            put("description", tool.description)
            put("parameters", parametersSchema())
        }.toString()
    }

    override fun getToolDescriptionJsonString(): String = declarationJson

    /**
     * Never called: the conversation disables automatic tool calling, so the runtime hands
     * calls back to the host instead of dispatching them here. If it ever is called, the
     * conversation was misconfigured: executing the tool at this point would silently skip
     * approval and HARDLINE, so refuse loudly and return a structured error the model can
     * read rather than doing the unsafe thing.
     */
    override fun execute(params: String): String {
        Log.e(
            TAG,
            "execute() called for '${tool.name}': automaticToolCalling should be false so " +
                "the host can apply approval and the HARDLINE floor. Refusing to run it here.",
        )
        return buildJsonObject {
            put("error", "tool_execution_not_permitted_here")
            put(
                "detail",
                "This tool must be executed by the host application so that approval and " +
                    "safety checks apply. Report this as a bug.",
            )
        }.toString()
    }

    /**
     * Translate the tool's [InputSchema] into a JSON-Schema object. A tool that declares no
     * parameters still gets a well-formed empty object schema, because a missing
     * `parameters` key makes some chat templates render a malformed declaration.
     */
    private fun parametersSchema(): JsonObject = when (val schema = tool.parameters()) {
        is InputSchema.Obj -> buildJsonObject {
            put("type", "object")
            put("properties", schema.properties)
            schema.required?.takeIf { it.isNotEmpty() }?.let { required ->
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
            }
        }

        null -> buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
        }
    }
}
