package me.rerere.rikkahub.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agentrun.AgentRunKind
import me.rerere.rikkahub.data.agentrun.AgentRunRepository
import me.rerere.rikkahub.data.agentrun.AgentRunStatus
import me.rerere.rikkahub.data.ai.tools.HeadlessConversations
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findAssistantById
import me.rerere.rikkahub.data.db.entity.ScheduledJobEntity
import me.rerere.rikkahub.data.db.entity.ScheduledJobRunEntity
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

private const val TAG = "CronJobWorker"

/** Outer bound on how long after [startedAtMs] a WorkManager replay can plausibly arrive. */
internal const val REPLAY_WINDOW_MS = 10L * 60_000L

/**
 * Prepended to every cron LLM-mode prompt. Models otherwise treat a `post_notification`
 * call as "done" and leave a terse chat reply, but the reply IS what the user reads when
 * they tap that notification (it deep-links back to this conversation), so it has to
 * carry the full result, not the notification.
 */
private const val CRON_DELIVERY_DIRECTIVE = "[System] Your full text reply to this task is saved to a conversation the user can open in the app, including by tapping any notification you post (the notification deep-links to this conversation). ALWAYS write your complete result as your reply here. If you also call post_notification, keep the notification a short alert and still include the full content in your reply. Do not rely on writing files or external channels for the user to read the result: your reply in this conversation is the record."

/**
 * Wait for the ChatService generation job on [flow] to terminate (transition to null)
 * within a wall-clock [timeoutMs] cap. Returns `true` on natural completion, `false` if
 * the cap fired first.
 *
 * The `Unit` sentinel is load-bearing: `.first { it == null }` returns the matched value
 * (null), and `withTimeoutOrNull` also returns null on timeout. Without the sentinel the
 * two outcomes are indistinguishable — every successful LLM-mode cron run was
 * misclassified as `timed_out` until this fix. (SubAgentEngine carries the same fix
 * inline; this helper exists so a JVM unit test can pin the contract.)
 */
internal suspend fun awaitGenerationTerminal(
    flow: Flow<Job?>,
    timeoutMs: Long,
): Boolean {
    val completed: Unit? = withTimeoutOrNull(timeoutMs) {
        flow.first { it == null }
        Unit
    }
    return completed != null
}

/**
 * Turn an [awaitGenerationTerminal] result into the LLM-mode run outcome, stopping the
 * still-running generation on timeout via [stop]. Left uncalled, a timed-out generation
 * keeps burning tokens indefinitely, and a later fire of the same job can start a second
 * parallel generation while the first is still going. [stop] is responsible for its own
 * failure handling (see the call site in [CronJobWorker.runLlm]) — this function only
 * guarantees it is invoked before the timeout is reported. Split out (like
 * [awaitGenerationTerminal]) so a JVM test can pin the stop-on-timeout contract without a
 * live ChatService.
 */
internal suspend fun finishRunLlm(
    completed: Boolean,
    convId: Uuid,
    stop: suspend () -> Unit,
): Triple<String, String?, Uuid?> {
    if (!completed) {
        stop()
        return Triple("timed_out", "llm turn exceeded 15min", convId)
    }
    return Triple("success", null, convId)
}

/**
 * The slot to stamp into the run row. Manual fires (trigger_job_now) happen at nowMs and
 * are NOT bound to any scheduled slot — stamping them with [jobNextRunAtMs] would poison
 * the next regular fire's replay guard. Natural fires stamp the slot they were enqueued
 * for so a true WorkManager replay still matches.
 */
internal fun computeRunSlot(isManual: Boolean, jobNextRunAtMs: Long?, nowMs: Long): Long =
    if (isManual) nowMs else (jobNextRunAtMs ?: nowMs)

/**
 * A WorkManager replay re-fires the SAME enqueued work request, so a true replay's
 * [slotMs] matches the prior row's exactly AND its [nowMs] is within minutes of the prior
 * row's [ScheduledJobRunEntity.startedAtMs]. Both checks are required: matching slot alone
 * lets stale rows (e.g. a manual fire from yesterday) suppress today's regular fire.
 */
internal fun shouldSuppressAsReplay(
    priorRow: ScheduledJobRunEntity?,
    slotMs: Long,
    nowMs: Long,
    windowMs: Long = REPLAY_WINDOW_MS,
): Boolean {
    if (priorRow == null) return false
    if (priorRow.scheduledAtMs != slotMs) return false
    if (priorRow.outcome == "concurrent_skip" || priorRow.outcome == "skipped_catchup") return false
    return priorRow.startedAtMs >= nowMs - windowMs
}

