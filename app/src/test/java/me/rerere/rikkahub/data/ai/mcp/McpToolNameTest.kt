package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * #88: [buildMcpToolName] is the single helper the tool-registration path (ChatService) and
 * the mcp_list_tools diagnostic listing (McpControlTools) both call, so the dispatchable name
 * shown to the model can never drift from the name the dispatch table was actually built with.
 */
class McpToolNameTest {

    @Test
    fun `builds the mcp__slug_name__tool shape with the first 8 hex chars of the id`() {
        val id = Uuid.parse("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        val name = buildMcpToolName(id, "my-server", "do_thing")

        assertEquals("mcp__aaaaaaaa_my-server__do_thing", name)
    }

    @Test
    fun `dashes are stripped from the slug`() {
        val id = Uuid.parse("12345678-9999-cccc-dddd-eeeeeeeeeeee")
        val name = buildMcpToolName(id, "s", "t")

        assertEquals("mcp__12345678_s__t", name)
    }

    @Test
    fun `two identically-named servers produce distinct dispatchable names`() {
        val a = buildMcpToolName(Uuid.random(), "shared-name", "tool")
        val b = buildMcpToolName(Uuid.random(), "shared-name", "tool")

        org.junit.Assert.assertNotEquals(a, b)
    }
}
