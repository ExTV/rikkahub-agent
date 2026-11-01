package me.rerere.rikkahub.service

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * Pins the runLlm timeout bug: when a scheduled LLM turn blows the 15-minute wall-clock
 * cap, the old code returned `timed_out` without ever stopping the still-running
 * generation. Left unstopped, the generation keeps burning tokens indefinitely, and a
 * later fire of the same job can start a second parallel generation while the first one
 * is still going.
 *
 * [finishRunLlm] is the extracted decision + side effect, split out (like
 * [awaitGenerationTerminal]) so this can be pinned without a live ChatService. Failure
 * handling for [stop] itself is the caller's responsibility (see the runCatching wrapper
 * around chatService.stopGeneration at the call site in [CronJobWorker.runLlm]) — kept out
 * of this pure function so it stays testable without touching android.util.Log, which
 * isn't mocked in this module's plain-JVM unit tests.
 */
class CronJobWorkerRunLlmTimeoutTest {

    @Test
    fun `completed generation returns success and never calls stop`() = runBlocking {
        var stopCalled = false
        val convId = Uuid.random()
        val result = finishRunLlm(completed = true, convId = convId) { stopCalled = true }
        assertEquals(Triple("success", null, convId), result)
        assertFalse("a naturally-completed generation must not be stopped", stopCalled)
    }

    @Test
    fun `timed-out generation calls stop and reports timed_out`() = runBlocking {
        var stopCalled = false
        val convId = Uuid.random()
        val result = finishRunLlm(completed = false, convId = convId) { stopCalled = true }
        assertEquals("timed_out", result.first)
        assertEquals(convId, result.third)
        assertTrue(
            "a timed-out generation must be stopped or it keeps burning tokens and a " +
                "later fire can start a duplicate parallel run",
            stopCalled,
        )
    }
}
