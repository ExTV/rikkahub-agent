package me.rerere.common.js

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickJSFetchBoundedBodyTest {

    private fun responseWithBody(content: String): Response {
        val request = Request.Builder().url("https://example.com/").build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(content.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()
    }

    @Test
    fun `caps a body larger than the limit instead of buffering it whole`() {
        val cap = 256 * 1024
        val huge = "a".repeat(cap * 2)

        val result = readBoundedBody(responseWithBody(huge), cap)

        assertTrue(result.length <= cap)
    }

    @Test
    fun `returns a small body intact`() {
        val small = """{"results":[{"title":"a","url":"https://a","text":"b"}]}"""

        val result = readBoundedBody(responseWithBody(small), 256 * 1024)

        assertEquals(small, result)
    }
}
