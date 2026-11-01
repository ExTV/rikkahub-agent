package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Coverage for [DocumentAsPromptTransformer.resolveWorkspacePathForFile] — the pure,
 * android.net.Uri-free part of the path-advertising gate shared by Document and Image parts
 * (issue #37, second bullet: generated images should be advertised the same way uploaded
 * documents are, but only for files that really live under filesDir/upload).
 *
 * Note: the android.net.Uri parsing step (`String.toUri()`) that sits in front of this
 * function is NOT covered here. The :app module's JVM unit tests run against the unmocked
 * Android stub jar (no Robolectric, no `unitTests.isReturnDefaultValues`), so
 * `android.net.Uri.parse` throws and is swallowed by the surrounding `runCatching`, making
 * `data:`/`http(s)` vs. `file:` URLs indistinguishable in this test environment (see the same
 * caveat in DownloadToolTest.kt). That parsing step is exercised implicitly by production use;
 * this test covers the part that is actually testable in isolation: given a real local file,
 * is it inside the upload folder, and if so what workspace path is advertised.
 */
class DocumentAsPromptTransformerTest {

    @Test
    fun `file inside the upload folder resolves to its workspace path`() {
        val file = File(File("/data/user/0/pkg/files/upload"), "abc.png")
        val path = DocumentAsPromptTransformer.resolveWorkspacePathForFile(file)
        assertEquals("/upload/abc.png", path)
    }

    @Test
    fun `file outside the upload folder resolves to null`() {
        val file = File(File("/data/user/0/pkg/files/other"), "abc.png")
        val path = DocumentAsPromptTransformer.resolveWorkspacePathForFile(file)
        assertNull(path)
    }

    @Test
    fun `file with no parent resolves to null`() {
        val file = File("abc.png")
        val path = DocumentAsPromptTransformer.resolveWorkspacePathForFile(file)
        assertNull(path)
    }
}
