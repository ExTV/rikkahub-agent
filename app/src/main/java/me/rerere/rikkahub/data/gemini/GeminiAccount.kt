package me.rerere.rikkahub.data.gemini

import kotlinx.serialization.Serializable

/**
 * One signed-in Google account usable against Cloud Code Assist.
 *
 * [projectId] is the `cloudaicompanionProject` resolved once at sign-in through
 * loadCodeAssist / onboardUser. Every generate request has to carry it, so it is stored with the
 * tokens rather than rediscovered per request.
 */
@Serializable
data class GeminiAccount(
    val id: String,
    val name: String,
    val email: String = "",
    val projectId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val enabled: Boolean = true,
    val tokenStatus: GeminiTokenStatus = GeminiTokenStatus.UNKNOWN,
    val usage: GeminiUsageSnapshot? = null,
)

/**
 * What is left of the account's Code Assist quota.
 *
 * Cloud Code Assist reports quota per model rather than per account, so each window here is the
 * scarcest reading across every model the account can reach: that is the one that will actually
 * stop a request.
 */
@Serializable
data class GeminiUsageSnapshot(
    val daily: GeminiUsageWindow? = null,
    val weekly: GeminiUsageWindow? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class GeminiUsageWindow(
    val remainingFraction: Double,
    val resetsAt: Long? = null, // epoch seconds
)

@Serializable
enum class GeminiTokenStatus {
    UNKNOWN,
    AVAILABLE,
    EXPIRED,
    INVALID,
}

internal fun GeminiAccount.isAvailable(): Boolean =
    enabled && tokenStatus != GeminiTokenStatus.INVALID
