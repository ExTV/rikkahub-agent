package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundWorkTrackerTest {
    @Test
    fun `resource remains active until every operation releases`() {
        val events = mutableListOf<String>()
        val tracker = ForegroundWorkTracker(
            onFirstAcquire = { events += "start" },
            onLastRelease = { events += "stop" },
        )

        val releaseFirst = tracker.acquire()
        val releaseSecond = tracker.acquire()
        releaseFirst()

        assertEquals(listOf("start"), events)

        releaseSecond()
        assertEquals(listOf("start", "stop"), events)
    }

    @Test
    fun `releasing an operation twice does not stop another operation`() {
        val events = mutableListOf<String>()
        val tracker = ForegroundWorkTracker(
            onFirstAcquire = { events += "start" },
            onLastRelease = { events += "stop" },
        )

        val releaseFirst = tracker.acquire()
        val releaseSecond = tracker.acquire()
        releaseFirst()
        releaseFirst()
        releaseSecond()

        assertEquals(listOf("start", "stop"), events)
    }
}
