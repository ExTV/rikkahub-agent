package me.rerere.rikkahub.ui.pages.setting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingPreferencesGeneralPageTest {

    @Test
    fun `parses a value inside the valid range`() {
        assertEquals(1000, parsePasteLongTextThreshold("1000"))
    }

    @Test
    fun `accepts the range boundaries`() {
        assertEquals(100, parsePasteLongTextThreshold("100"))
        assertEquals(10000, parsePasteLongTextThreshold("10000"))
    }

    @Test
    fun `rejects values below the minimum`() {
        assertNull(parsePasteLongTextThreshold("99"))
    }

    @Test
    fun `rejects values above the maximum`() {
        assertNull(parsePasteLongTextThreshold("10001"))
    }

    @Test
    fun `rejects blank input`() {
        assertNull(parsePasteLongTextThreshold(""))
    }

    @Test
    fun `rejects non-numeric input`() {
        assertNull(parsePasteLongTextThreshold("abc"))
    }
}
