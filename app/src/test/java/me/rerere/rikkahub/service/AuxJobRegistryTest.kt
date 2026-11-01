package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class AuxJobRegistryTest {
    @Test
    fun `launching the same key twice cancels the first`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val registry = AuxJobRegistry()
        val conversationId = Uuid.random()
        try {
            val first = registry.launch(scope, conversationId, AuxJobKind.TITLE) { awaitCancellation() }
            val second = registry.launch(scope, conversationId, AuxJobKind.TITLE) { awaitCancellation() }

            assertTrue(first.isCancelled)
            assertFalse(second.isCancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `different conversations are independent`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val registry = AuxJobRegistry()
        val conversationA = Uuid.random()
        val conversationB = Uuid.random()
        try {
            val jobA = registry.launch(scope, conversationA, AuxJobKind.TITLE) { awaitCancellation() }
            val jobB = registry.launch(scope, conversationB, AuxJobKind.TITLE) { awaitCancellation() }

            registry.cancelAll(conversationA)

            assertTrue(jobA.isCancelled)
            assertFalse(jobB.isCancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `starting a generation cancels only that conversation's aux jobs, of every kind`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val registry = AuxJobRegistry()
        val conversationId = Uuid.random()
        val otherConversationId = Uuid.random()
        try {
            val title = registry.launch(scope, conversationId, AuxJobKind.TITLE) { awaitCancellation() }
            val suggestion = registry.launch(scope, conversationId, AuxJobKind.SUGGESTION) { awaitCancellation() }
            val otherTitle = registry.launch(scope, otherConversationId, AuxJobKind.TITLE) { awaitCancellation() }

            // Mirrors ChatService.sendQueuedMessage / regenerateAtMessage calling
            // auxJobs.cancelAll(conversationId) right before starting a new main generation.
            registry.cancelAll(conversationId)

            assertTrue(title.isCancelled)
            assertTrue(suggestion.isCancelled)
            assertFalse(otherTitle.isCancelled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a completed job deregisters itself instead of leaking`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val registry = AuxJobRegistry()
        val conversationId = Uuid.random()
        try {
            val done = CompletableDeferred<Unit>()
            val job = registry.launch(scope, conversationId, AuxJobKind.TITLE) { done.await() }
            assertTrue(registry.isTracked(conversationId, AuxJobKind.TITLE))

            done.complete(Unit)
            job.join()

            assertFalse(registry.isTracked(conversationId, AuxJobKind.TITLE))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a cancelled job deregisters itself instead of leaking`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val registry = AuxJobRegistry()
        val conversationId = Uuid.random()
        try {
            val job = registry.launch(scope, conversationId, AuxJobKind.SUGGESTION) { awaitCancellation() }
            registry.cancelAll(conversationId)
            job.join()

            assertFalse(registry.isTracked(conversationId, AuxJobKind.SUGGESTION))
        } finally {
            scope.cancel()
        }
    }
}
