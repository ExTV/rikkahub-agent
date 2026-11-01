package me.rerere.rikkahub.data.ai.tools.local

import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.AgentTurnTracker
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import me.rerere.rikkahub.service.ActionLogEntry
import me.rerere.rikkahub.service.RikkaAccessibilityService

private val ALLOWED_BY = setOf("text", "content_description", "view_id_resource_name")
private const val MATCH_CAP = 50

private data class NodeMatch(val node: AccessibilityNodeInfo, val traversalIndex: Int)

/**
 * Matches nodes via the same traversal (and therefore the same traversalIndex numbering)
 * as read_window_tree, so returned node_ids agree across tools and a node visible in the
 * tree can never be no_match. Exact string match per axis. recycle stays OFF because
 * matched nodes are retained for clicking.
 */
private fun findMatchesUnified(
    svc: RikkaAccessibilityService,
    root: AccessibilityNodeInfo,
    by: String,
    value: String,
): Pair<List<NodeMatch>, Boolean> {
    val out = mutableListOf<NodeMatch>()
    val (_, _, truncated) = svc.traverseTree(
        root = root,
        filter = { n, _ ->
            when (by) {
                "text" -> n.text?.toString() == value
                "content_description" -> n.contentDescription?.toString() == value
                "view_id_resource_name" -> n.viewIdResourceName == value
                else -> false
            }
        },
        cap = MATCH_CAP,
        emit = { n, _, idx -> out.add(NodeMatch(n, idx)) },
    )
    return out to truncated
}

/**
 * Resolves a read_window_tree node_id back to a live node. Returns the node or null with
 * a reason string. Staleness detection: window changed, index out of range, or the node
 * is no longer visible.
 */
private fun resolveByNodeId(
    svc: RikkaAccessibilityService,
    root: AccessibilityNodeInfo,
    windowId: Int,
    traversalIndex: Int,
): Pair<AccessibilityNodeInfo?, String?> {
    if (root.windowId != windowId) return null to "stale_node_id"
    var found: AccessibilityNodeInfo? = null
    // filter=true makes emitted == seen, so cap=traversalIndex stops exactly at the node.
    svc.traverseTree(
        root = root,
        filter = { _, _ -> true },
        cap = traversalIndex,
        emit = { n, _, idx -> if (idx == traversalIndex) found = n },
    )
    val node = found ?: return null to "stale_node_id"
    if (!node.isVisibleToUser) return null to "stale_node_id"
    return node to null
}

private fun parseSelector(input: kotlinx.serialization.json.JsonElement): Triple<String?, String?, String?> {
    val by = input.jsonObject["by"]?.jsonPrimitive?.contentOrNull
    val value = input.jsonObject["value"]?.jsonPrimitive?.contentOrNull
    val pkg = input.jsonObject["package_name"]?.jsonPrimitive?.contentOrNull
    return Triple(by, value, pkg)
}

