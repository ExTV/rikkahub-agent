package me.rerere.rikkahub.subagent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the sub-agent timeout-without-stop bug: when a sub-agent's wait exceeded its
 * timeoutSeconds cap, the old code marked the run TIMED_OUT without ever stopping the
 * still-running generation. Left unstopped, the generation keeps burning tokens
 * indefinitely, and a later success races a duplicate parallel run against whatever the
 * parent does next.
 *
 * [finishSubAgentWait] is the extracted decision + side effect, split out (like
 * CronJobWorker's finishRunLlm) so this can be pinned without a live ChatService.
 */
class SubAgentTimeoutStopTest {

    @Test
    fun `completed wait never calls stop`() = runBlocking {
        var stopCalled = false
        val timedOut = finishSubAgentWait(completed = true) { stopCalled = true }
        assertFalse("a naturally-completed wait must not be reported as timed out", timedOut)
        assertFalse("a naturally-completed wait must not call stop", stopCalled)
    }

    @Test
    fun `timed-out wait calls stop and reports timeout`() = runBlocking {
        var stopCalled = false
        val timedOut = finishSubAgentWait(completed = false) { stopCalled = true }
        assertTrue("a timed-out wait must be reported as timed out", timedOut)
        assertTrue(
            "a timed-out wait must stop the generation or it keeps burning tokens and a " +
                "later success can race a duplicate parallel run",
            stopCalled,
        )
    }
}
