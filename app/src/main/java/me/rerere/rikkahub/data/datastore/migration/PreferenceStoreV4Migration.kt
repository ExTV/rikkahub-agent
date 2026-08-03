package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * Persists defaults for the automatic compaction and response retry settings introduced after
 * the original settings schema. The summary target intentionally remains absent when the user
 * has not entered an explicit token count, so the runtime can use the model context percentage
 * default instead of freezing a model-dependent value in DataStore.
 */
class PreferenceStoreV4Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 4
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()

        if (prefs[SettingsStore.ENABLE_AUTO_COMPACTION] == null) {
            prefs[SettingsStore.ENABLE_AUTO_COMPACTION] = false
        }
        if (prefs[SettingsStore.AUTO_COMPACTION_THRESHOLD_MODE] == null) {
            prefs[SettingsStore.AUTO_COMPACTION_THRESHOLD_MODE] = "PERCENT"
        }
        if (prefs[SettingsStore.AUTO_COMPACTION_THRESHOLD_PERCENT] == null) {
            prefs[SettingsStore.AUTO_COMPACTION_THRESHOLD_PERCENT] = 80
        }
        if (prefs[SettingsStore.AUTO_COMPACTION_THRESHOLD_TOKENS_K] == null) {
            prefs[SettingsStore.AUTO_COMPACTION_THRESHOLD_TOKENS_K] = 8
        }
        if (prefs[SettingsStore.AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS] == null) {
            prefs[SettingsStore.AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS] = 5
        }
        if (prefs[SettingsStore.RESPONSE_STREAM_MAX_RETRIES] == null) {
            prefs[SettingsStore.RESPONSE_STREAM_MAX_RETRIES] = 5
        }

        prefs[SettingsStore.VERSION] = 4
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}
}
