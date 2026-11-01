package me.rerere.rikkahub.data.grok

import kotlinx.serialization.Serializable

@Serializable
data class GrokAccount(
    val id: String,
    val userId: String = "",
    val name: String,
    val email: String = "",
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val enabled: Boolean = true,
    val tokenStatus: GrokTokenStatus = GrokTokenStatus.UNKNOWN,
    val usage: GrokUsageSnapshot? = null,
)

@Serializable
enum class GrokTokenStatus {
    UNKNOWN,
    AVAILABLE,
    EXPIRED,
    INVALID,
}

@Serializable
data class GrokUsageSnapshot(
    val weekly: GrokUsageWindow? = null,
    val planName: String? = null,
    val onDemandCap: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class GrokUsageWindow(
    val usedPercent: Double,
    val resetsAt: Long? = null, // epoch seconds
    val periodDurationMs: Long? = null,
)

internal fun GrokAccount.isAvailable(nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (!enabled || tokenStatus == GrokTokenStatus.INVALID) return false
    val weekly = usage?.weekly
    // Exhausted only when the weekly pool is spent AND there is no pay-as-you-go cap to fall back
    // on AND the window has not already rolled over.
    val exhausted = weekly != null &&
        weekly.usedPercent >= 100.0 &&
        (usage.onDemandCap <= 0.0) &&
        (weekly.resetsAt == null || weekly.resetsAt * 1000 > nowMillis)
    return !exhausted
}
