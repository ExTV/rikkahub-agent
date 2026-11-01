package me.rerere.rikkahub.data.grok

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class GrokAccountRepository internal constructor(
    private val store: GrokCredentialStore,
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val mutex = Mutex()
    private var state = store.read().let { stored ->
        stored.copy(
            accounts = stored.accounts.map { account ->
                if (
                    account.tokenStatus != GrokTokenStatus.INVALID &&
                    account.expiresAt <= System.currentTimeMillis()
                ) {
                    account.copy(tokenStatus = GrokTokenStatus.EXPIRED)
                } else {
                    account
                }
            }
        )
    }
    private val _accounts = MutableStateFlow(state.accounts)
    val accounts: StateFlow<List<GrokAccount>> = _accounts.asStateFlow()

    suspend fun saveLogin(tokenJson: String): GrokAccount = mutex.withLock {
        val token = json.parseToJsonElement(tokenJson).jsonObject
        val accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing access token")
        val identity = parseGrokIdentity(
            token = token["id_token"]?.jsonPrimitive?.contentOrNull ?: accessToken,
            json = json,
        )
        val now = System.currentTimeMillis()
        val existing = state.accounts.firstOrNull {
            (it.userId.isNotBlank() && it.userId == identity.userId) ||
                (it.email.isNotBlank() && it.email == identity.email)
        }
        val account = GrokAccount(
            id = existing?.id ?: identity.userId.ifBlank { identity.email }.ifBlank { accessToken.take(16) },
            userId = identity.userId,
            name = identity.name,
            email = identity.email,
            accessToken = accessToken,
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: existing?.refreshToken
                ?: error("Missing refresh token"),
            expiresAt = now + (
                token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                ) * 1000,
            enabled = existing?.enabled ?: true,
            tokenStatus = GrokTokenStatus.AVAILABLE,
            usage = existing?.usage,
        )
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == account.id } + account
            )
        )
        account
    }

    suspend fun acquireAccount(): GrokAccount = mutex.withLock {
        if (state.accounts.isEmpty()) error("No Grok account is signed in")
        repeat(state.accounts.size) {
            val index = selectGrokAccountIndex(
                accounts = state.accounts,
                startIndex = state.nextAccountIndex,
            ) ?: error("No available Grok account")
            val candidate = state.accounts[index]
            if (!candidate.isAvailable()) return@repeat
            updateState(state.copy(nextAccountIndex = (index + 1) % state.accounts.size))
            val fresh = runCatching { ensureFreshLocked(candidate) }.getOrNull() ?: return@repeat
            return fresh
        }
        error("No available Grok account")
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) = mutex.withLock {
        replaceAccount(accountId) { it.copy(enabled = enabled) }
    }

    suspend fun markInvalid(accountId: String) = mutex.withLock {
        replaceAccount(accountId) { it.copy(tokenStatus = GrokTokenStatus.INVALID) }
    }

    suspend fun delete(accountId: String) = mutex.withLock {
        updateState(
            state.copy(
                accounts = state.accounts.filterNot { it.id == accountId },
                nextAccountIndex = 0,
            )
        )
    }

    suspend fun refreshAccount(accountId: String): GrokAccount = mutex.withLock {
        val account = state.accounts.firstOrNull { it.id == accountId }
            ?: error("Grok account not found")
        val fresh = ensureFreshLocked(account, force = true)
        runCatching { fetchUsageLocked(fresh) }.getOrDefault(fresh)
    }

    suspend fun refreshAll() {
        accounts.value.forEach { account ->
            runCatching { refreshAccount(account.id) }
        }
    }

    private suspend fun ensureFreshLocked(
        account: GrokAccount,
        force: Boolean = false,
    ): GrokAccount {
        if (!force && account.expiresAt > System.currentTimeMillis() + REFRESH_MARGIN_MS) {
            return account
        }
        val response = withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", GrokOAuthManager.CLIENT_ID)
                .add("refresh_token", account.refreshToken)
                .build()
            client.newCall(
                Request.Builder()
                    .url(GrokOAuthManager.TOKEN_URL)
                    .post(body)
                    .build()
            ).await()
        }
        val responseBody = response.body.string()
        if (!response.isSuccessful) {
            if (isGrokRefreshAuthenticationFailure(response.code, responseBody, json)) {
                replaceAccount(account.id) { it.copy(tokenStatus = GrokTokenStatus.INVALID) }
            }
            error("Token refresh failed: ${response.code}")
        }
        val token = json.parseToJsonElement(responseBody).jsonObject
        val updated = account.copy(
            accessToken = token["access_token"]?.jsonPrimitive?.contentOrNull
                ?: error("Missing refreshed access token"),
            // xAI rotates the refresh_token on every refresh.
            refreshToken = token["refresh_token"]?.jsonPrimitive?.contentOrNull
                ?: account.refreshToken,
            expiresAt = System.currentTimeMillis() + (
                token["expires_in"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L
                ) * 1000,
            tokenStatus = GrokTokenStatus.AVAILABLE,
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private suspend fun fetchUsageLocked(account: GrokAccount): GrokAccount {
        val credits = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(CREDITS_URL).grokBillingHeaders(account).get().build()
            ).await()
        }
        if (!credits.isSuccessful) {
            if (credits.code == 401) {
                replaceAccount(account.id) { it.copy(tokenStatus = GrokTokenStatus.INVALID) }
            }
            error("Failed to fetch Grok usage: ${credits.code}")
        }
        val snapshot = parseGrokCreditsUsage(
            json.parseToJsonElement(credits.body.string()).jsonObject
        )
        // Plan name is best-effort — never fail a usage refresh just because /settings is down.
        val planName = runCatching {
            val settings = withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder().url(SETTINGS_URL).grokBillingHeaders(account).get().build()
                ).await()
            }
            if (settings.isSuccessful) {
                parseGrokPlanName(json.parseToJsonElement(settings.body.string()).jsonObject)
            } else {
                null
            }
        }.getOrNull()
        val updated = account.copy(
            tokenStatus = GrokTokenStatus.AVAILABLE,
            usage = snapshot.copy(planName = planName ?: account.usage?.planName),
        )
        replaceAccount(account.id) { updated }
        return updated
    }

    private fun Request.Builder.grokBillingHeaders(account: GrokAccount): Request.Builder {
        return addHeader("Authorization", "Bearer ${account.accessToken}")
            .addHeader("X-XAI-Token-Auth", "xai-grok-cli")
            .addHeader("Accept", "application/json")
    }

    private fun replaceAccount(
        accountId: String,
        transform: (GrokAccount) -> GrokAccount,
    ) {
        updateState(
            state.copy(
                accounts = state.accounts.map {
                    if (it.id == accountId) transform(it) else it
                }
            )
        )
    }

    private fun updateState(newState: GrokAccountState) {
        state = newState
        store.write(newState)
        _accounts.value = newState.accounts
    }

    companion object {
        private const val REFRESH_MARGIN_MS = 30_000L
        // Grok subscription usage lives on the CLI billing proxy (same surface the Grok CLI uses),
        // not on api.x.ai. The credits format returns the shared weekly pool.
        private const val CREDITS_URL = "https://cli-chat-proxy.grok.com/v1/billing?format=credits"
        private const val SETTINGS_URL = "https://cli-chat-proxy.grok.com/v1/settings"
    }
}

internal fun isGrokRefreshAuthenticationFailure(
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

internal fun selectGrokAccountIndex(
    accounts: List<GrokAccount>,
    startIndex: Int,
): Int? {
    if (accounts.isEmpty()) return null
    repeat(accounts.size) { offset ->
        val index = (startIndex + offset).mod(accounts.size)
        if (accounts[index].isAvailable()) return index
    }
    return null
}
