package me.rerere.rikkahub.ui.components.message

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [buildIframeWrapperHtml] and [shouldWrapInIframe] - the pure functions
 * behind honouring `iframe: true` on a skill webview card (#Task 10). Top-level navigation
 * to an embed-only endpoint like the Google Maps Embed API renders its own rejection page;
 * wrapping the same URL in a locally generated iframe page renders it correctly.
 */
class SkillWebviewCardIframeTest {

    @Test
    fun `buildIframeWrapperHtml escapes ampersand in a maps-style URL`() {
        val url = "https://www.google.com/maps/embed?pb=abc&output=embed"
        val html = buildIframeWrapperHtml(url)
        assertTrue(html.contains("https://www.google.com/maps/embed?pb=abc&amp;output=embed"))
        assertFalse(html.contains("pb=abc&output=embed"))
    }

    @Test
    fun `buildIframeWrapperHtml escapes quote so it cannot break out of the src attribute`() {
        val url = "https://evil.example.com/\" onload=\"alert(1)"
        val html = buildIframeWrapperHtml(url)
        assertFalse(html.contains("\" onload=\"alert(1)"))
        assertTrue(html.contains("&quot; onload=&quot;alert(1)"))
    }

    @Test
    fun `shouldWrapInIframe is true for https`() {
        assertTrue(shouldWrapInIframe("https://example.com", iframe = true))
    }

    @Test
    fun `shouldWrapInIframe is true for uppercase HTTP scheme`() {
        assertTrue(shouldWrapInIframe("HTTP://example.com", iframe = true))
    }

    @Test
    fun `shouldWrapInIframe is false for file scheme`() {
        assertFalse(shouldWrapInIframe("file:///android_asset/webview.html", iframe = true))
    }

    @Test
    fun `shouldWrapInIframe is false when iframe is false`() {
        assertFalse(shouldWrapInIframe("https://example.com", iframe = false))
    }
}
