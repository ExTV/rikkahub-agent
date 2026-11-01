package me.rerere.rikkahub.browser

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * BrowserAiStripe redesign: JVM coverage for [BrowserController]'s begin/complete action
 * model and the stripe's pure relative-time helper — none of this touches Android framework
 * classes, so it runs unbound (no WebView, no live task) like [BrowserToolsTest].
 */
class BrowserAiActionTest {

    /**
     * There's no dedicated test-only reset hook. `clearTaskWindow()` alone leaves a
     * non-empty trail (that's the whole point of task-scoped clearing — browser_done must
     * NOT wipe its own DONE entry). Driving `startTaskWindow()` from a null
     * `currentTaskStartedAt` is the public way to force a "fresh task" trail-clear, so
     * clear -> start (clears trail) -> clear again leaves every test starting from an empty
     * trail, `taskActiveFlow() == false`, and the step counter at 0.
     */
    @Before
    fun resetState() {
        BrowserController.clearTaskWindow()
        BrowserController.startTaskWindow()
        BrowserController.clearTaskWindow()
    }

    @After
    fun tearDown() {
        BrowserController.clearTaskWindow()
    }

    @Test fun `beginAction appends a RUNNING entry newest first with incrementing id and step`() {
        val id1 = BrowserController.beginAction(BrowserAiActionKind.OPEN, "https://a.example")
        val id2 = BrowserController.beginAction(BrowserAiActionKind.CLICK, "#btn")

        val actions = BrowserController.recentActionsFlow().value
        assertEquals(2, actions.size)
        // Newest first.
        assertEquals(id2, actions[0].id)
        assertEquals(id1, actions[1].id)
        assertEquals(BrowserAiActionOutcome.RUNNING, actions[0].outcome)
        assertEquals(BrowserAiActionOutcome.RUNNING, actions[1].outcome)
        assertTrue("id should be monotonically increasing", id2 > id1)
        assertTrue("step should increase within the task", actions[0].step > actions[1].step)
        assertEquals("https://a.example", actions[1].detail)
        assertEquals("#btn", actions[0].detail)
    }

    @Test fun `completeAction flips the entry to OK or FAILED`() {
        val okId = BrowserController.beginAction(BrowserAiActionKind.CLICK, "#btn")
        BrowserController.completeAction(okId, true)
        assertEquals(
            BrowserAiActionOutcome.OK,
            BrowserController.recentActionsFlow().value.first { it.id == okId }.outcome,
        )

        val failedId = BrowserController.beginAction(BrowserAiActionKind.SUBMIT, "#form")
        BrowserController.completeAction(failedId, false)
        assertEquals(
            BrowserAiActionOutcome.FAILED,
            BrowserController.recentActionsFlow().value.first { it.id == failedId }.outcome,
        )
    }

    @Test fun `completeAction on an unknown id is a no-op`() {
        BrowserController.beginAction(BrowserAiActionKind.CLICK, "#btn")
        val before = BrowserController.recentActionsFlow().value
        BrowserController.completeAction(id = -1L, ok = true)
        assertEquals(before, BrowserController.recentActionsFlow().value)
    }

    @Test fun `trail caps at 20 entries keeping the newest`() {
        // MAX_RECENT_ACTIONS is a private controller constant (currently 20) — begin one
        // more than that and confirm the oldest entry is evicted, not the newest.
        val ids = (1..21).map { i -> BrowserController.beginAction(BrowserAiActionKind.CLICK, "#el$i") }

        val actions = BrowserController.recentActionsFlow().value
        assertEquals(20, actions.size)
        assertEquals(ids.last(), actions.first().id)
        assertFalse("oldest entry should have aged out", actions.any { it.id == ids.first() })
    }

    @Test fun `startTaskWindow clears the trail only for a genuinely new task`() {
        BrowserController.beginAction(BrowserAiActionKind.CLICK, "#a")
        assertTrue(BrowserController.recentActionsFlow().value.isNotEmpty())

        // currentTaskStartedAt is null (reset by @Before) -> fresh task -> clears the trail.
        BrowserController.startTaskWindow()
        assertTrue(
            "a fresh task must clear the trail",
            BrowserController.recentActionsFlow().value.isEmpty(),
        )

        // Re-arm mid-task (currentTaskStartedAt is now non-null): browser_open navigating
        // again inside the same task must NOT wipe the in-progress trail.
        val id = BrowserController.beginAction(BrowserAiActionKind.OPEN, "https://b.example")
        BrowserController.startTaskWindow()
        assertTrue(
            "a mid-task re-arm must not clear the trail",
            BrowserController.recentActionsFlow().value.any { it.id == id },
        )
    }

    @Test fun `step counter resets to 1 on a fresh task`() {
        BrowserController.beginAction(BrowserAiActionKind.CLICK, "#a")
        BrowserController.beginAction(BrowserAiActionKind.CLICK, "#b")

        BrowserController.startTaskWindow() // fresh task -> clears trail + resets step counter
        val id = BrowserController.beginAction(BrowserAiActionKind.OPEN, "https://x.example")
        assertEquals(1, BrowserController.recentActionsFlow().value.first { it.id == id }.step)
    }

    @Test fun `startTaskWindow and clearTaskWindow flip taskActiveFlow`() {
        assertFalse(BrowserController.taskActiveFlow().value)
        BrowserController.startTaskWindow()
        assertTrue(BrowserController.taskActiveFlow().value)
        BrowserController.clearTaskWindow()
        assertFalse(BrowserController.taskActiveFlow().value)
    }

    @Test fun `stopCurrentTask flips taskActiveFlow false and records a STOPPED entry`() {
        BrowserController.startTaskWindow()
        assertTrue(BrowserController.taskActiveFlow().value)

        BrowserController.stopCurrentTask()

        assertFalse(BrowserController.taskActiveFlow().value)
        val newest = BrowserController.recentActionsFlow().value.first()
        assertEquals(BrowserAiActionKind.STOPPED, newest.kind)
        assertEquals(BrowserAiActionOutcome.OK, newest.outcome)
    }

    @Test fun `recordAction is an immediate begin+complete pair`() {
        BrowserController.recordAction(BrowserAiActionKind.DONE, "task summary", ok = true)
        val newest = BrowserController.recentActionsFlow().value.first()
        assertEquals(BrowserAiActionKind.DONE, newest.kind)
        assertEquals("task summary", newest.detail)
        assertEquals(BrowserAiActionOutcome.OK, newest.outcome)
    }

    // ---- formatRelativeActionTime (pure helper in BrowserAiStripe.kt) -----------------------

    @Test fun `formatRelativeActionTime formats seconds, minutes and hours`() {
        val now = 1_000_000_000L
        assertEquals("0s", formatRelativeActionTime(now, now))
        assertEquals("5s", formatRelativeActionTime(now - 5_000L, now))
        assertEquals("59s", formatRelativeActionTime(now - 59_000L, now))
        assertEquals("1m", formatRelativeActionTime(now - 60_000L, now))
        assertEquals("3m", formatRelativeActionTime(now - 180_000L, now))
        assertEquals("59m", formatRelativeActionTime(now - 3_599_000L, now))
        assertEquals("1h", formatRelativeActionTime(now - 3_600_000L, now))
        assertEquals("2h", formatRelativeActionTime(now - 7_200_000L, now))
    }

    @Test fun `formatRelativeActionTime clamps a future atMs to 0s`() {
        val now = 1_000_000_000L
        assertEquals("0s", formatRelativeActionTime(now + 5_000L, now))
    }
}
