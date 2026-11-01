package me.rerere.rikkahub.data.sync.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage for the WebDAV displayName sanitization fix: a malicious/misbehaving server fully
 * controls PROPFIND <displayname> text, and WebDavSync builds a local cache file path
 * (`File(context.cacheDir, item.displayName)`) directly from it. Without stripping path
 * separators here, a "backup_../../../../data/data/.../whatever.zip" displayname would still
 * pass the "backup_" + ".zip" filter in WebDavSync.listBackupFiles and traverse out of
 * cacheDir on restore/delete.
 */
class WebDavClientTest {

    @Test
    fun `strips parent-directory traversal segments from displayname`() {
        val sanitized = sanitizeWebDavDisplayName("backup_../../../../data/data/evil/databases/rikka_hub.db.zip")
        assertEquals("rikka_hub.db.zip", sanitized)
        assertTrue(!sanitized.contains("/"))
        assertTrue(!sanitized.contains(".."))
    }

    @Test
    fun `strips backslash traversal segments from displayname`() {
        assertEquals("evil.zip", sanitizeWebDavDisplayName("backup_..\\..\\evil.zip"))
    }

    @Test
    fun `leaves an ordinary backup filename untouched`() {
        assertEquals("backup_20260803_120000.zip", sanitizeWebDavDisplayName("backup_20260803_120000.zip"))
    }

    @Test
    fun `a displayname that is only a traversal segment sanitizes to blank`() {
        assertEquals("", sanitizeWebDavDisplayName(".."))
    }

    @Test
    fun `a bare current-directory displayname sanitizes to blank`() {
        assertEquals("", sanitizeWebDavDisplayName("."))
    }

    @Test
    fun `a blank displayname sanitizes to blank`() {
        assertEquals("", sanitizeWebDavDisplayName(""))
    }
}
