package me.rerere.search

import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.search.SearchService.Companion.httpClient
import me.rerere.search.extract.ExtractMode
import me.rerere.search.extract.ScrapeSchema
import me.rerere.search.extract.WebExtractor
import me.rerere.search.net.SearchCircuitBreaker
import me.rerere.search.net.hostIsBlockedLiteral
import me.rerere.search.net.withEgressGuard
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Thrown for a keyless-search failure that the model should treat as retryable: the engine's
 * anti-bot protection kicked in (rate-limited by our own circuit breaker, or a challenge page
 * detected fresh). Deliberately distinct from a generic exception so the tool layer's
 * `exception` field (which surfaces `javaClass.simpleName`) reads as something actionable
 * instead of a bare `IllegalArgumentException`.
 */
class SearchRateLimitedException(message: String) : Exception(message)

/**
 * Outcome of classifying a DuckDuckGo HTML-endpoint response. Kept separate from an exception
 * so classification stays a pure, unit-testable function (see [classifyDdgResponse]) - the
 * markup is what breaks, not the transport.
 */
internal sealed interface DdgOutcome {
    data class Results(val items: List<SearchResult.SearchResultItem>) : DdgOutcome
    data object Empty : DdgOutcome
    data class Blocked(val reason: String) : DdgOutcome
}

/** HTTP statuses DuckDuckGo's anti-bot layer is known to answer a challenge/rate-limit with. */
private val BLOCKED_HTTP_STATUSES = setOf(202, 403, 429)

/**
 * Case-insensitive substrings unique to DuckDuckGo's anti-bot challenge ("anomaly") page,
 * derived from an actual capture of `https://html.duckduckgo.com/html/?q=test` returned to a
 * rate-limited IP (HTTP 202): a "select all squares containing a duck" puzzle whose markup
 * carries the `anomaly-modal` class family and this description text.
 */
private val CHALLENGE_MARKERS = listOf(
    "anomaly-modal",
    "unfortunately, bots use duckduckgo too",
    "confirm this search was made by a human",
)

/** Substrings of DuckDuckGo's genuine "nothing matched your query" markup. */
private val NO_RESULTS_MARKERS = listOf(
    "no-results",
    "no results.",
)

/**
 * Classify a DuckDuckGo HTML-endpoint response into real results, a genuine empty SERP, or an
 * anti-bot block - replacing the old `require(results.isNotEmpty())`, which conflated "we were
 * blocked" with "the web has nothing" and reported both as "no results found".
 *
 * An unrecognised zero-result shape defaults to [DdgOutcome.Blocked] rather than [DdgOutcome.Empty]:
 * a retryable false-positive is safer than telling the model the web has nothing when the markup
 * just drifted.
 */
internal fun classifyDdgResponse(httpStatus: Int, html: String, limit: Int): DdgOutcome {
    val results = DuckDuckGoSearchService.parseResults(html, limit)
    if (results.isNotEmpty()) return DdgOutcome.Results(results)

    if (httpStatus in BLOCKED_HTTP_STATUSES || httpStatus >= 500) {
        return DdgOutcome.Blocked("http $httpStatus")
    }

    val lowerHtml = html.lowercase()
    if (CHALLENGE_MARKERS.any { lowerHtml.contains(it) }) {
        return DdgOutcome.Blocked("challenge page")
    }
    if (NO_RESULTS_MARKERS.any { lowerHtml.contains(it) }) {
        return DdgOutcome.Empty
    }
    return DdgOutcome.Blocked("unrecognised empty response")
}

/** Model-facing message for a search short-circuited locally by the (already open) breaker. */
private fun rateLimited(remainingMs: Long): SearchRateLimitedException {
    val seconds = (remainingMs / 1000L).coerceAtLeast(1L)
    return SearchRateLimitedException(
        "Search is temporarily rate-limited by the engine's anti-bot protection. " +
            "Wait about ${seconds}s before searching again, and avoid firing many searches in quick succession."
    )
}

