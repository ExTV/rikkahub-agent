package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AgentModeTest {

    @Test
    fun defaultModeIsBuild() {
        val convId = Uuid.random()
        assertEquals(AgentMode.BUILD, ConversationAgentMode.get(convId))
        assertFalse(ConversationAgentMode.isPlanMode(convId))
    }

    @Test
    fun switchingToPlanModeReflectsState() {
        val convId = Uuid.random()
        ConversationAgentMode.set(convId, AgentMode.PLAN)
        assertEquals(AgentMode.PLAN, ConversationAgentMode.get(convId))
        assertTrue(ConversationAgentMode.isPlanMode(convId))

        // Switching back to Build
        ConversationAgentMode.set(convId, AgentMode.BUILD)
        assertEquals(AgentMode.BUILD, ConversationAgentMode.get(convId))
        assertFalse(ConversationAgentMode.isPlanMode(convId))
    }

    @Test
    fun clearResetsStateToBuild() {
        val convId = Uuid.random()
        ConversationAgentMode.set(convId, AgentMode.PLAN)
        assertTrue(ConversationAgentMode.isPlanMode(convId))

        ConversationAgentMode.clear(convId)
        assertEquals(AgentMode.BUILD, ConversationAgentMode.get(convId))
        assertFalse(ConversationAgentMode.isPlanMode(convId))
    }

    @Test
    fun planModeAllowedToolsOnlyAllowSafeInspection() {
        // Allowed tools in Plan Mode
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("propose_plan"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("ask_user"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("get_time_info"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("workspace_read_file"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("workspace_read_folder"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("workspace_background_status"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("web_search"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("web_fetch"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("web_extract"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("read_window_tree"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("find_node"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("take_screenshot"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("browser_open"))
        assertTrue(ConversationAgentMode.isToolAllowedInPlanMode("browser_get_text"))

        // Disallowed modifying/executing tools in Plan Mode
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("workspace_write_file"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("workspace_edit_file"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("workspace_create_folder"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("workspace_shell"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("workspace_run_background"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("workspace_background_kill"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("shizuku_exec"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("tap"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("click_node"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("swipe"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("scroll"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("set_text"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("browser_click"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("browser_type"))
        assertFalse(ConversationAgentMode.isToolAllowedInPlanMode("browser_submit"))
    }
}
