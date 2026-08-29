package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 25 — Archive tools. file:// zip / unzip / list paths are fully JVM-testable
 * (openIn / openOut only touch java.io.File for file:// URIs, never the Context). The
 * content:// branches need an instrumented test.
 */
class ArchiveToolsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun `zip then unzip round-trips a directory`() {
        val srcDir = tmp.newFolder("src")
        File(srcDir, "a.txt").writeText("alpha")
        File(srcDir, "b.txt").writeText("beta")
        val nested = File(srcDir, "sub").apply { mkdirs() }
        File(nested, "c.txt").writeText("gamma")

        val zipPath = "${tmp.root.absolutePath}/out.zip"
        val zipResult = obj(execTool(
            zipFilesTool(NULL_CONTEXT),
            """{"sources":["${srcDir.absolutePath}"],"destination":"$zipPath"}""",
        ))
        assertTrue(zipResult["success"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(3, zipResult["entry_count"]!!.jsonPrimitive.content.toInt())
        assertTrue(File(zipPath).exists())

        val destDir = "${tmp.root.absolutePath}/extracted"
        val unzipResult = obj(execTool(
            unzipFileTool(NULL_CONTEXT),
            """{"source":"$zipPath","destination_dir":"$destDir"}""",
        ))
        assertTrue(unzipResult["success"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(3, unzipResult["entries_extracted"]!!.jsonPrimitive.content.toInt())
        // byte-equal verification of one of the round-tripped files
        val extractedA = File(destDir).walkTopDown().first { it.name == "a.txt" }
        assertEquals("alpha", extractedA.readText())
        val extractedC = File(destDir).walkTopDown().first { it.name == "c.txt" }
        assertEquals("gamma", extractedC.readText())
    }

    @Test fun `unzip rejects path-traversal entry`() {
        // Hand-craft a malicious zip with a ../ escape entry.
        val evilZip = File(tmp.root, "evil.zip")
        ZipOutputStream(evilZip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../../escape.txt"))
            zos.write("pwned".toByteArray())
            zos.closeEntry()
        }
        val destDir = tmp.newFolder("safe").absolutePath
        val result = obj(execTool(
            unzipFileTool(NULL_CONTEXT),
            """{"source":"${evilZip.absolutePath}","destination_dir":"$destDir"}""",
        ))
        assertEquals("unsafe_zip_entry", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `isUnsafeZipEntry catches escapes`() {
        assertTrue(isUnsafeZipEntry("../../etc/passwd"))
        assertTrue(isUnsafeZipEntry("../escape.txt"))
        assertTrue(isUnsafeZipEntry("/absolute/path"))
        assertTrue(isUnsafeZipEntry("a/../../b"))
        assertTrue(isUnsafeZipEntry("C:\\windows\\system32"))
        assertFalse(isUnsafeZipEntry("normal/path/file.txt"))
        assertFalse(isUnsafeZipEntry("file.txt"))
        assertFalse(isUnsafeZipEntry("sub/dir/file..name.txt"))
    }

    @Test fun `unzip refuses to overwrite by default`() {
        val srcDir = tmp.newFolder("o_src")
        File(srcDir, "dup.txt").writeText("v1")
        val zipPath = "${tmp.root.absolutePath}/o.zip"
        execTool(
            zipFilesTool(NULL_CONTEXT),
            """{"sources":["${srcDir.absolutePath}"],"destination":"$zipPath"}""",
        )
        val destDir = tmp.newFolder("o_dest")
        // Pre-create the colliding file.
        File(destDir, "o_src/dup.txt").apply { parentFile?.mkdirs(); writeText("existing") }
        val result = obj(execTool(
            unzipFileTool(NULL_CONTEXT),
            """{"source":"$zipPath","destination_dir":"${destDir.absolutePath}"}""",
        ))
        assertEquals("entry_exists", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `list_zip_contents returns entry shape`() {
        val srcDir = tmp.newFolder("l_src")
        File(srcDir, "one.txt").writeText("hello world")
        val zipPath = "${tmp.root.absolutePath}/l.zip"
        execTool(
            zipFilesTool(NULL_CONTEXT),
            """{"sources":["${srcDir.absolutePath}"],"destination":"$zipPath"}""",
        )
        val result = obj(execTool(
            listZipContentsTool(NULL_CONTEXT),
            """{"source":"$zipPath"}""",
        ))
        val entries = result["entries"]!!.jsonArray
        assertEquals(1, entries.size)
        val entry = entries[0].jsonObject
        assertTrue(entry["name"]!!.jsonPrimitive.content.endsWith("one.txt"))
        assertEquals(11L, entry["size"]!!.jsonPrimitive.content.toLong())
        assertFalse(entry["is_dir"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun `list_zip_contents rejects a non-zip file`() {
        val notZip = tmp.newFile("notzip.txt")
        notZip.writeText("definitely not a zip")
        val result = obj(execTool(
            listZipContentsTool(NULL_CONTEXT),
            """{"source":"${notZip.absolutePath}"}""",
        ))
        assertEquals("invalid_zip", result["error"]?.jsonPrimitive?.content)
    }

    @Test fun `zip_files errors on unreadable source`() {
        val result = obj(execTool(
            zipFilesTool(NULL_CONTEXT),
            """{"sources":["/nonexistent/path/xyz"],"destination":"${tmp.root.absolutePath}/x.zip"}""",
        ))
        assertEquals("source_unreadable", result["error"]?.jsonPrimitive?.content)
    }

    // --- #66: entry names mangled for archives made on Chinese Windows ----------------------
    //
    // Zip tools on Chinese Windows write entry names as raw GBK bytes and do not set the
    // UTF-8 general-purpose flag (bit 11), which is exactly what ZipOutputStream(out, GBK)
    // below reproduces. These fixture tests are also what proves which of the two known JDK
    // zip-coder failure modes (thrown IllegalArgumentException vs. a substituted U+FFFD) this
    // platform exhibits for a malformed name -- both are handled either way.

    @Test fun `GBK-encoded entry name round-trips through unzip_file`() {
        val gbkZip = File(tmp.root, "gbk.zip")
        val chineseName = "测试文件.txt"
        ZipOutputStream(gbkZip.outputStream(), Charset.forName("GBK")).use { zos ->
            zos.putNextEntry(ZipEntry(chineseName))
            zos.write("content".toByteArray())
            zos.closeEntry()
        }
        val destDir = tmp.newFolder("gbk_extracted").absolutePath
        val result = obj(execTool(
            unzipFileTool(NULL_CONTEXT),
            """{"source":"${gbkZip.absolutePath}","destination_dir":"$destDir"}""",
        ))
        assertTrue("expected success, got $result", result["success"]!!.jsonPrimitive.content.toBoolean())
        val extracted = File(destDir).walkTopDown().firstOrNull { it.name == chineseName }
        assertTrue(
            "expected an entry named $chineseName under $destDir",
            extracted != null,
        )
    }

    @Test fun `GBK-encoded entry name round-trips through list_zip_contents`() {
        val gbkZip = File(tmp.root, "gbk_list.zip")
        val chineseName = "报告.docx"
        ZipOutputStream(gbkZip.outputStream(), Charset.forName("GBK")).use { zos ->
            zos.putNextEntry(ZipEntry(chineseName))
            zos.write("content".toByteArray())
            zos.closeEntry()
        }
        val result = obj(execTool(
            listZipContentsTool(NULL_CONTEXT),
            """{"source":"${gbkZip.absolutePath}"}""",
        ))
        val entries = result["entries"]!!.jsonArray
        assertEquals(1, entries.size)
        assertEquals(chineseName, entries[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun `a UTF-8 entry name (with the UTF-8 flag set) still lists correctly`() {
        // The default ZipOutputStream(OutputStream) constructor writes UTF-8 and sets bit 11,
        // which the JDK zip coder honours per entry regardless of the charset passed to the
        // reader -- so a name like this must decode correctly even after the GBK fallback
        // exists.
        val zip = File(tmp.root, "utf8.zip")
        val name = "café_résumé.txt"
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry(name))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        val result = obj(execTool(listZipContentsTool(NULL_CONTEXT), """{"source":"${zip.absolutePath}"}"""))
        assertEquals(name, result["entries"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test fun `an all-ASCII archive is unaffected by the charset probe`() {
        val srcDir = tmp.newFolder("ascii_src")
        File(srcDir, "plain.txt").writeText("hello")
        val zipPath = "${tmp.root.absolutePath}/ascii.zip"
        execTool(
            zipFilesTool(NULL_CONTEXT),
            """{"sources":["${srcDir.absolutePath}"],"destination":"$zipPath"}""",
        )
        val listed = obj(execTool(listZipContentsTool(NULL_CONTEXT), """{"source":"$zipPath"}"""))
        assertTrue(listed["entries"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content.endsWith("plain.txt"))

        val destDir = "${tmp.root.absolutePath}/ascii_extracted"
        val unzipped = obj(execTool(
            unzipFileTool(NULL_CONTEXT),
            """{"source":"$zipPath","destination_dir":"$destDir"}""",
        ))
        assertTrue(unzipped["success"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("hello", File(destDir).walkTopDown().first { it.name == "plain.txt" }.readText())
    }

    @Test fun `isMalformedZipEntryName detects the U+FFFD replacement character`() {
        assertTrue(isMalformedZipEntryName("caf\uFFFD.txt"))
        assertFalse(isMalformedZipEntryName("normal.txt"))
    }
}
