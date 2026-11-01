package me.rerere.rikkahub.data.gemini

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import me.rerere.common.http.await
import me.rerere.rikkahub.R
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Loopback OAuth against Google's installed-app client, the same one Antigravity ships.
 *
 * Google issues that client as a desktop app rather than a public one, so the token exchange is
 * authenticated with a client secret and there is no PKCE leg. `access_type=offline` plus
 * `prompt=consent` are what make Google return a refresh token at all.
 */
class GeminiOAuthManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val repository: GeminiAccountRepository,
) {
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var callbackPort: Int? = null
    private val sessions = ConcurrentHashMap<String, String>()
    private val _status = MutableStateFlow<GeminiOAuthStatus>(GeminiOAuthStatus.Idle)
    val status: StateFlow<GeminiOAuthStatus> = _status.asStateFlow()

    fun startLogin() {
        val state = randomUrlSafe(32)
        try {
            val port = ensureCallbackServer()
            val redirect = "http://localhost:$port$CALLBACK_PATH"
            sessions[state] = redirect
            _status.value = GeminiOAuthStatus.Waiting

            val authUrl = Uri.parse(AUTHORIZE_URL).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirect)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("state", state)
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .build()
            context.startActivity(
                Intent(Intent.ACTION_VIEW, authUrl).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (error: Throwable) {
            sessions.remove(state)
            _status.value = GeminiOAuthStatus.Error(
                if (error.message == CALLBACK_PORTS_UNAVAILABLE) {
                    context.getString(R.string.gemini_oauth_ports_unavailable)
                } else {
                    error.message ?: "Unable to open the Google sign-in page"
                }
            )
        }
    }

    fun consumeResult() {
        _status.value = GeminiOAuthStatus.Idle
    }

    @Synchronized
    private fun ensureCallbackServer(): Int {
        callbackPort?.let { return it }
        var lastError: Throwable? = null
        for (port in CALLBACK_PORTS) {
            try {
                server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
                    routing {
                        get(CALLBACK_PATH) {
                            val callbackState = call.request.queryParameters["state"]
                            val code = call.request.queryParameters["code"]
                            val error = call.request.queryParameters["error"]
                            val redirectUri = callbackState?.let(sessions::remove)
                            when {
                                redirectUri == null -> {
                                    _status.value = GeminiOAuthStatus.Error("OAuth state mismatch")
                                    call.respondText(callbackPage(false), ContentType.Text.Html)
                                }

                                !error.isNullOrBlank() -> {
                                    _status.value = GeminiOAuthStatus.Error(error)
                                    call.respondText(callbackPage(false), ContentType.Text.Html)
                                }

                                code.isNullOrBlank() -> {
                                    _status.value =
                                        GeminiOAuthStatus.Error("Missing authorization code")
                                    call.respondText(callbackPage(false), ContentType.Text.Html)
                                }

                                else -> {
                                    call.respondText(callbackPage(true), ContentType.Text.Html)
                                    scope.launch {
                                        try {
                                            awaitNetworkUnblocked()
                                            val account = exchangeCode(code, redirectUri)
                                            _status.value = GeminiOAuthStatus.Success(account.id)
                                        } catch (error: Throwable) {
                                            Log.e(
                                                TAG,
                                                "OAuth token exchange failed: " +
                                                    "${error::class.java.name}: ${error.message}",
                                                error,
                                            )
                                            _status.value = GeminiOAuthStatus.Error(
                                                error.message ?: "OAuth token exchange failed"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.start(wait = false)
                callbackPort = port
                return port
            } catch (error: Throwable) {
                lastError = error
            }
        }
        throw IllegalStateException(CALLBACK_PORTS_UNAVAILABLE, lastError)
    }

    private suspend fun awaitNetworkUnblocked() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        suspendCancellableCoroutine { continuation ->
            lateinit var callback: ConnectivityManager.NetworkCallback
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                    if (!blocked && continuation.isActive) {
                        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
                        continuation.resume(Unit)
                    }
                }
            }
            continuation.invokeOnCancellation {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
            connectivityManager.registerDefaultNetworkCallback(callback)
        }
    }

    private suspend fun exchangeCode(code: String, redirectUri: String): GeminiAccount {
        val response = client.newCall(
            Request.Builder()
                .url(TOKEN_URL)
                .post(
                    FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("client_secret", CLIENT_SECRET)
                        .add("code", code)
                        .add("grant_type", "authorization_code")
                        .add("redirect_uri", redirectUri)
                        .build()
                )
                .build()
        ).await()
        val body = response.body.string()
        if (!response.isSuccessful) {
            error("Token exchange failed: ${response.code}")
        }
        return repository.saveLogin(body)
    }

    private fun callbackPage(success: Boolean): String {
        val status = if (success) "success" else "error"
        val deepLink = "rikkahub://gemini/oauth?status=${URLEncoder.encode(status, Charsets.UTF_8.name())}"
        return """
            <!doctype html>
            <html>
              <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <meta http-equiv="refresh" content="0; url=$deepLink">
                <title>RikkaHub Gemini OAuth</title>
              </head>
              <body>
                <p>${if (success) "Returning to RikkaHub..." else "Sign-in failed."}</p>
                <p><a href="$deepLink">Return to RikkaHub</a></p>
                <script>
                  window.location.replace("$deepLink");
                  setTimeout(function () { window.location.href = "$deepLink"; }, 500);
                </script>
              </body>
            </html>
        """.trimIndent()
    }

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        private const val TAG = "GeminiOAuthManager"
        private const val CALLBACK_PORTS_UNAVAILABLE = "OAuth callback ports are unavailable"

        // Google's published Antigravity installed-app credentials, in plaintext on purpose.
        // An installed-app OAuth client cannot hold a confidential secret: every copy of
        // Antigravity ships these and they are recoverable from any install, which is why Google
        // documents this client type as non-confidential. Encoding them would hide what they
        // are from a reader without hiding anything from anyone else.
        const val CLIENT_ID =
            "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        const val CLIENT_SECRET = "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"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"

        // cclog and experimentsandconfigs are Antigravity-specific and are part of what the
        // consent screen is registered for, so the grant is rejected without them.
        const val SCOPES = "https://www.googleapis.com/auth/cloud-platform " +
            "https://www.googleapis.com/auth/userinfo.email " +
            "https://www.googleapis.com/auth/userinfo.profile " +
            "https://www.googleapis.com/auth/cclog " +
            "https://www.googleapis.com/auth/experimentsandconfigs"
        private const val CALLBACK_PATH = "/oauth-callback"

        // Antigravity registers a single fixed loopback port with the OAuth client, so unlike a
        // free-choice port there is nothing to fall back to if it is taken.
        private val CALLBACK_PORTS = listOf(51121)
    }
}

sealed interface GeminiOAuthStatus {
    data object Idle : GeminiOAuthStatus
    data object Waiting : GeminiOAuthStatus
    data class Success(val accountId: String) : GeminiOAuthStatus
    data class Error(val message: String) : GeminiOAuthStatus
}
