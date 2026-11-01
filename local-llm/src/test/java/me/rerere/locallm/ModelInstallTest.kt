package me.rerere.locallm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** An [OkHttpClient] whose single interceptor answers every call with [bodyBytes] and an
 *  optional Content-Length header, without ever touching the network - lets [ModelInstall.download]
 *  be tested against a controlled response body/header pair. */
private fun clientReturning(bodyBytes: ByteArray, declaredContentLength: Long?): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val builder = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bodyBytes.toResponseBody("application/octet-stream".toMediaType()))
            if (declaredContentLength != null) {
                builder.header("Content-Length", declaredContentLength.toString())
            }
            builder.build()
        })
        .build()

/** A GGUF magic header followed by [payloadSize] arbitrary bytes. */
private fun ggufBytes(payloadSize: Int): ByteArray =
    byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(payloadSize) { (it % 251).toByte() }

/** Delivers [goodBytes] on the first read, then throws to simulate a dropped connection
 *  mid-copy (SocketException / IOException territory). */
private class FailingInputStream(private val goodBytes: ByteArray) : InputStream() {
    private var delivered = false
    override fun read(): Int = throw UnsupportedOperationException("not used by copyFromStream")
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (!delivered) {
            delivered = true
            System.arraycopy(goodBytes, 0, b, off, goodBytes.size)
            return goodBytes.size
        }
        throw IOException("simulated connection drop")
    }
}

/** Delivers [content] in caller-specified chunk sizes across successive reads, simulating a
 *  network-backed SAF DocumentsProvider (Google Drive and similar) whose first read(s) can
 *  hand back as few as 1 byte — InputStream.read(ByteArray) only guarantees at least one byte,
 *  never four. [chunkSizes] gives each read's byte count in order; once exhausted, every
 *  remaining byte is delivered in one final read. */
private class ChunkedInputStream(
    private val content: ByteArray,
    private val chunkSizes: List<Int>,
) : InputStream() {
    private var pos = 0
    private var chunkIndex = 0
    override fun read(): Int = throw UnsupportedOperationException("not used by copyFromStream")
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= content.size) return -1
        val want = if (chunkIndex < chunkSizes.size) chunkSizes[chunkIndex] else content.size - pos
        chunkIndex++
        val n = want.coerceAtMost(content.size - pos).coerceAtMost(len)
        System.arraycopy(content, pos, b, off, n)
        pos += n
        return n
    }
}

class ModelInstallTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test fun `validUrl accepts well-formed https URLs`() {
        assertTrue(ModelInstall.isValidDownloadUrl("https://huggingface.co/foo/bar/resolve/main/model.task"))
        assertTrue(ModelInstall.isValidDownloadUrl("https://example.com/path/to/model.litertlm"))
    }

    @Test fun `validUrl rejects http URLs`() {
        assertEquals(false, ModelInstall.isValidDownloadUrl("http://example.com/model.task"))
    }

    @Test fun `validUrl rejects malformed input`() {
        assertEquals(false, ModelInstall.isValidDownloadUrl(""))
        assertEquals(false, ModelInstall.isValidDownloadUrl("not a url"))
        assertEquals(false, ModelInstall.isValidDownloadUrl("file:///etc/passwd"))
    }

    @Test fun `runtimeForExtension routes litertlm to LiteRT, gguf to llama_cpp, and unknowns to null`() {
        assertEquals(LocalRuntime.LiteRT, ModelInstall.runtimeForExtension("litertlm"))
        assertEquals(LocalRuntime.LlamaCpp, ModelInstall.runtimeForExtension("gguf"))
        assertEquals(null, ModelInstall.runtimeForExtension("task"))
        assertEquals(null, ModelInstall.runtimeForExtension("tflite"))
    }

    @Test fun `runtimeForExtension is case-insensitive`() {
        assertEquals(LocalRuntime.LiteRT, ModelInstall.runtimeForExtension("LITERTLM"))
        assertEquals(LocalRuntime.LlamaCpp, ModelInstall.runtimeForExtension("GGUF"))
    }

    @Test fun `runtimeForExtension returns null for unrecognised extension`() {
        assertEquals(null, ModelInstall.runtimeForExtension("bin"))
        assertEquals(null, ModelInstall.runtimeForExtension(""))
    }

    @Test fun `extractFileNameFromUrl pulls the last path segment`() {
        assertEquals(
            "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm",
            ModelInstall.extractFileNameFromUrl("https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm"),
        )
    }

    @Test fun `extractFileNameFromUrl handles query strings`() {
        assertEquals(
            "model.task",
            ModelInstall.extractFileNameFromUrl("https://example.com/path/model.task?download=1"),
        )
    }

    @Test fun `targetFile builds a path under the runtime-specific subdir`() {
        val baseDir = File("/data/data/com.test/files/local-models")
        val out = ModelInstall.targetFile(baseDir, LocalRuntime.LiteRT, "model.task")
        assertEquals(
            "/data/data/com.test/files/local-models/litert/model.task",
            out.absolutePath,
        )
    }

    // normalizeHuggingFaceUrl ---------------------------------------------------

    @Test fun `normalizeHuggingFaceUrl transforms blob-main to resolve-main`() {
        val blob = "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM/blob/main/qwen35_2b_q4.litertlm"
        val expected = "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM/resolve/main/qwen35_2b_q4.litertlm"
        assertEquals(expected, ModelInstall.normalizeHuggingFaceUrl(blob))
    }

    @Test fun `normalizeHuggingFaceUrl does not alter a resolve URL`() {
        val resolve = "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM/resolve/main/qwen35_2b_q4.litertlm"
        assertEquals(resolve, ModelInstall.normalizeHuggingFaceUrl(resolve))
    }

    @Test fun `normalizeHuggingFaceUrl transforms blob with non-main branch`() {
        val blob = "https://huggingface.co/foo/bar/blob/dev/model.litertlm"
        val expected = "https://huggingface.co/foo/bar/resolve/dev/model.litertlm"
        assertEquals(expected, ModelInstall.normalizeHuggingFaceUrl(blob))
    }

    @Test fun `normalizeHuggingFaceUrl passes through non-huggingface URLs unchanged`() {
        val url = "https://example.com/models/blob/main/model.litertlm"
        assertEquals(url, ModelInstall.normalizeHuggingFaceUrl(url))
    }

    @Test fun `isValidDownloadUrl accepts blob-form HF URL (normalised before download)`() {
        // blob URLs are valid https; validation passes, then normalizeHuggingFaceUrl converts
        // /blob/ → /resolve/ before the HTTP call. Test that validation does NOT reject them.
        assertTrue(ModelInstall.isValidDownloadUrl(
            "https://huggingface.co/paulsp94/Qwen3.5-2B-LiteRT-LM/blob/main/qwen35_2b_q4.litertlm"
        ))
    }

    // looksLikeHtml ------------------------------------------------------------

    @Test fun `looksLikeHtml detects DOCTYPE preamble`() {
        val html = "<!DOCTYPE html><html>".toByteArray()
        assertTrue(ModelInstall.looksLikeHtml(html, html.size))
    }

    @Test fun `looksLikeHtml detects lowercase doctype`() {
        val html = "<!doctype html><html>".toByteArray()
        assertTrue(ModelInstall.looksLikeHtml(html, html.size))
    }

    @Test fun `looksLikeHtml ignores leading whitespace`() {
        val html = "  \n\n<html>".toByteArray()
        assertTrue(ModelInstall.looksLikeHtml(html, html.size))
    }

    @Test fun `looksLikeHtml detects xml preamble`() {
        val xml = "<?xml version=\"1.0\"?>".toByteArray()
        assertTrue(ModelInstall.looksLikeHtml(xml, xml.size))
    }

    @Test fun `looksLikeHtml false for binary model magic bytes`() {
        // "LMFF" + arbitrary bytes — binary model header, not HTML
        val bin = byteArrayOf(0x4C, 0x4D, 0x46, 0x46, 0x00, 0x01, 0x02, 0x03)
        assertFalse(ModelInstall.looksLikeHtml(bin, bin.size))
    }

    // isValidMagicForExtension -------------------------------------------------

    @Test fun `isValidMagicForExtension accepts LITERTLM magic for litertlm`() {
        val bytes = "LITERTLM      ".toByteArray().copyOf(16)
        assertTrue(ModelInstall.isValidMagicForExtension("litertlm", bytes))
        assertTrue(ModelInstall.isValidMagicForExtension("LITERTLM", bytes))  // case-insensitive
    }

    @Test fun `isValidMagicForExtension rejects all-zero file for litertlm`() {
        val bytes = ByteArray(16)  // all zeros
        assertFalse(ModelInstall.isValidMagicForExtension("litertlm", bytes))
    }

    @Test fun `isValidMagicForExtension rejects HTML file for litertlm`() {
        val bytes = "<!DOCTYPE html><html>".toByteArray().copyOf(16)
        assertFalse(ModelInstall.isValidMagicForExtension("litertlm", bytes))
    }

    @Test fun `isValidMagicForExtension accepts GGUF magic for gguf`() {
        val bytes = byteArrayOf(0x47, 0x47, 0x55, 0x46) + ByteArray(12)
        assertTrue(ModelInstall.isValidMagicForExtension("gguf", bytes))
        assertTrue(ModelInstall.isValidMagicForExtension("GGUF", bytes))  // case-insensitive
    }

    @Test fun `isValidMagicForExtension rejects a truncated or HTML-error download named gguf`() {
        // A truncated download or an HTML error page saved under a .gguf name must not be
        // accepted as a model — this is the regression the magic check exists to catch.
        val html = "<!DOCTYPE html><html>".toByteArray().copyOf(16)
        assertFalse(ModelInstall.isValidMagicForExtension("gguf", html))
        assertFalse(ModelInstall.isValidMagicForExtension("gguf", ByteArray(16)))
    }

    @Test fun `isValidMagicForExtension rejects buffer shorter than 4 bytes`() {
        // Any buffer < 4 bytes returns false regardless of extension.
        assertFalse(ModelInstall.isValidMagicForExtension("litertlm", byteArrayOf()))
        assertFalse(ModelInstall.isValidMagicForExtension("litertlm", byteArrayOf(0x4c)))
    }

    @Test fun `isValidMagicForExtension unknown extension rejects all-zero bytes`() {
        // The else branch: all-zero bytes should be rejected (looks like sparse-fill).
        val zeros = ByteArray(16)
        assertFalse(ModelInstall.isValidMagicForExtension("bin", zeros))
    }

    @Test fun `isValidMagicForExtension unknown extension accepts non-zero non-html bytes`() {
        // The else branch: a non-zero, non-HTML buffer should be accepted.
        val bytes = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()) + ByteArray(12)
        assertTrue(ModelInstall.isValidMagicForExtension("bin", bytes))
    }

    @Test fun `looksLikeHtml returns false for empty count`() {
        // count=0 means no bytes were read — not HTML.
        val buf = ByteArray(64)
        assertFalse(ModelInstall.looksLikeHtml(buf, 0))
    }

    @Test fun `extractFileNameFromUrl returns empty string for bare domain URL`() {
        // Edge case: URL with no file path segment after the last slash.
        assertEquals("", ModelInstall.extractFileNameFromUrl("https://example.com/"))
    }

    @Test fun `normalizeHuggingFaceUrl handles blob with main branch via regex only`() {
        // Verify the single-regex path correctly transforms /blob/main/ (regression guard
        // after removing the redundant literal string replace).
        val blob = "https://huggingface.co/user/repo/blob/main/file.litertlm"
        val expected = "https://huggingface.co/user/repo/resolve/main/file.litertlm"
        assertEquals(expected, ModelInstall.normalizeHuggingFaceUrl(blob))
    }

    // copyFromStream (SAF "install a GGUF I already have" import) -------------

    @Test fun `copyFromStream writes matching bytes and ends in Done`() = runBlocking {
        val content = ggufBytes(5000)
        val target = File(tempFolder.newFolder(), "model.gguf")

        val events = ModelInstall.copyFromStream(
            input = ByteArrayInputStream(content),
            target = target,
            totalBytes = content.size.toLong(),
            expectedExtension = "gguf",
        ).toList()

        assertTrue(events.first() is ModelInstall.Progress.Started)
        assertTrue(events.last() is ModelInstall.Progress.Done)
        assertTrue(target.exists())
        assertArrayEquals(content, target.readBytes())
        assertFalse(File(target.absolutePath + ".partial").exists())
    }

    @Test fun `copyFromStream reports a monotonically increasing byte count across multiple buffer fills`() = runBlocking {
        val content = ggufBytes(200_000) // several 64KB-buffer iterations
        val target = File(tempFolder.newFolder(), "model.gguf")

        val events = ModelInstall.copyFromStream(
            ByteArrayInputStream(content), target, content.size.toLong(), "gguf",
        ).toList()

        val ticks = events.filterIsInstance<ModelInstall.Progress.Tick>()
        assertTrue(ticks.size > 1)
        assertEquals(content.size.toLong(), ticks.last().bytesRead)
        assertTrue(ticks.zipWithNext().all { (a, b) -> b.bytesRead > a.bytesRead })
    }

    @Test fun `copyFromStream rejects a file whose first bytes are not the expected magic`() = runBlocking {
        val notGguf = "not a gguf file, just some text".toByteArray()
        val target = File(tempFolder.newFolder(), "model.gguf")

        val events = ModelInstall.copyFromStream(
            ByteArrayInputStream(notGguf), target, notGguf.size.toLong(), "gguf",
        ).toList()

        val failed = events.last() as ModelInstall.Progress.Failed
        assertTrue(failed.cause is IllegalArgumentException)
        assertFalse(target.exists())
        assertFalse(File(target.absolutePath + ".partial").exists())
    }

    @Test fun `copyFromStream validates against expectedExtension regardless of the target's own name`() = runBlocking {
        // The picked file is named "model.gguf" (matches the runtime's target), but its
        // content is HTML — expectedExtension must still be honoured over target.name.
        val html = "<!DOCTYPE html><html></html>".toByteArray()
        val target = File(tempFolder.newFolder(), "model.gguf")

        val events = ModelInstall.copyFromStream(
            ByteArrayInputStream(html), target, html.size.toLong(), "gguf",
        ).toList()

        assertTrue(events.last() is ModelInstall.Progress.Failed)
        assertFalse(target.exists())
    }

    @Test fun `copyFromStream overwrites an existing target file`() = runBlocking {
        val target = File(tempFolder.newFolder(), "model.gguf")
        target.writeBytes(ggufBytes(10)) // stale previous install
        val newContent = ggufBytes(50)

        ModelInstall.copyFromStream(
            ByteArrayInputStream(newContent), target, newContent.size.toLong(), "gguf",
        ).toList()

        assertArrayEquals(newContent, target.readBytes())
    }

    @Test fun `copyFromStream surfaces a mid-stream IOException as Failed and cleans up the partial`() = runBlocking {
        val target = File(tempFolder.newFolder(), "model.gguf")
        val source = FailingInputStream(ggufBytes(10))

        val events = ModelInstall.copyFromStream(source, target, null, "gguf").toList()

        assertTrue(events.last() is ModelInstall.Progress.Failed)
        assertFalse(target.exists())
        assertFalse(File(target.absolutePath + ".partial").exists())
    }

    @Test fun `copyFromStream still validates and copies byte-exact when the first reads return fewer than 4 bytes`() = runBlocking {
        // A network-backed SAF DocumentsProvider (Google Drive, etc.) can return 1-3 bytes on
        // an early read; InputStream.read(ByteArray) only guarantees at least one. The four
        // magic bytes now arrive one at a time across the first four reads.
        val content = ggufBytes(5000)
        val target = File(tempFolder.newFolder(), "model.gguf")
        val source = ChunkedInputStream(content, chunkSizes = listOf(1, 1, 1, 1))

        val events = ModelInstall.copyFromStream(
            input = source, target = target, totalBytes = content.size.toLong(), expectedExtension = "gguf",
        ).toList()

        assertTrue(events.last() is ModelInstall.Progress.Done)
        assertArrayEquals(content, target.readBytes())
        assertFalse(File(target.absolutePath + ".partial").exists())
    }

    @Test fun `copyFromStream rejects a genuinely truncated file that never reaches 4 bytes across short reads`() = runBlocking {
        // Only 2 bytes ever arrive, delivered one at a time, then EOF — must still be rejected
        // by the same size check as a single too-short read, not treated as "keep waiting".
        val tooShort = byteArrayOf(0x47, 0x47)
        val target = File(tempFolder.newFolder(), "model.gguf")
        val source = ChunkedInputStream(tooShort, chunkSizes = listOf(1, 1))

        val events = ModelInstall.copyFromStream(
            input = source, target = target, totalBytes = 2L, expectedExtension = "gguf",
        ).toList()

        val failed = events.last() as ModelInstall.Progress.Failed
        assertTrue(failed.cause is IllegalArgumentException)
        assertFalse(target.exists())
        assertFalse(File(target.absolutePath + ".partial").exists())
    }

    @Test fun `copyFromStream rejects a completely empty picked file instead of registering it`() = runBlocking {
        val target = File(tempFolder.newFolder(), "model.gguf")

        val events = ModelInstall.copyFromStream(
            ByteArrayInputStream(ByteArray(0)), target, totalBytes = 0L, expectedExtension = "gguf",
        ).toList()

        val failed = events.last() as ModelInstall.Progress.Failed
        assertTrue(failed.cause is IllegalArgumentException)
        assertFalse(target.exists())
        assertFalse(File(target.absolutePath + ".partial").exists())
    }

    @Test fun `copyFromStream passes a null totalBytes through unchanged`() = runBlocking {
        val content = ggufBytes(10)
        val target = File(tempFolder.newFolder(), "model.gguf")

        val events = ModelInstall.copyFromStream(
            ByteArrayInputStream(content), target, totalBytes = null, expectedExtension = "gguf",
        ).toList()

        assertEquals(null, (events.first() as ModelInstall.Progress.Started).totalBytes)
        assertEquals(null, (events.filterIsInstance<ModelInstall.Progress.Tick>().first()).totalBytes)
    }

    // download() short-read rejection -------------------------------------------

    @Test fun `download rejects a response that ends before Content-Length bytes arrive`() = runBlocking {
        // The connection closes cleanly (no exception) after 3000 of the 5000 declared
        // bytes - a server/proxy truncation, not a socket error. The bytes that did
        // arrive still start with a valid GGUF magic header, so only a byte-count check
        // against Content-Length can catch this.
        val fullContent = ggufBytes(5000)
        val truncated = fullContent.copyOf(3000)
        val client = clientReturning(truncated, declaredContentLength = fullContent.size.toLong())
        val target = File(tempFolder.newFolder(), "model.gguf")

        val events = ModelInstall.download(client, "https://example.com/model.gguf", target).toList()

        val failed = events.last() as ModelInstall.Progress.Failed
        assertTrue("expected an IOException, got ${failed.cause}", failed.cause is IOException)
        assertFalse("a short read must not be registered as an installed model", target.exists())
    }

    @Test fun `download accepts a response whose byte count matches Content-Length exactly`() = runBlocking {
        val content = ggufBytes(5000)
        val client = clientReturning(content, declaredContentLength = content.size.toLong())
        val target = File(tempFolder.newFolder(), "model.gguf")

        val events = ModelInstall.download(client, "https://example.com/model.gguf", target).toList()

        assertTrue("expected Done, got ${events.last()}", events.last() is ModelInstall.Progress.Done)
        assertArrayEquals(content, target.readBytes())
    }

    @Test fun `download does not reject a short read when the server sends no Content-Length at all`() = runBlocking {
        // Without a Content-Length there is nothing to compare totalRead against, so the
        // existing magic-byte check is the only signal available - this must not regress
        // into rejecting every no-Content-Length response outright.
        val content = ggufBytes(200)
        val client = clientReturning(content, declaredContentLength = null)
        val target = File(tempFolder.newFolder(), "model.gguf")

        val events = ModelInstall.download(client, "https://example.com/model.gguf", target).toList()

        assertTrue("expected Done, got ${events.last()}", events.last() is ModelInstall.Progress.Done)
    }
}
