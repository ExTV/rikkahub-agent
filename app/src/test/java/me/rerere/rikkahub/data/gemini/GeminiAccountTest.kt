package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiAccountTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun account(
        id: String,
        enabled: Boolean = true,
        tokenStatus: GeminiTokenStatus = GeminiTokenStatus.AVAILABLE,
    ) = GeminiAccount(
        id = id,
        name = id,
        projectId = "project-$id",
        accessToken = "access-$id",
        refreshToken = "refresh-$id",
        expiresAt = Long.MAX_VALUE,
        enabled = enabled,
        tokenStatus = tokenStatus,
    )

    @Test
    fun `an enabled account with a live token is available`() {
        assertTrue(account("a").isAvailable())
    }

    @Test
    fun `a disabled account is not available`() {
        assertFalse(account("a", enabled = false).isAvailable())
    }

    @Test
    fun `an invalid token makes an account unavailable`() {
        assertFalse(account("a", tokenStatus = GeminiTokenStatus.INVALID).isAvailable())
    }

    @Test
    fun `an expired token is still selectable so the caller gets a chance to refresh it`() {
        // acquireAccount() refreshes whatever it picks, so treating EXPIRED as unavailable here
        // would strand the only signed-in account permanently after its first hour.
        assertTrue(account("a", tokenStatus = GeminiTokenStatus.EXPIRED).isAvailable())
    }

    @Test
    fun `selection starts at the rotation index`() {
        val accounts = listOf(account("a"), account("b"), account("c"))
        assertEquals(1, selectGeminiAccountIndex(accounts, startIndex = 1))
    }

    @Test
    fun `selection wraps past the end of the list`() {
        val accounts = listOf(account("a"), account("b"), account("c"))
        assertEquals(0, selectGeminiAccountIndex(accounts, startIndex = 3))
    }

    @Test
    fun `selection skips unavailable accounts`() {
        val accounts = listOf(
            account("a", enabled = false),
            account("b", tokenStatus = GeminiTokenStatus.INVALID),
            account("c"),
        )
        assertEquals(2, selectGeminiAccountIndex(accounts, startIndex = 0))
    }

    @Test
    fun `selection returns null when every account is unavailable`() {
        val accounts = listOf(
            account("a", enabled = false),
            account("b", tokenStatus = GeminiTokenStatus.INVALID),
        )
        assertNull(selectGeminiAccountIndex(accounts, startIndex = 0))
    }

    @Test
    fun `selection returns null for an empty list`() {
        assertNull(selectGeminiAccountIndex(emptyList(), startIndex = 0))
    }

    @Test
    fun `a 401 is an authentication failure`() {
        assertTrue(isGeminiRefreshAuthenticationFailure(401, "", json))
    }

    @Test
    fun `a 400 invalid_grant is an authentication failure`() {
        assertTrue(
            isGeminiRefreshAuthenticationFailure(
                400,
                """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""",
                json,
            )
        )
    }

    @Test
    fun `a 400 that is not a grant problem is not an authentication failure`() {
        // Marking the account INVALID here would force a re-login over a transient or
        // malformed-request 400, which the user cannot act on.
        assertFalse(
            isGeminiRefreshAuthenticationFailure(400, """{"error":"invalid_request"}""", json)
        )
    }

    @Test
    fun `a 400 with an unparseable body is not an authentication failure`() {
        assertFalse(isGeminiRefreshAuthenticationFailure(400, "<html>bad gateway</html>", json))
    }

    @Test
    fun `a 500 is not an authentication failure`() {
        assertFalse(isGeminiRefreshAuthenticationFailure(500, "", json))
    }

    private fun load(body: String) = json.parseToJsonElement(body).jsonObject

    @Test
    fun `an account already on a tier is onboarded against that tier`() {
        // Regression: sign-in used to refuse any response carrying currentTier at all, which
        // rejected ordinary accounts that had already used Code Assist once. currentTier is the
        // tier to onboard against, not a signal that the account is unusable.
        val tier = selectGeminiTier(load("""{"currentTier":{"id":"free-tier"}}"""))
        assertNotNull(tier)
        assertEquals("free-tier", tier!!["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the current tier wins over the default allowed tier`() {
        val tier = selectGeminiTier(
            load(
                """
                {"currentTier":{"id":"free-tier"},
                 "allowedTiers":[{"id":"legacy-tier","isDefault":true}]}
                """
            )
        )
        assertEquals("free-tier", tier!!["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the default allowed tier is used when there is no current tier`() {
        val tier = selectGeminiTier(
            load(
                """
                {"allowedTiers":[{"id":"other-tier"},{"id":"free-tier","isDefault":true}]}
                """
            )
        )
        assertEquals("free-tier", tier!!["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no tier information at all leaves the caller on its legacy fallback`() {
        assertNull(selectGeminiTier(load("{}")))
    }

    @Test
    fun `a project delivered as a bare string is read`() {
        assertEquals("proj-1", readProjectId(load("""{"p":"proj-1"}""")["p"]))
    }

    @Test
    fun `a project delivered as an object is read from its id`() {
        assertEquals("proj-1", readProjectId(load("""{"p":{"id":"proj-1"}}""")["p"]))
    }

    @Test
    fun `a missing or blank project reads as null`() {
        // A blank string here would be stored as the account's project and every later request
        // would 400 against it, so it has to be treated the same as an absent one.
        assertNull(readProjectId(null))
        assertNull(readProjectId(load("""{"p":""}""")["p"]))
        assertNull(readProjectId(load("""{"p":{}}""")["p"]))
    }
}
