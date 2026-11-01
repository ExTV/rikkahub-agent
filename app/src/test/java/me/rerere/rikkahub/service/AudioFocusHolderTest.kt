package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for #30 (resume_media instantly re-pauses): [AudioFocusHolder] must hand
 * the *same* listener and request instance to every focus request, including after an abandon,
 * so the framework never treats a resume-triggered re-request as a different client stealing
 * focus from our own still-held request.
 */
class AudioFocusHolderTest {

    @Test
    fun `N successive requestFocus calls produce exactly one listener and one request`() {
        var listenerCreations = 0
        var requestCreations = 0
        val holder = AudioFocusHolder<Any, Any>(
            createListener = { listenerCreations++; Any() },
            createRequest = { requestCreations++; Any() },
            doRequest = { true },
            doAbandon = { },
        )

        repeat(5) { holder.requestFocus() }

        assertEquals(1, listenerCreations)
        assertEquals(1, requestCreations)
    }

    @Test
    fun `requestFocus reuses the same listener and request instances across calls`() {
        var capturedListener: Any? = null
        var capturedRequest: Any? = null
        val holder = AudioFocusHolder<Any, Any>(
            createListener = { Any().also { capturedListener = it } },
            createRequest = { listener -> Any().also { capturedRequest = it; assertSame(capturedListener, listener) } },
            doRequest = { true },
            doAbandon = { },
        )

        holder.requestFocus()
        val listenerAfterFirstCall = capturedListener
        val requestAfterFirstCall = capturedRequest

        holder.requestFocus()
        holder.requestFocus()

        assertSame(listenerAfterFirstCall, capturedListener)
        assertSame(requestAfterFirstCall, capturedRequest)
    }

    @Test
    fun `abandon then requestFocus still yields a granted request, reusing the same instance`() {
        var requestCreations = 0
        var abandonCalls = 0
        var abandonedRequest: Any? = null
        var requestedWith: Any? = null
        val holder = AudioFocusHolder<Any, Any>(
            createListener = { Any() },
            createRequest = { requestCreations++; Any() },
            doRequest = { request -> requestedWith = request; true },
            doAbandon = { request -> abandonCalls++; abandonedRequest = request },
        )

        assertTrue(holder.requestFocus())
        val firstRequest = requestedWith

        holder.abandon()
        assertEquals(1, abandonCalls)
        assertSame(firstRequest, abandonedRequest)

        assertTrue(holder.requestFocus())

        assertEquals(1, requestCreations) // rebuilt only once, ever - re-request reuses it
        assertSame(firstRequest, requestedWith)
    }
}
