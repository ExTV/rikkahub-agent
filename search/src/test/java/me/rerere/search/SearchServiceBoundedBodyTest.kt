package me.rerere.search

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchServiceBoundedBodyTest {

    private fun responseWithBody(content: String): Response {
        val request = Request.Builder().url("https://example.com/").build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(content.toResponseBody("text/html; charset=utf-8".toMediaType()))
            .build()
    }

    @Test
    fun `caps a body larger than the limit`() {
        val cap = 256 * 1024
        val huge = "a".repeat(cap * 2)

        val result = boundedBody(responseWithBody(huge), cap)

        assertTrue(result.length <= cap)
    }

    @Test
    fun `returns a small body intact`() {
        val small = "<html><body>hello</body></html>"

        val result = boundedBody(responseWithBody(small), 256 * 1024)

        assertEquals(small, result)
    }
}
