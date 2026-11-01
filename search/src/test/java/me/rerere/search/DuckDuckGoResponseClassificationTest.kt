package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification of the DuckDuckGo HTML endpoint response into Results / genuine Empty /
 * Blocked. The `challengeHtml` fixture below is a trimmed excerpt of the actual anti-bot
 * anomaly page returned by `https://html.duckduckgo.com/html/?q=test` (HTTP 202) when this
 * build host's IP was rate-limited - see the task report for the full capture. The distinctive
 * phrases and CSS class names in it are exactly what [CHALLENGE_MARKERS] is derived from.
 */
class DuckDuckGoResponseClassificationTest {

    private val resultsHtml = """
        <html><body>
          <div class="result results_links">
            <a class="result__a" href="https://example.com/one">First Result</a>
            <a class="result__snippet">The first snippet text.</a>
          </div>
        </body></html>
    """.trimIndent()

    private val genuineEmptyHtml = """
        <html><body>
          <div id="no-results">
            <div class="no-results">
              No results.
              <br>
              Try again without misspellings or using less specific words.
            </div>
          </div>
        </body></html>
    """.trimIndent()

    // Trimmed excerpt of the real DuckDuckGo anti-bot challenge page, captured 2026-07-21 via
    // `curl -A '<chrome-ua>' 'https://html.duckduckgo.com/html/?q=test'` from a rate-limited IP
    // (HTTP 202). Full response saved separately; only the load-bearing markers are kept here.
    private val challengeHtml = """
        <!DOCTYPE html>
        <html>
        <head><title>
                DuckDuckGo
            </title></head>
        <body class="anomaly-modal__body">
        <div class="anomaly-modal">
          <div class="anomaly-modal__box">
            <div class="anomaly-modal__description">
              Unfortunately, bots use DuckDuckGo too. Please complete the following
              challenge to confirm this search was made by a human.
            </div>
            <div class="anomaly-modal__puzzle">
              <img class="anomaly-modal__image" id="anomaly-modal-image-0" src="/anomaly/images/challenge/04beb9b54ced3d00789bf0afebf73dd5.jpg">
            </div>
            <div class="anomaly-modal__controls">
              <button class="anomaly-modal__check">Submit</button>
            </div>
          </div>
        </div>
        </body>
        </html>
    """.trimIndent()

    @Test
    fun `real results classify as Results`() {
        val outcome = classifyDdgResponse(httpStatus = 200, html = resultsHtml, limit = 10)

        assertTrue(outcome is DdgOutcome.Results)
        assertEquals(1, (outcome as DdgOutcome.Results).items.size)
    }

    @Test
    fun `genuine no-results markup classifies as Empty, not Blocked`() {
        val outcome = classifyDdgResponse(httpStatus = 200, html = genuineEmptyHtml, limit = 10)

        assertEquals(DdgOutcome.Empty, outcome)
    }

    @Test
    fun `the real captured challenge page classifies as Blocked`() {
        val outcome = classifyDdgResponse(httpStatus = 200, html = challengeHtml, limit = 10)

        assertTrue(outcome is DdgOutcome.Blocked)
    }

    @Test
    fun `a blocky http status with an empty body classifies as Blocked`() {
        listOf(202, 403, 429, 500, 503).forEach { status ->
            val outcome = classifyDdgResponse(httpStatus = status, html = "", limit = 10)
            assertTrue("status $status should be Blocked", outcome is DdgOutcome.Blocked)
        }
    }

    @Test
    fun `an unrecognised empty body defaults to Blocked rather than a false Empty`() {
        val outcome = classifyDdgResponse(httpStatus = 200, html = "<html><body></body></html>", limit = 10)

        assertTrue(outcome is DdgOutcome.Blocked)
        assertEquals("unrecognised empty response", (outcome as DdgOutcome.Blocked).reason)
    }
}
