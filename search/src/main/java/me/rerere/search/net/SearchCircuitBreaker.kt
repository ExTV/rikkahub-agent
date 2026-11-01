package me.rerere.search.net

/**
 * In-memory circuit breaker for a single keyless search engine. State lives only
 * in the holding object for the process lifetime - no disk, no cache. Thread-safe:
 * search() runs on Dispatchers.IO and calls may overlap.
 *
 * States: CLOSED (normal) -> OPEN (after [failureThreshold] consecutive failures) ->
 * HALF_OPEN (once [cooldownMillis] has elapsed, exactly one probe is let through) ->
 * CLOSED (probe succeeds) or OPEN (probe fails, cooldown restarts).
 */
class SearchCircuitBreaker(
    private val failureThreshold: Int = 3,
    private val cooldownMillis: Long = 60_000L,
    private val now: () -> Long = { System.currentTimeMillis() }, // injectable for tests
) {
    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private var state = State.CLOSED
    private var failureCount = 0
    private var lastFailureAt = 0L

    @Synchronized
    fun canAttempt(): Boolean = when (state) {
        State.CLOSED -> true
        State.HALF_OPEN -> false // a probe is already outstanding
        State.OPEN -> {
            if (now() - lastFailureAt >= cooldownMillis) {
                state = State.HALF_OPEN
                true
            } else {
                false
            }
        }
    }

    @Synchronized
    fun remainingCooldownMillis(): Long {
        if (state == State.CLOSED) return 0L
        val remaining = cooldownMillis - (now() - lastFailureAt)
        return remaining.coerceAtLeast(0L)
    }

    @Synchronized
    fun recordSuccess() {
        state = State.CLOSED
        failureCount = 0
    }

    @Synchronized
    fun recordFailure() {
        failureCount += 1
        lastFailureAt = now()
        if (state == State.HALF_OPEN || failureCount >= failureThreshold) {
            state = State.OPEN
        }
    }
}
