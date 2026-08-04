package me.rerere.rikkahub.service

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.canResumeToolExecution
import me.rerere.ai.ui.finishPendingTools
import me.rerere.ai.ui.finishReasoning
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.ai.ui.limitContext
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import me.rerere.rikkahub.CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.cancelNotification
import me.rerere.rikkahub.utils.sendNotification
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.ContextBudgetPlanner
import me.rerere.rikkahub.data.ai.ContextCompactionPlanner
import me.rerere.rikkahub.data.ai.ContextCompactionPresentation
import me.rerere.rikkahub.data.ai.CompactedMessageView
import me.rerere.rikkahub.data.ai.ContextCompactionView
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.createSearchTools
import me.rerere.rikkahub.data.ai.tools.createSkillTools
import me.rerere.rikkahub.data.ai.tools.createWorkspaceTools
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.ai.transformers.Base64ImageToLocalFileTransformer
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.RegexOutputTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.ThinkTagTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.ai.transformers.WorkspaceReminderTransformer
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.AutoCompactionThresholdMode
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.getAssistantById
import me.rerere.rikkahub.data.datastore.getCompactionContextLength
import me.rerere.rikkahub.data.datastore.getContextCompactionTargetTokens
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.ConversationCompaction
import me.rerere.rikkahub.data.model.AssistantAffectScope
import me.rerere.rikkahub.data.model.replaceRegexes
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.FolderRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.web.BadRequestException
import me.rerere.rikkahub.web.NotFoundException
import me.rerere.rikkahub.utils.applyPlaceholders
import me.rerere.workspace.WorkspaceShellStatus
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

private const val TAG = "ChatService"
private const val COMPACTION_REQUEST_TIMEOUT_MS = 3 * 60_000L
private const val COMPACTION_TOTAL_TIMEOUT_MS = 8 * 60_000L
private const val COMPACTION_MAX_REQUEST_OUTPUT_TOKENS = 16_384
private const val MAX_PARALLEL_COMPACTION_REQUESTS = 4
private const val MAX_FULL_CONTEXT_MAP_GROUPS = 8
/**
 * Streaming chunks can arrive once per token. Persisting every chunk rewrites all message nodes
 * and can throttle the provider, while persisting only at the end loses the visible response when
 * the user changes screens or the process is killed. Keep the durable snapshot reasonably fresh
 * without turning Room into the stream's bottleneck.
 */
private const val STREAMING_PERSIST_INTERVAL_MS = 500L

private fun Throwable.isContextLimitError(): Boolean {
    val markers = listOf(
        "context length",
        "context window",
        "maximum context",
        "max context",
        "context length exceeded",
        "maximum tokens",
        "prompt is too long",
        "prompt too long",
        "too many tokens",
        "token limit",
        "input is too long",
        "exceeds the model",
        "exceed.*context",
    )
    return generateSequence(this) { it.cause }
        .take(8)
        .any { cause ->
            val text = (cause.message.orEmpty() + " " + cause.toString())
                .lowercase()
                .replace('_', ' ')
            markers.any { marker ->
                if (marker.contains(".*")) Regex(marker).containsMatchIn(text) else marker in text
            }
        }
}

internal fun backgroundTextGenerationParams(
    model: Model,
    reasoningLevel: ReasoningLevel = ReasoningLevel.OFF,
): TextGenerationParams = TextGenerationParams(
    model = model,
    reasoningLevel = reasoningLevel,
    customHeaders = model.customHeaders,
    customBody = model.customBodies,
)

/**
 * Resolve the compression model's usable context ceiling. The token-threshold setting is an
 * explicit user-controlled limit when that mode is selected, which also covers providers such
 * as Codex whose model list does not publish context metadata.
 */
internal fun compactionContextLength(settings: Settings, model: Model): Int? =
    settings.getCompactionContextLength(model)

data class ChatError(
    val id: Uuid = Uuid.random(),
    val title: String? = null,
    val error: Throwable,
    val conversationId: Uuid? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val solution: ChatErrorSolution? = null,
)

enum class ChatErrorSolution {
    CheckTitleModelSettings,
}

private val inputTransformers by lazy {
    listOf(
        TimeReminderTransformer,
        PromptInjectionTransformer,
        PlaceholderTransformer,
        DocumentAsPromptTransformer,
        OcrTransformer,
    )
}

private val outputTransformers by lazy {
    listOf(
        ThinkTagTransformer,
        Base64ImageToLocalFileTransformer,
        RegexOutputTransformer,
    )
}

