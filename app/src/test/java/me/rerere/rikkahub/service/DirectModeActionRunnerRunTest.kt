package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Covers DirectModeActionRunner.run() outcome mapping. The focus is the tool-unavailable
 * path: a direct-mode cron job validates its tool list at creation time, but the assistant's
 * enabled-tools set can change later. When a referenced tool is no longer available at fire
 * time the runner must produce a FAILED outcome whose errorMessage NAMES the missing tool,
 * so the failed run-history row tells the user exactly what to re-enable.
 */
class DirectModeActionRunnerRunTest {

    private fun fakeTool(name: String): Tool = Tool(
        name = name,
        description = "fake",
        execute = { listOf(UIMessagePart.Text("ok")) },
    )

    private fun action(tool: String): DirectModeActionRunner.Action =
        DirectModeActionRunner.Action(tool, JsonObject(emptyMap()))

    @Test
    fun `all tools available yields success`() = runBlocking {
        val runner = DirectModeActionRunner(kotlinx.serialization.json.Json)
        val seq = runner.run(
            actions = listOf(action("send_notification")),
            availableTools = listOf(fakeTool("send_notification")),
        )
        assertEquals("success", seq.finalOutcome)
        assertNull(seq.errorMessage)
    }

    @Test
    fun `unavailable tool yields failed outcome naming the tool`() = runBlocking {
        val runner = DirectModeActionRunner(kotlinx.serialization.json.Json)
        val seq = runner.run(
            actions = listOf(action("send_notification")),
            availableTools = emptyList(), // tool was disabled after job creation
        )
        assertEquals("failed", seq.finalOutcome)
        val err = requireNotNull(seq.errorMessage)
        assertTrue("error uses the tool_unavailable code", err.contains("tool_unavailable"))
        assertTrue("error names the exact missing tool", err.contains("send_notification"))
        assertTrue("error names the action index", err.contains("action 0"))
    }

    @Test
    fun `unavailable tool in second action is still reported by name`() = runBlocking {
        val runner = DirectModeActionRunner(kotlinx.serialization.json.Json)
        val seq = runner.run(
            actions = listOf(action("get_battery"), action("send_sms")),
            availableTools = listOf(fakeTool("get_battery")), // send_sms missing
        )
        assertEquals("failed", seq.finalOutcome)
        val err = requireNotNull(seq.errorMessage)
        assertTrue("error names the missing tool", err.contains("send_sms"))
        assertTrue("error uses the tool_unavailable code", err.contains("tool_unavailable"))
        assertTrue("error points at action index 1", err.contains("action 1"))
    }

    /**
     * A cancelled tool must unwind the run as a real cancellation, not get turned into a
     * `failed` SequenceResult. WorkflowActionRunner already re-throws CancellationException
     * for this exact reason (see its `run()`); DirectModeActionRunner did not.
     */
    @Test
    fun `cancellation from a tool propagates instead of becoming a Failed outcome`() = runBlocking {
        val runner = DirectModeActionRunner(kotlinx.serialization.json.Json)
        val cancellingTool = Tool(
            name = "boom",
            description = "fake",
            execute = { throw CancellationException("run cancelled") },
        )
        var caught: CancellationException? = null
        try {
            runner.run(actions = listOf(action("boom")), availableTools = listOf(cancellingTool))
            fail("expected CancellationException to propagate, not be swallowed into a SequenceResult")
        } catch (c: CancellationException) {
            caught = c
        }
        assertTrue("must be the real CancellationException, not a converted Failed step", caught != null)
    }
}
