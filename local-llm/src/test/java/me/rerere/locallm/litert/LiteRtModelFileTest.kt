package me.rerere.locallm.litert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Tests for the `.litertlm` context-ceiling reader.
 *
 * The layout exercised here mirrors the real published artifacts: `LITERTLM`, a version
 * triple, a section table of `(end, begin)` u64 pairs, and an `LlmMetadataProto` section
 * starting at 16 KiB. Both real shapes are covered — files that declare field 5
 * (`qwen3_0_6b_mixed_int4` = 2048) and files that omit it entirely (the Gemma 4 family,
 * which carry no ceiling and must fall through to the caller's other sources).
 */
class LiteRtModelFileTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val metadataOffset = 16384

    // ---- protobuf writers -------------------------------------------------------------

    private fun varint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b); break
            }
            out.write(b or 0x80)
        }
        return out.toByteArray()
    }

    private fun varintField(field: Int, value: Long): ByteArray =
        varint((field shl 3).toLong()) + varint(value)

    private fun bytesField(field: Int, payload: ByteArray): ByteArray =
        varint(((field shl 3) or 2).toLong()) + varint(payload.size.toLong()) + payload

    private fun longLe(value: Long): ByteArray =
        ByteArray(8) { ((value shr (8 * it)) and 0xff).toByte() }

    /** A stand-in chat template: only the Jinja delimiter is load-bearing. */
    private val template = "{%- if tools %}\n{{- tool | tojson }}\n{%- endif %}"
        .toByteArray(Charsets.UTF_8)

    /**
     * Assemble a file with the real container shape. [metadata] is placed at 16 KiB and
     * its span recorded in the section table as the `(end, begin)` pair the reader scans
     * for, alongside a decoy weights section it must not mistake for the metadata.
     */
    private fun buildFile(metadata: ByteArray, magic: String = "LITERTLM"): File {
        val body = ByteArray(metadataOffset + metadata.size + 4096)
        magic.toByteArray(Charsets.US_ASCII).copyInto(body, 0)
        longLe(1L).copyInto(body, 8)   // version triple
        longLe(5L).copyInto(body, 16)

        // Decoy first: a large weights-like section, listed before the metadata.
        longLe((metadataOffset + metadata.size + 4096).toLong()).copyInto(body, 40)
        longLe((metadataOffset + metadata.size).toLong()).copyInto(body, 48)
        // The metadata section.
        longLe((metadataOffset + metadata.size).toLong()).copyInto(body, 64)
        longLe(metadataOffset.toLong()).copyInto(body, 72)

        metadata.copyInto(body, metadataOffset)
        return tempFolder.newFile().also { it.writeBytes(body) }
    }

    // ---- tests ------------------------------------------------------------------------

    @Test
    fun `reads the ceiling a file declares`() {
        // The shape of qwen3_0_6b_mixed_int4: sampler params, then field 5, then template.
        val metadata = bytesField(4, byteArrayOf(0x08, 0x02)) +
            varintField(5, 2048) +
            bytesField(7, template)
        assertEquals(2048, LiteRtModelFile.readDeclaredMaxTokens(buildFile(metadata)))
    }

    @Test
    fun `returns null when the file declares no ceiling`() {
        // The Gemma 4 shape: metadata present and valid, field 5 simply absent. The caller
        // must fall back to the ekvNNNN filename marker rather than invent a number.
        val metadata = bytesField(6, byteArrayOf(0x08, 0x01)) +
            bytesField(7, template) +
            bytesField(8, byteArrayOf(0x0a, 0x01, 0x02))
        assertNull(LiteRtModelFile.readDeclaredMaxTokens(buildFile(metadata)))
    }

    @Test
    fun `ignores a section that decodes but carries no chat template`() {
        // Guards the scan: a span that happens to parse as protobuf must not be allowed to
        // donate a bogus ceiling just because it has a field 5.
        val notMetadata = varintField(5, 999_999) + bytesField(7, "no jinja here".toByteArray())
        assertNull(LiteRtModelFile.readDeclaredMaxTokens(buildFile(notMetadata)))
    }

    @Test
    fun `rejects an implausible ceiling`() {
        val metadata = varintField(5, 7) + bytesField(7, template)
        assertNull(LiteRtModelFile.readDeclaredMaxTokens(buildFile(metadata)))
    }

    @Test
    fun `returns null for a file that is not a litertlm container`() {
        val metadata = varintField(5, 2048) + bytesField(7, template)
        assertNull(LiteRtModelFile.readDeclaredMaxTokens(buildFile(metadata, magic = "NOTLITER")))
    }

    @Test
    fun `returns null instead of throwing on a truncated or absent file`() {
        val truncated = tempFolder.newFile().also {
            it.writeBytes("LITERTLM".toByteArray(Charsets.US_ASCII))
        }
        assertNull(LiteRtModelFile.readDeclaredMaxTokens(truncated))
        assertNull(LiteRtModelFile.readDeclaredMaxTokens(File(tempFolder.root, "missing.litertlm")))
    }

    @Test
    fun `returns null on garbage where a section table should be`() {
        val junk = ByteArray(32768) { (it * 31 % 251).toByte() }
        "LITERTLM".toByteArray(Charsets.US_ASCII).copyInto(junk, 0)
        val file = tempFolder.newFile().also { it.writeBytes(junk) }
        assertNull(LiteRtModelFile.readDeclaredMaxTokens(file))
    }
}
