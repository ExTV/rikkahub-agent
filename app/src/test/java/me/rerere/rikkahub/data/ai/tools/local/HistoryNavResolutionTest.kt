package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [resolveHistoryNav], the pure decision extracted from [runHistoryNav] for issue #59:
 * Chromium's history-manipulation intervention marks gesture-less navigations (every
 * navigation this browser performs, since none carry a real user gesture) as skippable, so
 * `canGoBack()`/`canGoForward()` can be false even though `copyBackForwardList()` proves a
 * real entry exists in that direction. Same rationale as
 * [me.rerere.rikkahub.browser.BrowserWaitForPredicateTest]'s sibling for browser_wait_for:
 * pure function, unit-testable without Robolectric.
 */
class HistoryNavResolutionTest {

    @Test
    fun `back native regardless of index`() {
        assertEquals(HistoryNavMethod.NATIVE, resolveHistoryNav(forward = false, canNative = true, currentIndex = 0, size = 1))
    }

    @Test
    fun `back not native but an older entry exists falls back to offset`() {
        // The #59 repro shape: history.length == 2, canGoBack() == false.
        assertEquals(HistoryNavMethod.OFFSET, resolveHistoryNav(forward = false, canNative = false, currentIndex = 1, size = 2))
    }

    @Test
    fun `back not native and nothing to go back to is none`() {
        assertEquals(HistoryNavMethod.NONE, resolveHistoryNav(forward = false, canNative = false, currentIndex = 0, size = 1))
    }

    @Test
    fun `back not native but already at the oldest entry is none`() {
        assertEquals(HistoryNavMethod.NONE, resolveHistoryNav(forward = false, canNative = false, currentIndex = 0, size = 2))
    }

    @Test
    fun `forward not native but a newer entry exists falls back to offset`() {
        assertEquals(HistoryNavMethod.OFFSET, resolveHistoryNav(forward = true, canNative = false, currentIndex = 0, size = 2))
    }

    @Test
    fun `forward not native and already at the newest entry is none`() {
        assertEquals(HistoryNavMethod.NONE, resolveHistoryNav(forward = true, canNative = false, currentIndex = 1, size = 2))
    }
}
