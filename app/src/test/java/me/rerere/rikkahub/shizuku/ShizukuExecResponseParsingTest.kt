package me.rerere.rikkahub.shizuku

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [parseExecResponse], the AIDL-response parsing step [ShizukuManager.exec]
 * runs on whatever [ShizukuUserService.exec] returns over the binder. Pure string-in,
 * JsonObject-out; no Shizuku SDK or device involved.
 */
class ShizukuExecResponseParsingTest {

    @Test
    fun `well-formed exec result round-trips its fields`() {
        val raw = """{"success":true,"exit_code":0,"stdout":"hi\n","stderr":""}"""
        val parsed = parseExecResponse(raw)
        assertTrue(parsed["success"]!!.jsonPrimitive.boolean)
        assertEquals(0, parsed["exit_code"]!!.jsonPrimitive.int)
        assertEquals("hi\n", parsed["stdout"]!!.jsonPrimitive.content)
    }

    @Test
    fun `malformed json degrades to a structured error carrying the raw text`() {
        val raw = "not json at all {{{"
        val parsed = parseExecResponse(raw)
        assertEquals("shizuku_bad_response", parsed["error"]!!.jsonPrimitive.content)
        assertEquals(raw, parsed["raw"]!!.jsonPrimitive.content)
    }

    @Test
    fun `empty string degrades to a structured error rather than throwing`() {
        val parsed = parseExecResponse("")
        assertEquals("shizuku_bad_response", parsed["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a timeout envelope from the runner also round-trips through parsing`() {
        val raw = """{"error":"command_timeout","recovery":"bump timeout","partial_stdout":"before","partial_stderr":""}"""
        val parsed = parseExecResponse(raw)
        assertEquals("command_timeout", parsed["error"]!!.jsonPrimitive.content)
        assertEquals("before", parsed["partial_stdout"]!!.jsonPrimitive.content)
    }
}
