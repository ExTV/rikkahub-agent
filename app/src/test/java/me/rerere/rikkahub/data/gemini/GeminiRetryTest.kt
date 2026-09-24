package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pieces added to fix issue #34 (429 RESOURCE_EXHAUSTED on every model despite quota
 * being available): the pinned Antigravity `User-Agent` format, the daily/sandbox/prod endpoint
 * fallback order, and the retry classifier ([classifyGeminiError], [parseGeminiRetryDelayMs],
 * [resolveGeminiRetryDelayMs]) that decides whether and how long to wait before retrying a
 * `streamGenerateContent`/`fetchAvailableModels` failure. All of these are internal top-level (or
 * internal file) declarations, so the tests call them directly - no reflection needed.
 */
class GeminiRetryTest {
    private val json = Json { ignoreUnknownKeys = true }

    // --- User-Agent format ---------------------------------------------------------------

    @Test
    fun `the default user agent matches the pinned antigravity hub format`() {
        assertEquals(
            "antigravity/hub/2.8.0 (aidev_client; os_type=darwin; arch=arm64; cl=963137146)",
            buildAntigravityUserAgent(),
        )
    }

    @Test
    fun `a different version, os, arch and cl are all substituted into the format`() {
        assertEquals(
            "antigravity/hub/9.9.9 (aidev_client; os_type=linux; arch=amd64; cl=123)",
            buildAntigravityUserAgent(version = "9.9.9", os = "linux", arch = "amd64", cl = "123"),
        )
    }

    // --- Endpoint order --------------------------------------------------------------------

    @Test
    fun `generate traffic tries daily, then daily sandbox, then prod as a last resort`() {
        assertEquals(
            listOf(
                "https://daily-cloudcode-pa.googleapis.com",
                "https://daily-cloudcode-pa.sandbox.googleapis.com",
                "https://cloudcode-pa.googleapis.com",
            ),
            GEMINI_GENERATE_ENDPOINTS,
        )
    }

    @Test
    fun `the last generate endpoint is still the prod Code Assist endpoint`() {
        assertEquals(GeminiAccountRepository.CODE_ASSIST_ENDPOINT, GEMINI_GENERATE_ENDPOINTS.last())
    }

    // --- RetryInfo / ErrorInfo parsing -------------------------------------------------------

    @Test
    fun `retryDelay in seconds parses to milliseconds`() {
        assertEquals(12_000L, parseGeminiRetryDelayMs("12s"))
    }

    @Test
    fun `retryDelay in milliseconds parses directly`() {
        assertEquals(500L, parseGeminiRetryDelayMs("500ms"))
    }

    @Test
    fun `a fractional retryDelay in seconds parses to the nearest millisecond`() {
        assertEquals(34_074L, parseGeminiRetryDelayMs("34.074824224s"))
    }

    @Test
    fun `a malformed retryDelay does not throw and yields no value`() {
        assertNull(parseGeminiRetryDelayMs("soon"))
    }

    // --- classifyGeminiError: retryable vs terminal ------------------------------------------

