package me.rerere.rikkahub.data.datastore.migration

import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.Preferences
import me.rerere.rikkahub.data.datastore.SettingsStore

/**
 * Removes the fixed 2k value written by the previous compaction-target migration.
 *
 * That value represented an old application default, not an explicit user override. Keeping it
 * would make upgraded installs ignore the new 1%-of-context default forever. Other explicit
 * targets remain untouched.
 */
class PreferenceStoreV5Migration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean {
        val version = currentData[SettingsStore.VERSION]
        return version == null || version < 5
    }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        if (prefs[SettingsStore.CONTEXT_COMPACTION_TARGET_TOKENS_K] == LEGACY_DEFAULT_TARGET_K) {
            prefs.remove(SettingsStore.CONTEXT_COMPACTION_TARGET_TOKENS_K)
        }
        prefs[SettingsStore.VERSION] = 5
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {}

    private companion object {
        const val LEGACY_DEFAULT_TARGET_K = 2
    }
}
