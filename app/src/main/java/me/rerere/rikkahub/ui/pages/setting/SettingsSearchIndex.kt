package me.rerere.rikkahub.ui.pages.setting

import androidx.annotation.StringRes
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen

/**
 * One searchable entry in the settings hub. [titleRes] prefers the destination page's own
 * top-bar title over the hub row's headline when the two differ in wording, so a search for
 * either term finds the page.
 */
data class SettingsSearchEntry(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int? = null,
    @param:StringRes val groupRes: Int,
    val route: Screen,
)

/**
 * Static mirror of the hub rows in [SettingPage] and [SettingPreferencesPage]. This list is
 * NOT derived from those pages, so it drifts if a row is added, removed, or renamed there
 * without a matching edit here (see the reminder comment in SettingPage.kt).
 */
fun settingsSearchIndex(developerMode: Boolean): List<SettingsSearchEntry> {
    val entries = mutableListOf(
        // General settings
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences,
            descriptionRes = R.string.setting_page_preferences_desc,
            groupRes = R.string.setting_page_general_settings,
            route = Screen.SettingPreferences,
        ),
        SettingsSearchEntry(
            titleRes = R.string.assistant_page_title,
            descriptionRes = R.string.setting_page_assistant_desc,
            groupRes = R.string.setting_page_general_settings,
            route = Screen.Assistant,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_extensions,
            descriptionRes = R.string.setting_page_extensions_desc,
            groupRes = R.string.setting_page_general_settings,
            route = Screen.Extensions,
        ),

        // Model and services
        SettingsSearchEntry(
            titleRes = R.string.setting_model_page_title,
            descriptionRes = R.string.setting_page_default_model_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingModels,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_providers,
            descriptionRes = R.string.setting_page_providers_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingProvider,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_search_service,
            descriptionRes = R.string.setting_page_search_service_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingSearch,
        ),
        SettingsSearchEntry(
            titleRes = R.string.speech_page_title,
            descriptionRes = R.string.setting_page_tts_service_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingSpeech,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_mcp,
            descriptionRes = R.string.setting_page_mcp_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingMcp,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_sub_agents,
            descriptionRes = R.string.setting_page_sub_agents_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingSubAgents,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_web_server,
            descriptionRes = R.string.setting_page_web_server_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingWeb,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_telegram,
            descriptionRes = R.string.setting_page_telegram_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingTelegram,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_workflows,
            descriptionRes = R.string.setting_page_workflows_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingWorkflows,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_scheduled_jobs,
            descriptionRes = R.string.setting_page_scheduled_jobs_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingScheduledJobs,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_browser,
            descriptionRes = R.string.setting_page_browser_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingBrowser,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_termux,
            descriptionRes = R.string.setting_page_termux_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingTermux,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_shizuku,
            descriptionRes = R.string.setting_page_shizuku_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingShizuku,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_doctor,
            descriptionRes = R.string.setting_page_doctor_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingDoctor,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_tool_approvals,
            descriptionRes = R.string.setting_page_tool_approvals_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingToolApprovals,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_accessibility,
            descriptionRes = R.string.setting_page_accessibility_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingAccessibility,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_notifications,
            descriptionRes = R.string.setting_page_notifications_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingNotifications,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_permissions,
            descriptionRes = R.string.setting_page_permissions_desc,
            groupRes = R.string.setting_page_model_and_services,
            route = Screen.SettingPermissions,
        ),

        // Data settings
        SettingsSearchEntry(
            titleRes = R.string.backup_page_title,
            descriptionRes = R.string.setting_page_data_backup_desc,
            groupRes = R.string.setting_page_data_settings,
            route = Screen.Backup,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_files_page_title,
            descriptionRes = R.string.setting_page_chat_storage_desc,
            groupRes = R.string.setting_page_data_settings,
            route = Screen.SettingFiles,
        ),

        // About
        SettingsSearchEntry(
            titleRes = R.string.setting_page_about,
            descriptionRes = R.string.setting_page_about_desc,
            groupRes = R.string.setting_page_about,
            route = Screen.SettingAbout,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_request_logs,
            descriptionRes = R.string.setting_page_request_logs_desc,
            groupRes = R.string.setting_page_about,
            route = Screen.Log,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_donate,
            descriptionRes = R.string.setting_page_donate_desc,
            groupRes = R.string.setting_page_about,
            route = Screen.SettingDonate,
        ),

        // Preferences sub-pages
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_theme,
            descriptionRes = R.string.setting_page_preferences_theme_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesTheme,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_notification,
            descriptionRes = R.string.setting_page_preferences_notification_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesNotification,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_general,
            descriptionRes = R.string.setting_page_preferences_general_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesGeneral,
        ),
        SettingsSearchEntry(
            titleRes = R.string.setting_page_preferences_ui,
            descriptionRes = R.string.setting_page_preferences_ui_desc,
            groupRes = R.string.setting_page_preferences,
            route = Screen.SettingPreferencesUI,
        ),
    )

    if (developerMode) {
        entries.add(
            SettingsSearchEntry(
                titleRes = R.string.accessibility_developer_options,
                descriptionRes = null,
                groupRes = R.string.settings,
                route = Screen.Developer,
            )
        )
    }

    return entries
}
