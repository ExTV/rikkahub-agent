package me.rerere.rikkahub.data.ai.tools.local

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebExtractToolTest {

    @Test
    fun `tool is named web_extract and defaults to article`() {
        val tool = webExtractTool(OkHttpClient())

        assertEquals("web_extract", tool.name)
        assertTrue(tool.description.contains("article"))
    }

    @Test
    fun `description steers away from raw markup`() {
        val tool = webExtractTool(OkHttpClient())

        assertTrue(tool.description.lowercase().contains("readable"))
    }
}
