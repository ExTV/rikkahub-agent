package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coordinates an asynchronous Android foreground-service promotion with work that must not
 * start until the process has actually entered foreground-service state.
 */
internal class ForegroundServiceReadiness {
    private val lock = Any()
    private var requested = false
    private var ready = CompletableDeferred(false)

    fun requestStart() {
        synchronized(lock) {
            if (!requested) {
                ready = CompletableDeferred()
            }
            requested = true
        }
    }

    fun markReady() {
        synchronized(lock) {
            if (requested) ready.complete(true)
        }
    }

    fun markUnavailable() {
        synchronized(lock) {
            requested = false
            ready.complete(false)
        }
    }

    fun requestStop() {
        synchronized(lock) {
            requested = false
            ready.complete(false)
        }
    }

    suspend fun awaitReady(timeoutMillis: Long): Boolean {
        val signal = synchronized(lock) { ready }
        return withTimeoutOrNull(timeoutMillis.coerceAtLeast(1L)) {
            signal.await()
        } ?: false
    }
}
