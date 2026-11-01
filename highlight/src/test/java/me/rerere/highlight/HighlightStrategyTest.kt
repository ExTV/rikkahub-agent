package me.rerere.highlight

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the length thresholds that decide how [CodeHighlightText] highlights a code block.
 *
 * This is a regression test for GH-62: a fixed length cap that fell back to plain text as soon as
 * a block crossed it made ordinary code blocks (a couple hundred lines) look highlighted while
 * still streaming but flip to unhighlighted once they settled past the cutoff. Highlighting must
 * never be skipped below [MAX_HIGHLIGHT_LENGTH], no matter whether the block is still growing.
 */
class HighlightStrategyTest {
    @Test
    fun `short code highlights synchronously`() {
        assertEquals(HighlightStrategy.Synchronous, highlightStrategyFor(0))
        assertEquals(HighlightStrategy.Synchronous, highlightStrategyFor(4096))
    }

    @Test
    fun `a 200-line block still highlights, just off the main thread`() {
        // ~200 lines at a typical ~25 chars/line lands right past the old 4096-char cliff that
        // used to drop colors entirely once a block grew past it.
        val typical200LineLength = 200 * 25
        assertEquals(HighlightStrategy.Asynchronous, highlightStrategyFor(typical200LineLength))
    }

    @Test
    fun `just past the synchronous threshold goes async, not skipped`() {
        assertEquals(HighlightStrategy.Asynchronous, highlightStrategyFor(4097))
        assertEquals(HighlightStrategy.Asynchronous, highlightStrategyFor(200_000))
    }

    @Test
    fun `only pathologically large input skips highlighting`() {
        assertEquals(HighlightStrategy.Skip, highlightStrategyFor(200_001))
    }
}
