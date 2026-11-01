package me.rerere.search.extract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class WebExtractorTest {

    private val articleHtml = """
        <html><head><title>Real Title</title>
        <meta name="description" content="A description.">
        <meta property="og:site_name" content="Example News">
        </head>
        <body>
          <nav><a href="/a">Nav One</a><a href="/b">Nav Two</a><a href="/c">Nav Three</a></nav>
          <header><a href="/x">Header link</a></header>
          <div class="sidebar"><a href="/ad1">Ad</a><a href="/ad2">Promo</a></div>
          <article class="post-content">
            <h1>The Heading</h1>
            <p>${"First paragraph with enough words to clear the length floor. ".repeat(6)}</p>
            <p>${"Second paragraph also long enough to matter for scoring purposes. ".repeat(6)}</p>
          </article>
          <footer><a href="/f">Footer</a></footer>
          <script>var x = 1;</script>
        </body></html>
    """.trimIndent()

    @Test
    fun `article mode returns prose and drops chrome`() {
        val page = WebExtractor.extract(articleHtml, "https://example.com/p", ExtractMode.ARTICLE, 10_000, 0)

        assertTrue(page.text.contains("First paragraph"))
        assertTrue(page.text.contains("Second paragraph"))
        assertFalse(page.text.contains("Nav One"))
        assertFalse(page.text.contains("Footer"))
        assertFalse(page.text.contains("var x = 1"))
    }

    @Test
    fun `article mode separates block elements`() {
        val page = WebExtractor.extract(articleHtml, "https://example.com/p", ExtractMode.ARTICLE, 10_000, 0)

        // The reference server ran headings into body text ("Big HeadingHello world").
        assertFalse(page.text.contains("The HeadingFirst"))
        assertTrue(page.text.contains("The Heading\n"))
    }

    @Test
    fun `metadata mode reads title description and site name`() {
        val page = WebExtractor.extract(articleHtml, "https://example.com/p", ExtractMode.METADATA, 10_000, 0)

        assertEquals("Real Title", page.title)
        assertEquals("A description.", page.description)
        assertEquals("Example News", page.siteName)
        assertEquals("", page.text)
    }

    @Test
    fun `links mode resolves relative hrefs against the base url`() {
        val page = WebExtractor.extract(articleHtml, "https://example.com/p", ExtractMode.LINKS, 10_000, 0)

        assertTrue(page.links.any { it.href == "https://example.com/a" && it.text == "Nav One" })
    }

    @Test
    fun `pagination reports next index and resumes without loss`() {
        val first = WebExtractor.extract(articleHtml, "https://example.com/p", ExtractMode.ARTICLE, 40, 0)
        assertTrue(first.truncated)
        assertEquals(40, first.nextStartIndex)

        val second = WebExtractor.extract(articleHtml, "https://example.com/p", ExtractMode.ARTICLE, 40, 40)
        val whole = WebExtractor.extract(articleHtml, "https://example.com/p", ExtractMode.ARTICLE, 10_000, 0)
        assertEquals(whole.text.substring(0, 80), first.text + second.text)
    }

    @Test
    fun `final page is not marked truncated`() {
        val page = WebExtractor.extract(articleHtml, "https://example.com/p", ExtractMode.ARTICLE, 10_000, 0)
        assertFalse(page.truncated)
        assertNull(page.nextStartIndex)
    }

    @Test
    fun `start index past the end yields empty rather than throwing`() {
        val page = WebExtractor.extract(articleHtml, "https://example.com/p", ExtractMode.ARTICLE, 100, 99_999)
        assertEquals("", page.text)
        assertFalse(page.truncated)
    }

    @Test
    fun `link farm does not win over prose`() {
        val linkFarm = """
            <html><body>
              <div class="related">${"<a href='/x'>Some related link here</a>".repeat(60)}</div>
              <div class="entry-content"><p>${"Genuine article prose here. ".repeat(20)}</p></div>
            </body></html>
        """.trimIndent()

        val page = WebExtractor.extract(linkFarm, "https://example.com/p", ExtractMode.ARTICLE, 10_000, 0)

        assertTrue(page.text.contains("Genuine article prose"))
        assertFalse(page.text.contains("Some related link here"))
    }

    @Test
    fun `page with no article container falls back to body text`() {
        val bare = "<html><body><p>${"Just body prose with no wrapper. ".repeat(10)}</p></body></html>"
        val page = WebExtractor.extract(bare, "https://example.com/p", ExtractMode.ARTICLE, 10_000, 0)
        assertTrue(page.text.contains("Just body prose"))
    }

    @Test
    fun `empty document yields empty text not a crash`() {
        val page = WebExtractor.extract("", "https://example.com/p", ExtractMode.ARTICLE, 10_000, 0)
        assertEquals("", page.text)
    }
}
