package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStateTest {

    @Test
    fun `fnv1a64 is deterministic and order sensitive`() {
        assertEquals(fnv1a64(listOf("a", "b")), fnv1a64(listOf("a", "b")))
        assertNotEquals(fnv1a64(listOf("a", "b")), fnv1a64(listOf("b", "a")))
        // separator: ["ab","c"] must differ from ["a","bc"]
        assertNotEquals(fnv1a64(listOf("ab", "c")), fnv1a64(listOf("a", "bc")))
        assertNotEquals(fnv1a64(emptyList()), fnv1a64(listOf("")))
    }

    @Test
    fun `shadeOpen requires big systemui system window`() {
        val h = 2400
        val shade = SurfaceFacts("com.android.systemui", 3, 0, 2400)
        val statusBar = SurfaceFacts("com.android.systemui", 3, 0, 120)
        val bigAppWindow = SurfaceFacts("com.example.app", 1, 0, 2400)
        assertTrue(shadeOpen(listOf(statusBar, shade), h))
        assertFalse(shadeOpen(listOf(statusBar), h))
        assertFalse(shadeOpen(listOf(bigAppWindow), h))
        assertFalse(shadeOpen(emptyList(), h))
    }

    @Test
    fun `imeVisible detects input method window`() {
        assertTrue(imeVisible(listOf(SurfaceFacts("com.some.ime", 2, 1200, 2400))))
        assertFalse(imeVisible(listOf(SurfaceFacts("com.android.systemui", 3, 0, 120))))
    }

    @Test
    fun `parseNodeId accepts window colon index and rejects garbage`() {
        assertEquals(42 to 7, parseNodeId("42:7"))
        assertNull(parseNodeId("42"))
        assertNull(parseNodeId("42:0"))     // traversal index is 1-based
        assertNull(parseNodeId("a:b"))
        assertNull(parseNodeId("1:2:3"))
        assertNull(parseNodeId(""))
    }

    @Test
    fun `coordsOutOfBounds flags points outside the display`() {
        assertFalse(coordsOutOfBounds(0.0, 0.0, 1080, 2400))
        assertFalse(coordsOutOfBounds(1079.0, 2399.0, 1080, 2400))
        assertTrue(coordsOutOfBounds(1080.0, 100.0, 1080, 2400))
        assertTrue(coordsOutOfBounds(100.0, 2400.0, 1080, 2400))
    }

    @Test
    fun `awaitQuiet returns true once quiet window passes`() = runBlocking {
        var clock = 0L
        // no events after floor: quiet reached on first poll after quietMs
        val quiet = awaitQuiet(
            quietMs = 100, timeoutMs = 1000,
            now = { clock.also { clock += 50 } },
            lastEvent = { 0L },
            floor = 0L,
        )
        assertTrue(quiet)
    }

    @Test
    fun `awaitQuiet times out when events keep arriving`() = runBlocking {
        var clock = 0L
        val quiet = awaitQuiet(
            quietMs = 100, timeoutMs = 500,
            now = { clock.also { clock += 50 } },
            lastEvent = { clock },   // an event "just happened" at every poll
            floor = 0L,
        )
        assertFalse(quiet)
    }

    @Test
    fun `awaitQuiet floor prevents instant quiet from stale lastEvent`() = runBlocking {
        var clock = 1000L
        var polls = 0
        val quiet = awaitQuiet(
            quietMs = 100, timeoutMs = 1000,
            now = { polls++; clock.also { clock += 50 } },
            lastEvent = { 0L },      // stale: long before the action
            floor = 1000L,           // action just happened at t=1000
        )
        assertTrue(quiet)
        assertTrue("must actually wait, not return on first poll", polls > 1)
    }
}
