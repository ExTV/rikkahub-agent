package me.rerere.search.extract

import me.rerere.search.SearchServiceOptions
import me.rerere.search.SearXNGService
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScrapeDelegationTest {

    @Test
    fun `searxng advertises scraping parameters so the tool registers`() {
        val options = SearchServiceOptions.SearXNGOptions(url = "https://searx.example")

        // SearchTools.kt gates scrape_web registration on this being non-null.
        assertNotNull(SearXNGService.scrapingParameters(options))
    }
}