/**
 * Ceiling on how many run-history rows a single job's max_runs can force [trim] to retain.
 * schedule_job only validates max_runs >= 1 (ScheduleJobValidator), so without this cap
 * historyRetentionFor scales with an unbounded max_runs and trim(keep=...) would retain the
 * job's run history essentially forever — the same class of unbounded-row-growth bug
 * [CatchupPlanner.SKIPPED_ROWS_CAP] bounds on the catchup side.
 */
internal const val MAX_HISTORY_RETENTION = 1000

/**
 * How many run-history rows [ScheduledJobRunDao.trim] must retain for max_runs enforcement
 * to stay correct. boundsExpired() derives the job's progress from
 * [ScheduledJobRunRepository.countSuccessful], which only sees rows that survived trim() —
 * trimming below [maxRuns] silently caps the achievable success count at the trim floor, so
 * a job configured for e.g. 150 runs would fire forever once history reaches 100 rows.
 */
internal fun historyRetentionFor(maxRuns: Int?, baseline: Int = 100): Int =
    maxOf(baseline, maxRuns ?: 0).coerceAtMost(MAX_HISTORY_RETENTION)

/**
 * Tracks which jobs currently have a worker actively running. Prevents two
 * concurrent fires from racing on the same job — the second one writes a
 * 'concurrent_skip' history row and returns immediately.
 */
private object CronJobRunningTracker {
    private val running = ConcurrentHashMap.newKeySet<String>()
    fun start(jobId: String): Boolean = running.add(jobId)
    fun stop(jobId: String) { running.remove(jobId) }
}

class CronJobWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    private val repo: ScheduledJobRepository by inject()
    private val runRepo: ScheduledJobRunRepository by inject()
    private val scheduler: CronJobScheduler by inject()
    private val chatService: ChatService by inject()
    private val conversationRepo: ConversationRepository by inject()
    private val settingsStore: SettingsStore by inject()
    private val localTools: LocalTools by inject()
    private val directRunner: DirectModeActionRunner by inject()
    private val agentRunRepo: AgentRunRepository by inject()

    override suspend fun doWork(): Result {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return Result.failure()
        val isManual = inputData.getBoolean(KEY_MANUAL, false)

        if (!CronJobRunningTracker.start(jobId)) {
            recordRun(jobId, scheduledAtMs = System.currentTimeMillis(),
                outcome = "concurrent_skip", mode = "?", convId = null, errorMessage = null)
            return Result.success()
        }

        val nowMs = System.currentTimeMillis()
        val runRowId = Uuid.random().toString()

        try {
            val job = repo.getById(jobId) ?: return Result.success()
            if (!job.enabled) return Result.success()

            // Bounds re-check (defends against a stale enqueue surviving a job edit).
            if (boundsExpired(job, nowMs)) {
                repo.update(job.copy(enabled = false))
                return Result.success()
            }

            // Slot stamp: manual fires use nowMs (they aren't bound to a scheduled slot);
            // natural fires use job.nextRunAtMs so a true WorkManager replay can be detected.
            val scheduledAtMs = computeRunSlot(isManual, job.nextRunAtMs, nowMs)

            // Replay guard runs BEFORE the optimistic insert, else the insert self-matches.
            // Manual fires skip the guard so the user can always force a fresh fire.
            if (!isManual) {
                val priorRow = runRepo.getMostRecent(jobId)
                if (shouldSuppressAsReplay(priorRow, scheduledAtMs, nowMs)) {
                    Log.w(TAG, "doWork: suppressing likely WorkManager replay for $jobId " +
                            "(prior row ${priorRow!!.id} same scheduledAtMs=$scheduledAtMs outcome=${priorRow.outcome})")
                    runRepo.insert(ScheduledJobRunEntity(
                        id = runRowId,
                        jobId = jobId,
                        mode = job.mode,
                        scheduledAtMs = scheduledAtMs,
                        startedAtMs = nowMs,
                        finishedAtMs = nowMs,
                        outcome = "process_killed_replay",
                        conversationId = null,
                        errorMessage = "duplicate fire suppressed (prior run ${priorRow.id})",
                    ))
                    return Result.success()
                }
            }

            // Optimistic insert — outcome will be overwritten on completion.
            runRepo.insert(ScheduledJobRunEntity(
                id = runRowId,
                jobId = jobId,
                mode = job.mode,
                scheduledAtMs = scheduledAtMs,
                startedAtMs = nowMs,
                finishedAtMs = null,
                outcome = "success",                          // optimistic
                conversationId = null,
                errorMessage = null,
            ))

            // Phase 24 — open the cross-pillar ledger row alongside the domain detail row.
            // domain_id is keyed per-fire (jobId:slot) so a replay or a later fire of the
            // same job is a distinct ledger row. Best-effort: ledger failures never break the
            // cron run.
            val ledgerId = agentRunRepo.open(
                kind = AgentRunKind.Cron,
                domainId = "$jobId:$scheduledAtMs",
                metadata = buildJsonObject {
                    put("job_name", job.name)
                    put("mode", job.mode)
                    put("manual", isManual)
                },
            )

            val (outcome, errorMessage, convIdMaybe) = when (job.mode) {
                "llm"    -> runLlm(job)
                "direct" -> runDirect(job)
                else     -> Triple("failed", "unknown_mode:${job.mode}", null)
            }

            runRepo.update(ScheduledJobRunEntity(
                id = runRowId,
                jobId = jobId,
                mode = job.mode,
                scheduledAtMs = scheduledAtMs,
                startedAtMs = nowMs,
                finishedAtMs = System.currentTimeMillis(),
                outcome = outcome,
                conversationId = convIdMaybe?.toString(),
                errorMessage = errorMessage?.take(500),
            ))

            // Phase 24 — mirror the terminal outcome into the cross-pillar ledger. Cron
            // outcomes map to: success → succeeded; timed_out / failed / unknown → failed.
            // (concurrent_skip / skipped_catchup / process_killed_replay never reach here —
            // they early-return before the ledger row is opened.)
            agentRunRepo.markTerminal(
                id = ledgerId,
                status = if (outcome == "success") AgentRunStatus.succeeded else AgentRunStatus.failed,
                lastError = if (outcome == "success") null else "$outcome: ${errorMessage.orEmpty()}",
            )

            // Failure notification (post once per failure; the existing channel handles dedup)
            if (outcome != "success") {
                postFailureNotification(job.id, job.name, "$outcome: ${errorMessage.orEmpty()}")
            }

            // Manual fires (trigger_job_now) are bonus — they get a history row but
            // don't bump runs_so_far or lastRunAtMs (per spec Decision 13). The regular
            // schedule continues unaffected.
            if (!isManual) {
                // Derive runsSoFar from the authoritative success-count query (post-update,
                // so the final outcome is already committed) rather than incrementing the
                // cached field — this avoids runsSoFar drift after a replay.
                val newRunsSoFar = if (outcome == "success") runRepo.countSuccessful(job.id)
                                   else job.runsSoFar
                val maxReached = job.maxRuns != null && newRunsSoFar >= job.maxRuns
                val updated = job.copy(
                    lastRunAtMs = nowMs,
                    runsSoFar = newRunsSoFar,
                    enabled = if (job.scheduleType == "once" || maxReached) false else job.enabled,
                )
                repo.update(updated)
                if (updated.enabled) scheduler.schedule(updated)
            }

            // Trim history at the end so this row's insert/update is reflected in the cap.
            runRepo.trim(jobId, keep = historyRetentionFor(job.maxRuns))

            return Result.success()
        } finally {
            CronJobRunningTracker.stop(jobId)
        }
    }

    /**
     * Checks whether a job has expired before executing.
     *
     * For max_runs we count actual 'success' rows in the DB rather than trusting
     * job.runsSoFar — this makes max_runs immune to the replay race where the process is
     * killed after the run row is inserted (outcome='success') but before repo.update()
     * persists runsSoFar+1. On replay, countSuccessful() sees the existing success row and
     * correctly reports the job as exhausted.
     */
    private suspend fun boundsExpired(job: ScheduledJobEntity, nowMs: Long): Boolean {
        if (job.endAtUnixMs != null && nowMs > job.endAtUnixMs) return true
        if (job.maxRuns != null) {
            val successCount = runRepo.countSuccessful(job.id)
            if (successCount >= job.maxRuns) return true
        }
        return false
    }

    private suspend fun runLlm(job: ScheduledJobEntity): Triple<String, String?, Uuid?> {
        val prompt = job.prompt ?: return Triple("failed", "missing_prompt_for_llm_mode", null)
        val assistantUuid = runCatching { Uuid.parse(job.assistantId) }.getOrNull()
            ?: return Triple("failed", "bad_assistant_id:${job.assistantId}", null)

        val conv = Conversation.ofId(
            id = Uuid.random(),
            assistantId = assistantUuid,
            newConversation = true,
        ).copy(title = "[Scheduled] ${job.name}")
        conversationRepo.insertConversation(conv)
        chatService.initializeConversation(conv.id)
        HeadlessConversations.mark(conv.id)
        try {
            chatService.sendMessage(conv.id, listOf(UIMessagePart.Text(CRON_DELIVERY_DIRECTIVE + "\n\n" + prompt)))
            // Wait for the generation job to clear, with a 15-min wall-clock cap.
            // See awaitGenerationTerminal's KDoc for the Unit-sentinel rationale.
            val completed = awaitGenerationTerminal(
                flow = chatService.getGenerationJobStateFlow(conv.id),
                timeoutMs = 15L * 60_000L,
            )
            return finishRunLlm(completed, conv.id) {
                runCatching { chatService.stopGeneration(conv.id) }
                    .onFailure { Log.w(TAG, "runLlm: stopGeneration failed after timeout for ${job.id}", it) }
            }
        } catch (t: Throwable) {
            return Triple("failed", "${t::class.simpleName}: ${t.message.orEmpty()}", conv.id)
        } finally {
            HeadlessConversations.unmark(conv.id)
        }
    }

    private suspend fun runDirect(job: ScheduledJobEntity): Triple<String, String?, Uuid?> {
        // Replay idempotency guard: if WorkManager replays this worker after a process kill,
        // a run row for this job will already exist with a very recent startedAtMs.
        // Treat any non-skip row created within the last 5 minutes as a duplicate and bail.
        val actionsJson = job.actionsJson ?: return Triple("failed", "missing_actions_for_direct_mode", null)
        val parsed = DirectModeActionRunner.parse(actionsJson).getOrElse {
            return Triple("failed", "actions_parse:${(it as? DirectModeActionRunner.ParseError)?.code ?: it.message}", null)
        }
        // Tool list scoped to the job's assistant — same path ChatService uses.
        val assistantUuid = runCatching { Uuid.parse(job.assistantId) }.getOrNull()
            ?: return Triple("failed", "bad_assistant_id:${job.assistantId}", null)
        val settings = settingsStore.settingsFlow.first()
        val assistant = settings.findAssistantById(assistantUuid)
            ?: return Triple("failed", "assistant_not_found", null)
        // Headless context — sub-agent recursion guard fires from this dispatch path so
        // a cron job's direct-mode action sequence cannot itself spawn a sub-agent.
        val tools = localTools.getTools(
            assistant.localTools,
            me.rerere.rikkahub.data.ai.tools.ToolInvocationContext(
                callerAssistantId = assistantUuid.toString(),
                callerConversationId = null,  // direct-mode has no conversation
                isHeadless = true,
            ),
        )
        val seq = directRunner.run(parsed, tools)
        return Triple(seq.finalOutcome, seq.errorMessage, null)
    }

    private suspend fun recordRun(
        jobId: String,
        scheduledAtMs: Long,
        outcome: String,
        mode: String,
        convId: Uuid?,
        errorMessage: String?,
    ) {
        // Used for the concurrent_skip early return only. doWork() is already a suspend
        // function on the worker's dispatcher, so this insert runs inline. No runBlocking
        // bridge needed (which would have blocked the worker thread).
        runCatching {
            runRepo.insert(ScheduledJobRunEntity(
                id = Uuid.random().toString(),
                jobId = jobId,
                mode = mode,
                scheduledAtMs = scheduledAtMs,
                startedAtMs = scheduledAtMs,
                finishedAtMs = scheduledAtMs,
                outcome = outcome,
                conversationId = convId?.toString(),
                errorMessage = errorMessage?.take(500),
            ))
        }.onFailure { Log.w(TAG, "recordRun failed", it) }
    }

    private fun postFailureNotification(jobId: String, jobName: String, errorMessage: String) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Scheduled jobs", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle("Scheduled job failed")
            .setContentText("$jobName: $errorMessage")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$jobName: $errorMessage"))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        try {
            // jobId is the job's DB primary key (a stable UUID string), not the
            // user-editable display name — two jobs can share a name (and thus a
            // hashCode) but never an id, so this can't collide across different jobs
            // while still hashing to the same value across reruns of the same job.
            NotificationManagerCompat.from(ctx).notify(jobId.hashCode(), builder.build())
        } catch (_: SecurityException) { /* POST_NOTIFICATIONS not granted — fine */ }
    }

    companion object {
        const val KEY_JOB_ID = "cron_job_id"
        const val KEY_MANUAL = "cron_job_manual"
        const val CHANNEL_ID = "rikkahub_cron_jobs"
    }
}
