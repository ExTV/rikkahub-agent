package me.rerere.rikkahub.data.grok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.OffsetDateTime
import java.util.Base64

internal data class GrokIdentity(
    val userId: String,
    val email: String,
    val name: String,
)

/**
 * xAI OAuth access/id tokens are JWTs. Pull the identity claims from the payload so signed-in
 * accounts show a human-readable name. Falls back gracefully when a claim is absent.
 */
internal fun parseGrokIdentity(token: String, json: Json): GrokIdentity {
    val claims = runCatching {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid JWT" }
        val payload = Base64.getUrlDecoder().decode(parts[1])
        json.parseToJsonElement(payload.decodeToString()).jsonObject
    }.getOrNull()

    fun claim(key: String): String? = claims?.get(key)?.jsonPrimitive?.contentOrNull

    val email = claim("email").orEmpty()
    val userId = claim("sub") ?: claim("user_id") ?: email
    val name = claim("name")
        ?: claim("preferred_username")
        ?: claim("given_name")
        ?: email.substringBefore('@').ifBlank { "Grok" }
    return GrokIdentity(userId = userId, email = email, name = name)
}

private const val WEEKLY_PERIOD_TYPE = "USAGE_PERIOD_TYPE_WEEKLY"

/**
 * Parse the shared-pool billing snapshot from `GET /v1/billing?format=credits`. This is the same
 * proto-JSON shape the Grok CLI consumes; zero-valued fields are omitted (an absent
 * `creditUsagePercent` genuinely means 0). The weekly window is only surfaced when the account's
 * current period is weekly — a legacy monthly-only account has no weekly pool.
 */
internal fun parseGrokCreditsUsage(root: JsonObject): GrokUsageSnapshot {
    val config = root["config"]?.jsonObject
    val period = config?.get("currentPeriod")?.jsonObject
    val periodType = period?.get("type")?.jsonPrimitive?.contentOrNull
    val usedPercent = config?.get("creditUsagePercent")?.jsonPrimitive?.doubleOrNull ?: 0.0
    val onDemandCap = config?.get("onDemandCap")?.jsonObject
        ?.get("val")?.jsonPrimitive?.doubleOrNull ?: 0.0

    val weekly = if (periodType == WEEKLY_PERIOD_TYPE) {
        val start = period["start"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
        val end = period["end"]?.jsonPrimitive?.contentOrNull?.let(::parseIsoEpochSeconds)
        GrokUsageWindow(
            usedPercent = usedPercent,
            resetsAt = end,
            periodDurationMs = if (start != null && end != null) (end - start) * 1000 else null,
        )
    } else {
        null
    }
    return GrokUsageSnapshot(weekly = weekly, onDemandCap = onDemandCap)
}

/** The subscription tier name from `GET /v1/settings` (e.g. "SuperGrok"), or null if absent. */
internal fun parseGrokPlanName(root: JsonObject): String? =
    root["subscription_tier_display"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }

private fun parseIsoEpochSeconds(raw: String): Long? =
    runCatching { OffsetDateTime.parse(raw.trim()).toEpochSecond() }.getOrNull()
