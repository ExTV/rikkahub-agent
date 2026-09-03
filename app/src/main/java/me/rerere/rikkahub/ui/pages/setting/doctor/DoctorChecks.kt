package me.rerere.rikkahub.ui.pages.setting.doctor

import android.Manifest
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.ai.tools.local.AccessibilityServiceHandle
import me.rerere.rikkahub.data.ai.tools.local.AgentWorkspace
import me.rerere.rikkahub.data.ai.tools.local.NotificationListenerHandle
import me.rerere.rikkahub.data.ai.tools.local.PermissionHelper
import me.rerere.rikkahub.data.datastore.AutoCompactionThresholdMode
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRepository
import me.rerere.rikkahub.data.repository.ScheduledJobRunRepository
import me.rerere.rikkahub.data.telegram.TelegramBotPreferences
import me.rerere.rikkahub.service.TelegramBotService
import me.rerere.rikkahub.shizuku.ShizukuManager
import me.rerere.rikkahub.shizuku.ShizukuStatus
import me.rerere.rikkahub.subagent.SubAgentModelResolver
import me.rerere.rikkahub.subagent.SubAgentProfile
import me.rerere.rikkahub.workflow.repository.WorkflowRepository
import me.rerere.rikkahub.browser.BrowserPreferences
import me.rerere.rikkahub.browser.BrowserToolDefaults
import java.net.InetAddress
import java.io.File
import me.rerere.rikkahub.R

/**
 * Each row that depends on a system capability (a permission, an OS-level service binding,
 * Termux being installed) is "tool-aware": if no enabled tool needs the capability, the
 * row drops to INFO with a "not required" subtitle so the screen doesn't drown the user
 * in WARN noise about features they don't use.
 *
 * The map below records which [LocalToolOption] groups depend on which capability. The
 * answer comes from the tool registration code in `LocalTools.kt` — when a new tool is
 * added that needs a capability, also add its option here.
 */
private object Capability {
    val Notifications: Set<LocalToolOption> = setOf(
        LocalToolOption.Notification,        // post_notification tool
        LocalToolOption.TelegramBot,         // FGS notification
        LocalToolOption.CronJobs,            // CronJobWorker FGS notification
        LocalToolOption.Workflows,           // WorkflowTimeCronWorker FGS notification
    )
    val FineLocation: Set<LocalToolOption> = setOf(
        LocalToolOption.Location,            // get_location, geocode tools
        LocalToolOption.WifiInfo,            // SSID/BSSID on Android 10+
        LocalToolOption.Workflows,           // geofence_enter / geofence_exit triggers
    )
    val NotificationListener: Set<LocalToolOption> = setOf(
        LocalToolOption.NotificationListener,
        LocalToolOption.Workflows,           // notification_received trigger
    )
    val Accessibility: Set<LocalToolOption> = setOf(
        LocalToolOption.ScreenAutomation,    // take_screenshot, swipe, click_at, scroll, gesture
    )
    val Termux: Set<LocalToolOption> = setOf(
        LocalToolOption.Termux,
        LocalToolOption.SpeechToText,        // transcribe_audio_file uses Termux + whisper.cpp
        LocalToolOption.Ssh,                 // ssh_exec calls into termux ssh
    )
    val BatteryWhitelist: Set<LocalToolOption> = setOf(
        LocalToolOption.TelegramBot,         // long-poll loop
        LocalToolOption.CronJobs,            // worker fires
        LocalToolOption.Workflows,           // trigger receivers + cron worker
    )
    val AllFiles: Set<LocalToolOption> = setOf(
        LocalToolOption.Files,               // file_read / file_write to arbitrary paths
    )
    val Browser: Set<LocalToolOption> = setOf(
        LocalToolOption.Browser,             // 17 browser tools (in-app WebView)
    )
    // Phase 25 — Phase 3 second cut.
    val SendSms: Set<LocalToolOption> = setOf(
        LocalToolOption.SmsSend,
    )
    val Nfc: Set<LocalToolOption> = setOf(
        LocalToolOption.Nfc,
    )
    // Permissions that previously had no Doctor check at all. Each is gated on the tool that
    // actually needs it, so a denied perm only WARNs when its feature is enabled (opt-in) and
    // stays INFO otherwise. Closes the "Doctor reported all-clear while overlay etc. were denied"
    // gap.
    val Overlay: Set<LocalToolOption> = setOf(
        LocalToolOption.ScreenAutomation,    // "agent is working" overlay during automation
    )
    val WriteSettings: Set<LocalToolOption> = setOf(
        LocalToolOption.Brightness,          // set_brightness writes Settings.System
    )
    val BluetoothConnect: Set<LocalToolOption> = setOf(
        LocalToolOption.Workflows,           // workflow Bluetooth triggers read paired-device state
    )
    val NearbyWifi: Set<LocalToolOption> = setOf(
        LocalToolOption.WifiInfo,            // WiFi scan/info on Android 13+
    )
    val BackgroundLocation: Set<LocalToolOption> = setOf(
        LocalToolOption.Workflows,           // geofence triggers fire while the app is closed
    )
    val Shizuku: Set<LocalToolOption> = setOf(
        LocalToolOption.Shizuku,             // shizuku_exec runs shell commands with Shizuku's privileges
    )
}

/** Friendly name for the row's "needed by:" subtitle. */
private fun LocalToolOption.shortName(ctx: Context): String = when (this) {
    LocalToolOption.Location -> ctx.getString(R.string.tool_name_location)
    LocalToolOption.WifiInfo -> ctx.getString(R.string.tool_name_wifi)
    LocalToolOption.NotificationListener -> ctx.getString(R.string.tool_name_notif_listener)
    LocalToolOption.ScreenAutomation -> ctx.getString(R.string.tool_name_screen_automation)
    LocalToolOption.Termux -> ctx.getString(R.string.tool_name_termux)
    LocalToolOption.SpeechToText -> ctx.getString(R.string.tool_name_stt)
    LocalToolOption.Ssh -> ctx.getString(R.string.tool_name_ssh)
    LocalToolOption.TelegramBot -> ctx.getString(R.string.tool_name_tg_bot)
    LocalToolOption.CronJobs -> ctx.getString(R.string.tool_name_cron)
    LocalToolOption.Workflows -> ctx.getString(R.string.tool_name_workflows)
    LocalToolOption.Notification -> ctx.getString(R.string.tool_name_notification)
    LocalToolOption.Files -> ctx.getString(R.string.tool_name_files)
    LocalToolOption.Browser -> ctx.getString(R.string.tool_name_browser)
    LocalToolOption.SmsSend -> ctx.getString(R.string.tool_name_sms_send)
    LocalToolOption.Wallpaper -> ctx.getString(R.string.tool_name_wallpaper)
    LocalToolOption.Keystore -> ctx.getString(R.string.tool_name_keystore)
    LocalToolOption.Nfc -> ctx.getString(R.string.tool_name_nfc)
    LocalToolOption.ExternalStorage -> ctx.getString(R.string.tool_name_ext_storage)
    LocalToolOption.Archive -> ctx.getString(R.string.tool_name_archive)
    LocalToolOption.Shizuku -> ctx.getString(R.string.tool_name_shizuku)
    LocalToolOption.AppLauncher -> ctx.getString(R.string.tool_name_app_launcher)
    LocalToolOption.KeyboardControl -> ctx.getString(R.string.tool_name_keyboard)
    else -> this::class.simpleName ?: "?"
}

/**
 * Run every diagnostic check. Returns the flat list — the Doctor screen groups by
 * [DoctorCheck.category].
 *
 * Most checks are cheap (Settings.Secure reads, package manager queries, in-memory state)
 * but a few do I/O (DB integrity PRAGMA, DNS resolve). Run on Dispatchers.IO at the call
 * site; the function itself is suspending so individual probes can withTimeoutOrNull.
 *
 * Adding a new check: append to the appropriate `runXxxChecks` block. Each helper function
 * returns either a single check or a list. Keep checks short — one concern per row.
 */
