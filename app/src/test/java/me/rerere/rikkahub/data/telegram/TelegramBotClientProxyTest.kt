package me.rerere.rikkahub.data.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Proxy

/**
 * JVM unit tests for [telegramProxyOrNull] — the pure config-to-[Proxy] resolver shared by
 * TelegramBotClient's shortClient and pollClient. Covers plan Task 4's contract: disabled
 * config, both proxy types, and the "enabled but invalid" case that must yield no proxy
 * rather than throw.
 */
class TelegramBotClientProxyTest {

    @Test
    fun `disabled config yields no proxy`() {
        val cfg = TelegramBotConfig(proxyEnabled = false, proxyHost = "proxy.example.com", proxyPort = 1080)
        assertNull(telegramProxyOrNull(cfg))
    }

    @Test
    fun `enabled SOCKS5 config resolves a SOCKS proxy`() {
        val cfg = TelegramBotConfig(
            proxyEnabled = true,
            proxyType = "SOCKS5",
            proxyHost = "proxy.example.com",
            proxyPort = 1080,
        )
        val proxy = telegramProxyOrNull(cfg)
        assertEquals(Proxy.Type.SOCKS, proxy?.type())
    }

    @Test
    fun `enabled HTTP config resolves an HTTP proxy`() {
        val cfg = TelegramBotConfig(
            proxyEnabled = true,
            proxyType = "HTTP",
            proxyHost = "proxy.example.com",
            proxyPort = 8080,
        )
        val proxy = telegramProxyOrNull(cfg)
        assertEquals(Proxy.Type.HTTP, proxy?.type())
    }

    @Test
    fun `enabled but blank host yields no proxy instead of crashing`() {
        val cfg = TelegramBotConfig(proxyEnabled = true, proxyType = "SOCKS5", proxyHost = "", proxyPort = 1080)
        assertNull(telegramProxyOrNull(cfg))
    }

    @Test
    fun `enabled with port zero yields no proxy`() {
        val cfg = TelegramBotConfig(proxyEnabled = true, proxyHost = "proxy.example.com", proxyPort = 0)
        assertNull(telegramProxyOrNull(cfg))
    }

    @Test
    fun `enabled with port above 65535 yields no proxy`() {
        val cfg = TelegramBotConfig(proxyEnabled = true, proxyHost = "proxy.example.com", proxyPort = 70_000)
        assertNull(telegramProxyOrNull(cfg))
    }

    @Test
    fun `enabled with negative port yields no proxy`() {
        val cfg = TelegramBotConfig(proxyEnabled = true, proxyHost = "proxy.example.com", proxyPort = -1)
        assertNull(telegramProxyOrNull(cfg))
    }
}
