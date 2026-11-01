package me.rerere.rikkahub.data.ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

enum class AgentMode {
    BUILD,
    PLAN;

    val isPlan: Boolean get() = this == PLAN
    val isBuild: Boolean get() = this == BUILD
}

object ConversationAgentMode {
    private val modes = ConcurrentHashMap<Uuid, AgentMode>()
    private val modeFlows = ConcurrentHashMap<Uuid, MutableStateFlow<AgentMode>>()

    fun get(conversationId: Uuid): AgentMode =
        modes[conversationId] ?: AgentMode.BUILD

    fun getFlow(conversationId: Uuid): StateFlow<AgentMode> =
        modeFlows.computeIfAbsent(conversationId) {
            MutableStateFlow(get(conversationId))
        }.asStateFlow()

    fun set(conversationId: Uuid, mode: AgentMode) {
        if (mode == AgentMode.BUILD) {
            modes.remove(conversationId)
        } else {
            modes[conversationId] = mode
        }
        modeFlows[conversationId]?.value = mode
    }

    fun isPlanMode(conversationId: Uuid): Boolean =
        get(conversationId) == AgentMode.PLAN

    fun clear(conversationId: Uuid) {
        modes.remove(conversationId)
        modeFlows.remove(conversationId)
    }

    fun isToolAllowedInPlanMode(toolName: String): Boolean {
        if (toolName == "propose_plan") return true
        if (toolName == "ask_user") return true
        if (toolName == "get_time_info") return true
        if (toolName == "memory_tool") return true

        // Read-only workspace inspection
        if (toolName in setOf(
                "workspace_read_file",
                "workspace_read_folder",
                "workspace_background_status",
            )
        ) return true

        // Search & web reading
        if (toolName in setOf(
                "web_search",
                "web_fetch",
                "web_extract",
            )
        ) return true

        // Browser navigation & content reading (without clicks / JS / form submits)
        if (toolName in setOf(
                "browser_open",
                "browser_get_text",
                "browser_get_dom",
                "browser_get_links",
                "browser_current_url",
                "browser_wait_for",
                "browser_screenshot",
            )
        ) return true

        // Read-only screen inspection
        if (toolName in setOf(
                "read_window_tree",
                "find_node",
                "take_screenshot",
                "skill_get_content",
            )
        ) return true

        return false
    }
}
