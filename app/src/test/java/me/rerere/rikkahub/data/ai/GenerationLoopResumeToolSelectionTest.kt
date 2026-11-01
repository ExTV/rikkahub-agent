package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #107: a model step's unexecuted `Auto` tools were orphaned forever whenever a Pending
 * sibling in the SAME step made GenerationLoop break before executing anything - resuming after
 * the user's approval decision only picked up the tools the user had acted on
 * (canResumeExecution), never the Auto siblings, so the model never learned their results.
 *
 * Driving the full GenerationLoop.generateText resume path needs Android Context + the Koin
 * graph, out of JVM unit test scope (see GenerationHandlerTurnBudgetTest's note on the same
 * limitation), so this exercises the extracted pure selection function directly.
 */
class GenerationLoopResumeToolSelectionTest {

    private fun tool(
        id: String,
        state: ToolApprovalState,
        executed: Boolean = false,
        executionStartedAt: Long? = null,
    ) = UIMessagePart.Tool(
        toolCallId = id,
        toolName = "tool_$id",
        input = "{}",
        output = if (executed) listOf(UIMessagePart.Text("done")) else emptyList(),
        approvalState = state,
        executionStartedAt = executionStartedAt,
    )

    @Test
    fun `four unexecuted tools, two Approved and two Auto, all resume in original call order`() {
        val tools = listOf(
            tool("t1", ToolApprovalState.Approved),
            tool("t2", ToolApprovalState.Auto),
            tool("t3", ToolApprovalState.Approved),
            tool("t4", ToolApprovalState.Auto),
        )
        val resumed = resumableToolsIncludingUnexecutedAuto(tools)
        assertEquals(listOf("t1", "t2", "t3", "t4"), resumed.map { it.toolCallId })
    }

    @Test
    fun `a Denied sibling resumes alongside the unexecuted Auto tools`() {
        val tools = listOf(
            tool("t1", ToolApprovalState.Denied("not allowed")),
            tool("t2", ToolApprovalState.Auto),
            tool("t3", ToolApprovalState.Auto),
        )
        val resumed = resumableToolsIncludingUnexecutedAuto(tools)
        assertEquals(listOf("t1", "t2", "t3"), resumed.map { it.toolCallId })
    }

    @Test
    fun `an Answered tool resumes alongside the unexecuted Auto tools`() {
        val tools = listOf(
            tool("t1", ToolApprovalState.Answered("yes")),
            tool("t2", ToolApprovalState.Auto),
        )
        val resumed = resumableToolsIncludingUnexecutedAuto(tools)
        assertEquals(listOf("t1", "t2"), resumed.map { it.toolCallId })
    }

    @Test
    fun `a tool still Pending never resumes`() {
        val tools = listOf(
            tool("t1", ToolApprovalState.Approved),
            tool("t2", ToolApprovalState.Pending),
            tool("t3", ToolApprovalState.Auto),
        )
        val resumed = resumableToolsIncludingUnexecutedAuto(tools)
        assertEquals(listOf("t1", "t3"), resumed.map { it.toolCallId })
    }

    @Test
    fun `an already-executed Auto tool is not re-run`() {
        val tools = listOf(
            tool("t1", ToolApprovalState.Approved),
            tool("t2", ToolApprovalState.Auto, executed = true),
        )
        val resumed = resumableToolsIncludingUnexecutedAuto(tools)
        assertEquals(listOf("t1"), resumed.map { it.toolCallId })
    }

    @Test
    fun `an interrupted Auto tool (executionStartedAt set, no output) does not resume as unexecuted`() {
        // Auto tools are marked with executionStartedAt before GenerationLoop runs their
        // execute body, same as Approved. If the process is killed mid-execute, the tool is
        // still nominally Auto with empty output - it must not be picked up here as a fresh,
        // never-tried Auto tool and blindly re-run. In production this case is instead
        // caught by the top-of-generateText replay-safety pass (isInterruptedAttempt flips
        // it to Denied before this function ever sees it), but this pure function must not
        // rely on that ordering to be safe.
        val tools = listOf(
            tool("t1", ToolApprovalState.Approved),
            tool("t2", ToolApprovalState.Auto, executionStartedAt = 1_000L),
        )
        val resumed = resumableToolsIncludingUnexecutedAuto(tools)
        assertEquals(listOf("t1"), resumed.map { it.toolCallId })
    }

    @Test
    fun `no tools resume when everything is still Pending`() {
        val tools = listOf(
            tool("t1", ToolApprovalState.Pending),
            tool("t2", ToolApprovalState.Pending),
        )
        val resumed = resumableToolsIncludingUnexecutedAuto(tools)
        assertEquals(emptyList<String>(), resumed.map { it.toolCallId })
    }
}
