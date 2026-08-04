package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesStoreTest {
    @Test
    fun `compression target defaults to one percent of the active context`() {
        val settings = Settings()

        assertEquals(3_720, settings.getContextCompactionTargetTokens(372_000))
    }

    @Test
    fun `compression target uses a safe fallback without model metadata`() {
        val settings = Settings(contextCompactionTargetTokensK = null)

        assertEquals(2_000, settings.getContextCompactionTargetTokens(null))
        assertEquals(2_000, settings.getContextCompactionTargetTokens(0))
    }

    @Test
    fun `token threshold supplies context metadata for the one percent default`() {
        val settings = Settings(
            contextCompactionTargetTokensK = null,
            autoCompactionThresholdMode = AutoCompactionThresholdMode.TOKENS,
            autoCompactionThresholdTokensK = 372,
        )

        assertEquals(372_000, settings.getCompactionContextLength(null))
        assertEquals(3_720, settings.getContextCompactionTargetTokens(
            settings.getCompactionContextLength(null),
        ))
    }

    @Test
    fun `explicit compression target overrides the fixed default`() {
        val settings = Settings(contextCompactionTargetTokensK = 30)

        assertEquals(30_000, settings.getContextCompactionTargetTokens(372_000))
    }

    @Test
    fun `assistant max steps round trips through persisted settings json`() {
        val saved = Assistant(maxStepsPerTurn = 0)

        val restored = JsonInstant.decodeFromString<Assistant>(
            JsonInstant.encodeToString(saved)
        )

        assertEquals(0, restored.maxStepsPerTurn)
    }
}
