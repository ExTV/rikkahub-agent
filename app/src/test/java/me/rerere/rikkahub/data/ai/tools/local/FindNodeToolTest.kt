package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertTrue
import org.junit.Test

class FindNodeToolTest {

    @Test
    fun `find_node rejects bad by value`() {
        val tool = findNodeTool()
        val result = execTool(tool, """{"by":"class","value":"FooView"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `find_node rejects missing value`() {
        val tool = findNodeTool()
        val result = execTool(tool, """{"by":"text"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `find_node hits service-not-active when offline`() {
        val tool = findNodeTool()
        val result = execTool(tool, """{"by":"text","value":"OK"}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }

    @Test
    fun `click_node rejects negative nth`() {
        val tool = clickNodeTool()
        val result = execTool(tool, """{"by":"text","value":"OK","nth":-1}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `click_node rejects bad by`() {
        val tool = clickNodeTool()
        val result = execTool(tool, """{"by":"id","value":"x"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `click_node rejects malformed node_id`() {
        val tool = clickNodeTool()
        val result = execTool(tool, """{"node_id":"garbage"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
        assertTrue("error should name node_id, got: $result", result.contains("node_id"))
    }

    @Test
    fun `click_node with neither node_id nor selector errors`() {
        val tool = clickNodeTool()
        val result = execTool(tool, """{}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }

    @Test
    fun `click_node with valid node_id but offline service returns not-active`() {
        val tool = clickNodeTool()
        val result = execTool(tool, """{"node_id":"1:2"}""")
        assertTrue(
            "expected service-not-active envelope, got: $result",
            result.contains("AccessibilityService not active")
        )
    }

    @Test
    fun `set_text rejects malformed node_id`() {
        val tool = setTextTool()
        val result = execTool(tool, """{"node_id":"1:0","text":"hi"}""")
        assertTrue("expected error envelope, got: $result", result.contains("\"error\""))
    }
}
