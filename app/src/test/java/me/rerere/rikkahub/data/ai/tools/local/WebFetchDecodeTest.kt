package me.rerere.rikkahub.data.ai.tools.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class WebFetchDecodeTest {

    @Test
    fun `honours an explicit charset in content type`() {
        val text = "Grüße"
        val bytes = text.toByteArray(Charset.forName("ISO-8859-1"))

        val out = decodeBody(bytes, bytes.size, "text/html; charset=ISO-8859-1")

        assertEquals(text, out)
    }

    @Test
    fun `strips quotes around the charset token`() {
        val text = "Grüße"
        val bytes = text.toByteArray(Charset.forName("ISO-8859-1"))

        val out = decodeBody(bytes, bytes.size, "text/html; charset=\"ISO-8859-1\"")

        assertEquals(text, out)
    }

    @Test
    fun `defaults to utf8 when the header omits a charset`() {
        val text = "héllo"
        val bytes = text.toByteArray(Charsets.UTF_8)

        assertEquals(text, decodeBody(bytes, bytes.size, "text/html"))
        assertEquals(text, decodeBody(bytes, bytes.size, null))
    }

    @Test
    fun `falls back to utf8 on an unknown charset name rather than throwing`() {
        val text = "hello"
        val bytes = text.toByteArray(Charsets.UTF_8)

        assertEquals(text, decodeBody(bytes, bytes.size, "text/html; charset=not-a-real-charset"))
    }

    @Test
    fun `decodes only the first len bytes`() {
        val bytes = "abcdef".toByteArray(Charsets.UTF_8)

        assertEquals("abc", decodeBody(bytes, 3, "text/plain"))
    }
}
