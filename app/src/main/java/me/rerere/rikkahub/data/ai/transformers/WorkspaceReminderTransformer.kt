package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.workspace.WorkspaceShellStatus

/**
 * Workspace 系统提示注入转换器
 *
 * 根据 workspace 状态向系统提示注入不同内容, 让模型"认知"到 workspace 并能在需要时指导用户:
 * - 已绑定且 shell 就绪: 注入完整 <workspace> 引导 (workspace_* 工具可用)。
 * - 已绑定但 shell 未就绪: 注入 <workspace-setup>, 告知模型如何引导用户让 shell 就绪。
 * - 未绑定但存在至少一个 workspace: 注入 <workspace-setup>, 告知模型如何引导用户绑定。
 * - 完全没有 workspace: 不注入。
 *
 * 工具是否真正提供仍由 ChatService.createWorkspaceToolsIfReady 决定 (仅 READY 时提供),
 * 本转换器只扩展模型对 workspace 的认知, 不改变工具可用性。
 */
class WorkspaceReminderTransformer(
    private val workspaceRepository: WorkspaceRepository,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val workspaceId = ctx.assistant.workspaceId?.toString()
        val workspace = workspaceId?.let { workspaceRepository.getById(it) }
        // 仅在未解析到绑定的 workspace 时才需要查询是否存在其它 workspace (短路避免多余查询)
        val hasAnyWorkspace = workspace != null || workspaceRepository.getAll().isNotEmpty()

        val prompt = buildWorkspaceReminder(workspace, hasAnyWorkspace, ctx.workspaceCwd)
            ?: return messages

        // 追加到第一条 system 消息; 若不存在则插入一条
        val systemIndex = messages.indexOfFirst { it.role == MessageRole.SYSTEM }
        return if (systemIndex >= 0) {
            messages.toMutableList().apply {
                this[systemIndex] = this[systemIndex]
                    .appendText("\n\n$prompt")
                    .copy(isSynthetic = true)
            }
        } else {
            listOf(UIMessage.system(prompt).copy(isSynthetic = true)) + messages
        }
    }
}

/**
 * 纯函数: 根据 workspace 状态选择要注入的系统提示, 返回 null 表示不注入。
 * 抽离出来以便单元测试, 不依赖 Android / Repository。
 *
 * - workspace 就绪 (READY): 完整工具引导。
 * - workspace 已绑定但未就绪: 指导用户安装/修复 rootfs。
 * - 未绑定但存在 workspace: 指导用户绑定。
 * - 其它 (无 workspace): null。
 */
internal fun buildWorkspaceReminder(
    workspace: WorkspaceEntity?,
    hasAnyWorkspace: Boolean,
    cwd: String? = null,
): String? = when {
    workspace != null && workspace.shellStatus == WorkspaceShellStatus.READY.name ->
        buildWorkspacePrompt(workspace, cwd)

    workspace != null -> buildWorkspaceNotReadyPrompt(workspace)

    hasAnyWorkspace -> buildWorkspaceUnboundPrompt()

    else -> null
}