class DoctorChecks(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val telegramPrefs: TelegramBotPreferences,
    private val workflowRepository: WorkflowRepository,
    private val scheduledJobRepository: ScheduledJobRepository,
    private val scheduledJobRunRepository: ScheduledJobRunRepository,
    private val conversationRepository: ConversationRepository,
    private val database: AppDatabase,
    // Pass 3: per-tool browser toggle store. Used by the browser write-tools-enabled INFO
    // row so the user can spot-check which side-effecting tools are currently switched on.
    // Optional + nullable so callers that don't construct this DoctorChecks via the DI
    // graph (a few legacy tests) keep compiling — the row is silently skipped when null.
    private val browserPreferences: BrowserPreferences? = null,
    // Phase 25 — SAF tree-grant store, backs the "granted directories" Doctor row.
    // Nullable + defaulted so legacy test paths that don't build the full DI graph compile.
    private val storageVolumeGrantStore: me.rerere.rikkahub.data.storage.StorageVolumeGrantStore? = null,
    // Surface the persisted LiteRT accelerator decision so the user can see whether their
    // local models actually engaged GPU/NPU or silently fell back to CPU.
    // Nullable + defaulted same as the others above for legacy test path compatibility.
    private val localRuntimePreferences: me.rerere.locallm.LocalRuntimePreferences? = null,
    // Doctor refresh: backs the skills.* rows. Nullable + defaulted same as the others.
    private val skillManager: SkillManager? = null,
    // Doctor refresh: backs the service.mcp_servers row. Nullable + defaulted same as the others.
    private val mcpManager: McpManager? = null,
) {
    suspend fun runAll(): List<DoctorCheck> = withContext(Dispatchers.IO) {
        // Aggregate enabled tools across every assistant. A tool is "in use" if at least
        // one assistant has its LocalToolOption switched on. The Doctor uses this to
        // decide whether a missing capability is actually a problem worth flagging.
        val settings = runCatching { settingsStore.settingsFlow.first() }.getOrNull()
        val assistants = settings?.assistants.orEmpty()
        val enabled: Set<LocalToolOption> = assistants.flatMap { it.localTools }.toSet()

        buildList {
            addAll(permissionChecks(enabled))
            addAll(serviceChecks(enabled))
            addAll(assistantChecks())
            addAll(toolGroupChecks(enabled, assistants))
            addAll(databaseChecks(enabled))
            addAll(networkChecks())
            addAll(termuxChecks(enabled))
            addAll(shizukuChecks(enabled))
            addAll(browserChecks(enabled))
            addAll(mcpChecks())
            addAll(skillsChecks())
            addAll(storageChecks())
            addAll(maintenanceChecks())
            addAll(compactionChecks())
            addAll(diagnosticsChecks(enabled))
        }
    }

    /**
     * Render the "needed by:" subtitle for a tool-aware row. If the requirement is currently
     * unsatisfied, list the enabled tools that demand it so the user knows why they should
     * care. Returns null when no enabled tool needs the capability — callers down-grade
     * severity to INFO in that case.
     */
    private fun requirersOf(cap: Set<LocalToolOption>, enabled: Set<LocalToolOption>): List<LocalToolOption> =
        cap.filter { it in enabled }

    // ----- Permissions ----------------------------------------------------------------

    private fun permissionChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        add(
            capabilityRow(
                id = "perm.notifications",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_check_post_notifications),
                cap = Capability.Notifications,
                enabled = enabled,
                granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    PermissionHelper.hasRuntime(context, listOf(Manifest.permission.POST_NOTIFICATIONS)),
                grantedDetail = context.getString(R.string.doctor_detail_granted),
                missingDetail = context.getString(R.string.doctor_detail_perm_notifications_missing),
                fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_permissions), AppRouteKey.SettingPermissions),
            )
        )
        add(
            capabilityRow(
                id = "perm.location",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_check_fine_location),
                cap = Capability.FineLocation,
                enabled = enabled,
                granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_FINE_LOCATION)),
                grantedDetail = context.getString(R.string.doctor_detail_granted),
                missingDetail = context.getString(R.string.doctor_detail_perm_location_missing),
                fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_permissions), AppRouteKey.SettingPermissions),
            )
        )
        add(
            capabilityRow(
                id = "perm.battery_opt",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_check_battery_whitelist),
                cap = Capability.BatteryWhitelist,
                enabled = enabled,
                granted = PermissionHelper.ignoresBatteryOptimizations(context),
                grantedDetail = context.getString(R.string.doctor_detail_perm_battery_granted),
                missingDetail = context.getString(R.string.doctor_detail_perm_battery_missing),
                fix = FixAction.OpenIntent(
                    label = context.getString(R.string.doctor_fix_request_whitelist),
                    intent = PermissionHelper.requestIgnoreBatteryOptimizationsIntent(context),
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.notification_listener",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_check_notif_listener_access),
                cap = Capability.NotificationListener,
                enabled = enabled,
                granted = PermissionHelper.hasNotificationListener(context),
                grantedDetail = context.getString(R.string.doctor_detail_perm_notifications_granted),
                missingDetail = context.getString(R.string.doctor_detail_perm_notifications_missing),
                fix = FixAction.OpenIntent(
                    label = context.getString(R.string.doctor_fix_open_settings),
                    intent = PermissionHelper.notificationListenerSettingsIntent(),
                ),
            )
        )
        add(
            capabilityRow(
                id = "perm.accessibility",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_check_accessibility),
                cap = Capability.Accessibility,
                enabled = enabled,
                granted = PermissionHelper.hasAccessibilityService(context),
                grantedDetail = context.getString(R.string.doctor_detail_perm_accessibility_granted),
                missingDetail = context.getString(R.string.doctor_detail_perm_accessibility_missing),
                fix = FixAction.OpenIntent(
                    label = context.getString(R.string.doctor_fix_open_settings),
                    intent = PermissionHelper.accessibilitySettingsIntent(),
                ),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(
                capabilityRow(
                    id = "perm.all_files",
                    category = DoctorCategory.Permissions,
                    label = context.getString(R.string.doctor_check_all_files),
                    cap = Capability.AllFiles,
                    enabled = enabled,
                    granted = PermissionHelper.hasAllFilesAccess(context),
                    grantedDetail = context.getString(R.string.doctor_detail_perm_files_granted),
                    missingDetail = context.getString(R.string.doctor_detail_perm_files_missing),
                    fix = FixAction.OpenIntent(
                        label = context.getString(R.string.doctor_fix_open_settings),
                        intent = PermissionHelper.allFilesAccessIntent(context),
                    ),
                )
            )
        }
        // Phase 25 — SEND_SMS runtime permission row for the send_sms tool.
        add(
            capabilityRow(
                id = "perm.send_sms",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_check_send_sms),
                cap = Capability.SendSms,
                enabled = enabled,
                granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.SEND_SMS)),
                grantedDetail = context.getString(R.string.doctor_detail_granted),
                missingDetail = context.getString(R.string.doctor_detail_perm_sms_missing),
                fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_permissions), AppRouteKey.SettingPermissions),
            )
        )
        // Previously-unchecked permissions, now covered. Each is tool-aware: it only WARNs when
        // the feature that needs it is enabled, so the opt-in philosophy holds (a denied perm for
        // a disabled tool stays INFO). This is what fixes the "Doctor said all-clear while
        // Display-over-other-apps etc. were ungranted" report.
        add(
            capabilityRow(
                id = "perm.overlay",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_check_overlay),
                cap = Capability.Overlay,
                enabled = enabled,
                granted = android.provider.Settings.canDrawOverlays(context),
                grantedDetail = context.getString(R.string.doctor_detail_granted),
                missingDetail = context.getString(R.string.doctor_detail_perm_overlay_missing),
                fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_permissions), AppRouteKey.SettingPermissions),
            )
        )
        add(
            capabilityRow(
                id = "perm.write_settings",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_check_write_settings),
                cap = Capability.WriteSettings,
                enabled = enabled,
                granted = PermissionHelper.hasWriteSettings(context),
                grantedDetail = context.getString(R.string.doctor_detail_granted),
                missingDetail = context.getString(R.string.doctor_detail_perm_brightness_missing),
                fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_permissions), AppRouteKey.SettingPermissions),
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(
                capabilityRow(
                    id = "perm.bluetooth_connect",
                    category = DoctorCategory.Permissions,
                    label = context.getString(R.string.doctor_check_bluetooth),
                    cap = Capability.BluetoothConnect,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.BLUETOOTH_CONNECT)),
                    grantedDetail = context.getString(R.string.doctor_detail_granted),
                    missingDetail = context.getString(R.string.doctor_detail_perm_bluetooth_missing),
                    fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_permissions), AppRouteKey.SettingPermissions),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(
                capabilityRow(
                    id = "perm.nearby_wifi",
                    category = DoctorCategory.Permissions,
                    label = context.getString(R.string.doctor_check_nearby_wifi),
                    cap = Capability.NearbyWifi,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.NEARBY_WIFI_DEVICES)),
                    grantedDetail = context.getString(R.string.doctor_detail_granted),
                    missingDetail = context.getString(R.string.doctor_detail_perm_wifi_missing),
                    fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_permissions), AppRouteKey.SettingPermissions),
                )
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(
                capabilityRow(
                    id = "perm.background_location",
                    category = DoctorCategory.Permissions,
                    label = context.getString(R.string.doctor_check_bg_location),
                    cap = Capability.BackgroundLocation,
                    enabled = enabled,
                    granted = PermissionHelper.hasRuntime(context, listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)),
                    grantedDetail = context.getString(R.string.doctor_detail_granted),
                    missingDetail = context.getString(R.string.doctor_detail_perm_geofence_missing),
                    fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_permissions), AppRouteKey.SettingPermissions),
                )
            )
        }
        // Phase 25 — NFC combined hardware + system-toggle row. Tri-state: no hardware
        // (INFO, no fix), hardware present but disabled (WARN, open NFC settings), on (OK).
        run {
            val adapter = android.nfc.NfcAdapter.getDefaultAdapter(context)
            val nfcNeeders = requirersOf(Capability.Nfc, enabled)
            when {
                adapter == null -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = context.getString(R.string.doctor_check_nfc),
                        detail = context.getString(R.string.doctor_detail_nfc_no_hardware),
                        severity = Severity.INFO,
                    )
                )
                !adapter.isEnabled -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = context.getString(R.string.doctor_check_nfc),
                        detail = if (nfcNeeders.isEmpty())
                            context.getString(R.string.doctor_detail_nfc_off_not_required)
                        else
                            context.getString(R.string.doctor_detail_nfc_off_needed_by_3, nfcNeeders.joinToString(", ") { it.shortName(context) }),
                        severity = if (nfcNeeders.isEmpty()) Severity.INFO else Severity.WARN,
                        fix = if (nfcNeeders.isEmpty()) null else FixAction.OpenIntent(
                            label = context.getString(R.string.doctor_fix_open_nfc),
                            intent = android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        ),
                    )
                )
                else -> add(
                    DoctorCheck(
                        id = "perm.nfc_enabled",
                        category = DoctorCategory.Permissions,
                        label = context.getString(R.string.doctor_check_nfc),
                        detail = context.getString(R.string.doctor_detail_nfc_ok),
                        severity = Severity.OK,
                    )
                )
            }
        }
    }

    /**
     * Build a capability-aware Doctor row.
     *   granted = true                                  -> Severity.OK
     *   granted = false AND no enabled tool needs cap   -> Severity.INFO ("not required")
     *   granted = false AND some enabled tool needs cap -> Severity.WARN ("needed by: …")
     *
     * The Fix button is offered only when granted=false AND at least one tool needs the
     * capability — we don't push the user to grant a permission they don't currently use.
     */
    private fun capabilityRow(
        id: String,
        category: DoctorCategory,
        label: String,
        cap: Set<LocalToolOption>,
        enabled: Set<LocalToolOption>,
        granted: Boolean,
        grantedDetail: String,
        missingDetail: String,
        fix: FixAction,
    ): DoctorCheck {
        val needers = requirersOf(cap, enabled)
        val severity = when {
            granted -> Severity.OK
            needers.isEmpty() -> Severity.INFO
            else -> Severity.WARN
        }
        val detail = when {
            granted -> grantedDetail
            needers.isEmpty() -> context.getString(R.string.doctor_detail_not_required_tool)
            else -> context.getString(R.string.doctor_detail_perm_needed_by_2, missingDetail, needers.joinToString(", ") { it.shortName(context) })
        }
        return DoctorCheck(
            id = id,
            category = category,
            label = label,
            detail = detail,
            severity = severity,
            fix = if (!granted && needers.isNotEmpty()) fix else null,
        )
    }

    // ----- Background services ---------------------------------------------------------

    private suspend fun serviceChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val tg = telegramPrefs.current()
        // Telegram bot: token, enabled flag, FGS state should agree.
        if (tg.enabled) {
            add(
                DoctorCheck(
                    id = "service.telegram_token",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_check_tg_token),
                    // Don't render any portion of the token — Telegram bot tokens are
                    // formatted "<bot_id>:<secret>" and even the first 6 chars reveal the
                    // bot id, which an attacker could use to enumerate bot endpoints.
                    detail = if (tg.token.isNotBlank()) context.getString(R.string.doctor_detail_tg_token_ok, tg.token.length)
                    else context.getString(R.string.doctor_detail_tg_token_missing),
                    severity = if (tg.token.isNotBlank()) Severity.OK else Severity.FAIL,
                    fix = if (tg.token.isBlank())
                        FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_telegram), AppRouteKey.SettingTelegram)
                    else null,
                )
            )
            add(
                DoctorCheck(
                    id = "service.telegram_running",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_check_tg_fgs),
                    detail = if (TelegramBotService.isRunning) context.getString(R.string.doctor_detail_tg_running)
                    else context.getString(R.string.doctor_detail_tg_stopped),
                    severity = when {
                        TelegramBotService.isRunning -> Severity.OK
                        tg.token.isBlank() -> Severity.INFO  // token issue covers this
                        else -> Severity.FAIL
                    },
                )
            )
        } else {
            add(
                DoctorCheck(
                    id = "service.telegram_off",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_check_tg_bot),
                    detail = context.getString(R.string.doctor_detail_tg_disabled),
                    severity = Severity.INFO,
                )
            )
        }
        // Telegram proxy configuration: informational only, no reachability probe (out of
        // scope per the doctor-refresh plan). Exists so a user reporting "the bot stopped
        // working" can see at a glance whether a proxy is in the path.
        runCatching {
            add(
                DoctorCheck(
                    id = "service.telegram_proxy",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_check_tg_proxy),
                    detail = if (tg.proxyEnabled)
                        context.getString(R.string.doctor_detail_tg_proxy_configured, tg.proxyType, tg.proxyHost, tg.proxyPort)
                    else
                        context.getString(R.string.doctor_detail_tg_no_proxy),
                    severity = Severity.INFO,
                )
            )
        }.onFailure {
            add(
                DoctorCheck(
                    id = "service.telegram_proxy",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_check_tg_proxy),
                    detail = context.getString(R.string.doctor_detail_probe_failed, it::class.simpleName ?: "?", it.message ?: "?"),
                    severity = Severity.WARN,
                )
            )
        }
        // AccessibilityService binding — only flagged if a tool that needs it is enabled.
        val accNeeders = requirersOf(Capability.Accessibility, enabled)
        if (accNeeders.isNotEmpty()) {
            val bound = AccessibilityServiceHandle.isRunning()
            // The post-action screen state depends on getWindows() returning real data; a
            // service bound with a pre-update config (missing flagRetrieveInteractiveWindows)
            // reports bound=true but windows stays empty.
            val windowsOk = bound && me.rerere.rikkahub.service.RikkaAccessibilityService.instance
                ?.let { runCatching { it.windows.isNotEmpty() }.getOrDefault(false) } == true
            add(
                DoctorCheck(
                    id = "service.accessibility_bound",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_check_accessibility_bound),
                    detail = when {
                        bound && windowsOk ->
                            context.getString(R.string.doctor_detail_acc_alive, accNeeders.joinToString(", ") { it.shortName(context) })
                        bound ->
                            context.getString(R.string.doctor_detail_acc_bound_no_window)
                        PermissionHelper.hasAccessibilityService(context) ->
                            context.getString(R.string.doctor_detail_acc_enabled_not_bound)
                        else ->
                            context.getString(R.string.doctor_detail_acc_not_enabled, accNeeders.joinToString(", ") { it.shortName(context) })
                    },
                    severity = if (bound && windowsOk) Severity.OK else Severity.WARN,
                    fix = if (!bound || !windowsOk) FixAction.OpenIntent(
                        label = context.getString(R.string.doctor_fix_open_settings),
                        intent = PermissionHelper.accessibilitySettingsIntent(),
                    ) else null,
                )
            )
        }
        // NotificationListener binding — same logic.
        val nlNeeders = requirersOf(Capability.NotificationListener, enabled)
        if (nlNeeders.isNotEmpty()) {
            add(
                DoctorCheck(
                    id = "service.notification_listener_bound",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_check_notif_listener_bound),
                    detail = if (NotificationListenerHandle.isBound())
                        context.getString(R.string.doctor_detail_nl_bound, nlNeeders.joinToString(", ") { it.shortName(context) })
                    else if (PermissionHelper.hasNotificationListener(context))
                        context.getString(R.string.doctor_detail_nl_granted_not_bound)
                    else
                        context.getString(R.string.doctor_detail_nl_not_granted, nlNeeders.joinToString(", ") { it.shortName(context) }),
                    severity = when {
                        NotificationListenerHandle.isBound() -> Severity.OK
                        else -> Severity.WARN
                    },
                    fix = if (!NotificationListenerHandle.isBound()) FixAction.OpenIntent(
                        label = context.getString(R.string.doctor_fix_open_settings),
                        intent = PermissionHelper.notificationListenerSettingsIntent(),
                    ) else null,
                )
            )
        }
    }

    // ----- Active assistant ------------------------------------------------------------

    /**
     * Informational section. All rows are [Severity.INFO] — these are status rows, not
     * problem rows. The single "default assistant" row surfaces the assistant that:
     *   - New Telegram conversations use (when no explicit assistantId is configured).
     *   - Cron jobs run as (their assistantId is locked at job creation time, but new jobs
     *     inherit from the Settings default).
     *   - New in-app chats default to.
     *
     * A WARN row fires when the global assistant list is empty — that's a sign the settings
     * store was corrupted or a migration wiped the assistants list.
     *
     * A separate row shows the Telegram-bot-configured override if one is set.
     */
    private suspend fun assistantChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val assistants = settings.assistants
            val defaultAssistant = settings.getCurrentAssistant()

            // Row 1: default assistant name + id
            add(
                DoctorCheck(
                    id = "assistant.default",
                    category = DoctorCategory.AssistantInfo,
                    label = context.getString(R.string.doctor_check_default_assistant),
                    detail = if (assistants.isEmpty())
                        context.getString(R.string.doctor_detail_no_assistants)
                    else
                        "\"${defaultAssistant.name.ifBlank { context.getString(R.string.doctor_unnamed_paren) }}\" " +
                        "(id: ${defaultAssistant.id.toString().take(8)}…). " +
                        context.getString(R.string.doctor_detail_default_assistant_suffix),
                    severity = if (assistants.isEmpty()) Severity.WARN else Severity.INFO,
                    fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_assistants), AppRouteKey.Assistant),
                )
            )

            // Row 2: total assistant count
            add(
                DoctorCheck(
                    id = "assistant.count",
                    category = DoctorCategory.AssistantInfo,
                    label = context.getString(R.string.doctor_check_assistant_count),
                    detail = context.getString(R.string.doctor_detail_assistant_count, assistants.size),
                    severity = Severity.INFO,
                    fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_assistants), AppRouteKey.Assistant),
                )
            )

            // Row 3: Telegram-bot assistant override (if set)
            val tg = telegramPrefs.current()
            if (tg.enabled && tg.assistantId != null) {
                val tgAssistant = tg.assistantId.let { id ->
                    runCatching {
                        val uuid = kotlin.uuid.Uuid.parse(id)
                        assistants.find { it.id == uuid }
                    }.getOrNull()
                }
                add(
                    DoctorCheck(
                        id = "assistant.telegram_override",
                        category = DoctorCategory.AssistantInfo,
                        label = context.getString(R.string.doctor_check_tg_assistant_override),
                        detail = when {
                            tgAssistant != null ->
                                context.getString(R.string.doctor_detail_tg_assistant_override_2, tgAssistant.name.ifBlank { context.getString(R.string.doctor_unnamed_paren) }, tgAssistant.id.toString().take(8))
                            else ->
                                context.getString(R.string.doctor_detail_tg_assistant_missing_2, tg.assistantId.take(8))
                        },
                        severity = if (tgAssistant != null) Severity.INFO else Severity.WARN,
                        fix = if (tgAssistant == null)
                            FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_telegram), AppRouteKey.SettingTelegram)
                        else null,
                    )
                )
            }

            // Row 4: sub-agent profiles whose configured model no longer resolves. This is
            // the #28 failure class made visible: a profile with a stale/deleted model id
            // used to fall back to inheriting the parent's model with no indication anything
            // was wrong. Reuses SubAgentModelResolver so the Doctor can't drift from the
            // actual dispatch-time resolution logic.
            val subAgentStatus = subAgentProfileStatus(settings.subAgents, settings.providers)
            add(
                DoctorCheck(
                    id = "assistant.subagent_profiles",
                    category = DoctorCategory.AssistantInfo,
                    label = context.getString(R.string.doctor_check_subagent_profiles),
                    detail = when {
                        subAgentStatus.total == 0 -> context.getString(R.string.doctor_detail_no_subagents)
                        subAgentStatus.broken.isEmpty() ->
                            context.getString(R.string.doctor_detail_subagents_ok, subAgentStatus.total)
                        else ->
                            "${subAgentStatus.total} profile(s) configured. ${subAgentStatus.broken.size} " +
                                "reference a model that no longer resolves to a chat model of an enabled " +
                                "provider: ${subAgentStatus.broken.joinToString(", ")}."
                    },
                    severity = if (subAgentStatus.broken.isEmpty()) Severity.INFO else Severity.WARN,
                    fix = if (subAgentStatus.broken.isNotEmpty())
                        FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_subagents), AppRouteKey.SettingSubAgents)
                    else null,
                )
            )
        }
    }

    // ----- Tool groups -------------------------------------------------------------------

    /**
     * One row per backend-dependent tool group: enabled where, what it relies on, and
     * whether that backend is ready. Disabled groups are INFO with no fix (disabling is
     * a user choice); enabled groups with a missing backend are WARN with the fix.
     */
    private fun toolGroupChecks(
        enabled: Set<LocalToolOption>,
        assistants: List<me.rerere.rikkahub.data.model.Assistant>,
    ): List<DoctorCheck> = buildList {
        fun enabledBy(option: LocalToolOption): String {
            val names = assistants.filter { option in it.localTools }.map { it.name.ifBlank { context.getString(R.string.doctor_unnamed) } }
            return when {
                names.isEmpty() -> context.getString(R.string.doctor_detail_tools_disabled_all)
                names.size <= 3 -> context.getString(R.string.doctor_detail_tools_enabled_for, names.joinToString(", "))
                else -> context.getString(R.string.doctor_detail_tools_enabled_count, names.size, assistants.size)
            }
        }

        fun groupRow(
            id: String,
            option: LocalToolOption,
            label: String,
            reliesOn: String,
            backendReady: Boolean?,
            backendDetail: String,
            fix: FixAction?,
        ): DoctorCheck {
            val on = option in enabled
            val severity = when {
                !on -> Severity.INFO
                backendReady == false -> Severity.WARN
                else -> Severity.OK
            }
            val detail = buildString {
                append(enabledBy(option))
                append(" Relies on: ").append(reliesOn).append(".")
                if (on) append(" ").append(backendDetail)
            }
            return DoctorCheck(
                id = id,
                category = DoctorCategory.ToolGroups,
                label = label,
                detail = detail,
                severity = severity,
                fix = if (on && backendReady == false) fix else null,
            )
        }

        val accBound = AccessibilityServiceHandle.isRunning()
        add(
            groupRow(
                id = "tools.screen_automation",
                option = LocalToolOption.ScreenAutomation,
                label = context.getString(R.string.doctor_check_screen_automation),
                reliesOn = context.getString(R.string.doctor_relies_on_acc_overlay),
                backendReady = accBound,
                backendDetail = if (accBound) context.getString(R.string.doctor_detail_backend_acc_bound)
                else context.getString(R.string.doctor_detail_backend_acc_not_bound),
                fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_permissions), AppRouteKey.SettingPermissions),
            )
        )
        add(
            groupRow(
                id = "tools.app_launcher",
                option = LocalToolOption.AppLauncher,
                label = context.getString(R.string.doctor_check_app_launcher),
                reliesOn = context.getString(R.string.doctor_relies_on_launch),
                backendReady = true,
                backendDetail = if (accBound) context.getString(R.string.doctor_detail_backend_launch_acc_available)
                else context.getString(R.string.doctor_detail_backend_launch_no_acc),
                fix = null,
            )
        )
        val kbInstalled = me.rerere.rikkahub.data.keyboard.KeyboardApiClient(context).isKeyboardInstalled()
        val kbIme = me.rerere.rikkahub.data.keyboard.KeyboardApiClient.isEnabledAsIme(context)
        add(
            groupRow(
                id = "tools.keyboard",
                option = LocalToolOption.KeyboardControl,
                label = context.getString(R.string.doctor_check_agent_keyboard),
                reliesOn = context.getString(R.string.doctor_relies_on_keyboard),
                backendReady = kbInstalled && kbIme,
                backendDetail = when {
                    !kbInstalled -> context.getString(R.string.doctor_detail_kb_not_installed)
                    !kbIme -> context.getString(R.string.doctor_detail_kb_installed_not_enabled)
                    else -> context.getString(R.string.doctor_detail_kb_ok)
                },
                fix = FixAction.OpenIntent(
                    context.getString(R.string.doctor_fix_open_keyboard_settings),
                    android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS),
                ),
            )
        )
        // Notification listener / Termux / Shizuku / Files / Browser rows reuse the
        // readiness probes the existing category checks already compute, rather than
        // duplicating logic inline.
        add(
            groupRow(
                id = "tools.notification_listener",
                option = LocalToolOption.NotificationListener,
                label = context.getString(R.string.doctor_check_notif_listener),
                reliesOn = context.getString(R.string.doctor_relies_on_notification_listener),
                backendReady = PermissionHelper.hasNotificationListener(context),
                backendDetail = if (PermissionHelper.hasNotificationListener(context))
                    context.getString(R.string.doctor_detail_nl_granted)
                else
                    context.getString(R.string.doctor_detail_backend_nl_not_granted),
                fix = FixAction.OpenIntent(
                    context.getString(R.string.doctor_fix_open_settings),
                    PermissionHelper.notificationListenerSettingsIntent(),
                ),
            )
        )
        val termuxInstalled = isTermuxInstalled()
        add(
            groupRow(
                id = "tools.termux",
                option = LocalToolOption.Termux,
                label = context.getString(R.string.doctor_check_termux),
                reliesOn = context.getString(R.string.doctor_relies_on_termux),
                backendReady = termuxInstalled,
                backendDetail = if (termuxInstalled) context.getString(R.string.doctor_detail_termux_installed)
                else context.getString(R.string.doctor_detail_termux_not_installed),
                fix = null,
            )
        )
        val shizukuStatus = ShizukuManager.status(context)
        add(
            groupRow(
                id = "tools.shizuku",
                option = LocalToolOption.Shizuku,
                label = context.getString(R.string.doctor_check_shizuku),
                reliesOn = context.getString(R.string.doctor_relies_on_shizuku),
                backendReady = shizukuStatus == ShizukuStatus.READY,
                backendDetail = when (shizukuStatus) {
                    ShizukuStatus.READY -> context.getString(R.string.doctor_detail_shizuku_ready)
                    ShizukuStatus.NOT_INSTALLED -> context.getString(R.string.doctor_detail_shizuku_not_installed)
                    ShizukuStatus.NOT_RUNNING -> context.getString(R.string.doctor_detail_shizuku_not_running)
                    ShizukuStatus.PERMISSION_DENIED -> context.getString(R.string.doctor_detail_shizuku_no_permission)
                },
                fix = if (shizukuStatus != ShizukuStatus.READY)
                    FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_shizuku), AppRouteKey.SettingShizuku)
                else null,
            )
        )
        val filesReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || PermissionHelper.hasAllFilesAccess(context)
        add(
            groupRow(
                id = "tools.files",
                option = LocalToolOption.Files,
                label = context.getString(R.string.doctor_check_files),
                reliesOn = context.getString(R.string.doctor_relies_on_all_files),
                backendReady = filesReady,
                backendDetail = if (filesReady) context.getString(R.string.doctor_detail_files_ok)
                else context.getString(R.string.doctor_detail_files_restricted),
                fix = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    FixAction.OpenIntent(context.getString(R.string.doctor_fix_open_settings), PermissionHelper.allFilesAccessIntent(context))
                else null,
            )
        )
        val browserReady = isBrowserProfileDirReady()
        add(
            groupRow(
                id = "tools.browser",
                option = LocalToolOption.Browser,
                label = context.getString(R.string.doctor_check_browser),
                reliesOn = context.getString(R.string.doctor_relies_on_browser_profile),
                backendReady = browserReady,
                backendDetail = if (browserReady) context.getString(R.string.doctor_detail_browser_ok)
                else context.getString(R.string.doctor_detail_browser_fail),
                fix = FixAction.AutoFix(
                    label = context.getString(R.string.doctor_fix_create_dir),
                    run = {
                        val dir = browserProfileDir()
                        val created = runCatching { dir.mkdirs() }.getOrDefault(false)
                        val nowOk = dir.exists() && dir.canWrite()
                        AutoFixResult(
                            ok = nowOk,
                            message = if (nowOk) context.getString(R.string.doctor_fix_browser_created, dir.absolutePath)
                            else if (created) context.getString(R.string.doctor_fix_browser_created_not_writable)
                            else context.getString(R.string.doctor_fix_browser_mkdirs_failed),
                        )
                    },
                ),
            )
        )
    }

    /** Shared with [termuxChecks] so the "is Termux installed" probe is computed once. */
    private fun isTermuxInstalled(): Boolean =
        runCatching { context.packageManager.getPackageInfo("com.termux", 0); true }.getOrDefault(false)

    /** Shared with [browserChecks] so the profile-directory path is defined once. */
    private fun browserProfileDir(): File = File(context.filesDir, "browser-profile")

    /** Shared with [browserChecks] so the readiness probe is computed once. */
    private fun isBrowserProfileDirReady(): Boolean {
        val dir = browserProfileDir()
        val exists = runCatching { dir.exists() && dir.isDirectory }.getOrDefault(false)
        return exists && runCatching { dir.canWrite() }.getOrDefault(false)
    }

    // ----- Database --------------------------------------------------------------------

    private suspend fun databaseChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        // Migration version
        val version = runCatching { database.openHelper.readableDatabase.version }.getOrDefault(-1)
        add(
            DoctorCheck(
                id = "db.version",
                category = DoctorCategory.Database,
                label = context.getString(R.string.doctor_check_db_version),
                // Room refuses to open the DB unless the stored version matches the compiled schema;
                // if we got here, version is the live schema version (migrations ran successfully).
                detail = if (version > 0) context.getString(R.string.doctor_detail_db_version_ok, version)
                else context.getString(R.string.doctor_detail_db_version_fail),
                severity = if (version > 0) Severity.OK else Severity.WARN,
            )
        )
        // Integrity check
        val integrity = runCatching {
            withTimeoutOrNull(5_000L) {
                database.openHelper.readableDatabase
                    .query("PRAGMA integrity_check;")
                    .use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
        }.getOrNull()
        // Offer an AutoFix only when the corruption mentions message_fts — that's the one
        // we know how to repair (DROP + recreate + reindex from the messages table). For
        // any other integrity failure, surface the message and let the user decide; we
        // don't blanket-rebuild things we don't know are safe.
        val mentionsFts = integrity != null && integrity != "ok" && integrity.contains("message_fts", ignoreCase = true)
        add(
            DoctorCheck(
                id = "db.integrity",
                category = DoctorCategory.Database,
                label = context.getString(R.string.doctor_check_db_integrity),
                detail = when (integrity) {
                    null -> context.getString(R.string.doctor_detail_db_integrity_timeout)
                    "ok" -> context.getString(R.string.doctor_detail_db_integrity_ok)
                    else -> context.getString(R.string.doctor_detail_db_integrity_result, integrity)
                },
                severity = if (integrity == "ok") Severity.OK else Severity.FAIL,
                fix = if (mentionsFts) FixAction.AutoFix(
                    label = context.getString(R.string.doctor_fix_rebuild_index),
                    run = {
                        runCatching {
                            val n = conversationRepository.repairAndRebuildIndexes()
                            AutoFixResult(ok = true, message = context.getString(R.string.doctor_fix_db_rebuilt, n))
                        }.getOrElse {
                            AutoFixResult(
                                ok = false,
                                message = context.getString(R.string.doctor_fix_db_repair_failed, it::class.simpleName ?: "?", it.message ?: "?"),
                            )
                        }
                    },
                ) else null,
            )
        )
        // Workflows summary
        runCatching {
            val all = workflowRepository.observeAll().first()
            val enabled = all.count { it.entity.enabled }
            add(
                DoctorCheck(
                    id = "db.workflows",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_workflows),
                    detail = context.getString(R.string.doctor_detail_tools_count, all.size, enabled),
                    severity = Severity.INFO,
                    fix = if (all.isNotEmpty())
                        FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_workflows), AppRouteKey.SettingWorkflows)
                    else null,
                )
            )
        }
        // Scheduled jobs summary
        runCatching {
            val all = scheduledJobRepository.getAll()
            val enabled = all.count { it.enabled }
            add(
                DoctorCheck(
                    id = "db.scheduled_jobs",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_scheduled_jobs),
                    detail = context.getString(R.string.doctor_detail_tools_count, all.size, enabled),
                    severity = Severity.INFO,
                    fix = if (all.isNotEmpty())
                        FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_scheduled_jobs), AppRouteKey.SettingScheduledJobs)
                    else null,
                )
            )
        }
        // Stranded run rows (started but never finished — process killed mid-run)
        runCatching {
            val stranded = scheduledJobRunRepository.getStranded(System.currentTimeMillis() - 30 * 60_000L)
            add(
                DoctorCheck(
                    id = "db.stranded_runs",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_stranded_runs),
                    detail = if (stranded.isEmpty())
                        context.getString(R.string.doctor_detail_worker_clean)
                    else
                        context.getString(R.string.doctor_detail_worker_stranded, stranded.size),
                    severity = if (stranded.isEmpty()) Severity.OK else Severity.WARN,
                )
            )
        }
        // Phase 25 — SAF granted-directories live count for the ExternalStorage tool.
        // Reconciles against the OS persisted-permission list so revoked grants drop off.
        val store = storageVolumeGrantStore
        if (store != null) {
            runCatching {
                val externalStorageEnabled = enabled.contains(LocalToolOption.ExternalStorage)
                val grants = store.reconcile()
                add(
                    DoctorCheck(
                        id = "storage.granted_directories",
                        category = DoctorCategory.Database,
                        label = context.getString(R.string.doctor_check_granted_dirs),
                        detail = when {
                            !externalStorageEnabled && grants.isEmpty() ->
                                context.getString(R.string.doctor_detail_storage_not_enabled)
                            grants.isEmpty() ->
                                context.getString(R.string.doctor_detail_storage_no_grants)
                            else ->
                                context.getString(R.string.doctor_detail_storage_grants, grants.size, grants.joinToString(", ") { it.displayName })
                        },
                        severity = if (externalStorageEnabled && grants.isNotEmpty())
                            Severity.OK else Severity.INFO,
                    )
                )
            }
        }
    }

    // ----- Network & providers ---------------------------------------------------------

    private suspend fun networkChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val provs = settings.providers
            val configured = provs.count { p ->
                when (p) {
                    is me.rerere.ai.provider.ProviderSetting.OpenAI -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.Google -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.Claude -> p.apiKey.isNotBlank()
                    is me.rerere.ai.provider.ProviderSetting.AICore -> p.enabled  // on-device, no API key
                    // Local provider (LiteRT): usable when enabled AND at least one model has
                    // been loaded/downloaded. A disabled provider with no models is the factory
                    // default — don't count it.
                    is me.rerere.ai.provider.ProviderSetting.LiteRtLocal -> p.enabled && p.models.isNotEmpty()
                    // Local provider (llama.cpp): usable when enabled AND at least one model
                    // has been loaded, same criterion as LiteRT above.
                    is me.rerere.ai.provider.ProviderSetting.LlamaCppLocal -> p.enabled && p.models.isNotEmpty()
                    is me.rerere.ai.provider.ProviderSetting.Codex -> p.enabled  // OAuth, no API key
                    is me.rerere.ai.provider.ProviderSetting.Grok -> p.enabled  // OAuth, no API key
                    is me.rerere.ai.provider.ProviderSetting.GeminiOAuth -> p.enabled  // OAuth, no API key
                }
            }
            add(
                DoctorCheck(
                    id = "net.providers",
                    category = DoctorCategory.Network,
                    label = context.getString(R.string.doctor_check_providers),
                    detail = context.getString(R.string.doctor_detail_providers_count, configured, provs.size),
                    severity = if (configured > 0) Severity.OK else Severity.WARN,
                    fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_providers), AppRouteKey.SettingProvider),
                )
            )
        }
        // LiteRT accelerator status. The runtime's GPU -> CPU fallback is silent today:
        // if the device's OpenCL/OpenGL delegate fails to init (e.g. MLDrift's
        // "CreateSharedMemoryManager is not implemented" on some Adreno drivers), the
        // model loads on CPU and the user has no UI indication. LiteRtProvider now
        // persists the actually-chosen accelerator after every load; surface that here
        // so the user can confirm GPU is engaged.
        runCatching {
            val prefs = localRuntimePreferences
            if (prefs != null) {
                val accel = prefs.acceleratorFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                val forceCpu = prefs.forceCpu(me.rerere.locallm.LocalRuntime.LiteRT)
                val detail = when {
                    accel == null -> context.getString(R.string.doctor_detail_accel_not_probed)
                    forceCpu && accel == "CPU" ->
                        context.getString(R.string.doctor_detail_accel_cpu_full)
                    accel == "CPU" ->
                        context.getString(R.string.doctor_detail_accel_cpu_fallback_full)
                    accel == "GPU" -> context.getString(R.string.doctor_detail_accel_gpu_full)
                    accel == "QNN" || accel == "NPU" -> context.getString(R.string.doctor_detail_accel_npu_full)
                    accel == "NNAPI" -> context.getString(R.string.doctor_detail_accel_nnapi)
                    else -> context.getString(R.string.doctor_detail_accel_unknown, accel)
                }
                val severity = when {
                    accel == null -> Severity.INFO
                    accel == "CPU" && !forceCpu -> Severity.WARN  // unexpected fallback
                    else -> Severity.OK
                }
                add(
                    DoctorCheck(
                        id = "net.litert_accel",
                        category = DoctorCategory.Network,
                        label = context.getString(R.string.doctor_check_litert_accel),
                        detail = detail,
                        severity = severity,
                        fix = FixAction.OpenAppRoute(
                            context.getString(R.string.doctor_fix_open_local_litert),
                            AppRouteKey.SettingProvider,
                        ),
                    )
                )
                // Performance telemetry — surface the last-known prefill/decode tok/s for
                // each model so the user (and the support team triaging a slow report)
                // can see at a glance whether the runtime is hitting expected rates. We
                // INFO when present; WARN never (the model could legitimately be slow on a
                // weak device — the user knows their hardware better than we do).
                val perfMap = prefs.perfTelemetryFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                if (perfMap.isNotEmpty()) {
                    val rows = perfMap.values.sortedByDescending { it.sampledAtMs }
                    val detail = rows.joinToString("\n") { s ->
                        val spec = if (s.specDecodingEngaged) context.getString(R.string.doctor_detail_mtp_on) else ""
                        context.getString(R.string.doctor_detail_model_rates_row, s.modelId, "%.1f".format(s.prefillTps).toFloat(), "%.1f".format(s.decodeTps).toFloat(), spec)
                    }
                    add(
                        DoctorCheck(
                            id = "net.litert_perf",
                            category = DoctorCategory.Network,
                            label = context.getString(R.string.doctor_check_litert_perf),
                            detail = context.getString(R.string.doctor_detail_model_rates_full, detail),
                            severity = Severity.INFO,
                            fix = FixAction.OpenAppRoute(
                                context.getString(R.string.doctor_fix_open_local_litert),
                                AppRouteKey.SettingProvider,
                            ),
                        )
                    )
                }
                // Vision-encoder availability — surface any models the runtime had to drop
                // to text-only on this device's GPU. The provider's vision-CPU fallback
                // means a multimodal model still works for chat, but the user has lost
                // image input on this chip. Most common cause: Adreno 7xx + restrictive
                // OEM linker namespace (One UI / OriginOS) hitting upstream LiteRT-LM
                // issue #2292 (gpu_backend_opengl.cc:CreateSharedMemoryManager UNIMPLEMENTED).
                val visionUnavailable = prefs
                    .visionUnavailableFlow(me.rerere.locallm.LocalRuntime.LiteRT).first()
                if (visionUnavailable.isNotEmpty()) {
                    add(
                        DoctorCheck(
                            id = "net.litert_vision",
                            category = DoctorCategory.Network,
                            label = context.getString(R.string.doctor_check_litert_vision),
                            detail = context.getString(R.string.doctor_detail_vision_unavailable_full, visionUnavailable.joinToString(", ")),
                            severity = Severity.WARN,
                            fix = FixAction.OpenAppRoute(
                                context.getString(R.string.doctor_fix_open_local_litert),
                                AppRouteKey.SettingProvider,
                            ),
                        )
                    )
                }
            }
        }
        // llama.cpp installed-model check. Unlike LiteRT, this runtime is CPU-only with no
        // vision encoder and nothing to probe for an accelerator, so there is no analogue
        // to net.litert_accel/_perf/_vision here — those would be reporting on things that
        // cannot vary on this build. The one thing that genuinely can go wrong: a model
        // registered in prefs whose backing file was moved, deleted, or lives on a volume
        // that got unmounted. Own id (net.llamacpp_models) so it can't collide with the
        // net.litert_* rows above.
        runCatching {
            val prefs = localRuntimePreferences
            if (prefs != null) {
                val installed = prefs.installedModels(me.rerere.locallm.LocalRuntime.LlamaCpp)
                val status = llamaCppModelStatus(installed)
                val detail = when {
                    status.total == 0 -> context.getString(R.string.doctor_detail_no_llama_models)
                    status.missing.isEmpty() ->
                        "${status.total} model(s) installed, all present on disk."
                    else ->
                        context.getString(R.string.doctor_detail_llamacpp_missing, status.missing.size, status.total, status.missing.joinToString(", "))
                }
                add(
                    DoctorCheck(
                        id = "net.llamacpp_models",
                        category = DoctorCategory.Network,
                        label = context.getString(R.string.doctor_check_llamacpp_models),
                        detail = detail,
                        severity = when {
                            status.total == 0 -> Severity.INFO
                            status.missing.isEmpty() -> Severity.OK
                            else -> Severity.WARN
                        },
                        fix = if (status.missing.isNotEmpty()) FixAction.OpenAppRoute(
                            context.getString(R.string.doctor_fix_open_local_llama),
                            AppRouteKey.SettingProvider,
                        ) else null,
                    )
                )
            }
        }
        // DNS sanity — confirms the OkHttp clients aren't stuck on a stale resolver.
        val dnsOk = withTimeoutOrNull(2_500L) {
            runCatching { InetAddress.getByName("dns.google") != null }.getOrDefault(false)
        } == true
        add(
            DoctorCheck(
                id = "net.dns",
                category = DoctorCategory.Network,
                label = context.getString(R.string.doctor_check_dns),
                detail = if (dnsOk) context.getString(R.string.doctor_detail_dns_ok)
                else context.getString(R.string.doctor_detail_dns_fail),
                severity = if (dnsOk) Severity.OK else Severity.WARN,
            )
        )
    }

    // ----- Termux ----------------------------------------------------------------------

    private fun termuxChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val needers = requirersOf(Capability.Termux, enabled)
        // Skip the entire category when no Termux-using tool is enabled — keeps the
        // Doctor screen focused on what the user actually configured.
        if (needers.isEmpty()) return@buildList

        val termuxInstalled = isTermuxInstalled()
        add(
            DoctorCheck(
                id = "termux.installed",
                category = DoctorCategory.Termux,
                label = context.getString(R.string.doctor_check_termux_installed),
                detail = if (termuxInstalled) context.getString(R.string.doctor_detail_termux_installed_2)
                else context.getString(R.string.doctor_detail_termux_not_installed_2, needers.joinToString(", ") { it.shortName(context) }),
                severity = if (termuxInstalled) Severity.OK else Severity.WARN,
            )
        )
        if (termuxInstalled) {
            val runCommandPerm = runCatching {
                val perm = "com.termux.permission.RUN_COMMAND"
                context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)
            add(
                DoctorCheck(
                    id = "termux.run_command",
                    category = DoctorCategory.Termux,
                    label = context.getString(R.string.doctor_check_termux_perm),
                    detail = if (runCommandPerm) context.getString(R.string.doctor_detail_termux_run_command_granted)
                    else context.getString(R.string.doctor_detail_termux_run_command_not_granted),
                    severity = if (runCommandPerm) Severity.OK else Severity.WARN,
                )
            )
        }
    }

    // ----- Shizuku ------------------------------------------------------------------------

    /**
     * Doctor refresh: three rows tracking Shizuku the same way [termuxChecks] tracks Termux,
     * a companion privileged-helper app the `shizuku_exec` tool depends on. Unlike Termux,
     * these rows are always emitted (not skipped when unused) so the settings page shows
     * "not required" rather than silently omitting the whole category; severity still
     * downgrades to INFO when no enabled tool needs Shizuku, matching every other
     * capability-aware row in this file.
     *
     * All three derive their severity from a single [ShizukuStatus], computed once via
     * [ShizukuManager.status] (which itself delegates to
     * [me.rerere.rikkahub.shizuku.ShizukuStatusMapper.compute]) rather than re-deriving the
     * installed/running/permission precedence here.
     */
    private fun shizukuChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        runCatching {
            val needers = requirersOf(Capability.Shizuku, enabled)
            val status = ShizukuManager.status(context)
            val fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_shizuku), AppRouteKey.SettingShizuku)

            add(
                DoctorCheck(
                    id = "shizuku.installed",
                    category = DoctorCategory.Shizuku,
                    label = context.getString(R.string.doctor_check_shizuku_installed),
                    detail = when {
                        status != ShizukuStatus.NOT_INSTALLED ->
                            context.getString(R.string.doctor_detail_shizuku_present)
                        needers.isEmpty() -> "Not installed. Not required by any enabled tool."
                        else -> context.getString(R.string.doctor_detail_shizuku_not_installed_needed, needers.joinToString(", ") { it.shortName(context) })
                    },
                    severity = when {
                        status != ShizukuStatus.NOT_INSTALLED -> Severity.OK
                        needers.isEmpty() -> Severity.INFO
                        else -> Severity.WARN
                    },
                    fix = if (status == ShizukuStatus.NOT_INSTALLED && needers.isNotEmpty()) fix else null,
                )
            )
            add(
                DoctorCheck(
                    id = "shizuku.running",
                    category = DoctorCategory.Shizuku,
                    label = context.getString(R.string.doctor_check_shizuku_running),
                    detail = when (status) {
                        ShizukuStatus.NOT_INSTALLED -> context.getString(R.string.doctor_detail_shizuku_cant_check)
                        ShizukuStatus.NOT_RUNNING ->
                            if (needers.isEmpty()) "Binder not alive. Not required by any enabled tool."
                            else context.getString(R.string.doctor_detail_shizuku_binder_not_alive, needers.joinToString(", ") { it.shortName(context) })
                        else -> context.getString(R.string.doctor_detail_shizuku_binder_alive)
                    },
                    severity = when (status) {
                        ShizukuStatus.NOT_INSTALLED -> Severity.INFO
                        ShizukuStatus.NOT_RUNNING -> if (needers.isEmpty()) Severity.INFO else Severity.WARN
                        else -> Severity.OK
                    },
                    fix = if (status == ShizukuStatus.NOT_RUNNING && needers.isNotEmpty()) fix else null,
                )
            )
            add(
                DoctorCheck(
                    id = "shizuku.permission",
                    category = DoctorCategory.Shizuku,
                    label = context.getString(R.string.doctor_check_shizuku_perm),
                    detail = when (status) {
                        ShizukuStatus.NOT_INSTALLED, ShizukuStatus.NOT_RUNNING ->
                            context.getString(R.string.doctor_detail_shizuku_not_applicable)
                        ShizukuStatus.PERMISSION_DENIED ->
                            if (needers.isEmpty()) "Not granted. Not required by any enabled tool."
                            else context.getString(R.string.doctor_detail_shizuku_not_granted_needed, needers.joinToString(", ") { it.shortName(context) })
                        ShizukuStatus.READY -> context.getString(R.string.doctor_detail_shizuku_granted)
                    },
                    severity = when (status) {
                        ShizukuStatus.NOT_INSTALLED, ShizukuStatus.NOT_RUNNING -> Severity.INFO
                        ShizukuStatus.PERMISSION_DENIED -> if (needers.isEmpty()) Severity.INFO else Severity.WARN
                        ShizukuStatus.READY -> Severity.OK
                    },
                    fix = if (status == ShizukuStatus.PERMISSION_DENIED && needers.isNotEmpty()) fix else null,
                )
            )
        }.onFailure {
            add(
                DoctorCheck(
                    id = "shizuku.probe_failed",
                    category = DoctorCategory.Shizuku,
                    label = context.getString(R.string.doctor_check_shizuku),
                    detail = context.getString(R.string.doctor_detail_probe_failed, it::class.simpleName ?: "?", it.message ?: "?"),
                    severity = Severity.WARN,
                )
            )
        }
    }

    // ----- MCP servers ----------------------------------------------------------------------

    /**
     * Doctor refresh: read-only summary of configured MCP servers against
     * [McpManager.syncingStatus]: the live in-memory connection state cache. Never
     * initiates a connection; a server the app hasn't tried to sync yet just reads as "not
     * connected" here, same as one that failed.
     */
    private suspend fun mcpChecks(): List<DoctorCheck> = buildList {
        val manager = mcpManager ?: return@buildList
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val statuses = manager.syncingStatus.value
            val rows = settings.mcpServers.map { server ->
                val name = server.commonOptions.name.ifBlank { server.id.toString().take(8) }
                Triple(name, server.commonOptions.enable, statuses[server.id] is McpStatus.Connected)
            }
            val summary = mcpServerSummary(rows)
            add(
                DoctorCheck(
                    id = "service.mcp_servers",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_check_mcp_servers),
                    detail = when {
                        summary.configured == 0 -> context.getString(R.string.doctor_detail_no_mcp_servers)
                        summary.enabledNotConnected.isEmpty() ->
                            "${summary.configured} configured, ${summary.enabled} enabled, ${summary.connected} connected."
                        else ->
                            "${summary.configured} configured, ${summary.enabled} enabled, ${summary.connected} connected. " +
                                context.getString(R.string.doctor_detail_mcp_not_connected, summary.enabledNotConnected.joinToString(", "))
                    },
                    severity = if (summary.enabledNotConnected.isEmpty()) Severity.INFO else Severity.WARN,
                    fix = if (summary.configured > 0)
                        FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_mcp), AppRouteKey.SettingMcp)
                    else null,
                )
            )
        }.onFailure {
            add(
                DoctorCheck(
                    id = "service.mcp_servers",
                    category = DoctorCategory.Services,
                    label = context.getString(R.string.doctor_check_mcp_servers),
                    detail = context.getString(R.string.doctor_detail_probe_failed, it::class.simpleName ?: "?", it.message ?: "?"),
                    severity = Severity.WARN,
                )
            )
        }
    }

    // ----- Skills ---------------------------------------------------------------------------

    /**
     * Doctor refresh: installed-skill count (bundled vs user-added, told apart by the
     * `.seeded` sentinel [SkillManager.seedDefaultSkillsIfNeeded] writes) plus bundled-seed
     * health, whether the on-disk `.core-bundled-hash` sentinel still matches what the
     * currently-installed APK would seed. A stale sentinel is what froze bundled skill
     * updates before; it means seeding failed silently rather than that a re-seed is merely
     * pending, since [me.rerere.rikkahub.RikkaHubApp] runs the seed pass on every launch.
     */
    private fun skillsChecks(): List<DoctorCheck> = buildList {
        val mgr = skillManager ?: return@buildList
        val skillsResult = runCatching { mgr.listSkills() }
        val skills = skillsResult.getOrNull()
        if (skills == null) {
            val err = skillsResult.exceptionOrNull()
            val detail = context.getString(R.string.doctor_detail_probe_failed, err?.let { it::class.simpleName } ?: "?", err?.message ?: "?")
            add(DoctorCheck("skills.installed", DoctorCategory.Database, "Installed skills", detail, Severity.WARN))
            add(DoctorCheck("skills.seed", DoctorCategory.Database, "Bundled skill seed health", detail, Severity.WARN))
            return@buildList
        }

        runCatching {
            val bundledCount = skills.count { it.skillDir.resolve(".seeded").exists() }
            val userCount = skills.size - bundledCount
            add(
                DoctorCheck(
                    id = "skills.installed",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_skills_installed),
                    detail = context.getString(R.string.doctor_detail_skills_count, skills.size, bundledCount, userCount),
                    severity = Severity.INFO,
                    fix = FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_skills), AppRouteKey.Skills),
                )
            )
        }.onFailure {
            add(
                DoctorCheck(
                    id = "skills.installed",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_skills_installed),
                    detail = context.getString(R.string.doctor_detail_probe_failed, it::class.simpleName ?: "?", it.message ?: "?"),
                    severity = Severity.WARN,
                )
            )
        }

        runCatching {
            val bundledNames = mgr.bundledSkillNames()
            // Bundled-name match alone isn't ownership: a user can delete a bundled skill and
            // recreate a same-named one via saveSkill without writing .seeded/.core-bundled-hash.
            // Mirror decideSeedAction's ownedByUs rule here: core skills (autoLoad) are
            // unconditionally ours, non-core skills are ours only if we actually seeded them.
            val entries = skills.filter { skill ->
                skill.skillDir.name in bundledNames &&
                    (skill.autoLoad || skill.skillDir.resolve(".seeded").exists())
            }.map { skill ->
                val hashFile = skill.skillDir.resolve(".core-bundled-hash")
                val stored = if (hashFile.exists())
                    runCatching { hashFile.readText().trim() }.getOrNull()
                else null
                val current = runCatching { mgr.bundledSkillAssetHash(skill.skillDir.name) }.getOrNull()
                SkillSeedEntry(skill.skillDir.name, isBundled = true, storedHash = stored, currentHash = current)
            }
            val stale = staleSeedSkillNames(entries)
            add(
                DoctorCheck(
                    id = "skills.seed",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_skills_seed),
                    detail = if (stale.isEmpty())
                        context.getString(R.string.doctor_detail_skills_seed_ok)
                    else
                        context.getString(R.string.doctor_detail_skills_seed_stale, stale.joinToString(", ")),
                    severity = if (stale.isEmpty()) Severity.OK else Severity.WARN,
                    fix = if (stale.isNotEmpty())
                        FixAction.OpenAppRoute(context.getString(R.string.doctor_fix_open_skills), AppRouteKey.Skills)
                    else null,
                )
            )
        }.onFailure {
            add(
                DoctorCheck(
                    id = "skills.seed",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_skills_seed),
                    detail = context.getString(R.string.doctor_detail_probe_failed, it::class.simpleName ?: "?", it.message ?: "?"),
                    severity = Severity.WARN,
                )
            )
        }
    }

    // ----- Storage (gallery + workspace) -----------------------------------------------------

    /**
     * Doctor refresh: two read-only storage rows.
     *  - `storage.gallery_orphans`: the #39 bug class made visible, generated-image DB
     *    records ([me.rerere.rikkahub.data.db.entity.GenMediaEntity]) whose backing file
     *    under `filesDir/images/` no longer exists.
     *  - `storage.workspace`: health of the agent's `~` sandbox ([AgentWorkspace]), exists,
     *    is a directory, is writable, plus file count and size via the existing
     *    [directorySize] / [humanBytes] helpers.
     */
    private suspend fun storageChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val entities = database.genMediaDao().getAllMedia()
            val absolutePaths = entities.map { File(context.filesDir, it.path).absolutePath }
            val status = galleryOrphanStatus(absolutePaths)
            add(
                DoctorCheck(
                    id = "storage.gallery_orphans",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_gallery_orphans),
                    detail = if (status.orphanCount == 0)
                        context.getString(R.string.doctor_detail_images_all_ok, status.total)
                    else
                        context.getString(R.string.doctor_detail_images_orphan, status.orphanCount, status.total),
                    severity = if (status.orphanCount > 0) Severity.WARN else Severity.OK,
                )
            )
        }.onFailure {
            add(
                DoctorCheck(
                    id = "storage.gallery_orphans",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_gallery_orphans),
                    detail = context.getString(R.string.doctor_detail_probe_failed, it::class.simpleName ?: "?", it.message ?: "?"),
                    severity = Severity.WARN,
                )
            )
        }

        runCatching {
            val root = File(AgentWorkspace.rootPath())
            val exists = root.exists() && root.isDirectory
            val writable = exists && root.canWrite()
            add(
                DoctorCheck(
                    id = "storage.workspace",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_workspace),
                    detail = when {
                        !exists -> "${root.absolutePath} does not exist."
                        !writable -> "${root.absolutePath} exists but is not writable."
                        else -> {
                            val fileCount = root.walkTopDown().count { it.isFile }
                            "${root.absolutePath}: $fileCount file(s), ${humanBytes(directorySize(root))}."
                        }
                    },
                    severity = if (!exists || !writable) Severity.WARN else Severity.INFO,
                )
            )
        }.onFailure {
            add(
                DoctorCheck(
                    id = "storage.workspace",
                    category = DoctorCategory.Database,
                    label = context.getString(R.string.doctor_check_workspace),
                    detail = context.getString(R.string.doctor_detail_probe_failed, it::class.simpleName ?: "?", it.message ?: "?"),
                    severity = Severity.WARN,
                )
            )
        }
    }

    // ----- Context compaction ---------------------------------------------------------------

    /**
     * Doctor refresh: whether auto-compaction is enabled, its configured threshold, and how
     * many conversations have ever actually been compacted, the fact we've repeatedly been
     * unable to confirm on device. Always INFO: zero-with-it-enabled is an expected state
     * (no conversation has crossed the threshold yet), not a problem.
     */
    private suspend fun compactionChecks(): List<DoctorCheck> = buildList {
        runCatching {
            val settings = settingsStore.settingsFlow.first()
            val count = database.conversationCompactionDao().countAll()
            val thresholdDesc = when (settings.autoCompactionThresholdMode) {
                AutoCompactionThresholdMode.PERCENT -> "${settings.autoCompactionThresholdPercent}% of context"
                AutoCompactionThresholdMode.TOKENS -> "${settings.autoCompactionThresholdTokensK}k tokens"
            }
            add(
                DoctorCheck(
                    id = "diag.compaction",
                    category = DoctorCategory.Diagnostics,
                    label = context.getString(R.string.doctor_check_compaction),
                    detail = when {
                        !settings.enableAutoCompaction ->
                            context.getString(R.string.doctor_detail_compaction_disabled, count)
                        count == 0 ->
                            context.getString(R.string.doctor_detail_compaction_enabled_zero, thresholdDesc)
                        else ->
                            context.getString(R.string.doctor_detail_compaction_enabled_count, thresholdDesc, count)
                    },
                    severity = Severity.INFO,
                )
            )
        }.onFailure {
            add(
                DoctorCheck(
                    id = "diag.compaction",
                    category = DoctorCategory.Diagnostics,
                    label = context.getString(R.string.doctor_check_compaction),
                    detail = context.getString(R.string.doctor_detail_probe_failed, it::class.simpleName ?: "?", it.message ?: "?"),
                    severity = Severity.WARN,
                )
            )
        }
    }

    // ----- Browser (Pass 3) ------------------------------------------------------------

    /**
     * Pass 3: Doctor rows for the in-app browser feature.
     *  - `browser.profile_dir_writable` — the WebView profile lives at
     *    `${filesDir}/browser-profile/`. The directory MUST exist + be writable for cookies
     *    to persist across app restarts. AutoFix re-creates it on demand.
     *  - `browser.write_tools_status` — informational live count of which write-tools the
     *    user has switched on. Lets a user spot-check at a glance whether `browser_type`
     *    is unintentionally enabled. INFO severity, no fix action.
     *
     * The category is [DoctorCategory.Permissions] per the spec ("Permissions / Services").
     * Both rows are emitted regardless of master Browser-toggle state, but their severity
     * downgrades to INFO when no assistant has [LocalToolOption.Browser] enabled (matches
     * the existing capability-aware pattern used throughout the file).
     */
    private fun browserChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = buildList {
        val needers = requirersOf(Capability.Browser, enabled)
        val browserNeeded = needers.isNotEmpty()

        // Row 1: profile dir writable (with AutoFix to mkdirs).
        val profileDir = browserProfileDir()
        val exists = runCatching { profileDir.exists() && profileDir.isDirectory }.getOrDefault(false)
        val writable = exists && runCatching { profileDir.canWrite() }.getOrDefault(false)
        val ok = exists && writable
        add(
            DoctorCheck(
                id = "browser.profile_dir_writable",
                category = DoctorCategory.Permissions,
                label = context.getString(R.string.doctor_check_browser_profile),
                detail = when {
                    ok && browserNeeded -> "${profileDir.absolutePath} exists and is writable — cookies persist."
                    ok -> "${profileDir.absolutePath} exists. Not required by any enabled tool."
                    !exists && browserNeeded -> context.getString(R.string.doctor_detail_browser_not_exist)
                    !exists -> "Directory does not exist. Not required by any enabled tool."
                    !writable && browserNeeded -> context.getString(R.string.doctor_detail_browser_not_writable)
                    else -> context.getString(R.string.doctor_detail_browser_not_writable_generic)
                },
                severity = when {
                    ok -> Severity.OK
                    browserNeeded -> Severity.WARN
                    else -> Severity.INFO
                },
                fix = if (!ok && browserNeeded) FixAction.AutoFix(
                    label = context.getString(R.string.doctor_fix_create_dir),
                    run = {
                        val created = runCatching { profileDir.mkdirs() }.getOrDefault(false)
                        val nowOk = profileDir.exists() && profileDir.canWrite()
                        AutoFixResult(
                            ok = nowOk,
                            message = if (nowOk) context.getString(R.string.doctor_fix_browser_created_2, profileDir.absolutePath)
                            else if (created) context.getString(R.string.doctor_fix_browser_not_writable_2)
                            else context.getString(R.string.doctor_fix_browser_mkdirs_failed),
                        )
                    },
                ) else null,
            )
        )

        // Row 2: write-tools live count (INFO only). Skipped silently if BrowserPreferences
        // wasn't injected — the row is purely informational and the test harness paths
        // that don't construct prefs shouldn't fail.
        val prefs = browserPreferences
        if (prefs != null) {
            val snapshot = runCatching { prefs.snapshotBlocking() }.getOrDefault(BrowserToolDefaults.DEFAULT_ENABLED)
            val onWriteTools = BrowserToolDefaults.WRITE_TOOLS.filter { snapshot[it] == true }
            val detail = if (onWriteTools.isEmpty())
                "Live count of side-effecting browser tools enabled: 0. None of the write tools are switched on."
            else
                "Live count of side-effecting browser tools enabled: ${onWriteTools.size} (${onWriteTools.joinToString(", ") { it.removePrefix("browser_") }})."
            add(
                DoctorCheck(
                    id = "browser.write_tools_status",
                    category = DoctorCategory.Permissions,
                    label = context.getString(R.string.doctor_check_browser_write_tools),
                    detail = detail,
                    severity = Severity.INFO,
                )
            )
        }
    }

    // ----- Maintenance -----------------------------------------------------------------

    private fun maintenanceChecks(): List<DoctorCheck> = buildList {
        // Cache size on disk
        val cacheBytes = directorySize(context.cacheDir)
        add(
            DoctorCheck(
                id = "maint.cache_size",
                category = DoctorCategory.Maintenance,
                label = context.getString(R.string.doctor_check_cache_size),
                detail = context.getString(R.string.doctor_detail_cache_using, humanBytes(cacheBytes)) +
                    if (cacheBytes > 200L * 1024 * 1024) context.getString(R.string.doctor_detail_cache_clear_hint) else context.getString(R.string.doctor_detail_cache_normal),
                severity = if (cacheBytes > 500L * 1024 * 1024) Severity.WARN else Severity.OK,
                fix = FixAction.AutoFix(
                    label = context.getString(R.string.doctor_fix_clear_cache),
                    run = {
                        val freed = clearDirectoryContents(context.cacheDir)
                        AutoFixResult(ok = true, message = context.getString(R.string.doctor_fix_cache_freed, humanBytes(freed)))
                    },
                ),
            )
        )
    }

    // ----- Diagnostics summary ---------------------------------------------------------

    private fun diagnosticsChecks(enabled: Set<LocalToolOption>): List<DoctorCheck> = listOf(
        DoctorCheck(
            id = "diag.app",
            category = DoctorCategory.Diagnostics,
            label = context.getString(R.string.doctor_check_app_build),
            detail = context.getString(R.string.doctor_detail_app_build, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.DEBUG),
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.android",
            category = DoctorCategory.Diagnostics,
            label = context.getString(R.string.doctor_check_android),
            detail = context.getString(R.string.doctor_detail_android, Build.VERSION.SDK_INT, Build.VERSION.RELEASE, Build.MANUFACTURER, Build.MODEL),
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.runtime",
            category = DoctorCategory.Diagnostics,
            label = context.getString(R.string.doctor_check_runtime),
            detail = run {
                val rt = Runtime.getRuntime()
                val freeMb = rt.freeMemory() / (1024 * 1024)
                val totalMb = rt.totalMemory() / (1024 * 1024)
                val maxMb = rt.maxMemory() / (1024 * 1024)
                context.getString(R.string.doctor_detail_heap, freeMb, totalMb, maxMb)
            },
            severity = Severity.INFO,
        ),
        DoctorCheck(
            id = "diag.enabled_tools",
            category = DoctorCategory.Diagnostics,
            label = context.getString(R.string.doctor_check_enabled_tools),
            detail = if (enabled.isEmpty()) "No local tools enabled — agentic features won't work."
            else "${enabled.size} tool group(s) enabled.",
            severity = if (enabled.isEmpty()) Severity.WARN else Severity.INFO,
        ),
    )

    private fun directorySize(dir: File): Long = runCatching {
        if (!dir.exists()) return@runCatching 0L
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }.getOrDefault(0L)

    private fun clearDirectoryContents(dir: File): Long {
        var freed = 0L
        runCatching {
            dir.listFiles()?.forEach { f ->
                freed += directorySize(f)
                f.deleteRecursively()
            }
        }
        return freed
    }

    private fun humanBytes(bytes: Long): String {
        val mb = 1024.0 * 1024
        val gb = mb * 1024
        return when {
            bytes < mb -> "%.0f KB".format(bytes / 1024.0)
            bytes < gb -> "%.1f MB".format(bytes / mb)
            else -> "%.2f GB".format(bytes / gb)
        }
    }
}