fun findNodeTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "find_node",
    description = """
        Find accessibility nodes in the active window matching a selector. by: text |
        content_description | view_id_resource_name. Returns {matches: [...]} with at most 50
        node summaries. Use read_window_tree first to see what's available, then this for
        targeted lookups. Matching runs over the same traversal as read_window_tree, so
        returned node_ids agree across both tools; truncated=true means more than 50 nodes
        matched.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") })
                    put("description", "Selector axis")
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "Exact value to match")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional foreground package guard")
                })
            },
            required = listOf("by", "value")
        )
    },
    execute = { input ->
        me.rerere.rikkahub.service.RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val (by, value, pkgFilter) = parseSelector(input)
        if (by == null || by !in ALLOWED_BY || value == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "by must be one of [text, content_description, view_id_resource_name] and value is required")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            val root = svc.rootInActiveWindow
            if (root == null) {
                return@withService buildJsonObject {
                    put("error", "no_active_window")
                    put("matches", buildJsonArray {})
                }
            }
            val pkg = root.packageName?.toString().orEmpty()
            if (pkgFilter != null && pkgFilter != pkg) {
                return@withService buildJsonObject {
                    put("error", "wrong_foreground_app")
                    put("current", pkg)
                    put("matches", buildJsonArray {})
                }
            }
            val (matches, truncated) = findMatchesUnified(svc, root, by, value)
            svc.appendLog(
                ActionLogEntry(
                    type = "find_node",
                    paramsSummary = "$by=\"${value.take(40)}\" -> ${matches.size}",
                    success = true,
                    timestampMs = System.currentTimeMillis(),
                )
            )
            buildJsonObject {
                put("matches", buildJsonArray {
                    matches.forEach { m -> add(nodeToJson(m.node, root.windowId, m.traversalIndex)) }
                })
                if (truncated) put("truncated", true)
                put("screen_state", screenStateJson(svc, screenChanged = null))
            }
        }
        streamer.streamIfHeadless(invocationContext, "FindNode $by=\"${value.take(30)}\"")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

fun clickNodeTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "click_node",
    description = """
        Click a node. Preferred selector: node_id from read_window_tree / find_node. Alternative:
        by (text | content_description | view_id_resource_name) + value with optional nth. If the
        matched node is not clickable, the nearest clickable ancestor is clicked. On stale_node_id
        the screen changed since your last read: re-run read_window_tree. The result carries an
        "after" object; check screen_changed to confirm the click did something.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("node_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Node handle from read_window_tree/find_node (\"windowId:index\"). Preferred over by/value; on a stale id you get stale_node_id and should re-read the tree.")
                })
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") })
                })
                put("value", buildJsonObject { put("type", "string") })
                put("package_name", buildJsonObject { put("type", "string") })
                put("nth", buildJsonObject {
                    put("type", "integer")
                    put("description", "Zero-based index when multiple nodes match (default 0)")
                })
            },
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val (by, value, pkgFilter) = parseSelector(input)
        val nth = input.jsonObject["nth"]?.jsonPrimitive?.intOrNull ?: 0
        val rawNodeId = input.jsonObject["node_id"]?.jsonPrimitive?.contentOrNull
        val parsedNodeId = rawNodeId?.let { parseNodeId(it) }
        if (rawNodeId != null && parsedNodeId == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "node_id must look like \"windowId:index\" as returned by read_window_tree")
                    }.toString()
                )
            )
        }
        if (rawNodeId == null && (by == null || by !in ALLOWED_BY || value == null)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "provide node_id, or by (one of [text, content_description, view_id_resource_name]) plus value")
                    }.toString()
                )
            )
        }
        if (nth < 0) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "nth must be >= 0")
                    }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            withActionEnvelope(svc) { _ ->
                val root = svc.rootInActiveWindow
                    ?: return@withActionEnvelope buildJsonObject { put("error", "no_active_window") }
                val pkg = root.packageName?.toString().orEmpty()
                if (pkgFilter != null && pkgFilter != pkg) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "wrong_foreground_app")
                        put("current", pkg)
                    }
                }
                val (target, staleReason) = if (parsedNodeId != null) {
                    resolveByNodeId(svc, root, parsedNodeId.first, parsedNodeId.second)
                } else {
                    val (matches, truncated) = findMatchesUnified(svc, root, by!!, value!!)
                    when {
                        matches.isEmpty() -> null to "no_match"
                        nth >= matches.size -> return@withActionEnvelope buildJsonObject {
                            put("error", "nth_out_of_range")
                            put("available", matches.size)
                            if (truncated) put("truncated", true)
                        }
                        else -> matches[nth].node to null
                    }
                }
                if (target == null) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", staleReason ?: "no_match")
                        if (staleReason == "stale_node_id") {
                            put("hint", "the screen changed since read_window_tree; re-run it and use a fresh node_id")
                        }
                    }
                }
                val clickable = svc.resolveClickable(target)
                    ?: return@withActionEnvelope buildJsonObject { put("error", "no_clickable_ancestor") }
                val ok = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                svc.appendLog(
                    ActionLogEntry(
                        type = "click_node",
                        paramsSummary = (rawNodeId ?: "$by=\"${value?.take(40)}\" nth=$nth") + " -> " + if (ok) "ok" else "fail",
                        success = ok,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                buildJsonObject {
                    put("success", ok)
                    put("clicked", buildJsonObject {
                        val rect = android.graphics.Rect()
                        clickable.getBoundsInScreen(rect)
                        put("bounds", buildJsonArray {
                            add(rect.left); add(rect.top); add(rect.right); add(rect.bottom)
                        })
                        put("text", clickable.text?.toString() ?: "")
                        put("resolved_via_ancestor", clickable !== target)
                    })
                }
            }
        }
        streamer.streamIfHeadless(invocationContext, "ClickNode " + (rawNodeId ?: "$by=\"${value?.take(30)}\""))
        listOf(UIMessagePart.Text(payload.toString()))
    }
)

/**
 * Type text into an editable accessibility node by selector. Works on standard input fields
 * (URL bars, search boxes, form fields, IME-driven text views). Does NOT work on terminals
 * like Termux because they render to a Surface and do not expose editable nodes; for Termux
 * use termux_run_command instead.
 */
