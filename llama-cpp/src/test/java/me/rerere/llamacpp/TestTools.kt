package me.rerere.llamacpp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

object TestTools {
    fun two(): List<Tool> = listOf(
        buildTool("get_time", "Get the current time"),
        buildTool("get_weather", "Get the weather for a city"),
    )

    private fun buildTool(name: String, description: String): Tool =
        Tool(
            name = name,
            description = description,
            parameters = {
                InputSchema.Obj(properties = buildJsonObject { } as JsonObject)
            },
            execute = { _ -> emptyList() },
        )
}
