package me.rerere.rikkahub.data.api

import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.model.HfModelDetail
import me.rerere.rikkahub.data.model.HfModelSearchResult
import retrofit2.HttpException
import java.io.IOException

/**
 * Search the public HuggingFace model API for GGUF repos and list a repo's `.gguf` files with
 * their sizes. Mirrors [me.rerere.locallm.ModelInstall]'s shape: a stateless object taking its
 * network client as a parameter rather than a DI-managed instance, so tests exercise the pure
 * parts ([toFilesResult]) without a network call.
 *
 * No credentials are stored or sent (see [HuggingFaceAPI]). A gated or private repo surfaces
 * as [FilesResult.RequiresAccess]: HuggingFace serves full repo metadata for gated repos
 * unauthenticated, so this is decided from [HfModelDetail.gated]/[HfModelDetail.private]
 * before any file transfer is attempted, never by letting a download fail or hang.
 */
object HuggingFaceModelSearch {

    private const val SEARCH_LIMIT = 20

    sealed class FilesResult {
        data class Files(val entries: List<GgufFileEntry>) : FilesResult()
        data object RequiresAccess : FilesResult()
        data class Error(val message: String) : FilesResult()
    }

    data class GgufFileEntry(val fileName: String, val sizeBytes: Long)

    suspend fun search(api: HuggingFaceAPI, query: String): Result<List<HfModelSearchResult>> {
        if (query.isBlank()) return Result.success(emptyList())
        return runCatching {
            api.searchModels(search = query.trim(), filter = "gguf", limit = SEARCH_LIMIT)
        }
    }

    suspend fun listGgufFiles(api: HuggingFaceAPI, repoId: String): FilesResult = try {
        toFilesResult(api.getModel(repoId = repoId, blobs = true))
    } catch (e: HttpException) {
        // HuggingFace answers both a private repo AND a nonexistent one with 401/403
        // unauthenticated (it does not leak which, to avoid revealing private repo names).
        // Either way there is nothing an unauthenticated client can do about it, so both map
        // to the same "requires access" result rather than a generic error.
        if (e.code() == 401 || e.code() == 403) FilesResult.RequiresAccess
        else FilesResult.Error("HTTP ${e.code()}")
    } catch (e: IOException) {
        FilesResult.Error(e.message ?: "Network error")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Anything else the Retrofit/kotlinx-serialization call can throw (e.g. a
        // SerializationException from a captive portal or proxy returning HTTP 200 with a
        // non-JSON body) maps to a plain error rather than crashing the caller.
        FilesResult.Error(e.message ?: e::class.simpleName ?: "Unknown error")
    }

    /**
     * Pure, no network. Split out from [listGgufFiles] so the gating decision and the
     * `.gguf`-only filtering are unit-testable without a live HuggingFace call.
     */
    fun toFilesResult(detail: HfModelDetail): FilesResult {
        if (detail.gated || detail.private) return FilesResult.RequiresAccess
        val files = detail.siblings.mapNotNull { sibling ->
            val size = sibling.size ?: return@mapNotNull null
            if (!sibling.rfilename.endsWith(".gguf", ignoreCase = true)) return@mapNotNull null
            GgufFileEntry(sibling.rfilename, size)
        }
        return FilesResult.Files(files)
    }

    /** Same `/resolve/main/` URL shape [me.rerere.llamacpp.LlamaCppCatalogEntry.resolveUrl]
     *  builds for a curated entry: `ModelInstall.download` already normalises and streams
     *  this URL shape, so a HuggingFace search result installs through the identical path. */
    fun resolveUrl(repoId: String, fileName: String): String =
        "https://huggingface.co/$repoId/resolve/main/$fileName"
}