    private fun rateLimitExceededBody(retryDelay: String) = """
        {
          "error": {
            "code": 429,
            "message": "Resource has been exhausted (e.g. check quota).",
            "status": "RESOURCE_EXHAUSTED",
            "details": [
              {
                "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                "reason": "RATE_LIMIT_EXCEEDED",
                "domain": "cloudcode-pa.googleapis.com"
              },
              {
                "@type": "type.googleapis.com/google.rpc.RetryInfo",
                "retryDelay": "$retryDelay"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `a 429 with a short RATE_LIMIT_EXCEEDED retryDelay is retryable and parses the delay`() {
        val classification = classifyGeminiError(429, rateLimitExceededBody("12s"), json)
        assertTrue(classification.retryable)
        assertEquals("RATE_LIMIT_EXCEEDED", classification.reason)
        assertEquals(12_000L, classification.retryDelayMs)
    }

    @Test
    fun `a 429 whose own retryDelay is beyond the cap is treated as a long quota window, not retryable`() {
        // 6 minutes is past GEMINI_RETRY_DELAY_CAP_MS (5 minutes), so this is really an account
        // quota window even though the reason is RATE_LIMIT_EXCEEDED rather than QUOTA_EXHAUSTED.
        val classification = classifyGeminiError(429, rateLimitExceededBody("360s"), json)
        assertFalse(classification.retryable)
    }

    @Test
    fun `a 429 with reason QUOTA_EXHAUSTED is never retryable regardless of any retryDelay`() {
        val body = """
            {
              "error": {
                "code": 429,
                "message": "You have exhausted your capacity on this model.",
                "status": "RESOURCE_EXHAUSTED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "QUOTA_EXHAUSTED",
                    "domain": "cloudcode-pa.googleapis.com"
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "5s"
                  }
                ]
              }
            }
        """.trimIndent()
        val classification = classifyGeminiError(429, body, json)
        assertFalse(classification.retryable)
        assertEquals("QUOTA_EXHAUSTED", classification.reason)
        assertEquals(5_000L, classification.retryDelayMs)
    }

    @Test
    fun `a bare 429 with no structured detail is still retryable off the status alone`() {
        val classification = classifyGeminiError(429, """{"error":{"code":429,"message":"rate limited"}}""", json)
        assertTrue(classification.retryable)
        assertNull(classification.retryDelayMs)
    }

    @Test
    fun `a 500 is retryable`() {
        assertTrue(classifyGeminiError(500, null, json).retryable)
    }

    @Test
    fun `a 400 is never retryable`() {
        assertFalse(classifyGeminiError(400, """{"error":{"code":400,"message":"bad request"}}""", json).retryable)
    }

    @Test
    fun `a 401 is never retryable`() {
        assertFalse(classifyGeminiError(401, null, json).retryable)
    }

    @Test
    fun `an embedded 429 error code inside a 200 SSE event is retryable off the body's code`() {
        // No HTTP status: this is what a Cloud Code Assist stream error looks like as the first
        // SSE event after a 200 response (google-gemini-cli.ts:766-771 reads this same
        // error.code as the status for exactly this reason).
        val classification = classifyGeminiError(null, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"rate limited"}}""", json)
        assertTrue(classification.retryable)
        assertEquals(429, classification.status)
    }

    @Test
    fun `an embedded 400 error code inside a 200 SSE event is never retryable`() {
        val classification = classifyGeminiError(null, """{"error":{"code":400,"message":"invalid argument"}}""", json)
        assertFalse(classification.retryable)
        assertEquals(400, classification.status)
    }

    @Test
    fun `an embedded error body with no numeric code at all is not retryable`() {
        val classification = classifyGeminiError(null, """{"error":{"message":"boom"}}""", json)
        assertFalse(classification.retryable)
        assertNull(classification.status)
    }

    @Test
    fun `a real HTTP status wins over any embedded body code`() {
        // If a transport ever hands both, the HTTP status is authoritative, not the body.
        val classification = classifyGeminiError(400, """{"error":{"code":429,"message":"rate limited"}}""", json)
        assertFalse(classification.retryable)
        assertEquals(400, classification.status)
    }

    @Test
    fun `a RATE_LIMIT_EXCEEDED reason whose message names a per-model quota is promoted to terminal`() {
        // rate-limit.ts's ANTIGRAVITY_MODEL_QUOTA_PATTERN promotion: same reason and a short
        // retryDelay, but the message itself says this is a quota, not a throttle.
        val body = """
            {
              "error": {
                "code": 429,
                "message": "You have exhausted your capacity on this model. Your quota will reset after some time.",
                "status": "RESOURCE_EXHAUSTED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "RATE_LIMIT_EXCEEDED",
                    "domain": "cloudcode-pa.googleapis.com"
                  },
                  {
                    "@type": "type.googleapis.com/google.rpc.RetryInfo",
                    "retryDelay": "5s"
                  }
                ]
              }
            }
        """.trimIndent()
        val classification = classifyGeminiError(429, body, json)
        assertFalse(classification.retryable)
        assertEquals("QUOTA_EXHAUSTED", classification.reason)
    }

    @Test
    fun `an unparseable body degrades to classifying off the status alone rather than throwing`() {
        val classification = classifyGeminiError(503, "<html>upstream down</html>", json)
        assertTrue(classification.retryable)
        assertNull(classification.retryDelayMs)
    }

    // --- resolveGeminiRetryDelayMs -----------------------------------------------------------

    @Test
    fun `the server's own retry delay wins over the default backoff`() {
        val classification = GeminiErrorClassification(429, "RATE_LIMIT_EXCEEDED", 12_000L, retryable = true)
        assertEquals(12_000L, resolveGeminiRetryDelayMs(classification, attempt = 0))
    }

    @Test
    fun `with no server hint, backoff doubles from the base delay each attempt`() {
        val classification = GeminiErrorClassification(500, null, null, retryable = true)
        assertEquals(GEMINI_RETRY_BASE_DELAY_MS, resolveGeminiRetryDelayMs(classification, attempt = 0))
        assertEquals(GEMINI_RETRY_BASE_DELAY_MS * 2, resolveGeminiRetryDelayMs(classification, attempt = 1))
        assertEquals(GEMINI_RETRY_BASE_DELAY_MS * 4, resolveGeminiRetryDelayMs(classification, attempt = 2))
    }

    @Test
    fun `the resolved delay never exceeds the retry delay cap`() {
        val classification = GeminiErrorClassification(500, null, null, retryable = true)
        assertEquals(GEMINI_RETRY_DELAY_CAP_MS, resolveGeminiRetryDelayMs(classification, attempt = 20))
    }
}
