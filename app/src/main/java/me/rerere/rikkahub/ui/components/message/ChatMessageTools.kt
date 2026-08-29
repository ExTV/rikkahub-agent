package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Refresh03
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.Tools
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.message.tools.ToolUIContext
import me.rerere.rikkahub.ui.components.message.tools.ToolUIRegistry
import me.rerere.rikkahub.ui.components.richtext.ZoomableAsyncImage
import me.rerere.rikkahub.ui.components.ui.ChainOfThoughtScope
import me.rerere.rikkahub.ui.components.ui.DotLoading
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull

// Per-tool icon/title/summary/preview rendering now lives in ToolUIRegistry
// (see message/tools/BuiltinToolUIs.kt). This file only keeps the cross-cutting
// approval-card UI and the interactive ask_user flow, plus the small JSON helper
// they rely on.
private fun JsonElement?.getStringContent(key: String): String? =
    this?.jsonObjectOrNull?.get(key)?.jsonPrimitiveOrNull?.contentOrNull

private const val ASK_USER_TOOL_NAME = "ask_user"

@Composable
fun ChainOfThoughtScope.ChatMessageServerToolStep(tool: UIMessagePart.ServerTool) {
    val loading = !tool.isFinished
    ChainOfThoughtStep(
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.Tools,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f),
                )
            }
        },
        label = {
            Text(
                text = stringResource(R.string.chat_message_tool_call_generic, tool.toolName),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * Parses a tool's output text as JSON for [ToolUIContext.content]. Returns null both
 * when the tool hasn't executed yet and when the output isn't valid JSON (e.g. a
 * truncation notice - see `maybeTruncateToolOutput`), rather than collapsing a parse
 * failure into an empty [JsonObject]: that used to make bespoke card renderers (e.g.
 * search) read "no results" from a result that was never parsed, showing an empty
 * card instead of falling back to the raw-text preview (#93).
 */
internal fun parseToolOutputContent(tool: UIMessagePart.Tool): JsonElement? {
    if (!tool.isExecuted) return null
    return runCatching {
        JsonInstant.parseToJsonElement(
            tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        )
    }.getOrNull()
}

@Composable
fun ChainOfThoughtScope.ChatMessageToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean = false,
    // Whether a generation is running anywhere in this conversation, independent of
    // `loading` (which is already narrowed to false for an executed tool and can't
    // answer that question - see the rerun-button gate below).
    generationActive: Boolean = loading,
    onToolApproval: ((toolCallId: String, approved: Boolean, reason: String, scope: me.rerere.rikkahub.service.ChatService.ApprovalScope, toolName: String) -> Unit)? = null,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)? = null,
    onRerunTool: (suspend (toolCallId: String) -> me.rerere.rikkahub.service.ChatService.RerunToolResult)? = null,
) {
    // ask_user 是交互式问答流程, 不走注册式渲染框架
    if (tool.toolName == ASK_USER_TOOL_NAME) {
        AskUserToolStep(tool = tool, loading = loading, onToolAnswer = onToolAnswer)
        return
    }

    val renderer = remember(tool.toolName) { ToolUIRegistry.resolve(tool.toolName) }
    val context = remember(tool, loading) {
        ToolUIContext(
            tool = tool,
            arguments = tool.inputAsJson(),
            content = parseToolOutputContent(tool),
            loading = loading,
        )
    }

    var showResult by remember { mutableStateOf(false) }
    var showDenyDialog by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(true) }
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isDenied = tool.approvalState is ToolApprovalState.Denied
    val images = tool.output.filterIsInstance<UIMessagePart.Image>()
    // Text parts nested in tool.output (e.g. run_js's webview payload) that carry
    // rikkahub.webview metadata never reach the top-level Text rendering branch in
    // ChatMessage.kt, since tool output is always folded into this step. Surface them
    // here so skill webview cards (virtual-piano, interactive-map, text-spinner, …) render.
    val webviewParts = tool.output.filterIsInstance<UIMessagePart.Text>()
        .filter { it.hasSkillWebviewMeta() }

    // Summary detection is delegated to the registered renderer; image output, webview
    // cards, and denial reasons are common to all tools.
    val hasExtraContent = renderer.hasSummary(context) || isDenied || images.isNotEmpty() || webviewParts.isNotEmpty()

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(
                    size = 10.dp
                )
            } else {
                Icon(
                    imageVector = renderer.icon(context),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = renderer.title(context),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        extra = if (isPending && onToolApproval != null) {
            {
                // Per-row in-flight flag to debounce double-taps. Without this, two rapid
                // clicks on Approve fire handleToolApproval twice — the second cancel()s
                // the first's resume coroutine mid-flight, wastes the in-flight gen step,
                // and can race the persisted state mutation. Keyed on toolCallId so a
                // recomposition for a different tool doesn't carry the flag.
                var inFlight by remember(tool.toolCallId) { mutableStateOf(false) }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // schedule_job is the one approval that AUTHORISES future autonomous
                    // execution, not just one tool. Surface the consequence here so the
                    // user knows what they're approving — every tool the cron prompt
                    // invokes will run without prompts. (HARDLINE blocks still apply.)
                    if (tool.toolName == "schedule_job") {
                        Text(
                            text = stringResource(R.string.chat_message_tool_schedule_job_warning),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        // Surface mode-specific detail so the user sees WHAT will run.
                        val jobInput = tool.inputAsJson()
                        val mode = jobInput.getStringContent("mode")
                        if (mode == "direct") {
                            val actions = runCatching {
                                (jobInput as? JsonObject)?.get("actions")
                                    ?.jsonArray
                            }.getOrNull()
                            if (actions != null) {
                                Column {
                                    actions.forEachIndexed { i, el ->
                                        val obj = el as? JsonObject
                                        val toolName = obj?.get("tool")?.jsonPrimitive?.contentOrNull ?: "?"
                                        val args = obj?.get("args")?.toString().orEmpty()
                                        val truncatedArgs = if (args.length > 120) args.take(120) + "…" else args
                                        Text(
                                            text = "  ${i + 1}. $toolName $truncatedArgs",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        } else if (mode == "llm") {
                            val prompt = jobInput.getStringContent("prompt").orEmpty()
                            if (prompt.isNotEmpty()) {
                                val truncatedPrompt = if (prompt.length > 200) prompt.take(200) + "…" else prompt
                                Text(
                                    text = truncatedPrompt,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // MCP control tools: render args via the redacting helper so headers
                    // like Authorization / X-Api-Key never appear plainly in the approval
                    // card. Generic args display would leak them (audit finding).
                    if (tool.toolName.startsWith("mcp_")) {
                        val mcpRendered = runCatching {
                            (tool.inputAsJson() as? JsonObject)?.let {
                                me.rerere.rikkahub.data.ai.mcp.control.McpApprovalRenderer
                                    .render(tool.toolName, it)
                            }
                        }.getOrNull()
                        if (!mcpRendered.isNullOrBlank()) {
                            Text(
                                text = mcpRendered,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Workflow_* mutators: human-readable approval body ("Create workflow X /
                    // When: WiFi connects to HomeWiFi / Do: 1. ssh_exec_saved(host=home) / …").
                    // Action arg values whose key is in the secret-redaction list are masked.
                    if (me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer.isWorkflowTool(tool.toolName)
                        && tool.toolName !in setOf("workflow_list", "workflow_get")) {
                        val workflowRendered = runCatching {
                            me.rerere.rikkahub.workflow.tools.WorkflowApprovalRenderer
                                .renderPlain(tool.toolName, tool.input.ifBlank { "{}" })
                        }.getOrNull()
                        if (!workflowRendered.isNullOrBlank()) {
                            Text(
                                text = workflowRendered,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Four-button row: Allow / Always Allow / Allow for this chat / Deny.
                    // Order matches the Telegram inline-keyboard layout so the user sees the
                    // same mental model on both surfaces.
                    // Tools listed in ToolApprovalDefaults.NO_ALWAYS_ALLOW (e.g. mcp_add /
                    // mcp_update — adding an MCP server is a privilege-escalation surface)
                    // drop the Always-Allow button so each call requires fresh confirmation.
                    val allowAlwaysButton = me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
                        .allowsAlwaysAllow(tool.toolName)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilledTonalIconButton(
                            onClick = {
                                if (inFlight) return@FilledTonalIconButton
                                inFlight = true
                                onToolApproval(
                                    tool.toolCallId, true, "",
                                    me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
                                    tool.toolName,
                                )
                            },
                            enabled = !inFlight,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Tick01,
                                contentDescription = stringResource(R.string.chat_message_tool_approve),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        if (allowAlwaysButton) {
                            FilledTonalIconButton(
                                onClick = {
                                    if (inFlight) return@FilledTonalIconButton
                                    inFlight = true
                                    onToolApproval(
                                        tool.toolCallId, true, "",
                                        me.rerere.rikkahub.service.ChatService.ApprovalScope.Always,
                                        tool.toolName,
                                    )
                                },
                                enabled = !inFlight,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Text("∞", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        FilledTonalIconButton(
                            onClick = {
                                if (inFlight) return@FilledTonalIconButton
                                inFlight = true
                                onToolApproval(
                                    tool.toolCallId, true, "",
                                    me.rerere.rikkahub.service.ChatService.ApprovalScope.ChatScope,
                                    tool.toolName,
                                )
                            },
                            enabled = !inFlight,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Text("💬", style = MaterialTheme.typography.labelSmall)
                        }
                        FilledTonalIconButton(
                            onClick = {
                                if (inFlight) return@FilledTonalIconButton
                                showDenyDialog = true
                            },
                            enabled = !inFlight,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.chat_message_tool_deny),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        } else {
            null
        },
        // A parse failure (e.g. a truncation notice) leaves context.content null even
        // though the tool executed and has raw text worth showing in the preview sheet -
        // gate on tool.isExecuted instead so that case still gets a tap target (#93).
        onClick = if (tool.isExecuted || isPending || images.isNotEmpty() || webviewParts.isNotEmpty()) {
            { showResult = true }
        } else {
            null
        },
        content = if (hasExtraContent) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    renderer.Summary(context)
                    webviewParts.forEach { webviewPart ->
                        SkillWebviewCardOrNull(part = webviewPart)
                    }
                    if (images.isNotEmpty()) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.wrapContentWidth(),
                        ) {
                            items(images, key = { it.url }) { image ->
                                ZoomableAsyncImage(
                                    model = image.url,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .height(64.dp)
                                        .wrapContentWidth(),
                                )
                            }
                        }
                    }
                    if (isDenied) {
                        val reason = (tool.approvalState as ToolApprovalState.Denied).reason
                        Text(
                            text = stringResource(R.string.chat_message_tool_denied) +
                                if (reason.isNotBlank()) ": $reason" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            null
        },
    )

    if (showDenyDialog && onToolApproval != null) {
        ToolDenyReasonDialog(
            onDismiss = { showDenyDialog = false },
            onConfirm = { reason ->
                showDenyDialog = false
                onToolApproval(
                    tool.toolCallId, false, reason,
                    me.rerere.rikkahub.service.ChatService.ApprovalScope.Once,
                    tool.toolName,
                )
            }
        )
    }

    if (showResult) {
        ModalBottomSheet(
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
            ),
            onDismissRequest = { showResult = false },
            content = {
                Column {
                    renderer.Preview(
                        context = context,
                        onDismissRequest = { showResult = false },
                    )
                    // Only offer a re-run once the first attempt has produced output, and
                    // never while a generation is in flight for this message - rerunTool
                    // itself refuses that case, but hiding the button keeps a click from
                    // spinning up just to be told no.
                    if (tool.isExecuted && !generationActive && onRerunTool != null) {
                        val rerunScope = rememberCoroutineScope()
                        val toaster = LocalToaster.current
                        // "context" in this scope is the ToolUIContext passed to renderer.Preview
                        // above, not an android Context - resolve the failure toast's android
                        // Context separately.
                        val androidContext = androidx.compose.ui.platform.LocalContext.current
                        var rerunInFlight by remember(tool.toolCallId) { mutableStateOf(false) }
                        // The sonner toast host lives in the activity window, below the sheet's
                        // own dialog window, so a toast fired while this sheet is open is never
                        // seen. Surface the failure in-sheet too (toast still fires for the
                        // moment right after dismissal).
                        var rerunError by remember(tool.toolCallId) { mutableStateOf<String?>(null) }
                        // navigationBarsPadding on this wrapper (not just the button) keeps the
                        // whole action row - button and error text - out of the gesture-nav inset
                        // band on edge-to-edge devices; without it the row rendered flush against
                        // the bottom edge and taps landing there never reached the button.
                        Column(modifier = Modifier.navigationBarsPadding()) {
                            rerunError?.let { message ->
                                Text(
                                    text = message,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = {
                                        if (rerunInFlight) return@TextButton
                                        rerunInFlight = true
                                        rerunError = null
                                        rerunScope.launch {
                                            val result = onRerunTool(tool.toolCallId)
                                            rerunInFlight = false
                                            if (result is me.rerere.rikkahub.service.ChatService.RerunToolResult.Failure) {
                                                val message = androidContext.getString(
                                                    R.string.chat_message_tool_rerun_failed,
                                                    result.message,
                                                )
                                                rerunError = message
                                                toaster.show(
                                                    message = message,
                                                    type = ToastType.Error,
                                                )
                                            }
                                        }
                                    },
                                    enabled = !rerunInFlight,
                                ) {
                                    if (rerunInFlight) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            imageVector = HugeIcons.Refresh03,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                    Text(
                                        text = stringResource(R.string.chat_message_tool_rerun),
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainOfThoughtScope.AskUserToolStep(
    tool: UIMessagePart.Tool,
    loading: Boolean,
    onToolAnswer: ((toolCallId: String, answer: String) -> Unit)?,
) {
    val isPending = tool.approvalState is ToolApprovalState.Pending
    val isAnswered = tool.approvalState is ToolApprovalState.Answered
    val arguments = tool.inputAsJson()

    // Parse questions from arguments
    val questions = remember(arguments) {
        runCatching {
            arguments.jsonObject["questions"]?.jsonArray?.map { q ->
                val obj = q.jsonObject
                AskUserQuestion(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    question = obj["question"]?.jsonPrimitive?.contentOrNull ?: "",
                    options = obj["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                    selectionType = obj["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text"
                )
            } ?: emptyList()
        }.getOrElse { emptyList() }
    }

    // Track answers for text/single questions
    val answers = remember { mutableStateMapOf<String, String>() }
    // Track selected options for multi questions
    val multiAnswers = remember { mutableStateMapOf<String, Set<String>>() }

    val firstQuestion = questions.firstOrNull()?.question ?: "..."

    var expanded by remember { mutableStateOf(true) }

    ControlledChainOfThoughtStep(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        icon = {
            if (loading) {
                DotLoading(size = 10.dp)
            } else {
                Icon(
                    imageVector = HugeIcons.BubbleChatQuestion,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = LocalContentColor.current.copy(alpha = 0.7f)
                )
            }
        },
        label = {
            Text(
                text = if (questions.size <= 1) firstQuestion else stringResource(
                    R.string.chat_message_tool_ask_questions,
                    questions.size
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.shimmer(isLoading = loading),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                questions.forEach { q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = q.question,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )

                        if (isPending && onToolAnswer != null) {
                            when (q.selectionType) {
                                "single" -> {
                                    // Single select: chips only, no text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                "multi" -> {
                                    // Multi select: chips only, multiple can be selected
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                val selectedSet = multiAnswers[q.id] ?: emptySet()
                                                FilterChip(
                                                    selected = selectedSet.contains(option),
                                                    onClick = {
                                                        val current = selectedSet.toMutableSet()
                                                        if (current.contains(option)) current.remove(option)
                                                        else current.add(option)
                                                        multiAnswers[q.id] = current
                                                    },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                else -> {
                                    // Text (default): optional option chips + free text input
                                    if (q.options.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            q.options.forEach { option ->
                                                FilterChip(
                                                    selected = answers[q.id] == option,
                                                    onClick = { answers[q.id] = option },
                                                    label = {
                                                        Text(
                                                            text = option,
                                                            style = MaterialTheme.typography.labelSmall,
                                                        )
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    // Free text input
                                    OutlinedTextField(
                                        value = answers[q.id] ?: "",
                                        onValueChange = { answers[q.id] = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        textStyle = MaterialTheme.typography.bodySmall,
                                        singleLine = false,
                                        minLines = 1,
                                        maxLines = 3,
                                    )
                                }
                            }
                        } else if (isAnswered) {
                            // Show the user's answer
                            val answeredState = tool.approvalState as ToolApprovalState.Answered
                            val answerJson = runCatching {
                                JsonInstant.parseToJsonElement(answeredState.answer)
                            }.getOrNull()
                            val answerText = answerJson?.jsonObject?.get("answers")
                                ?.jsonObject?.get(q.id)?.jsonPrimitive?.contentOrNull
                                ?: answeredState.answer
                            Text(
                                text = answerText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }

                // Submit button
                if (isPending && onToolAnswer != null) {
                    FilledTonalButton(
                        onClick = {
                            val answerPayload = buildJsonObject {
                                put("answers", buildJsonObject {
                                    questions.forEach { q ->
                                        when (q.selectionType) {
                                            "multi" -> put(q.id, JsonPrimitive(multiAnswers[q.id]?.joinToString(", ") ?: ""))
                                            else -> put(q.id, JsonPrimitive(answers[q.id] ?: ""))
                                        }
                                    }
                                })
                            }
                            onToolAnswer(tool.toolCallId, answerPayload.toString())
                        },
                        enabled = questions.all { q ->
                            when (q.selectionType) {
                                "multi" -> !multiAnswers[q.id].isNullOrEmpty()
                                else -> !answers[q.id].isNullOrBlank()
                            }
                        },
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Tick01,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = stringResource(R.string.chat_message_tool_submit),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        },
    )
}

private data class AskUserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val selectionType: String = "text", // "text" | "single" | "multi"
)

@Composable
private fun ToolDenyReasonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.chat_message_tool_deny_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(R.string.chat_message_tool_deny_dialog_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                minLines = 2,
                maxLines = 4
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason) }) {
                Text(stringResource(R.string.chat_message_tool_deny))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
