package me.rerere.search

import me.rerere.search.extract.ExtractMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckDuckGoSearchServiceTest {

    private val sampleHtml = """
        <html><body>
          <div class="result results_links">
            <a class="result__a" href="https://example.com/one">First Result</a>
            <a class="result__snippet">The first snippet text.</a>
          </div>
          <div class="result results_links">
            <a class="result__a" href="https://example.org/two">Second Result</a>
            <a class="result__snippet">The second snippet text.</a>
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `parses titles urls and snippets from the html endpoint`() {
        val items = DuckDuckGoSearchService.parseResults(sampleHtml, limit = 10)

        assertEquals(2, items.size)
        assertEquals("First Result", items[0].title)
        assertEquals("https://example.com/one", items[0].url)
        assertTrue(items[0].text.contains("first snippet"))
    }

    @Test
    fun `respects the result limit`() {
        assertEquals(1, DuckDuckGoSearchService.parseResults(sampleHtml, limit = 1).size)
    }

    @Test
    fun `unwraps duckduckgo redirect urls`() {
        val wrapped = """
            <html><body><div class="result results_links">
              <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Freal&rut=x">T</a>
              <a class="result__snippet">S</a>
            </div></body></html>
        """.trimIndent()

        val items = DuckDuckGoSearchService.parseResults(wrapped, limit = 10)

        assertEquals("https://example.com/real", items[0].url)
    }

    @Test
    fun `empty html yields no results rather than throwing`() {
        assertEquals(0, DuckDuckGoSearchService.parseResults("", limit = 10).size)
    }

    @Test
    fun `advertises scraping so scrape_web registers`() {
        assertNotNull(
            DuckDuckGoSearchService.scrapingParameters(SearchServiceOptions.DuckDuckGoOptions()),
        )
    }
}
