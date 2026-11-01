package me.rerere.rikkahub.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentToolsEncodeRunTest {

    private fun makeRun(
        result: String? = "the answer",
        noResult: Boolean = false,
    ): SubAgentRun = SubAgentRun(
        id = "r1",
        parentChatId = "chat-1",
        parentAssistantId = "asst-1",
        label = "label",
        task = "task",
        modelId = null,
        tools = null,
        runInBackground = false,
        noResult = noResult,
        timeoutSeconds = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
        maxTrips = SubAgentDefaults.DEFAULT_MAX_TRIPS,
        status = SubAgentStatus.SUCCEEDED,
        result = result,
        startedAtMs = System.currentTimeMillis(),
    )

    @Test fun `result present and result_suppressed absent when noResult is false`() {
        val json = encodeRun(makeRun(result = "the answer", noResult = false))
        assertEquals("the answer", json["result"]?.toString()?.trim('"'))
        assertFalse(json.containsKey("result_suppressed"))
    }

    @Test fun `result absent and result_suppressed present when noResult is true`() {
        val json = encodeRun(makeRun(result = "the answer", noResult = true))
        assertFalse(json.containsKey("result"))
        assertTrue(json["result_suppressed"]?.toString()?.toBoolean() == true)
    }

    @Test fun `result_suppressed present even when result is null and noResult is true`() {
        val json = encodeRun(makeRun(result = null, noResult = true))
        assertFalse(json.containsKey("result"))
        assertTrue(json["result_suppressed"]?.toString()?.toBoolean() == true)
    }

    @Test fun `neither key present when result is null and noResult is false`() {
        val json = encodeRun(makeRun(result = null, noResult = false))
        assertFalse(json.containsKey("result"))
        assertFalse(json.containsKey("result_suppressed"))
    }
}
