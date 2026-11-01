package me.rerere.rikkahub.service

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a shared foreground-work resource alive while one or more chat operations are active.
 * Each [acquire] call returns an idempotent release callback so cancellation and normal
 * completion can race without stopping the resource for another still-running operation.
 */
internal class ForegroundWorkTracker(
    private val onFirstAcquire: () -> Unit,
    private val onLastRelease: () -> Unit,
) {
    private val lock = Any()
    private var activeCount = 0

    fun acquire(): () -> Unit {
        synchronized(lock) {
            if (activeCount++ == 0) onFirstAcquire()
        }

        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) {
                synchronized(lock) {
                    check(activeCount > 0) { "Foreground work count underflow" }
                    if (--activeCount == 0) onLastRelease()
                }
            }
        }
    }
}
