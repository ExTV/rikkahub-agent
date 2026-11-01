package me.rerere.tts.controller

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * JVM unit tests for [awaitWithRetry], the bounded retry + cache eviction bookkeeping
 * extracted from [TtsController.awaitOrCreate].
 *
 * Before the S-4 fix, one flaky synthesis call dropped that chunk from the read-aloud text
 * forever: the call was never retried, and the failed Deferred stayed cached, so even a
 * manual replay of the same key would just replay the same old failure. These tests pin
 * that a failure is retried up to the attempt limit, that the failed Deferred is evicted
 * before each retry, and that a permanent failure still surfaces (rather than hanging or
 * silently swallowing the error) once attempts are exhausted.
 *
 * The fakes are created on a dedicated SupervisorJob-backed scope, mirroring
 * [TtsController]'s own `scope` - a plain runBlocking job would propagate a failed child
 * `async`'s exception straight into cancelling the whole test, which is not how the real
 * caller is set up.
 */
class TtsControllerAwaitWithRetryTest {

    private fun supervisedScope() = CoroutineScope(SupervisorJob())

    @Test
    fun `succeeds on first attempt without retrying`() = runBlocking {
        val cache: ConcurrentMap<String, Deferred<String>> = ConcurrentHashMap()
        val scope = supervisedScope()
        var createCalls = 0

        val result = awaitWithRetry(cache, "chunk-1", maxAttempts = 2) {
            createCalls++
            scope.async { "ok" }
        }

        assertEquals("ok", result)
        assertEquals(1, createCalls)
    }

    @Test
    fun `retries after a failure and evicts the failed deferred before retrying`() = runBlocking {
        val cache: ConcurrentMap<String, Deferred<String>> = ConcurrentHashMap()
        val scope = supervisedScope()
        var createCalls = 0

        val result = awaitWithRetry(cache, "chunk-1", maxAttempts = 2) {
            createCalls++
            if (createCalls == 1) {
                scope.async<String> { throw IllegalStateException("flaky network call") }
            } else {
                scope.async { "recovered" }
            }
        }

        assertEquals("recovered", result)
        assertEquals("A retry must actually resynthesize, not replay the cached failure", 2, createCalls)
    }

    @Test
    fun `exhausting all attempts throws the last error instead of dropping the chunk silently`() = runBlocking {
        val cache: ConcurrentMap<String, Deferred<String>> = ConcurrentHashMap()
        val scope = supervisedScope()
        var createCalls = 0

        val error = try {
            awaitWithRetry(cache, "chunk-1", maxAttempts = 2) {
                createCalls++
                val attempt = createCalls
                scope.async<String> { throw IllegalStateException("attempt $attempt failed") }
            }
            null
        } catch (e: IllegalStateException) {
            e
        }

        assertEquals(2, createCalls)
        assertEquals("attempt 2 failed", error?.message)
    }

    @Test
    fun `a permanently failed key is not left cached after retries are exhausted`() = runBlocking {
        val cache: ConcurrentMap<String, Deferred<String>> = ConcurrentHashMap()
        val scope = supervisedScope()

        try {
            awaitWithRetry(cache, "chunk-1", maxAttempts = 2) {
                scope.async<String> { throw IllegalStateException("down") }
            }
        } catch (_: IllegalStateException) {
            // expected
        }

        assertFalse(
            "A future replay of this key must not immediately await the old failed Deferred",
            cache.containsKey("chunk-1"),
        )
    }

    @Test
    fun `a single retry attempt does not call create again after success`() = runBlocking {
        val cache: ConcurrentMap<String, Deferred<String>> = ConcurrentHashMap()
        val scope = supervisedScope()
        val deferred = CompletableDeferred<String>().apply { complete("cached") }
        cache["chunk-1"] = deferred

        val result = awaitWithRetry(cache, "chunk-1", maxAttempts = 2) {
            scope.async { "should not be called" }
        }

        assertTrue("An already-succeeded Deferred must be reused, not recreated", result == "cached")
    }
}
