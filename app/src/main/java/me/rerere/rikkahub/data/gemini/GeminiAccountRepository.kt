package me.rerere.rikkahub.data.gemini

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiAccountRepository internal constructor(
    private val store: GeminiCredentialStore,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var state = store.read().let { stored ->
        stored.copy(
            accounts = stored.accounts.map { account ->
                if (
                    account.tokenStatus != GeminiTokenStatus.INVALID &&
                    account.expiresAt <= System.currentTimeMillis()
                ) {
                    account.copy(tokenStatus = GeminiTokenStatus.EXPIRED)
                } else {
                    account
                }
            }
        )
    }
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<GeminiAccount>> = _accounts.asStateFlow()

    /**
     * Persist a freshly exchanged token set.
     *
     * Both the sign-in identity and the Cloud Code Assist project are resolved here, outside the
     * lock, because each is a network round trip and holding the mutex across them would stall
     * every concurrent generate request behind a sign-in.
     */
    suspend fun saveLogin(tokenJson: String): GeminiAccount {
        val token = json.parseToJsonElement(tokenJson).jsonObject
        val accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing access token")
        val refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing refresh token. Sign in again and grant offline access.")
        val identity = fetchIdentity(accessToken)
        val projectId = discoverProject(accessToken)
        val expiresAt = System.currentTimeMillis() + (
            token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
            ) * 1000

        return mutex.withLock {
            val id = identity.email.ifBlank { projectId }
            val existing = state.accounts.firstOrNull { it.id == id }
            val account = GeminiAccount(
                id = id,
                name = identity.name.ifBlank { identity.email.ifBlank { "Google account" } },
                email = identity.email,
                projectId = projectId,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAt = expiresAt,
                enabled = existing?.enabled ?: true,
                tokenStatus = GeminiTokenStatus.AVAILABLE,
            )
            updateState(
                state.copy(accounts = state.accounts.filterNot { it.id == account.id } + account)
            )
            account
        }
    }

    suspend fun acquireAccount(): GeminiAccount = mutex.withLock {
        if (state.accounts.isEmpty()) error("No Google account is signed in")
        repeat(state.accounts.size) {
            val index = selectGeminiAccountIndex(
                accounts = state.accounts,
                startIndex = state.nextAccountIndex,
            ) ?: error("No available Google account")
            val candidate = state.accounts[index]
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            val fresh = runCatching { ensureFreshLocked(candidate) }.getOrNull() ?: return@repeat
            return fresh
        }
        error("No available Google account")
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = mutex.withLock {
        replaceAccount(accountId) { it.copy(enabled = enabled) }
    }

    suspend fun markInvalid(accountId: String) = mutex.withLock {
        replaceAccount(accountId) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
    }

    suspend fun delete(accountId: String) = mutex.withLock {
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == accountId },
                nextAccountIndex = 0,
            )
        )
    }

    suspend fun refreshAccount(accountId: String): GeminiAccount = mutex.withLock {
        val account = state.accounts.firstOrNull { it.id == accountId }
            ?: error("Google account not found")
        val fresh = ensureFreshLocked(account, force = true)
        // Quota is informational, so a backend that will not report it must not turn a perfectly
        // good token refresh into a failure.
        runCatching { fetchUsageLocked(fresh) }.getOrDefault(fresh)
    }

    suspend fun refreshAll() {
        accounts.value.forEach { account ->
            runCatching { refreshAccount(account.id) }
        }
    }

    private suspend fun ensureFreshLocked(
        account: GeminiAccount,
        force: Boolean = false,
    ): GeminiAccount {
        if (!force && account.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            return account
        }
        val response = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder()
                    .url(GeminiOAuthManager.TOKEN_URL)
                    .post(
                        FormBody.Builder()
                            .add("client_id", GeminiOAuthManager.CLIENT_ID)
                            .add("client_secret", GeminiOAuthManager.CLIENT_SECRET)
                            .add("refresh_token", account.refreshToken)
                            .add("grant_type", "refresh_token")
                            .build()
                    )
                    .build()
            ).await()
        }
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            if (isGeminiRefreshAuthenticationFailure(response.code, responseBody, json)) {
                replaceAccount(account.id) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
            }
            error("Token refresh failed: ${response.code}")
        }
        val token = json.parseToJsonElement(responseBody).jsonObject
        val updated = account.copy(
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing refreshed access token"),
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: account.refreshToken,
            expiresAt = System.currentTimeMillis() + (
                token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                ) * 1000,
            tokenStatus = GeminiTokenStatus.AVAILABLE,
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    /**
     * Refresh the account's quota from `fetchAvailableModels`, which reports it alongside the
     * model list rather than on an endpoint of its own.
     */
    private suspend fun fetchUsageLocked(account: GeminiAccount): GeminiAccount {
        val outcome = client.postWithEndpointFallback(GEMINI_GENERATE_ENDPOINTS, json) { endpoint ->
            Request.Builder()
                .url("$endpoint/v1internal:fetchAvailableModels")
                .antigravityHeaders(account.accessToken)
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }
        if (!outcome.successful) {
            if (outcome.code == 401) {
                replaceAccount(account.id) { it.copy(tokenStatus = GeminiTokenStatus.INVALID) }
            }
            error("Failed to fetch Gemini usage: ${outcome.code} ${outcome.body}")
        }
        val snapshot = parseGeminiQuotaUsage(json.parseToJsonElement(outcome.body).jsonObject)
        if (snapshot == null) {
            // Keeping the previous snapshot beats blanking the card, but the user is then looking
            // at a stale reading, so say why rather than failing silently.
            Log.w(TAG, "fetchAvailableModels reported no quota; keeping the previous snapshot")
            return account
        }
        val updated = account.copy(usage = snapshot)
        replaceAccount(account.id) { updated }
        return updated
    }

    private suspend fun fetchIdentity(accessToken: String): GeminiIdentity =
        withContext(Dispatchers.IO) {
            val response = runCatching {
                client.newCall(
                    Request.Builder()
                        .url(USERINFO_URL)
                        .header("Authorization", "Bearer $accessToken")
                        .get()
                        .build()
                ).await()
            }.getOrNull() ?: return@withContext GeminiIdentity()
            if (!response.isSuccessful) {
                response.close()
                return@withContext GeminiIdentity()
            }
            val body = runCatching {
                json.parseToJsonElement(response.body.string()).jsonObject
            }.getOrNull() ?: return@withContext GeminiIdentity()
            GeminiIdentity(
                email = body["email"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                name = body["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        }

    /**
     * Resolve the `cloudaicompanionProject` this account generates against.
     *
     * Mirrors Antigravity's own onboarding: loadCodeAssist either hands back a project outright
     * or reports the tier to onboard against, and an account that has never used Code Assist is
     * provisioned one by onboardUser.
     */
    private suspend fun discoverProject(accessToken: String): String = withContext(Dispatchers.IO) {
        val loadResponse = client.newCall(
            Request.Builder()
                .url("$CODE_ASSIST_ENDPOINT/v1internal:loadCodeAssist")
                .antigravityHeaders(accessToken)
                .post(
                    json.encodeToString(
                        buildJsonObject {
                            put("metadata", clientMetadataJson())
                        }
                    ).toRequestBody(JSON_MEDIA_TYPE)
                )
                .build()
        ).await()
        val loadBody = loadResponse.body.string()
        if (!loadResponse.isSuccessful) {
            error("loadCodeAssist failed: ${loadResponse.code} $loadBody")
        }
        val load = json.parseToJsonElement(loadBody).jsonObject

        readProjectId(load["cloudaicompanionProject"])
            ?.let { return@withContext it }

        val tierId = selectGeminiTier(load)?.get("id")?.jsonPrimitive?.contentOrNull ?: TIER_LEGACY

        // onboardUser returns a long-running operation that is usually already finished. When it
        // is not, Antigravity re-sends the same request rather than polling the operation by name,
        // so the provisioning it kicked off is picked up by the next call's response.
        var operation: JsonObject? = null
        for (attempt in 0 until ONBOARD_MAX_ATTEMPTS) {
            if (attempt > 0) delay(ONBOARD_RETRY_INTERVAL_MS)
            val response = client.newCall(
                Request.Builder()
                    .url("$CODE_ASSIST_ENDPOINT/v1internal:onboardUser")
                    .antigravityHeaders(accessToken)
                    .post(
                        json.encodeToString(
                            buildJsonObject {
                                put("tierId", tierId)
                                put("metadata", clientMetadataJson())
                            }
                        ).toRequestBody(JSON_MEDIA_TYPE)
                    )
                    .build()
            ).await()
            val body = response.body.string()
            if (!response.isSuccessful) {
                error("onboardUser failed: ${response.code} $body")
            }
            val parsed = json.parseToJsonElement(body).jsonObject
            operation = parsed
            if (parsed["done"]?.jsonPrimitive?.booleanOrNull == true) break
        }

        val finished = operation ?: error("onboardUser returned nothing")
        readProjectId(finished["response"]?.jsonObject?.get("cloudaicompanionProject"))
            ?: error("onboardUser finished without returning a project: $finished")
    }

    private fun replaceAccount(
        accountId: String,
        transform: (GeminiAccount) -> GeminiAccount,
    ) {
        updateState(
            state.copy(
                accounts = state.accounts.map {
                    if (it.id == accountId) transform(it) else it
                }
            )
        )
    }

    private fun updateState(newState: GeminiAccountState) {
        state = newState
        store.write(newState)
        _accounts.value = newState.accounts
    }

    companion object {
        const val CODE_ASSIST_ENDPOINT = "https://cloudcode-pa.googleapis.com"
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v1/userinfo?alt=json"
        private const val REFRESH_MARGIN_MS = 30_000L
        private const val TAG = "GeminiAccountRepository"
        private const val TIER_LEGACY = "legacy-tier"
        private const val ONBOARD_RETRY_INTERVAL_MS = 2_000L
        private const val ONBOARD_MAX_ATTEMPTS = 5
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private data class GeminiIdentity(
    val email: String = "",
    val name: String = "",
)

/**
 * The Cloud Code Assist backend gates model routing and quota on the client it believes it is
 * talking to. The real `antigravity/hub` client's header is
 * `antigravity/hub/<version> (aidev_client; os_type=<os>; arch=<arch>; cl=<changelist>)`, with
 * `os_type`/`arch` pinned to the darwin/arm64 reference client the backend's model gating was
 * captured from - independent of the host platform - because the backend gates on the version,
 * not the platform (oh-my-pi packages/catalog/src/wire/gemini-headers.ts:26-33). Unlike the Gemini
 * CLI, Antigravity sends no `Client-Metadata` header: the same information travels in the request
 * body instead.
 */
internal fun Request.Builder.antigravityHeaders(accessToken: String): Request.Builder =
    header("Authorization", "Bearer $accessToken")
        .header("User-Agent", buildAntigravityUserAgent())

/** Builds the pinned Antigravity `User-Agent` header value; see [antigravityHeaders]. */
internal fun buildAntigravityUserAgent(
    version: String = ANTIGRAVITY_VERSION,
    os: String = ANTIGRAVITY_OS,
    arch: String = ANTIGRAVITY_ARCH,
    cl: String = ANTIGRAVITY_CL,
): String = "antigravity/hub/$version (aidev_client; os_type=$os; arch=$arch; cl=$cl)"

internal fun clientMetadataJson(): JsonObject = buildJsonObject {
    put("ideType", "ANTIGRAVITY")
    put("platform", "PLATFORM_UNSPECIFIED")
    put("pluginType", "GEMINI")
}

// oh-my-pi's DEFAULT_ANTIGRAVITY_VERSION, the pinned fallback it uses when it isn't reading the
// live Antigravity update manifest (packages/catalog/src/wire/gemini-headers.ts:35). The backend
// gates newer models on this version, so bump it there and here together when it moves; we do not
// fetch the manifest ourselves to avoid a runtime dependency on an extra Google-owned endpoint.
internal const val ANTIGRAVITY_VERSION = "2.8.0"
internal const val ANTIGRAVITY_OS = "darwin"
internal const val ANTIGRAVITY_ARCH = "arm64"
// The backend does not validate cl: stale, zero, and absent values all pass model gating
// (gemini-headers.ts:95-98), so this stays at opencode-antigravity-auth's captured value
// (src/constants.ts's getAntigravityUserAgent default) rather than tracking a real changelist.
internal const val ANTIGRAVITY_CL = "963137146"

// Antigravity generate traffic (streamGenerateContent, fetchAvailableModels) tries the daily
// Cloud Code Assist tier first, then its sandbox twin - oh-my-pi's ANTIGRAVITY_ENDPOINT_FALLBACKS
// (packages/ai/src/providers/google-gemini-cli.ts:314-316) - falling back to prod as a last
// resort so a signed-in account still works if both daily tiers are unreachable. loadCodeAssist
// and onboardUser (project discovery, sign-in) stay on prod only: sign-in already works there.
internal const val ANTIGRAVITY_DAILY_ENDPOINT = "https://daily-cloudcode-pa.googleapis.com"
internal const val ANTIGRAVITY_DAILY_SANDBOX_ENDPOINT = "https://daily-cloudcode-pa.sandbox.googleapis.com"
internal val GEMINI_GENERATE_ENDPOINTS = listOf(
    ANTIGRAVITY_DAILY_ENDPOINT,
    ANTIGRAVITY_DAILY_SANDBOX_ENDPOINT,
    GeminiAccountRepository.CODE_ASSIST_ENDPOINT,
)

internal fun isGeminiRefreshAuthenticationFailure(
    statusCode: Int,
    responseBody: String,
    json: Json,
): Boolean {
    if (statusCode == 401) return true
    if (statusCode != 400) return false
    val errorCode = runCatching {
        json.parseToJsonElement(responseBody).jsonObject["error"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return errorCode == "invalid_grant" || errorCode == "invalid_token"
}

/**
 * Pick the tier to onboard against from a `loadCodeAssist` response.
 *
 * `currentTier` wins when present: it is the tier the account is already on, not a signal that
 * the account is unusable. Only when there is no current tier does the default entry in
 * `allowedTiers` apply. Returning null leaves the caller on the legacy tier, which is the same
 * fallback Antigravity uses.
 */
internal fun selectGeminiTier(load: JsonObject): JsonObject? =
    load["currentTier"]?.jsonObject
        ?: load["allowedTiers"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["isDefault"]?.jsonPrimitive?.booleanOrNull == true }

private const val WINDOW_DAILY = "daily"
private const val WINDOW_WEEKLY = "weekly"
private const val ONE_DAY_SECONDS = 24 * 60 * 60L

// Quota can arrive under any of these keys, singular or as an array. The two prefixed ones name
// their own window; the bare ones have to be classified from what is inside them.
private val QUOTA_FIELDS = listOf(
    "quotaInfo" to null,
    "quotaInfos" to null,
    "dailyQuotaInfo" to WINDOW_DAILY,
    "dailyQuotaInfos" to WINDOW_DAILY,
    "weeklyQuotaInfo" to WINDOW_WEEKLY,
    "weeklyQuotaInfos" to WINDOW_WEEKLY,
)

/**
 * Collapse the per-model quota in a `fetchAvailableModels` response into one reading per window.
 *
 * Returns null when the response carries no quota at all, which keeps a backend that stops
 * reporting it from wiping a snapshot the user is still looking at.
 */
internal fun parseGeminiQuotaUsage(
    root: JsonObject,
    nowMillis: Long = System.currentTimeMillis(),
): GeminiUsageSnapshot? {
    val models = root["models"] as? JsonObject ?: return null
    var daily: GeminiUsageWindow? = null
    var weekly: GeminiUsageWindow? = null
    for (modelElement in models.values) {
        val model = modelElement as? JsonObject ?: continue
        for ((field, declaredWindow) in QUOTA_FIELDS) {
            for (info in quotaInfosIn(model[field])) {
                val fraction = info["remainingFraction"]?.jsonPrimitive?.doubleOrNull ?: continue
                val resetsAt = parseQuotaResetTime(info["resetTime"]?.jsonPrimitive?.contentOrNull)
                val window = GeminiUsageWindow(fraction.coerceIn(0.0, 1.0), resetsAt)
                val id = declaredWindow ?: classifyQuotaWindow(info, resetsAt, nowMillis)
                if (id == WINDOW_WEEKLY) {
                    weekly = scarcerOf(weekly, window)
                } else {
                    daily = scarcerOf(daily, window)
                }
            }
        }
    }
    if (daily == null && weekly == null) return null
    return GeminiUsageSnapshot(daily = daily, weekly = weekly, updatedAt = nowMillis)
}

private fun quotaInfosIn(element: kotlinx.serialization.json.JsonElement?): List<JsonObject> =
    when (element) {
        is JsonObject -> listOf(element)
        is kotlinx.serialization.json.JsonArray -> element.filterIsInstance<JsonObject>()
        else -> emptyList()
    }

private fun scarcerOf(current: GeminiUsageWindow?, candidate: GeminiUsageWindow) =
    if (current == null || candidate.remainingFraction < current.remainingFraction) {
        candidate
    } else {
        current
    }

private fun classifyQuotaWindow(
    info: JsonObject,
    resetsAt: Long?,
    nowMillis: Long,
): String {
    val source = listOfNotNull(
        info["windowId"]?.jsonPrimitive?.contentOrNull,
        info["windowLabel"]?.jsonPrimitive?.contentOrNull,
    ).joinToString(" ").lowercase()
    if (source.contains("week") || source.contains("7d")) return WINDOW_WEEKLY
    if (source.contains("day") || source.contains("24h")) return WINDOW_DAILY
    // Nothing labelled it, so fall back to how far out it resets: anything more than a day away
    // cannot be a daily window.
    val secondsUntilReset = resetsAt?.minus(nowMillis / 1000) ?: return WINDOW_DAILY
    return if (secondsUntilReset > ONE_DAY_SECONDS) WINDOW_WEEKLY else WINDOW_DAILY
}

private fun parseQuotaResetTime(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { java.time.Instant.parse(raw).epochSecond }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(raw).toEpochSecond() }.getOrNull()
}

/**
 * Read a `cloudaicompanionProject` value, which comes back either as a bare string or as an
 * object carrying an `id` depending on the tier, so accept both rather than assuming one shape.
 */
internal fun readProjectId(element: kotlinx.serialization.json.JsonElement?): String? =
    ((element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        ?: (element as? JsonPrimitive)?.contentOrNull)
        ?.takeIf { it.isNotBlank() }

internal fun selectGeminiAccountIndex(
    accounts: List<GeminiAccount>,
    startIndex: Int,
): Int? {
    if (accounts.isEmpty()) return null
    repeat(accounts.size) { offset ->
        val index = (startIndex + offset).mod(accounts.size)
        if (accounts[index].isAvailable()) return index
    }
    return null
}

// oh-my-pi's MAX_RETRIES / BASE_DELAY_MS (google-gemini-cli.ts:319-320): the last endpoint in the
// fallback chain gets this many extra attempts, backing off exponentially from this base.
internal const val GEMINI_MAX_RETRIES = 3
internal const val GEMINI_RETRY_BASE_DELAY_MS = 1_000L

// oh-my-pi's RATE_LIMIT_BUDGET_MS (google-gemini-cli.ts:322) and LONG_RATE_LIMIT_DELAY_MS
// (error/rate-limit.ts) name the same five-minute figure for two different purposes that both
// apply here: the longest delay worth actually waiting out, and the delay past which a
// RATE_LIMIT_EXCEEDED reason is really a long quota window rather than a short throttle.
internal const val GEMINI_RETRY_DELAY_CAP_MS = 5 * 60 * 1000L

private const val GOOGLE_RPC_ERROR_INFO_TYPE = "type.googleapis.com/google.rpc.ErrorInfo"
private const val GOOGLE_RPC_RETRY_INFO_TYPE = "type.googleapis.com/google.rpc.RetryInfo"
private val RETRY_DELAY_VALUE_PATTERN = Regex("""^([0-9.]+)(ms|s)$""")

// oh-my-pi's ANTIGRAVITY_MODEL_QUOTA_PATTERN (error/rate-limit.ts:114): Cloud Code Assist reuses
// the RATE_LIMIT_EXCEEDED reason for a per-model daily quota - distinguishable only by this phrase
// in error.message - so that case is promoted to QUOTA_EXHAUSTED the same way rate-limit.ts:162
// promotes it, regardless of any retry delay.
private val ANTIGRAVITY_MODEL_QUOTA_PATTERN = Regex("""\bexhausted your capacity on this model\b""", RegexOption.IGNORE_CASE)

/**
 * How a Cloud Code Assist error response should steer the caller's retry loop.
 *
 * [reason] is a `google.rpc.ErrorInfo.reason` read out of `error.details[]` when `error.status`
 * is `RESOURCE_EXHAUSTED` (oh-my-pi's `parseGoogleRpcRateLimitReason`,
 * packages/ai/src/error/rate-limit.ts:140-176), or the bare `error.status` otherwise. [retryDelayMs]
 * is a `google.rpc.RetryInfo.retryDelay` value such as `"12s"` (`extractRetryHint`,
 * packages/utils/src/fetch-retry.ts:8, 71). [retryable] mirrors that file's `fetchWithRetry`
 * (lines 375-382) plus `isTransientStatus` (error/retryable.ts:20-22): only a transient HTTP
 * status (408/429/5xx) is retryable at all, and even then a `QUOTA_EXHAUSTED` reason - or a
 * `RATE_LIMIT_EXCEEDED` one whose own retry delay is at or beyond [GEMINI_RETRY_DELAY_CAP_MS], or
 * whose message names a per-model quota ([ANTIGRAVITY_MODEL_QUOTA_PATTERN]) - is treated as a long
 * quota window, not a transient throttle, the same way rate-limit.ts:162,170-172 promotes it.
 */
internal data class GeminiErrorClassification(
    val status: Int?,
    val reason: String?,
    val retryDelayMs: Long?,
    val retryable: Boolean,
)

/**
 * Classifies a Cloud Code Assist error response for the retry loop. [statusCode] is the real HTTP
 * status when there is one and always wins; when it is null (an error delivered inside a 200 SSE
 * event body rather than as an HTTP failure) this falls back to the body's own numeric `error.code`
 * - the same field oh-my-pi's stream loop reads to classify an embedded error
 * (google-gemini-cli.ts:766-771: `chunk.error.code` feeds `GeminiCliApiError`'s status). A missing
 * or unparseable [body] degrades to classifying off whatever status is available, never throws.
 */
internal fun classifyGeminiError(statusCode: Int?, body: String?, json: Json): GeminiErrorClassification {
    val error = runCatching {
        body?.let { json.parseToJsonElement(it).jsonObject["error"]?.jsonObject }
    }.getOrNull()
    val effectiveStatus = statusCode ?: error?.get("code")?.jsonPrimitive?.intOrNull
    val transientStatus = effectiveStatus == 408 || effectiveStatus == 429 ||
        (effectiveStatus != null && effectiveStatus >= 500)
    val rpcStatus = error?.get("status")?.jsonPrimitive?.contentOrNull
    val details = (error?.get("details") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
    var reason = if (rpcStatus?.uppercase() == "RESOURCE_EXHAUSTED") {
        details.firstOrNull { it["@type"]?.jsonPrimitive?.contentOrNull == GOOGLE_RPC_ERROR_INFO_TYPE }
            ?.get("reason")?.jsonPrimitive?.contentOrNull
    } else {
        null
    }
    val message = error?.get("message")?.jsonPrimitive?.contentOrNull
    if (reason == "RATE_LIMIT_EXCEEDED" && message != null &&
        ANTIGRAVITY_MODEL_QUOTA_PATTERN.containsMatchIn(message)
    ) {
        reason = "QUOTA_EXHAUSTED"
    }
    val retryDelayMs = details
        .firstOrNull { it["@type"]?.jsonPrimitive?.contentOrNull == GOOGLE_RPC_RETRY_INFO_TYPE }
        ?.get("retryDelay")?.jsonPrimitive?.contentOrNull
        ?.let(::parseGeminiRetryDelayMs)
    val quotaExhausted = reason == "QUOTA_EXHAUSTED" ||
        (reason == "RATE_LIMIT_EXCEEDED" && retryDelayMs != null && retryDelayMs >= GEMINI_RETRY_DELAY_CAP_MS)
    val withinRetryBudget = retryDelayMs == null || retryDelayMs <= GEMINI_RETRY_DELAY_CAP_MS
    return GeminiErrorClassification(
        status = effectiveStatus,
        reason = reason ?: rpcStatus,
        retryDelayMs = retryDelayMs,
        retryable = transientStatus && !quotaExhausted && withinRetryBudget,
    )
}

/** Parses a `google.rpc.RetryInfo.retryDelay` value such as `"12s"` or `"500ms"` into milliseconds. */
internal fun parseGeminiRetryDelayMs(raw: String): Long? {
    val match = RETRY_DELAY_VALUE_PATTERN.find(raw.trim()) ?: return null
    val (numberPart, unit) = match.destructured
    val amount = numberPart.toDoubleOrNull() ?: return null
    return (if (unit == "ms") amount else amount * 1000.0).toLong()
}

/** The delay before the next retry attempt: the server's own hint if it gave one, else exponential backoff. */
internal fun resolveGeminiRetryDelayMs(classification: GeminiErrorClassification, attempt: Int): Long {
    val backoff = GEMINI_RETRY_BASE_DELAY_MS * (1L shl attempt)
    return (classification.retryDelayMs ?: backoff).coerceAtMost(GEMINI_RETRY_DELAY_CAP_MS)
}

/** The outcome of one Cloud Code Assist HTTP call, with the body always already drained to text. */
internal data class GeminiHttpOutcome(val code: Int, val body: String) {
    val successful: Boolean get() = code in 200..299
}

/**
 * Issues a Cloud Code Assist POST across [endpoints] in order, mirroring oh-my-pi's Antigravity
 * fallback rule (google-gemini-cli.ts:928-976's per-endpoint `fetchWithRetry` call): every
 * endpoint but the last gets exactly one attempt, and a transient failure (408/429/5xx) moves on
 * to the next endpoint immediately with no delay; the last endpoint gets [GEMINI_MAX_RETRIES]
 * extra attempts with backoff, honoring a `google.rpc.RetryInfo` delay when [classifyGeminiError]
 * finds one, capped at [GEMINI_RETRY_DELAY_CAP_MS]. A non-transient status, or one
 * [classifyGeminiError] rules out as a long quota window, stops the whole chain immediately rather
 * than trying the remaining endpoints.
 */
internal suspend fun OkHttpClient.postWithEndpointFallback(
    endpoints: List<String>,
    json: Json,
    buildRequest: (endpoint: String) -> Request,
): GeminiHttpOutcome {
    var last: GeminiHttpOutcome? = null
    for (index in endpoints.indices) {
        val endpoint = endpoints[index]
        val isLastEndpoint = index == endpoints.lastIndex
        val maxAttempts = if (isLastEndpoint) GEMINI_MAX_RETRIES + 1 else 1
        for (attempt in 0 until maxAttempts) {
            val response = withContext(Dispatchers.IO) { newCall(buildRequest(endpoint)).await() }
            val outcome = GeminiHttpOutcome(response.code, response.body.string())
            last = outcome
            if (outcome.successful) return outcome
            val classification = classifyGeminiError(outcome.code, outcome.body, json)
            if (!classification.retryable) return outcome
            if (attempt < maxAttempts - 1) {
                delay(resolveGeminiRetryDelayMs(classification, attempt))
            }
            // Otherwise this endpoint is exhausted; fall through to the next one.
        }
    }
    return last ?: error("No Cloud Code Assist endpoint was attempted")
}
