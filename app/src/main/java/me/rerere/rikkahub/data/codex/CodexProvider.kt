package me.rerere.rikkahub.data.codex

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.common.http.await
import me.rerere.rikkahub.AppScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody.Companion.asResponseBody

class CodexProvider(
    private val client: OkHttpClient,
    private val repository: CodexAccountRepository,
    private val json: Json,
    private val scope: AppScope,
) : Provider<ProviderSetting.Codex> {

    override suspend fun listModels(providerSetting: ProviderSetting.Codex): List<Model> =
        withContext(Dispatchers.IO) {
            val account = repository.acquireAccount()
            val request = Request.Builder()
                .url("$CODEX_API_BASE/models?client_version=$CLIENT_VERSION")
                .codexHeaders(account)
                .get()
                .build()
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                if (response.code == 401) repository.markInvalid(account.id)
                error("Failed to get Codex models: ${response.code} ${response.body.string()}")
            }
            val models = (json.parseToJsonElement(response.body.string()) as? JsonObject)
                ?.get("models") as? JsonArray
                ?: return@withContext emptyList()
            models.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                if (item["visibility"]?.jsonPrimitive?.contentOrNull != "list") {
                    return@mapNotNull null
                }
                val slug = item["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val modalities = item["input_modalities"]?.jsonArray
                    ?.mapNotNull { modality ->
                        when ((modality as? JsonPrimitive)?.contentOrNull) {
                            "text" -> Modality.TEXT
                            "image" -> Modality.IMAGE
                            else -> null
                        }
                    }
                    ?.ifEmpty { listOf(Modality.TEXT) }
                    ?: listOf(Modality.TEXT, Modality.IMAGE)
                Model(
                    modelId = slug,
                    displayName = item["display_name"]?.jsonPrimitive?.contentOrNull ?: slug,
                    inputModalities = modalities,
                    abilities = buildList {
                        add(ModelAbility.TOOL)
                        if (
                            item["supported_reasoning_levels"]?.jsonArray?.isNotEmpty() == true ||
                            item["supports_reasoning_summaries"]?.jsonPrimitive?.booleanOrNull == true
                        ) {
                            add(ModelAbility.REASONING)
                        }
                    },
                )
            }
        }

    override suspend fun generateText(
        providerSetting: ProviderSetting.Codex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val account = repository.acquireAccount()
        return responseApiFor(account).generateText(
            providerSetting = syntheticSetting(providerSetting, account),
            messages = withDefaultInstructions(messages),
            params = withCodexParams(params, account, stream = false),
        )
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.Codex,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        val account = repository.acquireAccount()
        return responseApiFor(account).streamText(
            providerSetting = syntheticSetting(providerSetting, account),
            messages = withDefaultInstructions(messages),
            params = withCodexParams(params, account, stream = true),
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams,
    ): Flow<ImageGenerationItem> {
        error("Image generation is not supported by the Codex provider")
    }

    private fun Request.Builder.codexHeaders(account: CodexAccount): Request.Builder {
        return header("Authorization", "Bearer ${account.accessToken}")
            .header("ChatGPT-Account-Id", account.chatgptAccountId)
            .header("OpenAI-Beta", "responses=experimental")
            .header("originator", "codex_cli_rs")
            .header("User-Agent", CODEX_USER_AGENT)
    }

    // apiKey = the account's own OAuth token, so ResponseAPI's normal "Authorization: Bearer
    // <apiKey>" header lands on exactly the token codexHeaders used to set by hand.
    private fun syntheticSetting(providerSetting: ProviderSetting.Codex, account: CodexAccount) =
        ProviderSetting.OpenAI(
            id = providerSetting.id,
            enabled = providerSetting.enabled,
            name = providerSetting.name,
            models = providerSetting.models,
            baseUrl = CODEX_API_BASE,
            apiKey = account.accessToken,
            useResponseApi = true,
        )

    // The Codex backend needs a system/instructions item to behave; fall back to a generic one
    // when the caller didn't supply a system message, same as the request body used to do by
    // hand via the `instructions` field.
    private fun withDefaultInstructions(messages: List<UIMessage>): List<UIMessage> =
        if (messages.any { it.role == MessageRole.SYSTEM }) {
            messages
        } else {
            listOf(UIMessage.system(DEFAULT_INSTRUCTIONS)) + messages
        }

    private fun withCodexParams(
        params: TextGenerationParams,
        account: CodexAccount,
        stream: Boolean,
    ): TextGenerationParams {
        val reasoningEffort = params.model.abilities
            .takeIf { it.contains(ModelAbility.REASONING) }
            ?.let { codexReasoningEffort(params.reasoningLevel) }
        return params.copy(
            customHeaders = params.customHeaders + buildList {
                add(CustomHeader("ChatGPT-Account-Id", account.chatgptAccountId))
                add(CustomHeader("OpenAI-Beta", "responses=experimental"))
                add(CustomHeader("originator", "codex_cli_rs"))
                add(CustomHeader("User-Agent", CODEX_USER_AGENT))
                if (stream) add(CustomHeader("Accept", "text/event-stream"))
            },
            customBody = params.customBody + listOfNotNull(
                reasoningEffort?.let { effort ->
                    CustomBody(
                        key = "reasoning",
                        value = buildJsonObject {
                            put("effort", effort)
                            put("summary", "auto")
                        },
                    )
                },
            ),
        )
    }

    /**
     * Wraps [client] with an account-scoped interceptor so the same response that carries the
     * model reply also carries the account's rate-limit headers (quota tracking) and a 401
     * (invalidated token) signal, without a second network round-trip. Also patches a missing
     * Content-Type so OkHttp's SSE factory recognizes the stream - some Codex backend responses
     * omit it.
     */
    private fun responseApiFor(account: CodexAccount): ResponseAPI {
        val accountAwareClient = client.newBuilder()
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                parseCodexUsage(response.headers)?.let { usage ->
                    scope.launch { repository.updateUsage(account.id, usage) }
                }
                if (response.code == 401) {
                    scope.launch { repository.markInvalid(account.id) }
                }
                if (response.isSuccessful && response.header("Content-Type") == null) {
                    val body = response.body
                    response.newBuilder()
                        .header("Content-Type", "text/event-stream")
                        .body(
                            body.source().asResponseBody(
                                contentType = "text/event-stream".toMediaType(),
                                contentLength = body.contentLength(),
                            )
                        )
                        .build()
                } else {
                    response
                }
            }
            .build()
        return ResponseAPI(accountAwareClient)
    }

    private companion object {
        const val CODEX_API_BASE = "${CodexAccountRepository.CODEX_BASE_URL}/codex"
        const val CLIENT_VERSION = "0.144.5"

        // The Codex backend routes newer models (e.g. gpt-5.6-luna, which is gated on
        // minimal_client_version 0.144.0) by the codex version advertised in the User-Agent, not
        // just the `client_version` query param on /models. Without a codex-shaped UA the backend
        // resolves the public slug to an unavailable internal engine and returns 404 "Model not
        // found". Mirror the codex CLI's UA format: "<originator>/<version> (<os>; <arch>)".
        val CODEX_USER_AGENT =
            "codex_cli_rs/$CLIENT_VERSION (Android ${Build.VERSION.RELEASE}; " +
                "${Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64"})"
        const val DEFAULT_INSTRUCTIONS = "You are a helpful assistant."
    }
}

internal fun codexReasoningEffort(level: ReasoningLevel): String? {
    return when (level) {
        ReasoningLevel.AUTO -> null
        ReasoningLevel.LOW -> "low"
        ReasoningLevel.MEDIUM -> "medium"
        ReasoningLevel.HIGH -> "high"
        ReasoningLevel.XHIGH -> "xhigh"
        // Codex's API tops out at "xhigh"; mirror XHIGH rather than sending an effort it doesn't know.
        ReasoningLevel.MAX -> "xhigh"
        ReasoningLevel.OFF -> "none"
    }
}

internal fun parseCodexIncompleteMessage(payload: JsonObject): String {
    val reason = runCatching {
        payload["response"]?.jsonObject
            ?.get("incomplete_details")?.jsonObject
            ?.get("reason")?.jsonPrimitive?.contentOrNull
            ?: payload["incomplete_details"]?.jsonObject
                ?.get("reason")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return if (reason.isNullOrBlank()) {
        "Codex response incomplete"
    } else {
        "Codex response incomplete: $reason"
    }
}
