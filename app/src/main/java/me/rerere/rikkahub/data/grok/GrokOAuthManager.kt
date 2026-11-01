package me.rerere.rikkahub.data.grok

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Drives the xAI Grok OAuth 2.0 Device Authorization Grant (RFC 8628): request a device+user code
 * from auth.x.ai, send the user to the verification page, then poll the token endpoint until they
 * approve. This is the flow the grok-cli / Hermes clients use for SuperGrok / X Premium+ accounts.
 */
class GrokOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val repository: GrokAccountRepository,
    private val json: Json,
) {
    private val _status = MutableStateFlow<GrokOAuthStatus>(GrokOAuthStatus.Idle)
    val status: StateFlow<GrokOAuthStatus> = _status.asStateFlow()
    private var pollJob: Job? = null

    fun startLogin() {
        pollJob?.cancel()
        _status.value = GrokOAuthStatus.Starting
        pollJob = scope.launch {
            try {
                val device = requestDeviceCode()
                _status.value = GrokOAuthStatus.AwaitingApproval(
                    userCode = device.userCode,
                    verificationUri = device.verificationUri,
                )
                openBrowser(device.verificationUriComplete ?: device.verificationUri)
                val account = pollForToken(device)
                _status.value = GrokOAuthStatus.Success(account.id)
                runCatching { repository.refreshAccount(account.id) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                Log.e(TAG, "Grok OAuth failed: ${error::class.java.name}: ${error.message}", error)
                _status.value = GrokOAuthStatus.Error(error.message ?: "Grok sign-in failed")
            }
        }
    }

    fun cancel() {
        pollJob?.cancel()
        _status.value = GrokOAuthStatus.Idle
    }

    fun consumeResult() {
        _status.value = GrokOAuthStatus.Idle
    }

    private suspend fun requestDeviceCode(): DeviceAuthorization = withContext(Dispatchers.IO) {
        val response = client.newCall(
            Request.Builder()
                .url(DEVICE_CODE_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .post(
                    FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("scope", SCOPE)
                        .build()
                )
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Device authorization failed: ${response.code} $body")
        }
        val obj = json.parseToJsonElement(body).jsonObject
        fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull
        DeviceAuthorization(
            deviceCode = str("device_code") ?: error("Missing device_code"),
            userCode = str("user_code") ?: error("Missing user_code"),
            verificationUri = str("verification_uri")
                ?: str("verification_uri_complete")
                ?: error("Missing verification_uri"),
            verificationUriComplete = str("verification_uri_complete"),
            intervalSeconds = (obj["interval"]?.jsonPrimitive?.intOrNull ?: 5).coerceAtLeast(1),
            expiresAtMillis = System.currentTimeMillis() +
                (obj["expires_in"]?.jsonPrimitive?.intOrNull ?: 600) * 1000L,
        )
    }

    private suspend fun pollForToken(device: DeviceAuthorization): GrokAccount {
        var intervalMs = device.intervalSeconds * 1000L
        while (true) {
            if (System.currentTimeMillis() > device.expiresAtMillis) {
                error("The sign-in code expired. Please try again.")
            }
            delay(intervalMs)
            val response = withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder()
                        .url(TOKEN_URL)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "application/json")
                        .post(
                            FormBody.Builder()
                                .add("grant_type", DEVICE_CODE_GRANT_TYPE)
                                .add("device_code", device.deviceCode)
                                .add("client_id", CLIENT_ID)
                                .build()
                        )
                        .build()
                ).await()
            }
            val body = response.body.string()
            if (response.isSuccessful) {
                return repository.saveLogin(body)
            }
            when (val err = json.runCatching {
                parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()) {
                "authorization_pending" -> Unit
                "slow_down" -> intervalMs += 5000L
                "expired_token" -> error("The sign-in code expired. Please try again.")
                "access_denied" -> error("Sign-in was denied.")
                else -> error("Grok sign-in failed: ${err ?: response.code}")
            }
        }
    }

    private fun openBrowser(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    companion object {
        private const val TAG = "GrokOAuthManager"
        const val CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val ISSUER = "https://auth.x.ai"
        const val DEVICE_CODE_URL = "$ISSUER/oauth2/device/code"
        const val TOKEN_URL = "$ISSUER/oauth2/token"
        const val SCOPE = "openid profile email offline_access grok-cli:access api:access"
        const val DEVICE_CODE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        const val USER_AGENT = "grok-cli"
    }
}

private data class DeviceAuthorization(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val intervalSeconds: Int,
    val expiresAtMillis: Long,
)

sealed interface GrokOAuthStatus {
    data object Idle : GrokOAuthStatus
    data object Starting : GrokOAuthStatus
    data class AwaitingApproval(
        val userCode: String,
        val verificationUri: String,
    ) : GrokOAuthStatus

    data class Success(val accountId: String) : GrokOAuthStatus
    data class Error(val message: String) : GrokOAuthStatus
}
