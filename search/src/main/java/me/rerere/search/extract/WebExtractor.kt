package me.rerere.search.extract

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** What shape of content the caller wants back. */
enum class ExtractMode {
    /** Main article prose, chrome removed. The default and the useful one. */
    ARTICLE,

    /** All body text, chrome removed but no article scoring. */
    TEXT,

    /** Anchors only. */
    LINKS,

    /** Title / description / site name / language only, no body. */
    METADATA,
}

data class ExtractedLink(
    val href: String,
    val text: String,
)

data class ExtractedPage(
    val title: String? = null,
    val siteName: String? = null,
    val description: String? = null,
    val language: String? = null,
    val text: String = "",
    val links: List<ExtractedLink> = emptyList(),
    val truncated: Boolean = false,
    val nextStartIndex: Int? = null,
)

/**
 * HTML to useful text, using jsoup only. No WebView, no Android APIs, no network, so this
 * is host-JVM unit-testable and usable from both the `:search` module and the app's tools.
 *
 * The ARTICLE scorer is a deliberately small reimplementation of the idea behind Mozilla's
 * Readability: prefer containers with a lot of text and few links, nudged by conventional
 * class/id naming. It is not a port and will not match Readability on adversarial pages.
 * The interface is the contract; if quality proves inadequate on real sites, replace the
 * internals here without touching any caller.
 */
object WebExtractor {

    /** Elements that never carry article content. */
    private val STRIP_TAGS = listOf(
        "script", "style", "noscript", "iframe", "svg", "form", "button",
        "nav", "aside", "header", "footer", "template", "figure",
    )

    /** Block elements whose boundaries must survive as paragraph breaks. */
    private const val BLOCK_SELECTOR = "h1, h2, h3, h4, h5, h6, p, li, blockquote, pre, tr, dd, dt"

    private val POSITIVE = Regex(
        "article|body|content|entry|main|page|post|story|text|blog",
        RegexOption.IGNORE_CASE,
    )
    private val NEGATIVE = Regex(
        "ad-|ads|advert|banner|comment|disqus|footer|header|menu|meta|nav|promo|" +
            "related|share|sidebar|social|sponsor|widget|popup|cookie|newsletter",
        RegexOption.IGNORE_CASE,
    )

    /** Minimum text length before a container is considered article-worthy. */
    private const val MIN_CANDIDATE_CHARS = 140

    /** Above this share of link text, a container is navigation, not prose. */
    private const val MAX_LINK_DENSITY = 0.5

    fun extract(
        html: String,
        baseUrl: String,
        mode: ExtractMode,
        maxChars: Int,
        startIndex: Int = 0,
    ): ExtractedPage {
        if (html.isBlank()) return ExtractedPage()

        val doc = Jsoup.parse(html, baseUrl)
        val meta = readMetadata(doc)

        if (mode == ExtractMode.METADATA) return meta

        if (mode == ExtractMode.LINKS) {
            val links = doc.select("a[href]")
                .map { ExtractedLink(href = it.absUrl("href"), text = it.text().trim()) }
                .filter { it.href.isNotBlank() }
            return meta.copy(links = links)
        }

        doc.select(STRIP_TAGS.joinToString(",")).remove()

        val root = when (mode) {
            ExtractMode.ARTICLE -> bestArticleElement(doc)
            else -> doc.body()
        }

        val full = blockAwareText(root)
        return meta.copy(text = "").withWindow(full, maxChars, startIndex)
    }

    /** Slice [full] to a [maxChars] window at [startIndex], reporting resumability. */
    private fun ExtractedPage.withWindow(
        full: String,
        maxChars: Int,
        startIndex: Int,
    ): ExtractedPage {
        val safeMax = maxChars.coerceAtLeast(1)
        val from = startIndex.coerceIn(0, full.length)
        val to = minOf(from + safeMax, full.length)
        val slice = full.substring(from, to)
        val more = to < full.length
        return copy(
            text = slice,
            truncated = more,
            nextStartIndex = if (more) to else null,
        )
    }

    private fun readMetadata(doc: Document): ExtractedPage = ExtractedPage(
        title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.ifBlank { null }
            ?: doc.title().ifBlank { null },
        siteName = doc.selectFirst("meta[property=og:site_name]")?.attr("content")?.ifBlank { null },
        description = doc.selectFirst("meta[name=description]")?.attr("content")?.ifBlank { null }
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")?.ifBlank { null },
        language = doc.selectFirst("html")?.attr("lang")?.ifBlank { null },
    )

    /**
     * Pick the container most likely to hold the article. Score is text length discounted by
     * link density, bonused per paragraph, and nudged by class/id naming conventions.
     */
    private fun bestArticleElement(doc: Document): Element {
        val body = doc.body()
        var best: Element? = null
        var bestScore = 0.0

        for (el in body.select("article, main, [role=main], section, div")) {
            val text = el.text()
            if (text.length < MIN_CANDIDATE_CHARS) continue

            val linkChars = el.select("a").sumOf { it.text().length }
            val linkDensity = linkChars.toDouble() / text.length.toDouble()
            if (linkDensity > MAX_LINK_DENSITY) continue

            var score = text.length * (1.0 - linkDensity)
            score += el.select("p").size * 25.0

            val marker = "${el.className()} ${el.id()}"
            if (POSITIVE.containsMatchIn(marker)) score *= 1.5
            if (NEGATIVE.containsMatchIn(marker)) score *= 0.4
            if (el.tagName() == "article") score *= 1.5

            if (score > bestScore) {
                bestScore = score
                best = el
            }
        }
        return best ?: body
    }

    /**
     * Text with block boundaries preserved as blank lines. `Element.text()` alone collapses
     * everything into one run, which is how "Big HeadingHello world" happens.
     */
    private fun blockAwareText(root: Element): String {
        val blocks = root.select(BLOCK_SELECTOR)
        if (blocks.isEmpty()) return root.text().trim()

        val sb = StringBuilder()
        for (b in blocks) {
            // Skip a block whose text is fully contained in an ancestor block already emitted.
            if (b.parents().any { it.`is`(BLOCK_SELECTOR) }) continue
            val t = b.text().trim()
            if (t.isNotEmpty()) sb.append(t).append("\n\n")
        }
        val out = sb.toString().trim()
        return out.ifEmpty { root.text().trim() }
    }
}

/** The `url` argument schema every keyless scraper advertises. */
object ScrapeSchema {
    const val URL_DESCRIPTION = "The absolute http(s) URL to read"
    const val MODE_DESCRIPTION = "article (default), text, links, or metadata"

    /** Neither SearXNGService nor BingSearchService declares a user-agent constant today. */
    const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
}
