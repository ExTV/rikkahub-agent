package me.rerere.rikkahub.service

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceReadinessTest {
    @Test
    fun `work waits until foreground promotion is confirmed`() = runBlocking {
        val readiness = ForegroundServiceReadiness()
        readiness.requestStart()

        val waiting = async { readiness.awaitReady(timeoutMillis = 1_000) }
        delay(20)
        assertFalse(waiting.isCompleted)

        readiness.markReady()
        assertTrue(waiting.await())
    }

    @Test
    fun `failed promotion releases waiter as unavailable`() = runBlocking {
        val readiness = ForegroundServiceReadiness()
        readiness.requestStart()
        readiness.markUnavailable()

        assertFalse(readiness.awaitReady(timeoutMillis = 1_000))
    }

    @Test
    fun `new start can recover after service becomes unavailable`() = runBlocking {
        val readiness = ForegroundServiceReadiness()
        readiness.requestStart()
        readiness.markReady()
        readiness.markUnavailable()

        readiness.requestStart()
        val waiting = async { readiness.awaitReady(timeoutMillis = 1_000) }
        delay(20)
        assertFalse(waiting.isCompleted)

        readiness.markReady()
        assertTrue(waiting.await())
    }

    @Test
    fun `new start after stop requires a new promotion`() = runBlocking {
        val readiness = ForegroundServiceReadiness()
        readiness.requestStart()
        readiness.markReady()
        assertTrue(readiness.awaitReady(timeoutMillis = 1_000))

        readiness.requestStop()
        readiness.requestStart()
        assertFalse(readiness.awaitReady(timeoutMillis = 20))

        readiness.markReady()
        assertTrue(readiness.awaitReady(timeoutMillis = 1_000))
    }
}
