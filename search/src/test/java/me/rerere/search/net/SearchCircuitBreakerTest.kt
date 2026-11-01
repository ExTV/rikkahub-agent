package me.rerere.search.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCircuitBreakerTest {

    private class FakeClock(var millis: Long = 0L) {
        fun advance(by: Long) {
            millis += by
        }
    }

    @Test
    fun `stays closed and attemptable below the failure threshold`() {
        val clock = FakeClock()
        val breaker = SearchCircuitBreaker(failureThreshold = 3, cooldownMillis = 60_000L, now = { clock.millis })

        breaker.recordFailure()
        breaker.recordFailure()

        assertTrue(breaker.canAttempt())
        assertEquals(0L, breaker.remainingCooldownMillis())
    }

    @Test
    fun `opens after reaching the failure threshold`() {
        val clock = FakeClock()
        val breaker = SearchCircuitBreaker(failureThreshold = 3, cooldownMillis = 60_000L, now = { clock.millis })

        breaker.recordFailure()
        breaker.recordFailure()
        breaker.recordFailure()

        assertFalse(breaker.canAttempt())
        assertTrue(breaker.remainingCooldownMillis() > 0L)
    }

    @Test
    fun `short-circuits every call while open and within cooldown`() {
        val clock = FakeClock()
        val breaker = SearchCircuitBreaker(failureThreshold = 3, cooldownMillis = 60_000L, now = { clock.millis })
        repeat(3) { breaker.recordFailure() }

        clock.advance(30_000L)

        assertFalse(breaker.canAttempt())
        assertEquals(30_000L, breaker.remainingCooldownMillis())
    }

    @Test
    fun `half-opens after the cooldown elapses via the injected clock`() {
        val clock = FakeClock()
        val breaker = SearchCircuitBreaker(failureThreshold = 3, cooldownMillis = 60_000L, now = { clock.millis })
        repeat(3) { breaker.recordFailure() }

        clock.advance(60_000L)

        assertTrue(breaker.canAttempt())
    }

    @Test
    fun `a success in half-open closes the breaker and resets failures`() {
        val clock = FakeClock()
        val breaker = SearchCircuitBreaker(failureThreshold = 3, cooldownMillis = 60_000L, now = { clock.millis })
        repeat(3) { breaker.recordFailure() }
        clock.advance(60_000L)
        assertTrue(breaker.canAttempt())

        breaker.recordSuccess()

        assertTrue(breaker.canAttempt())
        assertEquals(0L, breaker.remainingCooldownMillis())

        // and the failure count was reset, so a single subsequent failure does not reopen it
        breaker.recordFailure()
        assertTrue(breaker.canAttempt())
    }

    @Test
    fun `a failure in half-open re-opens a fresh cooldown`() {
        val clock = FakeClock()
        val breaker = SearchCircuitBreaker(failureThreshold = 3, cooldownMillis = 60_000L, now = { clock.millis })
        repeat(3) { breaker.recordFailure() }
        clock.advance(60_000L)
        assertTrue(breaker.canAttempt())

        breaker.recordFailure()

        assertFalse(breaker.canAttempt())
        assertEquals(60_000L, breaker.remainingCooldownMillis())
    }

    @Test
    fun `remainingCooldownMillis never goes negative once cooldown has long passed`() {
        val clock = FakeClock()
        val breaker = SearchCircuitBreaker(failureThreshold = 3, cooldownMillis = 60_000L, now = { clock.millis })
        repeat(3) { breaker.recordFailure() }

        clock.advance(1_000_000L)

        assertEquals(0L, breaker.remainingCooldownMillis())
    }
}