class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val memoryRepository: MemoryRepository,
    private val generationHandler: GenerationHandler,
    private val templateTransformer: TemplateTransformer,
    private val providerManager: ProviderManager,
    private val localTools: LocalTools,
    val mcpManager: McpManager,
    private val filesManager: FilesManager,
    private val skillManager: SkillManager,
    private val toolApprovalPreferences: me.rerere.rikkahub.data.preferences.ToolApprovalPreferences,
    private val workspaceRepository: WorkspaceRepository,
    private val folderRepository: FolderRepository,
) {
    // workspace 系统提示注入 (依赖 workspaceRepository, 故在类内构造)
    private val workspaceReminderTransformer = WorkspaceReminderTransformer(workspaceRepository)

    // 统一会话管理
    private val sessions = ConcurrentHashMap<Uuid, ConversationSession>()
    private val _sessionsVersion = MutableStateFlow(0L)

    /**
     * Per-conversation mutex serialising state-mutating operations: handleToolApproval,
     * stopGeneration, the chunk-handling save path, and explicit DB writes. Without this
     * the audit reports identified multiple write races where a fresh approval mutation
     * gets clobbered by a concurrent write from a stale snapshot. Generation chunks
     * themselves are NOT held under this mutex — only the persist boundaries.
     */
    private val sessionMutexes = ConcurrentHashMap<Uuid, Mutex>()
    private fun mutexFor(conversationId: Uuid): Mutex =
        sessionMutexes.getOrPut(conversationId) { Mutex() }

    private val compactionMutexes = ConcurrentHashMap<Uuid, Mutex>()
    private fun compactionMutexFor(conversationId: Uuid): Mutex =
        compactionMutexes.getOrPut(conversationId) { Mutex() }

    /** Serialises full conversation rewrites, including periodic streaming snapshots. */
    private val persistenceMutexes = ConcurrentHashMap<Uuid, Mutex>()
    private fun persistenceMutexFor(conversationId: Uuid): Mutex =
        persistenceMutexes.getOrPut(conversationId) { Mutex() }

    /**
     * A monotonically increasing marker lets a flush tell whether a newer stream chunk arrived
     * while the Room transaction was in progress. Removing only the marker we observed avoids
     * dropping that newer dirty state.
     */
    private val streamingPersistenceSequence = AtomicLong(0L)
    private val pendingStreamingPersistence = ConcurrentHashMap<Uuid, Long>()
    private val lastStreamingPersistAt = ConcurrentHashMap<Uuid, Long>()

    // This starts before the chat coroutine is dispatched, so a user can background the app
    // immediately after pressing Send without racing the foreground-service promotion.
    private val foregroundWorkTracker = ForegroundWorkTracker(
        onFirstAcquire = { ChatGenerationForegroundService.start(context) },
        onLastRelease = { ChatGenerationForegroundService.stop(context) },
    )

    /**
     * Hydrate the in-memory session for [conversationId] from disk if it's currently
     * blank. Used by entry points (callback handlers, approval handlers) that may be hit
     * after a process restart with an empty session map — without this they read an
     * empty Conversation, mutate it, and `saveConversation` then OVERWRITES the persisted
     * state with empty content (silent data loss). Idempotent and cheap when the session
     * is already populated.
     */
    suspend fun ensureHydrated(conversationId: Uuid) {
        val session = getOrCreateSession(conversationId)
        if (session.state.value.messageNodes.isEmpty()) {
            val fromDb = conversationRepo.getConversationById(conversationId) ?: return
            if (fromDb.messageNodes.isNotEmpty()) {
                session.state.value = fromDb
            }
        }
    }

    // 错误状态
    private val _errors = MutableStateFlow<List<ChatError>>(emptyList())
    val errors: StateFlow<List<ChatError>> = _errors.asStateFlow()

    fun addError(
        error: Throwable,
        conversationId: Uuid? = null,
        title: String? = null,
        solution: ChatErrorSolution? = null,
    ) {
        if (error is CancellationException) return
        _errors.update { it + ChatError(title = title, error = error, conversationId = conversationId, solution = solution) }
    }

    fun dismissError(id: Uuid) {
        _errors.update { list -> list.filter { it.id != id } }
    }

    fun clearAllErrors() {
        _errors.value = emptyList()
    }

    // 生成完成流
    private val _generationDoneFlow = MutableSharedFlow<Uuid>()
    val generationDoneFlow: SharedFlow<Uuid> = _generationDoneFlow.asSharedFlow()

    // 前台状态管理
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> {
                _isForeground.value = false
                // A user leaving the app does not cancel AppScope generation. Flush the latest
                // in-memory stream state while the process is still alive so returning to the
                // conversation (or an imminent process kill) never falls behind the UI.
                appScope.launch(Dispatchers.IO) {
                    runCatching { flushStreamingPersistence() }
                        .onFailure { Log.w(TAG, "flushStreamingPersistence on stop failed", it) }
                }
            }
            else -> {}
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
    }

    fun cleanup() = runCatching {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        sessions.values.forEach { it.cleanup() }
        sessions.clear()
        sessionMutexes.clear()
        compactionMutexes.clear()
        persistenceMutexes.clear()
        pendingStreamingPersistence.clear()
        lastStreamingPersistAt.clear()
    }.onFailure {
        // Don't let a teardown hiccup escape, but don't swallow it silently either —
        // a failure here can leave the lifecycle observer registered (slow leak).
        Log.w(TAG, "cleanup failed", it)
    }

    // ---- Session 管理 ----

    private fun getOrCreateSession(conversationId: Uuid): ConversationSession {
        return sessions.computeIfAbsent(conversationId) { id ->
            val settings = settingsStore.settingsFlow.value
            ConversationSession(
                id = id,
                initial = Conversation.ofId(
                    id = id,
                    assistantId = settings.getCurrentAssistant().id
                ),
                scope = appScope,
                onIdle = { removeSession(it) }
            ).also {
                _sessionsVersion.value++
                Log.i(TAG, "createSession: $id (total: ${sessions.size + 1})")
            }
        }
    }

    private fun removeSession(conversationId: Uuid) {
        val session = sessions[conversationId] ?: return
        if (session.isInUse) {
            Log.d(TAG, "removeSession: skipped $conversationId (still in use)")
            return
        }
        if (sessions.remove(conversationId, session)) {
            session.cleanup()
            // Evict the per-conversation mutex so it doesn't accumulate forever.
            // dropSession() already removes it; removeSession() (idle eviction path)
            // was previously missing this cleanup, causing a slow leak on heavy-use
            // sessions where many conversations cycle in and out of memory.
            sessionMutexes.remove(conversationId)
            compactionMutexes.remove(conversationId)
            if (!pendingStreamingPersistence.containsKey(conversationId)) {
                persistenceMutexes.remove(conversationId)
                lastStreamingPersistAt.remove(conversationId)
            }
            _sessionsVersion.value++
            Log.i(TAG, "removeSession: $conversationId (remaining: ${sessions.size})")
        }
    }

    /**
     * Force-drop the in-memory session for [conversationId] regardless of refcount /
     * generation status. Used by /new in TelegramBotService to make sure a straggler
     * coroutine writing back to the session can't resurrect the conversation after the
     * user reset it. Safe to call when no session exists — no-op.
     */
    fun dropSession(conversationId: Uuid) {
        val session = sessions.remove(conversationId) ?: return
        session.cleanup()
        sessionMutexes.remove(conversationId)
        compactionMutexes.remove(conversationId)
        pendingStreamingPersistence.remove(conversationId)
        lastStreamingPersistAt.remove(conversationId)
        persistenceMutexes.remove(conversationId)
        _sessionsVersion.value++
        Log.i(TAG, "dropSession: $conversationId (remaining: ${sessions.size})")
    }

    // ---- 引用管理 ----

    fun addConversationReference(conversationId: Uuid) {
        getOrCreateSession(conversationId).acquire()
    }

    fun removeConversationReference(conversationId: Uuid) {
        sessions[conversationId]?.release()
    }

    private fun launchWithConversationReference(
        conversationId: Uuid,
        block: suspend () -> Unit
    ): Job = appScope.launch {
        addConversationReference(conversationId)
        try {
            block()
        } finally {
            removeConversationReference(conversationId)
        }
    }

    // ---- 对话状态访问 ----

    fun getConversationFlow(conversationId: Uuid): StateFlow<Conversation> {
        return getOrCreateSession(conversationId).state
    }

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> {
        val session = sessions[conversationId] ?: return flowOf(null)
        return session.generationJob
    }

    fun getProcessingStatusFlow(conversationId: Uuid): StateFlow<String?> {
        val session = sessions[conversationId] ?: return MutableStateFlow(null)
        return session.processingStatus
    }

    fun getConversationJobs(): Flow<Map<Uuid, Job?>> {
        return _sessionsVersion.flatMapLatest {
            val currentSessions = sessions.values.toList()
            if (currentSessions.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(currentSessions.map { s ->
                    s.generationJob.map { job -> s.id to job }
                }) { pairs ->
                    pairs.filter { it.second != null }.toMap()
                }
            }
        }
    }

    // ---- 初始化对话 ----

    suspend fun initializeConversation(conversationId: Uuid) {
        getOrCreateSession(conversationId) // 确保 session 存在
        val conversation = conversationRepo.getConversationById(conversationId)
        if (conversation != null) {
            updateConversation(conversationId, conversation)
            settingsStore.updateAssistant(conversation.assistantId)
        } else {
            // A send can race this asynchronous initialization for a brand-new conversation.
            // Once the session already contains a user message, never replace it with the
            // assistant preset that was computed from the stale empty snapshot.
            if (getConversationFlow(conversationId).value.messageNodes.isNotEmpty()) return
            // 新建对话, 并添加预设消息
            val currentSettings = settingsStore.settingsFlowRaw.first()
            val assistant = currentSettings.getCurrentAssistant()
            val newConversation = Conversation.ofId(
                id = conversationId,
                assistantId = assistant.id,
                newConversation = true
            ).updateCurrentMessages(assistant.presetMessages)
            updateConversation(conversationId, newConversation)
        }
    }

    // ---- 发送消息 ----

    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>, answer: Boolean = true) {
        if (content.isEmptyInputMessage()) return

        val session = getOrCreateSession(conversationId)
        val previousJob = session.getJob()
        val previousGenerationWasActive = previousJob?.isActive == true
        previousJob?.cancel()

        val releaseForegroundWork = foregroundWorkTracker.acquire()
        val job = appScope.launch {
            try {
                awaitForegroundWorkReady()
                runCatching { previousJob?.join() }
                // Only a still-running generation was interrupted by this send. A completed
                // failed turn may leave an old pending tool in history, but relabelling every
                // such node as "Generation cancelled by user" corrupts the original failure
                // and makes several earlier context-overflow attempts look user-cancelled.
                if (previousGenerationWasActive) {
                    finishInterruptedPendingTools(conversationId)
                }

                // The chat screen can be recreated before its asynchronous initialization
                // finishes. Load the durable conversation before taking the snapshot used for
                // the new user message, otherwise that message can be appended to an empty
                // in-memory session and visually replace the recovered history.
                ensureHydrated(conversationId)
                val currentConversation = session.state.value
                // Resolve the assistant from the conversation's own assistantId, not the
                // global current-assistant pointer — otherwise switching assistants mid-
                // generation makes one conversation preprocess input with another's config.
                val settings = settingsStore.settingsFlow.first()
                val assistant = settings.getAssistantById(currentConversation.assistantId)
                    ?: settings.getCurrentAssistant()
                val processedContent = preprocessUserInputParts(content, assistant)

                // 添加消息到列表
                val withUser = currentConversation.copy(
                    messageNodes = currentConversation.messageNodes + UIMessage(
                        role = MessageRole.USER,
                        parts = processedContent,
                    ).toMessageNode(),
                )
                saveConversation(conversationId, withUser)

                // Phase 16 — fast-path router. If the assistant has it enabled and the user's
                // message matches a deterministic intent, run the matching tool and inject the
                // result as a synthetic assistant message — skipping the LLM entirely.
                // Conservative: any match failure (tool throws, no result) falls back to the
                // normal LLM path. Headless conversations and non-text messages are skipped.
                val routedHandled = if (answer)
                    tryFastPathRoute(conversationId, processedContent, withUser, assistant)
                else false

                // 开始补全 — only if router didn't handle the turn
                if (answer && !routedHandled) {
                    handleMessageComplete(conversationId)
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                e.printStackTrace()
                addError(e, conversationId, title = context.getString(R.string.error_title_send_message))
            } finally {
                releaseForegroundWork()
            }
        }
        session.setJob(job)
    }

    /**
     * Phase 16 — fast-path router entry. Returns `true` if the router successfully handled
     * the turn (synthesised an assistant message and stored it) so the caller knows to skip
     * the normal LLM dispatch. Returns `false` to fall through.
     */
    private suspend fun tryFastPathRoute(
        conversationId: Uuid,
        userParts: List<UIMessagePart>,
        afterUserSave: me.rerere.rikkahub.data.model.Conversation,
        assistant: Assistant,
    ): Boolean {
        // Headless paths (cron / sub-agent / external-automation / workflow) must always go
        // through the LLM — the fast-path is a per-user-turn optimisation, not a system-flow.
        if (me.rerere.rikkahub.data.ai.tools.HeadlessConversations.isHeadless(conversationId)) return false

        // assistant is resolved from the conversation's own assistantId by the caller — do NOT
        // re-read the global getCurrentAssistant() here or a mid-turn assistant switch makes the
        // router read fastPathRouterEnabled / localTools off the wrong assistant.
        if (!assistant.fastPathRouterEnabled) return false

        val userText = userParts.filterIsInstance<UIMessagePart.Text>().joinToString(" ") { it.text }.trim()
        if (userText.isBlank()) return false

        val match = me.rerere.rikkahub.skills.FastPathRouter.route(userText) ?: return false

        // Tool list construction is non-trivial on assistants with many enabled categories
        // (allocates a fresh List<Tool> each call). Defer until AFTER a router match so the
        // common no-match path stays at a single regex scan + an early return.
        // Fast-path is gated on !isHeadless above; pass the caller context so any tools the
        // router fires inherit the right assistant id (workflows / sub-agents / etc).
        val tools = localTools.getTools(
            assistant.localTools,
            me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = assistant.id.toString(),
                callerConversationId = conversationId.toString(),
                isHeadless = false,  // gated above
            ),
        )
        val tool = tools.firstOrNull { it.name == match.toolName } ?: run {
            android.util.Log.d("FastPathRouter", "matched intent=${match.intent} but tool=${match.toolName} not registered for assistant; falling through")
            return false
        }

        // Defence-in-depth — even though v1's intent set is read-only, run HARDLINE here so
        // that adding a side-effecting intent later (e.g. "set brightness 50%") can't bypass
        // the floor by routing around the LLM-tool-call path that normally enforces it.
        val hardlineReason = me.rerere.rikkahub.data.ai.tools.HardlineCommandGuard
            .checkTool(match.toolName, match.args.toString())
        if (hardlineReason != null) {
            android.util.Log.w("FastPathRouter", "hardline-blocked intent=${match.intent} tool=${match.toolName}: $hardlineReason; falling through to LLM")
            return false
        }

        val rendered: String = try {
            val out = tool.execute(match.args)
            val rawText = out.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            val parsed = runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(rawText).jsonObject
            }.getOrNull()
            val formatted = if (match.format != null && parsed != null) {
                runCatching { match.format.invoke(parsed) }
                    .onFailure { Log.w("FastPathRouter", "formatter for intent=${match.intent} threw; falling back to raw text", it) }
                    .getOrNull()
            } else null
            // Fall back to raw text if formatter throws or produces nothing.
            formatted?.takeIf { it.isNotBlank() } ?: rawText
        } catch (t: Throwable) {
            android.util.Log.w("FastPathRouter", "tool ${match.toolName} threw, falling back to LLM", t)
            me.rerere.rikkahub.skills.FastPathRouterLog.record(
                me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                    whenMs = System.currentTimeMillis(),
                    intent = match.intent,
                    toolName = match.toolName,
                    userText = userText.take(120),
                    resultPreview = "tool threw: ${t.message?.take(80)}",
                    skippedLlm = false,
                )
            )
            return false
        }

        // Inject synthetic assistant message into the conversation.
        val withAssistant = afterUserSave.copy(
            messageNodes = afterUserSave.messageNodes + UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Text(rendered)),
            ).toMessageNode(),
        )
        saveConversation(conversationId, withAssistant)
        me.rerere.rikkahub.skills.FastPathRouterLog.record(
            me.rerere.rikkahub.skills.FastPathRouterLog.Entry(
                whenMs = System.currentTimeMillis(),
                intent = match.intent,
                toolName = match.toolName,
                userText = userText.take(120),
                resultPreview = rendered.take(200),
                skippedLlm = true,
            )
        )
        return true
    }

    private fun preprocessUserInputParts(
        parts: List<UIMessagePart>,
        assistant: Assistant,
    ): List<UIMessagePart> {
        return parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    part.copy(
                        text = part.text.replaceRegexes(
                            assistant = assistant,
                            scope = AssistantAffectScope.USER,
                            visual = false
                        )
                    )
                }

                else -> part
            }
        }
    }

    // ---- 重新生成消息 ----

    fun regenerateAtMessage(
        conversationId: Uuid,
        message: UIMessage,
        regenerateAssistantMsg: Boolean = true
    ) {
        val session = getOrCreateSession(conversationId)
        session.getJob()?.cancel()

        val releaseForegroundWork = foregroundWorkTracker.acquire()
        val job = appScope.launch {
            try {
                awaitForegroundWorkReady()
                val conversation = session.state.value

                // Locate the message's node up front. indexOf returns -1 when the node is no
                // longer in the conversation (e.g. it was edited or removed between the tap and
                // here). Both branches index off this: the USER branch would subList(0, 0) and
                // silently wipe the conversation, and the regenerate branch builds `0..<-1`,
                // whose endInclusive is -2, which handleMessageComplete turns into
                // subList(0, -1) and crashes ("fromIndex(0) > toIndex(-1)"). Bail on not-found.
                val node = conversation.getMessageNodeByMessage(message)
                val indexAt = conversation.messageNodes.indexOf(node)
                if (indexAt < 0) {
                    Log.w(TAG, "regenerateAtMessage: node for message ${message.id} not in conversation; skipping")
                    return@launch
                }
                conversationRepo.clearCompaction(conversationId)
                if (message.role == MessageRole.USER) {
                    // 如果是用户消息，则截止到当前消息
                    val newConversation = conversation.copy(
                        messageNodes = conversation.messageNodes.subList(0, indexAt + 1)
                    )
                    saveConversation(conversationId, newConversation)
                    handleMessageComplete(conversationId)
                } else {
                    if (regenerateAssistantMsg) {
                        handleMessageComplete(conversationId, messageRange = 0..<indexAt)
                    } else {
                        saveConversation(conversationId, conversation)
                    }
                }

                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_regenerate_message))
            } finally {
                releaseForegroundWork()
            }
        }

        session.setJob(job)
    }

    // ---- 处理工具调用审批 ----

    /** Scope of an "approve" decision. Once = this single tool call only. ChatScope =
     *  every future call of the same tool name in this conversation (until /new). Always =
     *  every future call of this tool name across the whole app, persisted to disk. */
    enum class ApprovalScope { Once, ChatScope, Always }

    fun handleToolApproval(
        conversationId: Uuid,
        toolCallId: String,
        approved: Boolean,
        reason: String = "",
        answer: String? = null,
        scope: ApprovalScope = ApprovalScope.Once,
        toolName: String? = null,
    ) {
        val session = getOrCreateSession(conversationId)
        val convMutex = mutexFor(conversationId)

        // Snapshot the prior generation job BEFORE the appScope.launch below replaces it
        // via setJob. session.setJob runs synchronously after launch returns; the launched
        // body is dispatched and runs LATER (Dispatchers.Main posts to the looper). So
        // calling session.getJob() inside the body would return THIS very job — and
        // cancelAndJoin would self-cancel the resume coroutine: saveConversation's first
        // suspend then throws CancellationException, the tool stays Pending, and the
        // generation never resumes. The YOLO toggle masked this because auto-approval
        // skips the Pending → handleToolApproval path entirely.
        val priorGenerationJob = session.getJob()

        // Commit the broader-scope grant on a NonCancellable scope BEFORE the cancellable
        // mutation block. Previous design ran grantAlways() inside the cancellable
        // appScope.launch — a rapid second tap would cancel the first job and silently
        // drop the persisted Always-Allow grant; the user thinks they granted it, the next
        // prompt reappears. NonCancellable + before-launch-completion guarantees the write.
        if (approved && toolName != null && scope != ApprovalScope.Once) {
            appScope.launch(NonCancellable) {
                runCatching {
                    // Smart-cast on the surrounding `if` excluded Once already, so only
                    // ChatScope and Always remain — the when is exhaustive without else.
                    when (scope) {
                        ApprovalScope.ChatScope -> me.rerere.rikkahub.data.ai.tools
                            .ToolApprovalAllowList.grantForChat(conversationId, toolName)
                        ApprovalScope.Always -> toolApprovalPreferences.grantAlways(toolName)
                        ApprovalScope.Once -> Unit
                    }
                }.onFailure { Log.w(TAG, "approval grant write failed", it) }
            }
        }

        val releaseForegroundWork = foregroundWorkTracker.acquire()
        val job = appScope.launch {
            try {
                awaitForegroundWorkReady()
                convMutex.withLock {
                    // Hydrate from disk if the in-memory session is empty (post-restart
                    // path). Without this, the snapshot read below sees an empty
                    // Conversation and the saveConversation downstream OVERWRITES the
                    // persisted Pending tool with empty content — silent data loss.
                    ensureHydrated(conversationId)

                    // Wait for any prior generation job to actually finish writing before
                    // we read state. cancelAndJoin (vs bare cancel) closes the race where
                    // the prior coroutine emits one last chunk into `messages` between
                    // our cancel call and our state.value read. Use the SNAPSHOT taken
                    // before launch — see the comment on priorGenerationJob above.
                    priorGenerationJob?.let { runCatching { it.cancelAndJoin() } }

                    val conversation = session.state.value
                    val newApprovalState = when {
                        answer != null -> ToolApprovalState.Answered(answer)
                        approved -> ToolApprovalState.Approved
                        else -> ToolApprovalState.Denied(reason)
                    }

                    // Update the tool approval state, but only on the SPECIFIC tool that
                    // was approved AND only if it's still actually Pending. A racing
                    // /stop or a concurrent decision could have already flipped it to
                    // Denied(cancelled); we don't want to overwrite that with Approved.
                    var foundActivePending = false
                    val updatedNodes = conversation.messageNodes.map { node ->
                        node.copy(
                            messages = node.messages.map { msg ->
                                msg.copy(
                                    parts = msg.parts.map { part ->
                                        if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                                            if (part.isPending) {
                                                foundActivePending = true
                                                part.copy(approvalState = newApprovalState)
                                            } else part
                                        } else part
                                    }
                                )
                            }
                        )
                    }
                    if (!foundActivePending) {
                        // Tool was already resolved (concurrent stop / dual-surface tap /
                        // restart that hydrated a non-pending state). No-op the mutation.
                        return@withLock
                    }
                    val updatedConversation = conversation.copy(messageNodes = updatedNodes)
                    saveConversation(conversationId, updatedConversation)

                    // Check if there are still pending tools across the conversation
                    val hasPendingTools = updatedNodes.any { node ->
                        node.currentMessage.parts.any { part ->
                            part is UIMessagePart.Tool && part.isPending
                        }
                    }

                    // Only continue generation when all pending tools are handled. Run
                    // OUTSIDE the mutex (handleMessageComplete is a long-running flow
                    // collect; holding the mutex through generation would block every
                    // subsequent state mutation for the whole turn).
                    if (!hasPendingTools) {
                        // Release the mutex via early-returning from the withLock block,
                        // then start generation. We can't `return@withLock` and then call
                        // handleMessageComplete in the same coroutine without losing the
                        // try/catch, so use a flag.
                    }
                }
                // Outside the mutex: kick off the resume generation if no tools remain pending.
                val pendingNow = session.state.value.messageNodes.any { node ->
                    node.currentMessage.parts.any { part ->
                        part is UIMessagePart.Tool && part.isPending
                    }
                }
                if (!pendingNow) {
                    handleMessageComplete(conversationId)
                }
                _generationDoneFlow.emit(conversationId)
            } catch (e: Exception) {
                addError(e, conversationId, title = context.getString(R.string.error_title_tool_approval))
            } finally {
                releaseForegroundWork()
            }
        }

        session.setJob(job)
    }

    // ---- 处理消息补全 ----

    private suspend fun handleMessageComplete(
        conversationId: Uuid,
        messageRange: ClosedRange<Int>? = null,
        allowContextRetry: Boolean = true,
    ) {
        val settings = settingsStore.settingsFlow.first()
        // Resolve the assistant from this conversation's own assistantId — the global
        // current-assistant pointer can have moved if the user switched assistants while
        // this generation was queued (multi-assistant crosstalk). Everything downstream
        // (model, memories, tools, sender name) keys off this resolved assistant.
        val initialConversation = getConversationFlow(conversationId).value
        val assistant = settings.getAssistantById(initialConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val model = settings.findModelById(assistant.chatModelId ?: settings.chatModelId)
            ?: throw IllegalStateException(
                "No chat model selected. Pick one in Settings → Default models, or send /model in Telegram."
            )
        // Defence against an upstream-Settings bug where disabling all providers can leave
        // the assistant's chatModelId pointing at a model whose provider has enabled=false:
        // the model lookup walks every provider regardless of state, so without this gate
        // inference fires (and bills) against the "disabled" provider's API key. Surface
        // the disabled state clearly instead of silently spending tokens.
        val resolvedProvider = model.findProvider(settings.providers)
        if (resolvedProvider == null) {
            throw IllegalStateException(
                "Selected model '${model.displayName.ifBlank { model.modelId }}' has no matching provider. " +
                    "Pick a different model in Settings or with /model."
            )
        }
        if (!resolvedProvider.enabled) {
            throw IllegalStateException(
                "Provider '${resolvedProvider.name}' is disabled — refusing to send. " +
                    "Re-enable it in Settings → Providers, or pick a different model with /model."
            )
        }

        val senderName = if (assistant.useAssistantAvatar) {
            assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
        } else {
            model.displayName
        }

        val generationResult = runCatching {
            // reset suggestions
            updateConversation(conversationId, initialConversation.copy(chatSuggestions = emptyList()))

            // memory tool
            if (!model.abilities.contains(ModelAbility.TOOL)) {
                if (assistant.enableWebSearch || mcpManager.getAllAvailableTools().isNotEmpty()) {
                    addError(
                        IllegalStateException(context.getString(R.string.tools_warning)),
                        conversationId,
                        title = context.getString(R.string.error_title_tool_unavailable)
                    )
                }
            }

            // check invalid messages
            checkInvalidMessages(conversationId)
            val conversation = getConversationFlow(conversationId).value

            // start generating
            val session = getOrCreateSession(conversationId)
            var compactedMessageView = if (messageRange == null) {
                prepareMessagesForGeneration(
                    conversation = conversation,
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    processingStatus = session.processingStatus,
                )
            } else {
                null
            }
            val messagesForGeneration = if (messageRange != null) {
                conversation.currentMessages.subList(
                    messageRange.start,
                    messageRange.endInclusive + 1,
                )
            } else {
                compactedMessageView!!.messages
            }
            generationHandler.generateText(
                settings = settings,
                model = model,
                maxSteps = assistant.maxStepsPerTurn,
                processingStatus = session.processingStatus,
                // Read once per call so the surface that wrote the addendum (Telegram bot,
                // anything else) gets its runtime context into the system prompt without
                // having to plumb a parameter all the way through sendMessage. Returns null
                // for in-app conversations that didn't register one.
                systemAddendum = me.rerere.rikkahub.data.ai.tools
                    .ConversationSystemAddendum.get(conversationId),
                isToolAutoApproved = { toolName ->
                    // YOLO mode ("I AM STUPID" toggle in Settings → Tool approvals): every
                    // tool auto-approves. User opted into this explicitly. HARDLINE still
                    // blocks rm -rf / et al — that check runs BEFORE auto-approval in
                    // GenerationHandler, so YOLO can't smuggle one through.
                    //
                    // Headless conversations (cron-driven) also auto-approve EVERY tool;
                    // the user pre-authorised the schedule itself at job-creation time
                    // and there's no UI surface to prompt at fire time.
                    //
                    // Otherwise: "Allow for this chat" (in-memory, per-conversation) OR
                    // "Always Allow" (DataStore-backed, across the whole app). The
                    // Once-grant lives in the message itself as
                    // ToolApprovalState.Approved, so it's already handled by the regular
                    // Pending → Approved transition.
                    //
                    // ask_user is a human-input request, NOT a permission gate. It must pause
                    // for the user whenever there's a surface to ask on (the in-app question card
                    // or the Telegram clarify flow), so it ignores YOLO and the allow-lists —
                    // otherwise it auto-executes its placeholder body and returns
                    // ask_user_unavailable. In a headless run (cron / sub-agent) there's nobody to
                    // answer, so it still auto-approves there and falls through to that graceful
                    // envelope instead of hanging the turn.
                    if (toolName == "ask_user") {
                        me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                            .shouldAutoApprove(conversationId)
                    } else {
                        toolApprovalPreferences.currentYolo() ||
                            me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                                .shouldAutoApprove(conversationId) ||
                            me.rerere.rikkahub.data.ai.tools.ToolApprovalAllowList
                                .isAllowedForChat(conversationId, toolName) ||
                            toolApprovalPreferences.current().contains(toolName)
                    }
                },
                onAfterToolExecution = { generatedMessages ->
                    if (messageRange != null || !settings.enableAutoCompaction) {
                        null
                    } else {
                        val actualPromptTokens = generatedMessages.lastOrNull()?.usage
                            ?.promptTokens
                            ?.takeIf { it > 0 }
                        // The provider reports usage before tool execution. Estimate only the
                        // execution result appended afterwards so the threshold reflects the
                        // next request without turning automatic compaction into a preflight
                        // estimate of an otherwise unverified conversation.
                        val nextRequestTokens = ContextBudgetPlanner
                            .estimateInputTokens(generatedMessages)
                        val triggerTokens = automaticCompactionTriggerTokens(settings, model)
                        if (actualPromptTokens == null ||
                            triggerTokens == null ||
                            nextRequestTokens < triggerTokens
                        ) {
                            null
                        } else {
                            Log.i(
                                TAG,
                                "Actual prompt usage reached compaction threshold after tool execution: " +
                                    "$actualPromptTokens reported, $nextRequestTokens including tool results " +
                                    ">= $triggerTokens",
                            )
                            val compacted = prepareMessagesForGeneration(
                                conversation = getConversationFlow(conversationId).value,
                                settings = settings,
                                assistant = assistant,
                                model = model,
                                processingStatus = session.processingStatus,
                                force = true,
                            )
                            compacted.newlyCreatedAutoCompaction?.let { compaction ->
                                generatedMessages.lastOrNull()
                                    ?.takeIf { it.role == MessageRole.ASSISTANT }
                                    ?.let { sourceMessage ->
                                        attachAutomaticCompactionPresentation(
                                            conversationId = conversationId,
                                            messageId = sourceMessage.id,
                                            compaction = compaction,
                                        )
                                    }
                            }
                            compactedMessageView = compacted
                            compacted.messages
                        }
                    }
                },
                onBeforeModelRequest = {
                    awaitForegroundWorkReady()
                },
                messages = messagesForGeneration,
                assistant = assistant,
                conversationSystemPrompt = conversation.customSystemPrompt,
                conversationModeInjectionIds = conversation.modeInjectionIds,
                conversationLorebookIds = conversation.lorebookIds,
                workspaceCwd = conversation.workspaceCwd,
                memories = if (assistant.useGlobalMemory) {
                    memoryRepository.getGlobalMemories()
                } else {
                    memoryRepository.getMemoriesOfAssistant(assistant.id.toString())
                },
                inputTransformers = buildList {
                    addAll(inputTransformers)
                    add(templateTransformer)
                    add(workspaceReminderTransformer)
                },
                outputTransformers = outputTransformers,
                tools = buildList {
                    if (assistant.enableWebSearch) {
                        addAll(createSearchTools(settings))
                    }
                    // Pass the caller context so context-aware tools (subagent_dispatch
                    // recursion guard, workflow_create authoring-id) can read the
                    // calling conversation + assistant. isHeadless is read from
                    // HeadlessConversations — true iff this is a cron / sub-agent /
                    // workflow / external-automation flow.
                    val invocationCtx = me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                        callerAssistantId = assistant.id.toString(),
                        callerConversationId = conversationId.toString(),
                        isHeadless = me.rerere.rikkahub.data.ai.tools.HeadlessConversations
                            .isHeadless(conversationId),
                        // show_image keys its result envelope off this — a text-only model
                        // gets told it cannot see the image instead of confabulating one.
                        modelCanSeeImages = Modality.IMAGE in model.inputModalities,
                    )
                    addAll(localTools.getTools(assistant.localTools, invocationCtx))
                    addAll(createWorkspaceToolsIfReady(assistant.workspaceId?.toString(), conversation.workspaceCwd))
                    if (assistant.enabledSkills.isNotEmpty()) {
                        addAll(
                            createSkillTools(
                                enabledSkills = assistant.enabledSkills,
                                allSkills = skillManager.listSkills(),
                                skillManager = skillManager,
                            )
                        )
                    }
                    mcpManager.getAllAvailableTools().also { allTools ->
                        // Upstream name validation: a server name that isn't pure
                        // English+digits would produce an invalid `mcp__<name>__tool`
                        // surface, so surface it as an error rather than emit a tool the
                        // model can't address.
                        val invalidNames = allTools
                            .map { it.second }
                            .distinct()
                            .filter { name -> name.isEmpty() || !name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' } }
                        if (invalidNames.isNotEmpty()) {
                            addError(
                                error = IllegalStateException(
                                    context.getString(
                                        R.string.error_mcp_invalid_server_name,
                                        invalidNames.joinToString(", ")
                                    )
                                ),
                                conversationId = conversationId,
                            )
                            return
                        }
                    }.forEach { (serverId, serverName, tool) ->
                        // Namespace MCP tools by a server-id slug so two enabled servers that
                        // each expose a tool of the same name don't collide (which would 400 or
                        // mis-route to whichever server registered last). Keep the `mcp__` prefix
                        // intact: HardlineCommandGuard and ToolApprovalDefaults both branch on
                        // `startsWith("mcp__")`. The slug is the first 8 hex chars of the id with
                        // dashes stripped; the validated server name follows for human-readable
                        // disambiguation, keeping the name within the 64-char /
                        // ^[a-zA-Z0-9_-]+$ limit. The execute lambda below still calls callTool
                        // with the REAL tool.name, since the namespacing exists only on the
                        // model-facing surface.
                        val serverSlug = serverId.toString().take(8).replace("-", "")
                        val mcpToolName = "mcp__" + serverSlug + "_" + serverName + "__" + tool.name
                        add(
                            Tool(
                                name = mcpToolName,
                                description = tool.description ?: "",
                                parameters = { tool.inputSchema },
                                // MCP servers' tool surfaces are opaque to us — we can't
                                // tell read from write or safe from destructive — so
                                // every MCP call is approval-gated by default. The user
                                // can grant Always-Allow per-tool to suppress prompts on
                                // a known-safe MCP server. The HARDLINE floor still
                                // applies via HardlineCommandGuard's `mcp__*` branch,
                                // which scans every string arg for shell-content
                                // patterns (rm -rf /, mkfs, shutdown, encoded payloads).
                                needsApproval = {
                                    me.rerere.rikkahub.data.ai.tools
                                        .ToolApprovalDefaults.requiresApproval(mcpToolName) ||
                                        tool.needsApproval
                                },
                                execute = {
                                    mcpManager.callTool(serverId, tool.name, it.jsonObject)
                                },
                            )
                        )
                    }
                },
            ).onCompletion { completionCause ->
                // 取消 Live Update 通知
                cancelLiveUpdateNotification(conversationId)

                // 可能被取消了，或者意外结束，兜底更新
                val updatedConversation = getConversationFlow(conversationId).value.copy(
                    messageNodes = getConversationFlow(conversationId).value.messageNodes.map { node ->
                        node.copy(messages = node.messages.map { it.finishReasoning() })
                    },
                    updateAt = Instant.now()
                )
                updateConversation(conversationId, updatedConversation)
                // The stream may end because it is waiting for approval, because the user
                // stopped it, or because transport failed. All three cases must leave the latest
                // partial assistant message and tool calls on disk.
                markStreamingPersistence(conversationId)
                // onCompletion also runs for cancellation. Keep this final write alive after a
                // user presses Stop so a cancelled job cannot discard its last partial chunk.
                withContext(NonCancellable) {
                    persistStreamingStateNow(conversationId, updateSearchIndex = true)
                }

                // A Pending tool is an intentional pause, not a completed response. Surface the
                // approval request when the UI is backgrounded so the foreground-service chip
                // disappearing is not mistaken for the turn finishing. Transport failures and
                // cancellation are reported by their existing error paths and must not emit a
                // misleading completion notification here.
                if (
                    completionCause == null &&
                    !isForeground.value &&
                    settings.displaySetting.enableNotificationOnMessageGeneration
                ) {
                    val pendingTool = updatedConversation.currentMessages
                        .asReversed()
                        .asSequence()
                        .flatMap { it.parts.asReversed().asSequence() }
                        .filterIsInstance<UIMessagePart.Tool>()
                        .firstOrNull { it.isPending }
                    if (pendingTool != null) {
                        sendToolApprovalRequiredNotification(
                            conversationId = conversationId,
                            senderName = senderName,
                            pendingTool = pendingTool,
                        )
                    } else {
                        sendGenerationDoneNotification(conversationId, senderName)
                    }
                }
            }.collect { chunk ->
                when (chunk) {
                    is GenerationChunk.Messages -> {
                        val currentConversation = getConversationFlow(conversationId).value
                        val updatedConversation = if (compactedMessageView?.compaction != null) {
                            ContextCompactionView.mergeGeneratedMessages(
                                conversation = currentConversation,
                                view = compactedMessageView,
                                generatedMessages = chunk.messages,
                            )
                        } else {
                            currentConversation.updateCurrentMessages(chunk.messages)
                        }
                        updateConversation(conversationId, updatedConversation)
                        markStreamingPersistence(conversationId)
                        persistStreamingStateIfDue(conversationId)

                        // Persist immediately when a tool transitions to "execution
                        // started but no output yet" — this writes the executionStartedAt
                        // breadcrumb to disk so a process kill mid-execute leaves a clear
                        // signal for the next replay (see GenerationHandler.kt's replay
                        // safety pass: Approved + executionStartedAt + empty → Denied
                        // interrupted_unknown_outcome). Without this, the marker stays in
                        // memory only and replay can't distinguish "freshly approved,
                        // never tried" from "interrupted mid-execute" → silent re-run.
                        val needsImmediatePersist = chunk.messages.lastOrNull()?.parts?.any { p ->
                            p is UIMessagePart.Tool &&
                                p.executionStartedAt != null &&
                                p.output.isEmpty() &&
                                p.approvalState is ToolApprovalState.Approved
                        } ?: false
                        if (needsImmediatePersist) {
                            persistStreamingStateNow(conversationId)
                        }

                        // 如果应用不在前台，发送 Live Update 通知
                        if (!isForeground.value && settings.displaySetting.enableNotificationOnMessageGeneration && settings.displaySetting.enableLiveUpdateNotification) {
                            sendLiveUpdateNotification(conversationId, chunk.messages, senderName)
                        }
                    }
                }
            }
        }

        val generationFailure = generationResult.exceptionOrNull()
        val lastMessage = getConversationFlow(conversationId).value.currentMessages.lastOrNull()
        // A tool round normally ends with an ASSISTANT message containing executed
        // tool calls. That message is still a valid point for a context-limit retry:
        // compact the conversation and retry after the tool results have been stored.
        val canRetryAfterContextLimit =
            lastMessage?.role != MessageRole.ASSISTANT ||
                lastMessage.getTools().any { it.isExecuted }
        if (
            allowContextRetry &&
            messageRange == null &&
            settings.enableAutoCompaction &&
            generationFailure != null &&
            generationFailure.isContextLimitError() &&
            canRetryAfterContextLimit
        ) {
            // Some providers omit context_length metadata or reject requests because their
            // system/tool schema overhead is larger than the local estimate. Force one
            // compaction pass and retry the same user turn once before surfacing the provider
            // error. The partial-output guard above prevents duplicating an already-streamed
            // assistant response.
            val session = getOrCreateSession(conversationId)
            val forcedView = runCatching {
                prepareMessagesForGeneration(
                    conversation = getConversationFlow(conversationId).value,
                    settings = settings,
                    assistant = assistant,
                    model = model,
                    processingStatus = session.processingStatus,
                    force = true,
                    compactEntireContext = true,
                )
            }.onFailure {
                Log.w(TAG, "Context-limit retry compaction failed", it)
            }.getOrNull()
            if (forcedView?.compaction != null) {
                forcedView.newlyCreatedAutoCompaction?.let { compaction ->
                    getConversationFlow(conversationId).value.currentMessages
                        .lastOrNull { it.role == MessageRole.ASSISTANT }
                        ?.let { sourceMessage ->
                            attachAutomaticCompactionPresentation(
                                conversationId = conversationId,
                                messageId = sourceMessage.id,
                                compaction = compaction,
                            )
                        }
                }
                handleMessageComplete(
                    conversationId = conversationId,
                    messageRange = null,
                    allowContextRetry = false,
                )
                return
            }
        }

        // Retry status is transient. Clear it before surfacing the final result so a failed
        // stream does not leave the conversation stuck on “retrying” after the error card appears.
        getOrCreateSession(conversationId).processingStatus.value = null

        generationResult.onFailure {
            // 取消 Live Update 通知
            cancelLiveUpdateNotification(conversationId)

            // Persist the in-memory snapshot so the Auto/Pending → Denied transitions
            // GenerationHandler did inside its try/catch (the "generation_failed" recovery
            // path) survive a process restart. Without this, the failure path only
            // updates memory and the persisted DB row keeps the stale Pending state
            // forever — replay would re-run the loop against unrecoverable shape.
            runCatching {
                val final = getConversationFlow(conversationId).value
                saveConversation(conversationId, final)
            }.onFailure { saveErr ->
                Log.w(TAG, "handleMessageComplete: failure-path save failed", saveErr)
            }

            it.printStackTrace()
            addError(it, conversationId, title = context.getString(R.string.error_title_generation))
            Logging.log(TAG, "handleMessageComplete: $it")
            Logging.log(TAG, it.stackTraceToString())
        }.onSuccess {
            val finalConversation = getConversationFlow(conversationId).value
            saveConversation(conversationId, finalConversation)

            launchWithConversationReference(conversationId) {
                generateTitle(conversationId, finalConversation)
            }
            launchWithConversationReference(conversationId) {
                generateSuggestion(conversationId, finalConversation)
            }
        }
    }

    private suspend fun createWorkspaceToolsIfReady(workspaceId: String?, cwd: String? = null): List<Tool> {
        if (workspaceId.isNullOrBlank()) return emptyList()
        val workspace = workspaceRepository.getById(workspaceId) ?: return emptyList()
        if (workspace.shellStatus != WorkspaceShellStatus.READY.name) {
            Log.d(
                TAG,
                "createWorkspaceToolsIfReady: skip workspace tools, workspace=$workspaceId, status=${workspace.shellStatus}"
            )
            return emptyList()
        }
        return createWorkspaceTools(workspaceId, workspaceRepository, cwd)
    }

    // ---- 检查无效消息 ----

    private suspend fun checkInvalidMessages(conversationId: Uuid) {
        val conversation = getConversationFlow(conversationId).value
        var messagesNodes = conversation.messageNodes

        // Close unresolved Auto/Pending tools left by a killed process, but keep the assistant
        // message itself. Removing the whole node here made the recovered tool call disappear as
        // soon as the user sent a follow-up message, and the next request then appeared to have
        // no trace of the interrupted turn. Approved/Denied/Answered tools remain resumable and
        // are handled by GenerationHandler's normal replay path.
        messagesNodes = messagesNodes.map { node ->
            val currentMessage = node.currentMessage
            val hasUnresumableTool = currentMessage.getTools().any {
                !it.isExecuted && !it.approvalState.canResumeToolExecution()
            }
            if (!hasUnresumableTool) {
                node
            } else {
                val repairedMessage = currentMessage.finishPendingTools(::cancelToolByRecovery)
                node.copy(
                    messages = node.messages.map { message ->
                        if (message.id == currentMessage.id) repairedMessage else message
                    }
                )
            }
        }

        // 更新index
        messagesNodes = messagesNodes.map { node ->
            if (node.messages.isNotEmpty() && node.selectIndex !in node.messages.indices) {
                node.copy(selectIndex = 0)
            } else {
                node
            }
        }

        // 移除无效消息
        messagesNodes = messagesNodes.filter { it.messages.isNotEmpty() }

        if (messagesNodes != conversation.messageNodes) {
            conversationRepo.clearCompaction(conversationId)
            // Persist the repair before the model request starts. If the process is killed again
            // during the continuation, the historical tool call remains visible and replayable.
            saveConversation(conversationId, conversation.copy(messageNodes = messagesNodes))
        }
    }

    private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied("Generation cancelled by user")
        )
    }

    private fun cancelToolByRecovery(tool: UIMessagePart.Tool): UIMessagePart.Tool {
        return tool.copy(
            output = listOf(
                UIMessagePart.Text(
                    """{"status":"interrupted","error":"The previous generation ended before this tool completed. No tool execution was resumed automatically."}"""
                )
            ),
            approvalState = ToolApprovalState.Denied(
                "Previous generation interrupted before tool execution completed"
            )
        )
    }

    private suspend fun finishInterruptedPendingTools(conversationId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val lastNode = currentConversation.messageNodes.lastOrNull() ?: return
        val lastMessage = lastNode.currentMessage
        val updatedMessage = lastMessage.finishPendingTools(::cancelToolByUser)
        if (updatedMessage == lastMessage) {
            return
        }

        // The newest user send can interrupt only the latest generation. Keep every older
        // node byte-for-byte intact so historical pending/failed tool records are not
        // rewritten as if the user cancelled them now.
        val updatedConversation = currentConversation.copy(
            messageNodes = currentConversation.messageNodes.dropLast(1) + lastNode.copy(
                messages = lastNode.messages.map { message ->
                    if (message.id == lastMessage.id) updatedMessage else message
                }
            )
        )
        saveConversation(conversationId, updatedConversation)
    }

    // ---- 生成标题 ----

    suspend fun generateTitle(
        conversationId: Uuid,
        conversation: Conversation,
        force: Boolean = false
    ) {
        val shouldGenerate = when {
            force -> true
            conversation.title.isBlank() -> true
            else -> false
        }
        if (!shouldGenerate) return

        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val model = settings.findModelById(settings.titleModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) return

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        prompt = settings.titlePrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(4).joinToString("\n\n") { it.summaryAsText(maxLength = 500) })
                    ),
                ),
                params = backgroundTextGenerationParams(model),
            )

            // 生成完，conversation可能不是最新了，因此需要重新获取
            conversationRepo.getConversationById(conversation.id)?.let {
                saveConversation(
                    conversationId,
                    it.copy(title = result.choices[0].message?.toText()?.trim() ?: "")
                )
            }
        }.onFailure {
            // Title generation is auxiliary — a failure here doesn't block the chat
            // and surfaces visibly as a blank conversation title in the list. Don't
            // push it onto the user-facing error stream: when the title model 429s,
            // the next message sees title.isBlank()==true, tries again, 429s again,
            // and the user gets a popup per message until they switch models. Match
            // the generateSuggestion pattern (log only) to keep the surface quiet.
            Log.w(TAG, "generateTitle failed", it)
        }
    }

    // ---- 生成建议 ----

    suspend fun generateSuggestion(conversationId: Uuid, conversation: Conversation) {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            if (!settings.enableSuggestion) return
            val model = settings.findModelById(settings.suggestionModelId, fallback = settings.fastModelId) ?: return
            val provider = model.findProvider(settings.providers) ?: return
            // Same defence as handleLlmTurn: don't burn tokens on a disabled provider.
            if (!provider.enabled) return

            sessions[conversationId]?.let { session ->
                updateConversation(
                    conversationId,
                    session.state.value.copy(chatSuggestions = emptyList())
                )
            }

            val providerHandler = providerManager.getProviderByType(provider)
            val result = providerHandler.generateText(
                providerSetting = provider,
                messages = listOf(
                    UIMessage.user(
                        settings.suggestionPrompt.applyPlaceholders(
                            "locale" to Locale.getDefault().displayName,
                            "content" to conversation.currentMessages
                                .takeLast(8).joinToString("\n\n") { it.summaryAsText(maxLength = 500) }),
                    )
                ),
                params = backgroundTextGenerationParams(model),
            )
            val suggestions =
                result.choices[0].message?.toText()?.split("\n")?.map { it.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()

            val latestConversation = conversationRepo.getConversationById(conversationId)
                ?: sessions[conversationId]?.state?.value
                ?: conversation
            saveConversation(
                conversationId,
                latestConversation.copy(
                    chatSuggestions = suggestions.take(
                        10
                    )
                )
            )
        }.onFailure {
            // Suggestion generation is auxiliary — log only, don't push onto the
            // user-facing error stream (mirrors the generateTitle failure handling).
            Log.w(TAG, "generateSuggestion failed", it)
        }
    }

    // ---- 压缩对话历史 ----

    private suspend fun prepareMessagesForGeneration(
        conversation: Conversation,
        settings: Settings,
        assistant: Assistant,
        model: Model,
        processingStatus: MutableStateFlow<String?>,
        force: Boolean = false,
        compactEntireContext: Boolean = false,
    ): CompactedMessageView {
        var view = loadCompactedMessageView(conversation)
        if (!settings.enableAutoCompaction) {
            return if (view.compaction?.isAuto == true) {
                ContextCompactionView.build(conversation, null)
            } else {
                view
            }
        }

        // Automatic compaction is deliberately driven by provider-reported usage after a
        // tool result, or by an actual context-limit error. Do not estimate the history here
        // and compact before the model has had a chance to execute its tools.
        if (!force) return view

        Log.i(
            TAG,
            "Auto compaction starting for ${conversation.id}: " +
                "provider usage reached the configured threshold or context limit, " +
                "modelContext=${model.contextLength}",
        )
        processingStatus.value = context.getString(R.string.chat_page_compressing)
        try {
            var newlyCreatedAutoCompaction: ConversationCompaction? = null
            val compaction = compactionMutexFor(conversation.id).withLock {
                val latestConversation = getConversationFlow(conversation.id).value
                view = loadCompactedMessageView(latestConversation)
                // A previous compaction may already cover every raw message. This can
                // happen when the provider reports another boundary before a new message
                // is appended. There is no further source material to summarize, so keep
                // the existing summary and continue with the compacted request view.
                if (
                    view.compaction != null &&
                    view.rawTailStartIndex >= latestConversation.messageNodes.size
                ) {
                    Log.i(TAG, "Auto compaction skipped: no new raw messages after existing summary")
                    view.compaction
                } else {
                    val targetTokens = settings.getContextCompactionTargetTokens(
                        compactionContextLength(settings, model),
                    )
                    val firstCompaction = createAutomaticCompaction(
                        conversation = latestConversation,
                        currentView = view,
                        settings = settings,
                        targetTokens = targetTokens,
                        // A provider-reported context overflow means the local estimate missed
                        // provider overhead (often a very large tool result or schema). Drop the
                        // raw tail for this recovery pass so the continuation cannot immediately
                        // submit the same oversized request again. The deterministic tool ledger
                        // and the prose summary preserve the completed execution history, while
                        // the original messages remain stored in the conversation.
                        keepRecentToolCalls = if (compactEntireContext) {
                            0
                        } else {
                            settings.autoCompactionKeepRecentToolCalls
                        },
                    )
                    val firstView = ContextCompactionView.build(latestConversation, firstCompaction)
                    val triggerTokens = automaticCompactionTriggerTokens(settings, model)
                    if (
                        triggerTokens != null &&
                        firstView.rawTailStartIndex < latestConversation.messageNodes.size &&
                        ContextBudgetPlanner.estimateContextTokens(firstView.messages) >= triggerTokens
                    ) {
                        Log.i(
                            TAG,
                            "Automatic compaction tail still exceeds threshold; compacting the full active context",
                        )
                        createAutomaticCompaction(
                            conversation = latestConversation,
                            currentView = firstView,
                            settings = settings,
                            targetTokens = targetTokens,
                            keepRecentToolCalls = 0,
                        )
                    } else {
                        firstCompaction
                    }.also { newlyCreatedAutoCompaction = it }
                }
            }
            val latestConversation = getConversationFlow(conversation.id).value
            return ContextCompactionView.build(latestConversation, compaction).copy(
                newlyCreatedAutoCompaction = newlyCreatedAutoCompaction,
            )
        } finally {
            processingStatus.value = null
        }
    }

    private suspend fun loadCompactedMessageView(conversation: Conversation): CompactedMessageView {
        val compaction = conversationRepo.getCompaction(conversation.id)
            ?: return CompactedMessageView(
                messages = conversation.currentMessages,
                compaction = null,
                rawTailStartIndex = 0,
            )
        val view = ContextCompactionView.build(conversation, compaction)
        if (view.compaction == null) {
            conversationRepo.clearCompaction(conversation.id)
        }
        return view
    }

    private suspend fun createAutomaticCompaction(
        conversation: Conversation,
        currentView: CompactedMessageView,
        settings: Settings,
        targetTokens: Int,
        keepRecentToolCalls: Int,
    ): ConversationCompaction {
        if (conversation.messageNodes.size < 2) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }

        val rawTailStartIndex = ContextCompactionPlanner.automaticTailStartIndex(
            messages = conversation.currentMessages,
            rawTailStartIndex = currentView.rawTailStartIndex,
            keepRecentToolCalls = keepRecentToolCalls,
        )

        // A subsequent compaction may legitimately consume the final raw tail message,
        // leaving the persisted summary as the only request-context prefix. The summary
        // entity uses a null tailStartNodeId to represent this boundary at messageNodes.size.
        if (rawTailStartIndex !in 1..conversation.messageNodes.size) {
            throw IllegalStateException(context.getString(R.string.chat_page_compress_not_enough_messages))
        }
        check(rawTailStartIndex > currentView.rawTailStartIndex) {
            "No new messages available for automatic compaction"
        }

        val messagesToCompress = buildList {
            currentView.compaction?.let { add(UIMessage.user(it.summary)) }
            addAll(
                conversation.currentMessages.subList(
                    currentView.rawTailStartIndex,
                    rawTailStartIndex,
                )
            )
        }
        return generateAndStoreCompaction(
            conversation = conversation,
            settings = settings,
            messagesToCompress = messagesToCompress,
            rawTailStartIndex = rawTailStartIndex,
            additionalPrompt = "",
            targetTokens = targetTokens.coerceAtLeast(1),
            isAuto = true,
        )
    }

    private fun automaticCompactionTriggerTokens(settings: Settings, model: Model): Int? =
        when (settings.autoCompactionThresholdMode) {
            AutoCompactionThresholdMode.PERCENT -> model.contextLength
                ?.takeIf { it > 0 }
                ?.let { length ->
                    (length.toLong() * settings.autoCompactionThresholdPercent
                        .coerceIn(5, 95) / 100L)
                        .coerceAtLeast(1L)
                        .toInt()
                }
            AutoCompactionThresholdMode.TOKENS ->
                (settings.autoCompactionThresholdTokensK
                    .coerceAtLeast(1)
                    .toLong() * 1_000L)
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
        }

    /**
     * Keep the persisted compaction summary request-only, but show the user the automatic
     * compression as an executed tool on the assistant message that triggered it.
     */
    private fun attachAutomaticCompactionPresentation(
        conversationId: Uuid,
        messageId: Uuid,
        compaction: ConversationCompaction,
    ) {
        updateConversationState(conversationId) { current ->
            ContextCompactionPresentation.attachToMessage(
                conversation = current,
                messageId = messageId,
                tool = ContextCompactionPresentation.createTool(compaction),
            )
        }
    }

    suspend fun compressConversation(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32
    ): Result<Unit> {
        val releaseForegroundWork = foregroundWorkTracker.acquire()
        return runCatching {
            awaitForegroundWorkReady()
            compactionMutexFor(conversationId).withLock {
                val latestConversation = getConversationFlow(conversationId).value
                    .takeIf { it.messageNodes.isNotEmpty() }
                    ?: conversation
                val allMessages = latestConversation.currentMessages
                if (allMessages.isEmpty()) {
                    throw IllegalStateException(
                        context.getString(R.string.chat_page_compress_not_enough_messages)
                    )
                }

                val currentView = loadCompactedMessageView(latestConversation)
                // The retained-message setting is an upper bound. If a short conversation has
                // fewer nodes than the requested tail but still overflowed because of a huge tool
                // result, compact every node instead of rejecting the manual operation.
                val requestedTailStart = if (keepRecentMessages in 1 until allMessages.size) {
                    allMessages.size - keepRecentMessages
                } else {
                    allMessages.size
                }
                // Existing compactions already cover the raw prefix. Advance their boundary only;
                // regenerating from all raw history is both needlessly expensive and can overflow
                // the compression model before it gets a chance to summarize anything.
                val rawTailStartIndex = maxOf(
                    currentView.rawTailStartIndex,
                    requestedTailStart,
                )
                val messagesToCompress = buildList {
                    currentView.compaction?.let { add(UIMessage.user(it.summary)) }
                    addAll(
                        allMessages.subList(
                            currentView.rawTailStartIndex,
                            rawTailStartIndex,
                        )
                    )
                }

                generateAndStoreCompaction(
                    conversation = latestConversation,
                    settings = settingsStore.settingsFlow.first(),
                    messagesToCompress = messagesToCompress,
                    rawTailStartIndex = rawTailStartIndex,
                    additionalPrompt = additionalPrompt,
                    targetTokens = targetTokens.coerceAtLeast(1),
                    isAuto = false,
                )
                Unit
            }
        }.also {
            releaseForegroundWork()
        }
    }

    private suspend fun awaitForegroundWorkReady() {
        // Reassert the service for every model round. This is cheap when it is already running,
        // and recovers when an OEM reclaimed it between a tool result and the next request.
        ChatGenerationForegroundService.start(context)
        if (!ChatGenerationForegroundService.awaitReady()) {
            // Keep the operation usable on devices that reject FGS promotion, while making the
            // degraded path explicit in logs. Normal interactive sends wait only until the
            // service confirms startForeground() and wake-lock acquisition.
            Log.w(TAG, "Chat foreground service was not ready before work started")
        }
    }

    /**
     * Manual compression can outlive the chat screen. Keep it on [AppScope] so removing the
     * activity from recents does not cancel an in-progress multi-pass compression request.
     */
    fun compressConversationAsync(
        conversationId: Uuid,
        conversation: Conversation,
        additionalPrompt: String,
        targetTokens: Int,
        keepRecentMessages: Int = 32,
    ): Job = appScope.launch {
        compressConversation(
            conversationId = conversationId,
            conversation = conversation,
            additionalPrompt = additionalPrompt,
            targetTokens = targetTokens,
            keepRecentMessages = keepRecentMessages,
        ).onFailure {
            addError(
                it,
                conversationId = conversationId,
                title = context.getString(R.string.error_title_compress_conversation),
            )
        }
    }

    private suspend fun generateAndStoreCompaction(
        conversation: Conversation,
        settings: Settings,
        messagesToCompress: List<UIMessage>,
        rawTailStartIndex: Int,
        additionalPrompt: String,
        targetTokens: Int,
        isAuto: Boolean,
    ): ConversationCompaction = withTimeout(COMPACTION_TOTAL_TIMEOUT_MS) {
        require(messagesToCompress.isNotEmpty()) { "No messages selected for compression" }
        require(rawTailStartIndex in 1..conversation.messageNodes.size) {
            "Invalid compaction boundary"
        }

        val model = settings.findModelById(settings.compressModelId)
            ?: settings.getCurrentChatModel()
            ?: throw IllegalStateException("No model available for compression")
        val provider = model.findProvider(settings.providers)
            ?: throw IllegalStateException("Provider not found")
        // Same defence as handleLlmTurn — refuse to compress against a disabled provider.
        if (!provider.enabled) {
            throw IllegalStateException(
                "Provider '${provider.name}' is disabled — cannot compress. " +
                    "Re-enable it in Settings → Providers, or set a different compression model."
            )
        }

        val providerHandler = providerManager.getProviderByType(provider)
        // In token-threshold mode the user has supplied an explicit request-size ceiling for
        // this model family. Prefer it over missing/stale provider metadata. In percent mode we
        // still use the model's advertised context, falling back to the planner's conservative
        // default when a provider does not publish one.
        val compressionContextLength = compactionContextLength(settings, model)
        val hasExplicitCompressionContext = settings.autoCompactionThresholdMode ==
            AutoCompactionThresholdMode.TOKENS
        val inputBudgetTokens = ContextCompactionPlanner.inputBudgetTokens(
            contextLength = compressionContextLength,
            targetTokens = targetTokens,
            allowFullContext = hasExplicitCompressionContext,
        )
        val mapInputBudgetTokens = ContextCompactionPlanner.mapInputBudgetTokens(
            inputBudgetTokens = inputBudgetTokens,
            allowLargeContext = hasExplicitCompressionContext,
        )
        val rawContextRetentionReport = ContextCompactionPlanner.rawContextRetentionReport(
            conversation.currentMessages.drop(rawTailStartIndex)
        )
        // Reserve roughly one third of the configured target for a deterministic ledger of
        // completed tool results. This remains in the request even if the model's prose summary
        // ignores a tool record.
        val toolDigest = ContextCompactionPlanner.mandatoryToolExecutionDigest(
            messages = messagesToCompress,
            maxTokens = (targetTokens / 3).coerceAtLeast(1),
        )
        // The deterministic tool digest is appended after the model response. It does not
        // consume the model's output budget, so keep the configured prose target intact.
        val modelSummaryTargetTokens = targetTokens.coerceAtLeast(1)

        suspend fun compressSources(
            sources: List<String>,
            requestedTargetTokens: Int,
        ): String {
            val contentToCompress = sources.joinToString("\n\n")
            val prompt = buildString {
                // Put this before the editable prompt and the source material so it remains a
                // top-level instruction even when a user wrote a minimal custom template.
                append(ContextCompactionPlanner.requiredToolRetentionInstructions())
                if (rawContextRetentionReport.isNotBlank()) {
                    appendLine()
                    appendLine()
                    appendLine(
                        "SCOPE NOTE: This request summarizes only a prefix of the conversation. " +
                            "Completed tool calls remain verbatim after the generated summary. " +
                            "Do not claim that the whole conversation has no tool calls."
                    )
                }
                appendLine()
                appendLine()
                append(
                    settings.compressPrompt.applyPlaceholders(
                        "content" to contentToCompress,
                        "target_tokens" to requestedTargetTokens.toString(),
                        "additional_context" to if (additionalPrompt.isNotBlank()) {
                            "Additional instructions from user: $additionalPrompt"
                        } else "",
                        "locale" to Locale.getDefault().displayName
                    )
                )
            }

            val result = withTimeout(COMPACTION_REQUEST_TIMEOUT_MS) {
                providerHandler.generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(prompt)),
                    params = backgroundTextGenerationParams(model).copy(
                        maxTokens = requestedTargetTokens,
                    ),
                )
            }

            return result.choices.firstOrNull()?.message?.toText()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Failed to generate compressed summary")
        }

        // Ordinary compression is a map pass over a small number of large groups. The full
        // source is used whenever it fits within a reasonable request count. Providers that omit
        // context metadata can otherwise turn one large tool result into dozens of tiny serial
        // requests, so switch to the bounded per-tool preview only for that pathological plan.
        val fullSources = messagesToCompress.map(ContextCompactionPlanner::sourceText)
        val fullSourceGroups = ContextCompactionPlanner.partitionSources(
            sources = fullSources,
            maxInputTokens = mapInputBudgetTokens,
        )
        var sourceGroups = fullSourceGroups
        var usingMapPreviews = false
        if (fullSourceGroups.size > MAX_FULL_CONTEXT_MAP_GROUPS) {
            val previewSources = messagesToCompress.map(ContextCompactionPlanner::mapSourceText)
            val previewGroups = ContextCompactionPlanner.partitionSources(
                sources = previewSources,
                maxInputTokens = mapInputBudgetTokens,
            )
            if (previewGroups.size < fullSourceGroups.size) {
                sourceGroups = previewGroups
                usingMapPreviews = true
            }
        }
        check(sourceGroups.isNotEmpty()) { "No usable messages selected for compression" }
        Log.i(
            TAG,
            "Compaction plan: model=${model.modelId}, contextLimit=" +
                "${compressionContextLength ?: "default"}, inputBudget=$mapInputBudgetTokens, " +
                "fullGroups=${fullSourceGroups.size}, selectedGroups=${sourceGroups.size}, " +
                "parallelism=$MAX_PARALLEL_COMPACTION_REQUESTS, mapPreviews=$usingMapPreviews",
        )

        suspend fun compressGroups(
            groups: List<List<String>>,
            requestedTargetTokens: Int,
        ): List<String> = groups
            .chunked(MAX_PARALLEL_COMPACTION_REQUESTS)
            .flatMap { batch ->
                coroutineScope {
                    batch.map { group ->
                        async(Dispatchers.IO) {
                            compressSources(group, requestedTargetTokens)
                        }
                    }.awaitAll()
                }
            }

        var reductionPasses = 0
        var finalSummary: String? = null
        while (finalSummary == null) {
            val passTargetTokens = if (sourceGroups.size == 1) {
                modelSummaryTargetTokens.coerceAtMost(COMPACTION_MAX_REQUEST_OUTPUT_TOKENS)
            } else {
                ContextCompactionPlanner.mapOutputTargetTokens(
                    finalTargetTokens = ContextCompactionPlanner.intermediateTargetTokens(
                        finalTargetTokens = modelSummaryTargetTokens,
                        inputBudgetTokens = mapInputBudgetTokens,
                    ),
                    groupCount = sourceGroups.size,
                    maxPerRequestTokens = COMPACTION_MAX_REQUEST_OUTPUT_TOKENS,
                )
            }
            Log.i(
                TAG,
                "Compaction pass ${reductionPasses + 1}: groups=${sourceGroups.size}, " +
                    "targetTokens=$passTargetTokens",
            )
            val summaries = compressGroups(sourceGroups, passTargetTokens)
            val combinedSummary = summaries.joinToString("\n\n")
            if (
                summaries.size == 1 ||
                ContextCompactionPlanner.estimateTokens(combinedSummary) <= mapInputBudgetTokens
            ) {
                // Preserve group order. For the common two-group case this is the final result,
                // so no extra reduce request is needed.
                finalSummary = combinedSummary
                continue
            }

            sourceGroups = ContextCompactionPlanner.partitionSources(
                sources = summaries,
                maxInputTokens = mapInputBudgetTokens,
            )
            reductionPasses++
            check(reductionPasses <= 12) {
                "Compression model did not reduce the conversation enough to merge its summaries"
            }
        }

        val expectedBoundary = conversation.messageNodes
            .take(rawTailStartIndex)
            .map { node -> node.id to node.currentMessage.id }
        val latestBoundary = getConversationFlow(conversation.id).value.messageNodes
            .take(rawTailStartIndex)
            .map { node -> node.id to node.currentMessage.id }
        check(expectedBoundary == latestBoundary) {
            "Conversation changed while context was being compressed"
        }

        val compaction = ConversationCompaction(
            conversationId = conversation.id,
            summary = listOfNotNull(
                finalSummary,
                toolDigest.takeIf { it.isNotBlank() },
                rawContextRetentionReport.takeIf { it.isNotBlank() },
            )
                .joinToString("\n\n"),
            tailStartNodeId = conversation.messageNodes.getOrNull(rawTailStartIndex)?.id,
            sourceEndNodeId = conversation.messageNodes[rawTailStartIndex - 1].id,
            summaryModelId = model.id,
            isAuto = isAuto,
            sourceTokenEstimate = ContextBudgetPlanner.estimateInputTokens(messagesToCompress),
            createdAt = Instant.now(),
        )
        conversationRepo.upsertCompaction(compaction)
        compaction
    }

    // ---- 通知 ----

    private fun sendGenerationDoneNotification(conversationId: Uuid, senderName: String) {
        // 先取消 Live Update 通知
        cancelLiveUpdateNotification(conversationId)

        val conversation = getConversationFlow(conversationId).value
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1
        ) {
            title = senderName
            content = conversation.currentMessages.lastOrNull()?.toText()?.take(50)?.trim() ?: ""
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun sendToolApprovalRequiredNotification(
        conversationId: Uuid,
        senderName: String,
        pendingTool: UIMessagePart.Tool,
    ) {
        val toolName = pendingTool.toolName
            .removePrefix("mcp__")
            .substringAfter("__", missingDelimiterValue = pendingTool.toolName.removePrefix("mcp__"))
        context.sendNotification(
            channelId = CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
            notificationId = 1,
        ) {
            title = senderName
            content = "${context.getString(R.string.setting_mcp_page_needs_approval)}: $toolName"
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = getPendingIntent(context, conversationId)
        }
    }

    private fun getLiveUpdateNotificationId(conversationId: Uuid): Int {
        return conversationId.hashCode() + 10000
    }

    private fun sendLiveUpdateNotification(
        conversationId: Uuid,
        messages: List<UIMessage>,
        senderName: String
    ) {
        val lastMessage = messages.lastOrNull() ?: return
        val parts = lastMessage.parts

        // 确定当前状态
        val (chipText, statusText, contentText) = determineNotificationContent(parts)

        context.sendNotification(
            channelId = CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
            notificationId = getLiveUpdateNotificationId(conversationId)
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = getPendingIntent(context, conversationId)
            requestPromotedOngoing = true
            shortCriticalText = chipText
        }
    }

    private fun determineNotificationContent(parts: List<UIMessagePart>): Triple<String, String, String> {
        // 检查最近的 part 来确定状态
        val lastReasoning = parts.filterIsInstance<UIMessagePart.Reasoning>().lastOrNull()
        val lastTool = parts.filterIsInstance<UIMessagePart.Tool>().lastOrNull()
        val lastText = parts.filterIsInstance<UIMessagePart.Text>().lastOrNull()

        return when {
            // 正在执行工具
            lastTool != null && !lastTool.isExecuted -> {
                // MCP tools are exposed as `mcp__<serverSlug>_<serverName>__<toolName>`; strip
                // both the prefix and the server segment so the notification shows the bare tool
                // name. Non-MCP tool names (no `mcp__` prefix) fall through unchanged via the
                // missingDelimiterValue, instead of being truncated at an embedded `__`.
                val toolName = lastTool.toolName
                    .removePrefix("mcp__")
                    .substringAfter("__", missingDelimiterValue = lastTool.toolName.removePrefix("mcp__"))
                Triple(
                    context.getString(R.string.notification_live_update_chip_tool),
                    context.getString(R.string.notification_live_update_tool, toolName),
                    lastTool.input.take(100)
                )
            }
            // 正在思考（Reasoning 未结束）
            lastReasoning != null && lastReasoning.finishedAt == null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_thinking),
                    context.getString(R.string.notification_live_update_thinking),
                    lastReasoning.reasoning.takeLast(200)
                )
            }
            // 正在写回复
            lastText != null -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_writing),
                    lastText.text.takeLast(200)
                )
            }
            // 默认状态
            else -> {
                Triple(
                    context.getString(R.string.notification_live_update_chip_writing),
                    context.getString(R.string.notification_live_update_title),
                    ""
                )
            }
        }
    }

    private fun cancelLiveUpdateNotification(conversationId: Uuid) {
        context.cancelNotification(getLiveUpdateNotificationId(conversationId))
    }

    private fun getPendingIntent(context: Context, conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    // ---- 对话状态更新 ----

    private fun updateConversation(conversationId: Uuid, conversation: Conversation) {
        if (conversation.id != conversationId) return
        val session = getOrCreateSession(conversationId)
        checkFilesDelete(conversation, session.state.value)
        session.state.value = conversation
    }

    fun updateConversationState(conversationId: Uuid, update: (Conversation) -> Conversation) {
        // Atomic compare-and-set via StateFlow.update so two concurrent writers can't
        // race on read-modify-write (each reading the SAME pre-state and overwriting
        // each other). Also routes through checkFilesDelete so attached files keep
        // being garbage-collected when removed from the conversation.
        val session = getOrCreateSession(conversationId)
        session.state.update { current ->
            val next = update(current)
            if (next.id != conversationId) current
            else {
                checkFilesDelete(next, current)
                next
            }
        }
    }

    /**
     * 移动会话到文件夹（folderId 为 null 表示移出到未归类）。
     *
     * 若该会话当前有活跃 session（正在查看或后台生成），先同步内存态再落库：
     * 否则仅改数据库 folder_id，而内存里那份 Conversation 仍是旧 folderId，
     * 后续任意 saveConversation(id, state.value) 会用整对象把 folder_id 覆盖回旧值，导致移动丢失。
     * 先改内存可确保这段窗口内的整对象保存也带上新 folderId。
     */
    suspend fun moveConversationToFolder(conversationId: Uuid, folderId: Uuid?) {
        if (sessions.containsKey(conversationId)) {
            updateConversationState(conversationId) { it.copy(folderId = folderId) }
        }
        conversationRepo.updateConversationFolderId(conversationId, folderId)
    }

    /**
     * 文件夹内是否存在正在生成回复的会话。
     * 仅活跃 session 可能在生成；内存态 folderId 为权威（移动会先同步内存态）。
     */
    fun hasGeneratingConversationInFolder(folderId: Uuid): Boolean {
        return sessions.values.any { it.isGenerating && it.state.value.folderId == folderId }
    }

    /**
     * 删除文件夹（folder_id 归属会被清空，会话本身保留）。
     *
     * 先把内存中归属该文件夹的活跃 session folderId 置空，再删库：
     * 否则 clearFolder 只改了数据库，而活跃 session 内存态仍指向该文件夹，
     * 后续整对象保存会写回一个已被删除的 folder_id，导致会话在列表中悬空。
     */
    suspend fun deleteFolder(folderId: Uuid) {
        sessions.values
            .filter { it.state.value.folderId == folderId }
            .forEach { updateConversationState(it.id) { c -> c.copy(folderId = null) } }
        folderRepository.deleteFolder(folderId)
    }

    private fun checkFilesDelete(newConversation: Conversation, oldConversation: Conversation) {
        val newFiles = newConversation.files
        val oldFiles = oldConversation.files
        val deletedFiles = oldFiles.filter { file ->
            newFiles.none { it == file }
        }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "checkFilesDelete: $deletedFiles")
        }
    }

    private fun markStreamingPersistence(conversationId: Uuid) {
        pendingStreamingPersistence[conversationId] = streamingPersistenceSequence.incrementAndGet()
    }

    /** Persist a stream snapshot at most twice per second while keeping the collector ordered. */
    private suspend fun persistStreamingStateIfDue(conversationId: Uuid) {
        val now = SystemClock.elapsedRealtime()
        val last = lastStreamingPersistAt[conversationId]
        if (last == null || now - last >= STREAMING_PERSIST_INTERVAL_MS) {
            persistStreamingStateNow(conversationId)
        }
    }

    /**
     * Persist the newest in-memory state. The snapshot is read while the persistence mutex is
     * held, so a user edit/approval racing with a stream flush cannot be overwritten by an older
     * chunk. A failed write leaves the dirty marker in place for the next chunk or lifecycle flush.
     */
    private suspend fun persistStreamingStateNow(
        conversationId: Uuid,
        updateSearchIndex: Boolean = false,
    ) {
        val observedMarker = pendingStreamingPersistence[conversationId] ?: return
        val persisted = runCatching {
            persistenceMutexFor(conversationId).withLock {
                persistConversationSnapshot(
                    conversationId = conversationId,
                    conversation = getConversationFlow(conversationId).value,
                    updateSearchIndex = updateSearchIndex,
                )
            }
        }.onFailure {
            Log.w(TAG, "persistStreamingStateNow failed for $conversationId", it)
        }.getOrDefault(false)

        if (persisted) {
            lastStreamingPersistAt[conversationId] = SystemClock.elapsedRealtime()
            // Keep a marker that arrived while the transaction was running.
            pendingStreamingPersistence.remove(conversationId, observedMarker)
        }
    }

    private suspend fun flushStreamingPersistence() {
        val ids = buildSet {
            addAll(pendingStreamingPersistence.keys)
            sessions.values
                .filter { it.isGenerating }
                .mapTo(this) { it.id }
        }
        ids.forEach { persistStreamingStateNow(it) }
    }

    suspend fun saveConversation(conversationId: Uuid, conversation: Conversation) {
        persistenceMutexFor(conversationId).withLock {
            val updatedConversation = conversation.copy()
            if (!persistConversationSnapshot(conversationId, updatedConversation)) return@withLock
            updateConversation(conversationId, updatedConversation)
        }
    }

    /** Room write shared by explicit saves and stream snapshots. Caller holds the persistence mutex. */
    private suspend fun persistConversationSnapshot(
        conversationId: Uuid,
        conversation: Conversation,
        updateSearchIndex: Boolean = true,
    ): Boolean {
        val exists = conversationRepo.existsConversationById(conversation.id)
        if (!exists && conversation.title.isBlank() && conversation.messageNodes.isEmpty()) {
            return false // 新会话且为空时不保存
        }
        // Refuse to overwrite a non-empty stored row with an empty in-memory snapshot.
        // This is the silent-data-loss guard: handleToolApproval / stopGeneration / etc.
        // could be called against an unhydrated session (post-restart), build an empty
        // updatedConversation, and call saveConversation. Without this guard we'd wipe
        // the Pending tool the user was trying to approve.
        if (exists && conversation.messageNodes.isEmpty()) {
            val storedHasContent = runCatching {
                conversationRepo.getConversationById(conversation.id)?.messageNodes?.isNotEmpty() == true
            }.getOrDefault(false)
            if (storedHasContent) {
                Log.w(TAG, "saveConversation: refusing to overwrite non-empty $conversationId with empty snapshot — likely an unhydrated session")
                return false
            }
        }

        if (!exists) {
            conversationRepo.insertConversation(
                conversation = conversation,
                updateSearchIndex = updateSearchIndex,
            )
        } else {
            conversationRepo.updateConversation(
                conversation = conversation,
                updateSearchIndex = updateSearchIndex,
            )
        }
        return true
    }

    // ---- 翻译消息 ----

    fun translateMessage(
        conversationId: Uuid,
        message: UIMessage,
        targetLanguage: Locale
    ) {
        appScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsStore.settingsFlow.first()

                val messageText = message.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("\n\n") { it.text }
                    .trim()

                if (messageText.isBlank()) return@launch

                // Set loading state for translation
                val loadingText = context.getString(R.string.translating)
                updateTranslationField(conversationId, message.id, loadingText)

                generationHandler.translateText(
                    settings = settings,
                    sourceText = messageText,
                    targetLanguage = targetLanguage
                ) { translatedText ->
                    // Update translation field in real-time
                    updateTranslationField(conversationId, message.id, translatedText)
                }.collect { /* Final translation already handled in onStreamUpdate */ }

                // Save the conversation after translation is complete
                saveConversation(conversationId, getConversationFlow(conversationId).value)
            } catch (e: Exception) {
                // Clear translation field on error
                clearTranslationField(conversationId, message.id)
                addError(e, conversationId, title = context.getString(R.string.error_title_translate_message))
            }
        }
    }

    private fun updateTranslationField(
        conversationId: Uuid,
        messageId: Uuid,
        translationText: String
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = translationText)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // ---- 消息操作 ----

    suspend fun editMessage(
        conversationId: Uuid,
        messageId: Uuid,
        parts: List<UIMessagePart>
    ) {
        if (parts.isEmptyInputMessage()) return

        val currentConversation = getConversationFlow(conversationId).value
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.getAssistantById(currentConversation.assistantId)
            ?: settings.getCurrentAssistant()
        val processedParts = preprocessUserInputParts(parts, assistant)
        var edited = false

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (!node.messages.any { it.id == messageId }) {
                return@map node
            }
            edited = true

            node.copy(
                messages = node.messages + UIMessage(
                    role = node.role,
                    parts = processedParts,
                ),
                selectIndex = node.messages.size
            )
        }

        if (!edited) return

        conversationRepo.clearCompaction(conversationId)
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun forkConversationAtMessage(
        conversationId: Uuid,
        messageId: Uuid
    ): Conversation {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNodeIndex = currentConversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            throw NotFoundException("Message not found")
        }

        val copiedNodes = currentConversation.messageNodes
            .subList(0, targetNodeIndex + 1)
            .map { node ->
                node.copy(
                    id = Uuid.random(),
                    messages = node.messages.map { message ->
                        message.copy(
                            parts = message.parts.map { part ->
                                part.copyWithForkedFileUrl()
                            }
                        )
                    }
                )
            }

        val forkConversation = Conversation(
            id = Uuid.random(),
            assistantId = currentConversation.assistantId,
            messageNodes = copiedNodes,
            customSystemPrompt = currentConversation.customSystemPrompt,
            modeInjectionIds = currentConversation.modeInjectionIds,
            lorebookIds = currentConversation.lorebookIds,
        )

        saveConversation(forkConversation.id, forkConversation)
        return forkConversation
    }

    suspend fun selectMessageNode(
        conversationId: Uuid,
        nodeId: Uuid,
        selectIndex: Int
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val targetNode = currentConversation.messageNodes.firstOrNull { it.id == nodeId }
            ?: throw NotFoundException("Message node not found")

        if (selectIndex !in targetNode.messages.indices) {
            throw BadRequestException("Invalid selectIndex")
        }

        if (targetNode.selectIndex == selectIndex) {
            return
        }

        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.id == nodeId) {
                node.copy(selectIndex = selectIndex)
            } else {
                node
            }
        }

        conversationRepo.clearCompaction(conversationId)
        saveConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        messageId: Uuid,
        failIfMissing: Boolean = true,
    ) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedConversation = buildConversationAfterMessageDelete(currentConversation, messageId)

        if (updatedConversation == null) {
            if (failIfMissing) {
                throw NotFoundException("Message not found")
            }
            return
        }

        conversationRepo.clearCompaction(conversationId)
        saveConversation(conversationId, updatedConversation)
    }

    suspend fun deleteMessage(
        conversationId: Uuid,
        message: UIMessage,
    ) {
        deleteMessage(conversationId, message.id, failIfMissing = false)
    }

    private fun buildConversationAfterMessageDelete(
        conversation: Conversation,
        messageId: Uuid,
    ): Conversation? {
        val targetNodeIndex = conversation.messageNodes.indexOfFirst { node ->
            node.messages.any { it.id == messageId }
        }
        if (targetNodeIndex == -1) {
            return null
        }

        val updatedNodes = conversation.messageNodes.mapIndexedNotNull { index, node ->
            if (index != targetNodeIndex) {
                return@mapIndexedNotNull node
            }

            val nextMessages = node.messages.filterNot { it.id == messageId }
            if (nextMessages.isEmpty()) {
                return@mapIndexedNotNull null
            }

            val nextSelectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex)
            node.copy(
                messages = nextMessages,
                selectIndex = nextSelectIndex,
            )
        }

        return conversation.copy(messageNodes = updatedNodes)
    }

    private fun UIMessagePart.copyWithForkedFileUrl(): UIMessagePart {
        fun copyLocalFileIfNeeded(url: String): String {
            if (!url.startsWith("file:")) return url
            val copied = filesManager.createChatFilesByContents(listOf(url.toUri())).firstOrNull()
            return copied?.toString() ?: url
        }

        return when (this) {
            is UIMessagePart.Image -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Document -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Video -> copy(url = copyLocalFileIfNeeded(url))
            is UIMessagePart.Audio -> copy(url = copyLocalFileIfNeeded(url))
            else -> this
        }
    }

    fun clearTranslationField(conversationId: Uuid, messageId: Uuid) {
        val currentConversation = getConversationFlow(conversationId).value
        val updatedNodes = currentConversation.messageNodes.map { node ->
            if (node.messages.any { it.id == messageId }) {
                val updatedMessages = node.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(translation = null)
                    } else {
                        msg
                    }
                }
                node.copy(messages = updatedMessages)
            } else {
                node
            }
        }

        updateConversation(conversationId, currentConversation.copy(messageNodes = updatedNodes))
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val convMutex = mutexFor(conversationId)
        // cancelAndJoin BEFORE the mutex so the cancelled coroutine can drain its own
        // writes (which may try to acquire the same mutex via their save path).
        sessions[conversationId]?.getJob()?.let { runCatching { it.cancelAndJoin() } }

        convMutex.withLock {
            // Hydrate from disk so we mark Pending tools cancelled even when the user
            // hits /stop after a process restart (sessions map is empty post-restart;
            // the old code returned early on the !sessions[id]?.getJob() check, leaving
            // the persisted Pending tool stranded forever).
            ensureHydrated(conversationId)

            val currentConversation = getConversationFlow(conversationId).value
            // Walk EVERY node, not just the last — Pending tools can appear on a non-last
            // node after branching / regenerate. finishPendingTools is now scoped to
            // tools that are NOT already in a terminal state, so a hardline-blocked
            // Denied tool keeps its original reason rather than being relabeled as
            // "cancelled by user".
            var changed = false
            val updatedNodes = currentConversation.messageNodes.map { node ->
                node.copy(
                    messages = node.messages.map { msg ->
                        val updated = msg.finishPendingTools(::cancelToolByUser)
                        if (updated !== msg) changed = true
                        updated
                    }
                )
            }
            if (!changed) return@withLock

            val updatedConversation = currentConversation.copy(messageNodes = updatedNodes)
            saveConversation(conversationId, updatedConversation)
        }
    }
}
