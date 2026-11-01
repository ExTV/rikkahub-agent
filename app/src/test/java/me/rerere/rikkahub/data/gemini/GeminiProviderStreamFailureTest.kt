package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException

/**
 * Covers `resolveStreamFailureCause`, the body of `streamText`'s `onFailure` listener extracted
 * into a pure, testable function. Its whole point is to guarantee `close()` always gets a cause
 * even when reading or parsing the error body throws (a truncated/aborted body): OkHttp never
 * re-dispatches a signalled callback, so a throw escaping `onFailure` would strand the
 * `callbackFlow` producer and its collector would wait forever. It is a top-level file-private
 * function, so reflection targets the `GeminiProviderKt` facade class like
 * [GeminiProviderRequestTest].
 */
class GeminiProviderStreamFailureTest {

    private val geminiProviderKt = Class.forName("me.rerere.rikkahub.data.gemini.GeminiProviderKt")
    private val json = Json { ignoreUnknownKeys = true }

    private fun resolve(
        t: Throwable?,
        responseCode: Int?,
        readDetail: () -> String?,
    ): Throwable {
        val method = geminiProviderKt.getDeclaredMethod(
            "resolveStreamFailureCause",
            Throwable::class.java,
            Integer::class.java,
            Json::class.java,
            kotlin.jvm.functions.Function0::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, t, responseCode, json, readDetail) as Throwable
    }

    @Test
    fun `a body read that throws does not escape - the producer always gets a close cause`() {
        // If the implementation let this propagate instead of catching it, the reflective
        // invoke() below would throw and fail this test - that failure mode is exactly the
        // stranded-producer bug this task fixes.
        val readFailure = IOException("truncated error body")
        val cause = resolve(t = null, responseCode = 500) { throw readFailure }
        assertSame(readFailure, cause)
    }

    @Test
    fun `an underlying transport throwable wins over a body read that also throws`() {
        val transportFailure = IOException("connection reset")
        val cause = resolve(t = transportFailure, responseCode = null) { throw IOException("also broken") }
        assertSame(transportFailure, cause)
    }

    @Test
    fun `the 400 case still surfaces the parsed provider message`() {
        val body = """{"error":{"code":400,"message":"invalid argument"}}"""
        val cause = resolve(t = null, responseCode = 400) { body }
        assertEquals("Cloud Code Assist error (400): invalid argument", cause.message)
    }

    @Test
    fun `an unparseable body falls back to the raw code and detail`() {
        val cause = resolve(t = null, responseCode = 503) { "<html>upstream down</html>" }
        assertEquals("Cloud Code Assist request failed: 503 <html>upstream down</html>", cause.message)
    }

    @Test
    fun `a null detail (no response body) falls back to the raw code`() {
        val cause = resolve(t = null, responseCode = 500) { null }
        assertEquals("Cloud Code Assist request failed: 500 null", cause.message)
    }
}