/**
 * Pure decision logic backing the "net.llamacpp_models" row: given the filename ->
 * absolute-path map from [me.rerere.locallm.LocalRuntimePreferences.installedModels],
 * report the total installed count and which filenames' backing file is no longer on
 * disk. Extracted to a top-level function (rather than left inline) so it's unit-testable
 * on the JVM without an Android Context — [DoctorChecks] itself needs one for every other
 * check, which rules out constructing it directly in a plain JUnit test.
 */
internal data class LlamaCppModelStatus(val total: Int, val missing: List<String>)

internal fun llamaCppModelStatus(installed: Map<String, String>): LlamaCppModelStatus =
    LlamaCppModelStatus(
        total = installed.size,
        missing = installed.filterValues { path -> !File(path).exists() }.keys.sorted(),
    )

/**
 * Pure decision logic backing the "storage.gallery_orphans" row: given the resolved
 * absolute paths of every generated-image DB record, report the total and how many no
 * longer have a backing file on disk (the #39 bug class). Mirrors [llamaCppModelStatus]'s
 * shape so both are unit-testable on the JVM without a Context.
 */
internal data class GalleryOrphanStatus(val total: Int, val orphanCount: Int)

internal fun galleryOrphanStatus(absolutePaths: List<String>): GalleryOrphanStatus =
    GalleryOrphanStatus(
        total = absolutePaths.size,
        orphanCount = absolutePaths.count { path -> !File(path).exists() },
    )

