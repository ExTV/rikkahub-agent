package me.rerere.common.http

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KClass

class RequestTest {
    @Test
    fun `cancelling await cancels the underlying http call`() = runBlocking {
        val call = NeverCompletingCall()
        val job = launch { call.await() }
        yield()

        job.cancelAndJoin()

        assertTrue(call.isCanceled())
    }

    private class NeverCompletingCall : Call {
        private val cancelled = AtomicBoolean(false)
        private val request = Request.Builder().url("https://example.invalid").build()

        override fun request(): Request = request

        override fun execute(): Response = error("This test call never executes synchronously")

        override fun enqueue(responseCallback: Callback) = Unit

        // Present on this project's OkHttp; the interface gained it after the test
        // double was written.
        override fun addEventListener(eventListener: EventListener) = Unit

        override fun cancel() {
            cancelled.set(true)
        }

        override fun isExecuted(): Boolean = true

        override fun isCanceled(): Boolean = cancelled.get()

        override fun timeout(): Timeout = Timeout.NONE

        override fun <T : Any> tag(type: KClass<T>): T? = null

        override fun <T> tag(type: Class<out T>): T? = null

        override fun <T : Any> tag(type: KClass<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun <T : Any> tag(type: Class<T>, computeIfAbsent: () -> T): T =
            computeIfAbsent()

        override fun clone(): Call = NeverCompletingCall()
    }
}
