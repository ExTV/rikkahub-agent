package me.rerere.rikkahub.data.api

import me.rerere.rikkahub.data.model.HfModelDetail
import me.rerere.rikkahub.data.model.HfModelSearchResult
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The public, unauthenticated HuggingFace Hub model API. No credentials are sent or accepted:
 * gated and private repos are surfaced through [HfModelDetail.gated] / [HfModelDetail.private]
 * on [getModel], never fetched with an access token.
 */
interface HuggingFaceAPI {
    @GET("api/models")
    suspend fun searchModels(
        @Query("search") search: String,
        @Query("filter") filter: String,
        @Query("limit") limit: Int,
    ): List<HfModelSearchResult>

    /** [repoId] is `<owner>/<name>`, e.g. "Qwen/Qwen3-4B-GGUF"; `encoded = true` so the `/`
     *  reaches HuggingFace as a real path separator instead of being percent-escaped. */
    @GET("api/models/{repoId}")
    suspend fun getModel(
        @Path(value = "repoId", encoded = true) repoId: String,
        @Query("blobs") blobs: Boolean,
    ): HfModelDetail

    companion object {
        fun create(httpClient: OkHttpClient): HuggingFaceAPI {
            return Retrofit.Builder()
                .client(httpClient)
                .baseUrl("https://huggingface.co/")
                .addConverterFactory(JsonInstant.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(HuggingFaceAPI::class.java)
        }
    }
}