/**
 * Pure decision logic backing the "assistant.subagent_profiles" row: which configured
 * [SubAgentProfile]s have a `modelId` that no longer resolves to a chat model of an
 * enabled provider (the #28 failure class; it used to fail silently at dispatch time).
 * Reuses [SubAgentModelResolver.resolve] itself rather than re-deriving model lookup; a
 * profile's `modelId` is already a resolved [kotlin.uuid.Uuid], so it's passed through as
 * the resolver's string input, exactly like a `subagent_dispatch` caller would.
 */
internal data class SubAgentProfileStatus(val total: Int, val broken: List<String>)

internal fun subAgentProfileStatus(
    profiles: List<SubAgentProfile>,
    providers: List<ProviderSetting>,
): SubAgentProfileStatus = SubAgentProfileStatus(
    total = profiles.size,
    broken = profiles.filter { profile ->
        val modelId = profile.modelId ?: return@filter false
        SubAgentModelResolver.resolve(modelId.toString(), providers) is SubAgentModelResolver.Result.Failed
    }.map { it.name },
)

/**
 * Pure decision logic backing the "service.mcp_servers" row: given each configured
 * server's (name, enabled, connected) triple, report the configured/enabled/connected
 * counts and which enabled servers are not currently connected.
 */
internal data class McpServerSummary(
    val configured: Int,
    val enabled: Int,
    val connected: Int,
    val enabledNotConnected: List<String>,
)

internal fun mcpServerSummary(servers: List<Triple<String, Boolean, Boolean>>): McpServerSummary =
    McpServerSummary(
        configured = servers.size,
        enabled = servers.count { (_, enabled, _) -> enabled },
        connected = servers.count { (_, _, connected) -> connected },
        enabledNotConnected = servers.filter { (_, enabled, connected) -> enabled && !connected }
            .map { (name, _, _) -> name },
    )

/**
 * Pure decision logic backing the "skills.seed" row: a bundled skill's on-disk
 * `.core-bundled-hash` sentinel is stale when it's missing, unreadable, or doesn't match
 * the hash of what the app would currently seed. Non-bundled (user-added) entries are
 * never flagged: [isBundled] gates them out entirely, mirroring
 * [me.rerere.rikkahub.data.files.decideSeedAction]'s "never touch a directory we didn't
 * create" rule.
 */
internal data class SkillSeedEntry(
    val name: String,
    val isBundled: Boolean,
    val storedHash: String?,
    val currentHash: String?,
)

internal fun staleSeedSkillNames(entries: List<SkillSeedEntry>): List<String> =
    entries.filter { it.isBundled && it.storedHash != it.currentHash }.map { it.name }
