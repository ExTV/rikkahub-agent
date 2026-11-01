package me.rerere.locallm.litert

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtToolDeclarationTest {

    private fun tool(
        name: String = "web_fetch",
        description: String = "Fetch a URL",
        schema: InputSchema? = InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The URL to fetch")
                })
            },
            required = listOf("url"),
        ),
    ) = Tool(
        name = name,
        description = description,
        parameters = { schema },
        execute = { emptyList() },
    )

    private fun describe(t: Tool): JsonObject =
        Json.parseToJsonElement(LiteRtToolDeclaration(t).getToolDescriptionJsonString()).jsonObject

    @Test
    fun `declaration carries the tool's real name and description`() {
        val json = describe(tool())
        assertEquals("web_fetch", json["name"]?.jsonPrimitive?.content)
        assertEquals("Fetch a URL", json["description"]?.jsonPrimitive?.content)
    }

    @Test
    fun `parameters are emitted as a JSON-Schema object with properties and required`() {
        val params = describe(tool())["parameters"]!!.jsonObject
        assertEquals("object", params["type"]?.jsonPrimitive?.content)
        val props = params["properties"]!!.jsonObject
        assertEquals("string", props["url"]!!.jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("url"),
            params["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `a tool with no schema still declares a well-formed empty object`() {
        val params = describe(tool(schema = null))["parameters"]!!.jsonObject
        assertEquals("object", params["type"]?.jsonPrimitive?.content)
        assertTrue(params["properties"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `required is omitted rather than emitted empty when the tool has no required args`() {
        val noRequired = tool(
            schema = InputSchema.Obj(properties = buildJsonObject {}, required = emptyList()),
        )
        assertTrue("required" !in describe(noRequired)["parameters"]!!.jsonObject)
    }

    @Test
    fun `execute refuses instead of running the tool, so approval can never be bypassed`() {
        var ran = false
        val t = Tool(
            name = "termux_run_command",
            description = "Run a shell command",
            parameters = { null },
            execute = { ran = true; emptyList() },
        )
        val out = Json
            .parseToJsonElement(LiteRtToolDeclaration(t).execute("""{"command":"rm -rf /"}"""))
            .jsonObject
        assertEquals("tool_execution_not_permitted_here", out["error"]?.jsonPrimitive?.content)
        assertTrue("the tool must not have been executed", !ran)
    }
}
