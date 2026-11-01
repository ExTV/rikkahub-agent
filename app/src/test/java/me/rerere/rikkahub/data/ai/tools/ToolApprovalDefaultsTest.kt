package me.rerere.rikkahub.data.ai.tools

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for issue #42: launch_activity had no approval gate while its sibling launch_app
 * did. Asserts the fix without re-testing the whole [ToolApprovalDefaults] set.
 */
class ToolApprovalDefaultsTest {

    @Test
    fun `launch_activity requires approval, same as launch_app`() {
        assertTrue(ToolApprovalDefaults.requiresApproval("launch_activity"))
        assertTrue(ToolApprovalDefaults.allowsAlwaysAllow("launch_activity"))
    }
}
