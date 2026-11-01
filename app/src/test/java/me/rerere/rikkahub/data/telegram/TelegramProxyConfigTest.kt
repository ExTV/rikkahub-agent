package me.rerere.rikkahub.data.telegram

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * JVM unit tests for [readTelegramProxyConfig] / [writeTelegramProxyConfig] — the pure
 * key<->[TelegramBotConfig] mapping TelegramBotPreferences delegates to for its six proxy
 * fields. Exercised against a real file-backed DataStore<Preferences> (constructed directly
 * via [PreferenceDataStoreFactory], no Android Context involved) because
 * TelegramBotPreferences itself needs a Context-backed DataStore to construct, and this
 * project has no Robolectric/Mockito seam to fake one.
 */
class TelegramProxyConfigTest {

    private fun newStore(dir: File): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(dir, "telegram_proxy_test.preferences_pb") },
        )

    private fun withTempStore(block: suspend (DataStore<Preferences>) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("telegram-proxy-test").toFile()
        try {
            block(newStore(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a proxy config round-trips through all six fields`() = withTempStore { store ->
        val cfg = TelegramBotConfig(
            proxyEnabled = true,
            proxyType = "HTTP",
            proxyHost = "proxy.example.com",
            proxyPort = 8080,
            proxyUsername = "alice",
            proxyPassword = "hunter2",
        )

        store.edit { p -> writeTelegramProxyConfig(p, cfg) }
        val read = readTelegramProxyConfig(store.data.first())

        assertEquals(cfg.proxyEnabled, read.proxyEnabled)
        assertEquals(cfg.proxyType, read.proxyType)
        assertEquals(cfg.proxyHost, read.proxyHost)
        assertEquals(cfg.proxyPort, read.proxyPort)
        assertEquals(cfg.proxyUsername, read.proxyUsername)
        assertEquals(cfg.proxyPassword, read.proxyPassword)
    }

    @Test
    fun `a missing proxy key reads as the TelegramBotConfig default`() = withTempStore { store ->
        val defaults = TelegramBotConfig()

        val read = readTelegramProxyConfig(store.data.first())

        assertEquals(defaults.proxyEnabled, read.proxyEnabled)
        assertEquals(defaults.proxyType, read.proxyType)
        assertEquals(defaults.proxyHost, read.proxyHost)
        assertEquals(defaults.proxyPort, read.proxyPort)
        assertEquals(defaults.proxyUsername, read.proxyUsername)
        assertEquals(defaults.proxyPassword, read.proxyPassword)
    }

    @Test
    fun `disabled proxy round-trips false without dragging in unrelated defaults`() = withTempStore { store ->
        val cfg = TelegramBotConfig(proxyEnabled = false, proxyType = "SOCKS5")

        store.edit { p -> writeTelegramProxyConfig(p, cfg) }
        val read = readTelegramProxyConfig(store.data.first())

        assertEquals(false, read.proxyEnabled)
        assertEquals("SOCKS5", read.proxyType)
    }
}
