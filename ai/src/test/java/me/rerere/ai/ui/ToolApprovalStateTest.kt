package me.rerere.ai.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalStateTest {
    @Test
    fun `cancelled pending tool no longer waits for approval or resumes execution`() {
        val pending = UIMessagePart.Tool("call", "write_file", "{}",
            approvalState = ToolApprovalState.Pending)
        assertTrue(pending.isPending)
        val cancelled = pending.copy(output = listOf(UIMessagePart.Text("cancelled")))
        assertFalse(cancelled.isPending)
        assertFalse(cancelled.canResumeExecution)
        assertTrue(cancelled.approvalState is ToolApprovalState.Pending)
    }

    @Test
    fun `approved denied and answered states can resume tool execution`() {
        assertTrue(ToolApprovalState.Approved.canResumeToolExecution())
        assertTrue(ToolApprovalState.Denied("no").canResumeToolExecution())
        assertTrue(ToolApprovalState.Answered("""{"answers":{"q1":"yes"}}""").canResumeToolExecution())
    }

    @Test
    fun `auto and pending states cannot resume tool execution`() {
        assertFalse(ToolApprovalState.Auto.canResumeToolExecution())
        assertFalse(ToolApprovalState.Pending.canResumeToolExecution())
    }

    @Test
    fun `an Auto tool that started executing but produced no output is an interrupted attempt`() {
        // Auto tools take the identical mark-then-execute path as Approved tools in
        // GenerationLoop (executionStartedAt is set before toolDef.execute runs). A process
        // kill mid-execute must be detectable the same way for both, not just Approved.
        val interruptedAuto = UIMessagePart.Tool(
            toolCallId = "call",
            toolName = "write_file",
            input = "{}",
            approvalState = ToolApprovalState.Auto,
            executionStartedAt = 1_000L,
        )
        assertTrue(interruptedAuto.isInterruptedAttempt)
    }

    @Test
    fun `an Auto tool that never started executing is not an interrupted attempt`() {
        val freshAuto = UIMessagePart.Tool(
            toolCallId = "call",
            toolName = "write_file",
            input = "{}",
            approvalState = ToolApprovalState.Auto,
        )
        assertFalse(freshAuto.isInterruptedAttempt)
    }
}
