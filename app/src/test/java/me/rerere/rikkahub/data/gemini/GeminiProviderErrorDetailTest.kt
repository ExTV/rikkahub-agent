package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers `parseErrorMessage`'s rendering of `error.status` and `error.details[]`
 * (`fieldViolations` in particular) for Cloud Code Assist's `INVALID_ARGUMENT` responses. It is a
 * file-private top-level function, so reflection targets the `GeminiProviderKt` facade class like
 * [GeminiProviderRequestTest].
 */
class GeminiProviderErrorDetailTest {

    private val geminiProviderKt = Class.forName("me.rerere.rikkahub.data.gemini.GeminiProviderKt")
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(body: String?): String? {
        val method = geminiProviderKt.getDeclaredMethod(
            "parseErrorMessage",
            String::class.java,
            Json::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, body, json) as String?
    }

    @Test
    fun `a fieldViolations entry surfaces the offending field and description`() {
        val body = """
            {
              "error": {
                "code": 400,
                "message": "Request contains an invalid argument.",
                "status": "INVALID_ARGUMENT",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.BadRequest",
                    "fieldViolations": [
                      {
                        "field": "generationConfig.thinkingConfig.thinkingLevel",
                        "description": "Invalid value at 'thinking_level'"
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
        val message = parse(body)
        assertTrue(message?.startsWith("Cloud Code Assist error (400): Request contains an invalid argument.") == true)
        assertTrue(message?.contains("status=INVALID_ARGUMENT") == true)
        assertTrue(message?.contains("field=generationConfig.thinkingConfig.thinkingLevel") == true)
        assertTrue(message?.contains("description=Invalid value at 'thinking_level'") == true)
    }

    @Test
    fun `a non-BadRequest detail renders its type plus scalar fields`() {
        val body = """
            {
              "error": {
                "code": 400,
                "message": "denied",
                "status": "PERMISSION_DENIED",
                "details": [
                  {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "API_KEY_INVALID",
                    "domain": "googleapis.com"
                  }
                ]
              }
            }
        """.trimIndent()
        val message = parse(body)
        assertTrue(message?.contains("type.googleapis.com/google.rpc.ErrorInfo") == true)
        assertTrue(message?.contains("reason=API_KEY_INVALID") == true)
    }

    @Test
    fun `an empty details array behaves like today - message unchanged`() {
        val body = """{"error":{"code":400,"message":"invalid argument","details":[]}}"""
        assertEquals("Cloud Code Assist error (400): invalid argument", parse(body))
    }

    @Test
    fun `details in an unexpected shape degrade to just the prefix rather than throw`() {
        val body = """{"error":{"code":400,"message":"invalid argument","details":"not-an-array"}}"""
        assertEquals("Cloud Code Assist error (400): invalid argument", parse(body))
    }

    @Test
    fun `a fieldViolations entry missing both field and description is skipped, not thrown`() {
        val body = """
            {"error":{"code":400,"message":"invalid argument","details":[
                {"@type":"type.googleapis.com/google.rpc.BadRequest","fieldViolations":[{}]}
            ]}}
        """.trimIndent()
        val message = parse(body)
        assertTrue(message?.startsWith("Cloud Code Assist error (400): invalid argument") == true)
    }

    @Test
    fun `a non-JSON body degrades to null rather than throw`() {
        assertNull(parse("<html>upstream down</html>"))
    }

    @Test
    fun `a null body still returns null`() {
        assertNull(parse(null))
    }
}