/** Model-facing message for a challenge/block detected fresh (breaker not yet open). */
private fun blocked(reason: String): SearchRateLimitedException =
    SearchRateLimitedException(
        "The search engine returned an anti-bot challenge instead of results ($reason). " +
            "This usually clears in a few seconds; wait before retrying and avoid rapid repeated searches."
    )

object DuckDuckGoSearchService : SearchService<SearchServiceOptions.DuckDuckGoOptions> {

    override val name: String = "Built-in"

    private const val ENDPOINT = "https://html.duckduckgo.com/html/?q="

    // Process-lifetime only: no persistence, no cache. Living on this singleton is what makes
    // repeated blocks across calls visible without any disk/cache writes.
    private val breaker = SearchCircuitBreaker()

    @Composable
    override fun Description() {
    }

    override fun parameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? =
        InputSchema.Obj(
            properties = buildJsonObject {
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "search keyword")
                })
            },
            required = listOf("query")
        )

    override fun scrapingParameters(options: SearchServiceOptions.DuckDuckGoOptions): InputSchema? =
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
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        val query = params["query"]?.jsonPrimitive?.content
            ?: return@withContext Result.failure(IllegalStateException("query is required"))

        if (!breaker.canAttempt()) {
            return@withContext Result.failure(rateLimited(breaker.remainingCooldownMillis()))
        }

        val url = ENDPOINT + URLEncoder.encode(query, "UTF-8")
        val locale = Locale.getDefault()
        val acceptLanguage = "${locale.language}-${locale.country},${locale.language}"

        runCatching {
            val resp = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", acceptLanguage)
                .header("Accept-Encoding", "gzip, deflate, sdch")
                .header("Accept-Charset", "utf-8")
                .header("Connection", "keep-alive")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Upgrade-Insecure-Requests", "1")
                .referrer("https://duckduckgo.com/")
                .ignoreHttpErrors(true) // otherwise jsoup throws on 403/429 and we can't read the body to classify it
                .timeout(12_000)
                .execute()

            classifyDdgResponse(resp.statusCode(), resp.body(), commonOptions.resultSize)
        }.fold(
            onSuccess = { outcome ->
                when (outcome) {
                    is DdgOutcome.Results -> {
                        breaker.recordSuccess()
                        Result.success(SearchResult(items = outcome.items))
                    }

                    DdgOutcome.Empty -> {
                        // A genuine empty SERP is a valid answer, not a failure: we reached DDG
                        // and it answered, so it does not invite a retry storm.
                        breaker.recordSuccess()
                        Result.success(SearchResult(items = emptyList()))
                    }

                    is DdgOutcome.Blocked -> {
                        breaker.recordFailure()
                        Result.failure(blocked(outcome.reason))
                    }
                }
            },
            onFailure = { e ->
                breaker.recordFailure()
                Result.failure(e)
            },
        )
    }

    override suspend fun scrape(
        params: JsonObject,
        commonOptions: SearchCommonOptions,
        serviceOptions: SearchServiceOptions.DuckDuckGoOptions
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

    /**
     * Parse the HTML endpoint's result list. Split out from [search] so it is testable
     * without a network round trip: the markup, not the transport, is what breaks.
     */
    internal fun parseResults(html: String, limit: Int): List<SearchResult.SearchResultItem> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html)
        return doc.select("div.result").mapNotNull { row ->
            val anchor = row.selectFirst("a.result__a") ?: return@mapNotNull null
            val title = anchor.text().trim().ifBlank { return@mapNotNull null }
            val href = unwrapRedirect(anchor.attr("href")).ifBlank { return@mapNotNull null }
            val snippet = row.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            SearchResult.SearchResultItem(title = title, url = href, text = snippet)
        }.take(limit)
    }

    /** DuckDuckGo wraps outbound links as `//duckduckgo.com/l/?uddg=<encoded>`. */
    private fun unwrapRedirect(href: String): String {
        if (!href.contains("uddg=")) return href
        val encoded = href.substringAfter("uddg=").substringBefore("&")
        return runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(href)
    }
}
