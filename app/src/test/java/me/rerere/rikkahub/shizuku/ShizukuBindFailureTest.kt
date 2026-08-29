package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [bindFailedResponse], the `shizuku_bind_failed` envelope builder that adds a
 * `phase` (and, for a thrown bind, a `reason`) so #45's three collapsed failure modes are
 * distinguishable again. Pure JsonObject-in/out; no Shizuku SDK or device involved, see
 * [ShizukuManager.ensureBound].
 */
class ShizukuBindFailureTest {

    @Test
    fun `bind threw maps to the bind_threw phase`() {
        val response = bindFailedResponse(BindResult.Failure.BindThrew(IllegalStateException("boom")))
        assertEquals("bind_threw", response["phase"]!!.jsonPrimitive.content)
    }

    @Test
    fun `binding died maps to the binding_died phase`() {
        val response = bindFailedResponse(BindResult.Failure.BindingDied)
        assertEquals("binding_died", response["phase"]!!.jsonPrimitive.content)
    }

    @Test
    fun `timeout maps to the bind_timeout phase`() {
        val response = bindFailedResponse(BindResult.Failure.Timeout)
        assertEquals("bind_timeout", response["phase"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reason is present only for bind_threw and carries class name and message`() {
        val threw = bindFailedResponse(BindResult.Failure.BindThrew(IllegalStateException("boom")))
        assertEquals("IllegalStateException: boom", threw["reason"]!!.jsonPrimitive.content)

        val died = bindFailedResponse(BindResult.Failure.BindingDied)
        assertNull(died["reason"])

        val timeout = bindFailedResponse(BindResult.Failure.Timeout)
        assertNull(timeout["reason"])
    }

    @Test
    fun `existing error and recovery fields are unchanged across all phases`() {
        val expectedRecovery = "Could not bind the Shizuku user service. Retry; if it keeps failing, restart the Shizuku service and re-grant permission from Settings -> Shizuku."
        for (failure in listOf(
            BindResult.Failure.BindThrew(RuntimeException("x")),
            BindResult.Failure.BindingDied,
            BindResult.Failure.Timeout,
        )) {
            val response = bindFailedResponse(failure)
            assertEquals("shizuku_bind_failed", response["error"]!!.jsonPrimitive.content)
            assertEquals(expectedRecovery, response["recovery"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `reason carries a null message as the literal string null`() {
        // Throwable#message can be null; the reason must still be a well-formed string rather
        // than throwing while building the envelope.
        val response = bindFailedResponse(BindResult.Failure.BindThrew(RuntimeException()))
        assertEquals("RuntimeException: null", response["reason"]!!.jsonPrimitive.content)
    }
}