private fun buildWorkspacePrompt(workspace: WorkspaceEntity, cwd: String? = null): String = buildString {
    appendLine("<workspace>")
    appendLine("You have access to a persistent Linux workspace named \"${workspace.name}\", running in a sandboxed proot rootfs environment.")
    appendLine("- The workspace files area is mounted at `/workspace`. Use it as your working directory; files written there persist across turns of this conversation.")
    appendLine("- All paths passed to workspace tools must be absolute and inside the Rootfs (for example `/workspace/notes.md`).")
    appendLine("- Available tools:")
    appendLine("  - `workspace_read_file`: read file contents.")
    appendLine("  - `workspace_write_file` / `workspace_edit_file`: create files, or make precise edits to existing files.")
    appendLine("  - `workspace_create_folder`: create a directory (and missing parents).")
    appendLine("  - `workspace_read_folder`: recursively list a directory as an indented tree.")
    appendLine("  - `workspace_shell`: run shell commands (the files area is mounted at /workspace).")
    appendLine("  - `workspace_run_background`: start a long-running command that persists across tool calls and survives after the call returns (dev servers, long installs, file watchers, batch jobs); returns a task id. The command runs in the FOREGROUND of its own persistent process, so do NOT append `&`.")
    appendLine("  - `workspace_background_status`: check status and recent output of background tasks (all, or one by task id).")
    appendLine("  - `workspace_background_kill`: stop a background task by task id.")
    appendLine("- Prefer `workspace_shell` for tasks that standard Unix tools handle well, and prefer `workspace_edit_file` for targeted edits over rewriting whole files.")
    appendLine("- To preview web files in the browser, start a static server with `workspace_run_background` (for example `python3 -m http.server 8000`) and then open it, since file:// breaks ES modules and fetch.")
    appendLine("- The skills directory is mounted at `/skills`. Each skill is a subdirectory `/skills/<skill-name>/` containing a `SKILL.md` (with `name` and `description` frontmatter) plus any supporting files. Read a skill's `SKILL.md` before using it, and follow its instructions.")
    appendLine("- Files the user uploaded are mounted at `/upload`. Treat `/upload` as READ-ONLY: read uploaded files from `/upload/<file-name>`, but never modify, overwrite, or delete anything there. If you need to change an uploaded file, copy it into `/workspace` first and edit the copy.")
    if (!cwd.isNullOrBlank()) {
        appendLine("- Current working directory: `$cwd`. Use this as the default context for file operations and shell commands.")
    }
    append("</workspace>")
}

/**
 * 已绑定 workspace 但 shell 未就绪 (DISABLED / INSTALLING / BROKEN): 工具不可用, 指导用户使其就绪。
 */
private fun buildWorkspaceNotReadyPrompt(workspace: WorkspaceEntity): String = buildString {
    appendLine("<workspace-setup>")
    appendLine("A workspace named \"${workspace.name}\" is bound to this assistant, but its Linux shell is not ready (status: ${workspace.shellStatus}), so the workspace tools (workspace_read_file, workspace_write_file, workspace_edit_file, workspace_create_folder, workspace_read_folder, workspace_shell, workspace_run_background, workspace_background_status, workspace_background_kill) are NOT available right now.")
    val howto = when (workspace.shellStatus) {
        WorkspaceShellStatus.INSTALLING.name ->
            "the rootfs is currently installing; ask them to wait for the install to finish, then send a new message."
        WorkspaceShellStatus.BROKEN.name ->
            "the rootfs is broken; ask them to open Extensions > Workspace, open this workspace, and reinstall/repair its rootfs until the shell status shows Ready."
        else ->
            "ask them to open Extensions > Workspace, open this workspace, and install its rootfs until the shell status shows Ready."
    }
    appendLine("If the user wants to use the workspace, explain in the user's language how to make it ready: $howto")
    appendLine("Do not claim to have workspace tools or attempt to call them until the shell status is Ready.")
    append("</workspace-setup>")
}

/**
 * 存在 workspace 但未绑定到当前助手: 工具不可用, 指导用户绑定。
 */
private fun buildWorkspaceUnboundPrompt(): String = buildString {
    appendLine("<workspace-setup>")
    appendLine("The user has a workspace, but none is bound to this assistant, so the workspace tools (workspace_read_file, workspace_write_file, workspace_edit_file, workspace_create_folder, workspace_read_folder, workspace_shell, workspace_run_background, workspace_background_status, workspace_background_kill) are NOT available.")
    appendLine("If the user asks to save files or run shell / Linux commands in a workspace, explain in the user's language how to enable it:")
    appendLine("1. Tap the + button in the chat input bar and select a workspace to bind it to this assistant.")
    appendLine("2. If that workspace's shell is not Ready yet, open Extensions > Workspace, open the workspace, and install its rootfs until the shell status shows Ready.")
    appendLine("Do not claim to have workspace tools or attempt to call them until a workspace is bound and its shell is Ready.")
    append("</workspace-setup>")
}

private fun UIMessage.appendText(extra: String): UIMessage {
    val updatedParts = parts.toMutableList()
    val firstTextIndex = updatedParts.indexOfFirst { it is UIMessagePart.Text }
    if (firstTextIndex >= 0) {
        val text = updatedParts[firstTextIndex] as UIMessagePart.Text
        updatedParts[firstTextIndex] = text.copy(text = text.text + extra)
    } else {
        updatedParts.add(UIMessagePart.Text(extra))
    }
    return copy(parts = updatedParts)
}