fun setTextTool(
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "set_text",
    description = """
        Type or replace text in an editable field, selected by node_id (preferred) or by/value.
        Type or replace text in an editable input field on screen. Find the field by selector
        (text / content_description / view_id_resource_name). Works for URL bars, search boxes,
        form fields. Does NOT work for terminals like Termux that render natively - for Termux
        use termux_run_command if that tool is enabled, otherwise report that terminals cannot
        be typed into. Returns {success, set_to, ...} or a structured error. The result carries
        an "after" object; check screen_changed in the after object to confirm the text landed.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("node_id", buildJsonObject {
                    put("type", "string")
                    put("description", "Node handle from read_window_tree/find_node (\"windowId:index\"). Preferred over by/value; on a stale id you get stale_node_id and should re-read the tree.")
                })
                put("by", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray { add("text"); add("content_description"); add("view_id_resource_name") })
                    put("description", "Selector axis to find the editable node")
                })
                put("value", buildJsonObject {
                    put("type", "string")
                    put("description", "Selector value to match against")
                })
                put("text", buildJsonObject {
                    put("type", "string")
                    put("description", "The text to set on the matched node")
                })
                put("package_name", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional foreground package guard")
                })
                put("nth", buildJsonObject {
                    put("type", "integer")
                    put("description", "Zero-based index when multiple nodes match (default 0)")
                })
            },
            required = listOf("text")
        )
    },
    execute = { input ->
        AgentTurnTracker.recordAutomationAction()
        RikkaAccessibilityService.instance?.let { wakeScreenIfNeeded(it) }
        val (by, value, pkgFilter) = parseSelector(input)
        val nth = input.jsonObject["nth"]?.jsonPrimitive?.intOrNull ?: 0
        val newText = input.jsonObject["text"]?.jsonPrimitive?.contentOrNull
        val rawNodeId = input.jsonObject["node_id"]?.jsonPrimitive?.contentOrNull
        val parsedNodeId = rawNodeId?.let { parseNodeId(it) }
        if (newText == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "text is required") }.toString()
                )
            )
        }
        if (rawNodeId != null && parsedNodeId == null) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "node_id must look like \"windowId:index\" as returned by read_window_tree")
                    }.toString()
                )
            )
        }
        if (rawNodeId == null && (by == null || by !in ALLOWED_BY || value == null)) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "provide node_id, or by (one of [text, content_description, view_id_resource_name]) plus value")
                    }.toString()
                )
            )
        }
        if (nth < 0) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "nth must be >= 0") }.toString()
                )
            )
        }
        val payload = AccessibilityServiceHandle.withService { svc ->
            withActionEnvelope(svc) { _ ->
                val root = svc.rootInActiveWindow
                    ?: return@withActionEnvelope buildJsonObject { put("error", "no_active_window") }
                val pkg = root.packageName?.toString().orEmpty()
                if (pkgFilter != null && pkgFilter != pkg) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "wrong_foreground_app")
                        put("current", pkg)
                    }
                }
                val (target, staleReason) = if (parsedNodeId != null) {
                    resolveByNodeId(svc, root, parsedNodeId.first, parsedNodeId.second)
                } else {
                    val (matches, truncated) = findMatchesUnified(svc, root, by!!, value!!)
                    when {
                        matches.isEmpty() -> null to "no_match"
                        nth >= matches.size -> return@withActionEnvelope buildJsonObject {
                            put("error", "nth_out_of_range")
                            put("available", matches.size)
                            if (truncated) put("truncated", true)
                        }
                        else -> matches[nth].node to null
                    }
                }
                if (target == null) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", staleReason ?: "no_match")
                        if (staleReason == "stale_node_id") {
                            put("hint", "the screen changed since read_window_tree; re-run it and use a fresh node_id")
                        }
                    }
                }
                // ACTION_SET_TEXT requires the node to be editable. Walk up the parent chain
                // looking for one - some apps wrap their EditText in a non-editable container.
                var editable: AccessibilityNodeInfo? = target
                while (editable != null && !editable.isEditable) {
                    editable = editable.parent
                }
                if (editable == null) {
                    return@withActionEnvelope buildJsonObject {
                        put("error", "node_not_editable")
                        put("recovery", "The matched node is not an editable input. Terminals (Termux) render natively and do not expose editable nodes; if the termux_run_command tool is enabled use it instead, otherwise report that terminals cannot be typed into.")
                    }
                }
                val args = android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        newText
                    )
                }
                val ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                svc.appendLog(
                    ActionLogEntry(
                        type = "set_text",
                        paramsSummary = (rawNodeId ?: "$by=\"${value?.take(30)}\"") + " -> \"${newText.take(30)}\"",
                        success = ok,
                        timestampMs = System.currentTimeMillis(),
                    )
                )
                buildJsonObject {
                    put("success", ok)
                    if (!ok) put("reason", "action_rejected")
                    put("set_to", newText)
                }
            }
        }
        streamer.streamIfHeadless(invocationContext, "SetText " + (rawNodeId ?: "$by=\"${value?.take(20)}\"") + " -> \"${newText.take(20)}\"")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
