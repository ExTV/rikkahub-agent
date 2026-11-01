package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.web.WebServerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * JVM unit tests for [WebServerService.shouldStopOnError].
 *
 * WebServerManager is a Koin single, so its StateFlow keeps a failed attempt's terminal
 * state (error set, isLoading false) around after the observer that saw it is gone. A fresh
 * ACTION_START's collector replays that stale value as its very first emission, before the
 * retry's own start() has run. [WebServerService.shouldStopOnError] tells apart that stale
 * replay from a genuine failure of the current attempt by comparing the state's startId
 * against the id that was already current when the observer subscribed.
 */
class WebServerServiceStateObserverTest {

    @Test
    fun `stale terminal error replayed to a fresh collector does not stop the service`() {
        // The state still carries the id from the attempt that produced it - no new
        // attempt has written anything yet, so this is the stale state left over from a
        // previous failed start.
        assertFalse(
            "A replayed stale error must not be treated as this attempt's result",
            WebServerService.shouldStopOnError(
                error = "Port 8080 is already in use",
                isLoading = false,
                startId = 1,
                baselineStartId = 1,
            ),
        )
    }

    @Test
    fun `error carrying a new startId stops the service`() {
        assertTrue(
            "An error whose startId differs from the baseline is this attempt's own result",
            WebServerService.shouldStopOnError(
                error = "Port 8080 is already in use",
                isLoading = false,
                startId = 2,
                baselineStartId = 1,
            ),
        )
    }

    @Test
    fun `no error never stops the service`() {
        assertFalse(
            WebServerService.shouldStopOnError(
                error = null,
                isLoading = false,
                startId = 2,
                baselineStartId = 1,
            ),
        )
    }

    @Test
    fun `error while still loading does not stop the service`() {
        // isLoading=true means the attempt is still in flight (start() writes isLoading=true
        // by copying the current value forward, which can still carry a stale error field);
        // only a settled error counts.
        assertFalse(
            WebServerService.shouldStopOnError(
                error = "Port 8080 is already in use",
                isLoading = true,
                startId = 2,
                baselineStartId = 1,
            ),
        )
    }

    @Test
    fun `id-based check catches a port-busy failure that an isLoading latch would have missed`() {
        // Reproduces the actual race: WebServerManager.start()'s port-busy path writes
        // isLoading=true and then, with no suspension point in between, the terminal error -
        // both on the same coroutine. A collector sharing that dispatcher (Dispatchers.Main
        // in production) can be scheduled only after both writes have landed, so it never
        // observes the intermediate isLoading=true emission at all: StateFlow only keeps the
        // latest value, it does not queue every value written since the collector last ran.
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val state = MutableStateFlow(WebServerState(startId = 1, error = "stale error from a previous failed attempt"))
            val baselineStartId = state.value.startId

            var sawLoadingLatch = false
            var oldLatchBasedStopped = false
            var idBasedStopped = false
            val collectorStarted = CountDownLatch(1)

            val job = CoroutineScope(dispatcher).launch {
                state.collect { s ->
                    collectorStarted.countDown()
                    if (s.isLoading) sawLoadingLatch = true
                    // What the previous fix did: gate on having witnessed isLoading=true first.
                    if (s.error != null && !s.isLoading && sawLoadingLatch) oldLatchBasedStopped = true
                    // What shouldStopOnError does now: gate on the state's own startId.
                    if (WebServerService.shouldStopOnError(s.error, s.isLoading, s.startId, baselineStartId)) {
                        idBasedStopped = true
                    }
                }
            }
            collectorStarted.await()

            runBlocking(dispatcher) {
                // No suspension point between these two writes, exactly like WebServerManager.start().
                state.value = state.value.copy(isLoading = true, startId = 2)
                state.value = WebServerState(startId = 2, error = "Port 8080 is already in use")
            }
            runBlocking(dispatcher) { } // let the collector drain the queued resumption

            job.cancel()

            assertFalse(
                "the intermediate isLoading=true write must be conflated away for this regression to be meaningful",
                sawLoadingLatch,
            )
            assertFalse(
                "documents the bug this fix replaces: an isLoading-latch design misses a real failure once the transition is conflated away",
                oldLatchBasedStopped,
            )
            assertTrue(
                "the id-based check must still catch the failure despite the conflation",
                idBasedStopped,
            )
        } finally {
            dispatcher.close()
        }
    }
}
