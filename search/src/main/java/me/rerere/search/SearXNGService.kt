package me.rerere.search

import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchResult.SearchResultItem
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.SearchService.Companion.json
import me.rerere.search.extract.ExtractMode
import me.rerere.search.extract.ScrapeSchema
import me.rerere.search.extract.WebExtractor
import me.rerere.search.net.hostIsBlockedLiteral
import me.rerere.search.net.withEgressGuard
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.net.URLEncoder

private const val TAG = "SearXNGService"

object SearXNGService : SearchService<SearchServiceOptions.SearXNGOptions> {
    override val name: String = "SearXNG"

    @Composable
    override fun Description() {
        Text(stringResource(R.string.searxng_desc_1))
        Text(stringResource(R.string.searxng_desc_2))
    }

    override fun parameters(options: SearchServiceOptions.SearXNGOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.SearXNGOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", ScrapeSchema.URL_DESCRIPTION)
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", ScrapeSchema.MODE_DESCRIPTION)
                })
            },
            required = listOf("url"),
        )

    override suspend fun search(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(serviceOptions.url.isNotBlank()) {
                "SearXNG URL cannot be empty"
            }

            val query = params["query"]?.jsonPrimitive?.content ?: error("query is required")

            // 构建查询URL
            val baseUrl = serviceOptions.url.trimEnd('/')
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/search?q=$encodedQuery&format=json"
                .toHttpUrl()
                .newBuilder()
                .apply {
                    if (serviceOptions.engines.isNotBlank()) {
                        addQueryParameter("engines", serviceOptions.engines)
                    }
                    if (serviceOptions.language.isNotBlank()) {
                        addQueryParameter("language", serviceOptions.language)
                    }
                }
                .build()

            // 发送请求
            val request = Request.Builder()
                .url(url)
                .get()
                .apply {
                    // 添加HTTP Basic Auth支持
                    if (serviceOptions.username.isNotBlank() && serviceOptions.password.isNotBlank()) {
                        header("Authorization", Credentials.basic(serviceOptions.username, serviceOptions.password))
                    }
                }
                .build()

            Log.i(TAG, "search: $url")

            val response = httpClient.newCall(request).await()
            if (response.isSuccessful) {
                val bodyRaw = response.body.string()
                val searchResponse = runCatching {
                    json.decodeFromString<SearXNGResponse>(bodyRaw)
                }.onFailure {
                    Log.e(TAG, "Failed to decode SearXNG response: $bodyRaw", it)
                    error("Failed to decode SearXNG response: ${it.message}")
                }.getOrThrow()

                // 转换为标准格式，取前 N 个结果
                val items = searchResponse.results
                    .take(commonOptions.resultSize)
                    .map { result ->
                        SearchResultItem(
                            title = result.title,
                            url = result.url,
                            text = result.content
                        )
                    }

                return@withContext Result.success(SearchResult(items = items))
            } else {
                val errorBody = response.body.string()
                Log.e(TAG, "SearXNG API error: ${response.code} - $errorBody")
                error("SearXNG request failed with status ${response.code}")
            }
        }
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.SearXNGOptions
    ): Result<ScrapedResult> = withContext(Dispatchers.IO) {
        runCatching {
            val url = params["url"]?.jsonPrimitive?.contentOrNull?.trim()
            require(!url.isNullOrBlank()) { "url is required" }
            require(url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                "url must be an absolute http(s) URL"
            }
            require(!hostIsBlockedLiteral(url.toHttpUrlOrNull()?.host)) {
                "blocked_private_address: $url"
            }
            val mode = when (params["mode"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "text" -> ExtractMode.TEXT
                "links" -> ExtractMode.LINKS
                "metadata" -> ExtractMode.METADATA
                else -> ExtractMode.ARTICLE
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", ScrapeSchema.DEFAULT_USER_AGENT)
                .get()
                .build()

            val guarded = httpClient.withEgressGuard()
            guarded.newCall(request).execute().use { resp ->
                require(resp.isSuccessful) { "HTTP ${resp.code} from $url" }
                val html = boundedBody(resp, SCRAPE_BODY_CAP)
                val page = WebExtractor.extract(
                    html = html,
                    baseUrl = url,
                    mode = mode,
                    maxChars = 32 * 1024,
                    startIndex = 0,
                )
                require(page.text.isNotBlank() || mode != ExtractMode.ARTICLE) {
                    "no readable content extracted from $url"
                }
                ScrapedResult(
                    urls = listOf(
                        ScrapedResultUrl(
                            url = url,
                            content = page.text,
                            metadata = ScrapedResultMetadata(
                                title = page.title,
                                description = page.description,
                                language = page.language,
                            ),
                        ),
                    ),
                )
            }
        }
    }


    @Serializable
    data class SearXNGResponse(
        @SerialName("results")
        val results: List<SearXNGResult>,
    )

    @Serializable
    data class SearXNGResult(
        @SerialName("url")
        val url: String,
        @SerialName("title")
        val title: String,
        @SerialName("content")
        val content: String,
    )
}
