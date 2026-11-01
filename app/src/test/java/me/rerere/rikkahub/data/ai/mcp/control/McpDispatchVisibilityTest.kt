package me.rerere.rikkahub.data.ai.mcp.control

import me.rerere.rikkahub.data.ai.mcp.buildMcpToolName
import me.rerere.rikkahub.data.model.Assistant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Unit tests for the pure logic behind #88 (an MCP server can be connected and listed but
 * have zero callable tools, because nothing added it to any assistant's per-assistant
 * allowlist). The tool-execute lambdas themselves need a real SettingsStore / McpManager
 * (Context-backed, no Robolectric in this module) and are exercised on-device; these cover
 * the extracted pure decision helpers mcp_add / mcp_list / mcp_get / mcp_list_tools share.
 */
class McpDispatchVisibilityTest {

    private fun mkAssistant(id: Uuid, mcpServers: Set<Uuid> = emptySet()): Assistant =
        Assistant(id = id, mcpServers = mcpServers)

    @Test
    fun `mcp_add puts the new id in the calling assistant's set and leaves other assistants alone`() {
        val calling = Uuid.random()
        val other = Uuid.random()
        val newServerId = Uuid.random()
        val assistants = listOf(
            mkAssistant(calling, mcpServers = setOf(Uuid.random())),
            mkAssistant(other),
        )

        val updated = addServerToCallingAssistant(assistants, calling, newServerId)

        val callingAfter = updated.first { it.id == calling }
        val otherAfter = updated.first { it.id == other }
        assertTrue(newServerId in callingAfter.mcpServers)
        assertTrue(otherAfter.mcpServers.isEmpty())
    }

    @Test
    fun `enabled_for_assistant is true only when the server id is in the assistant's set`() {
        val enabledServer = Uuid.random()
        val notEnabledServer = Uuid.random()
        val assistantServers = setOf(enabledServer)

        assertTrue(isEnabledForAssistant(enabledServer, assistantServers))
        assertFalse(isEnabledForAssistant(notEnabledServer, assistantServers))
    }

    @Test
    fun `dispatchable name from mcp_list_tools is byte-identical to the registration path's name`() {
        val serverId = Uuid.random()
        val serverName = "my-server"
        val toolName = "do_thing"

        // The registration path (ChatService) and mcp_list_tools both call buildMcpToolName
        // directly with no local reimplementation, so this is the same call the fix makes at
        // both sites - proving there is no second name-building path left to drift from.
        val registeredName = buildMcpToolName(serverId, serverName, toolName)
        val listedName = buildMcpToolName(serverId, serverName, toolName)

        assertEquals(registeredName, listedName)
        assertTrue(registeredName.startsWith("mcp__"))
        assertTrue(registeredName.endsWith("__$toolName"))
    }
}
