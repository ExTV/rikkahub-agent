package me.rerere.ai.provider.providers.openai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.provider.providers.PartGroup
import me.rerere.ai.provider.providers.groupPartsByToolBoundary
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.OpenAIReasoningMetadata
import me.rerere.ai.ui.ReasoningType
import me.rerere.ai.ui.ServerToolMetadata
import me.rerere.ai.ui.ServerToolProtocol
import me.rerere.ai.ui.ServerToolStatus
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.metadataAs
import me.rerere.ai.ui.toMetadata
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.configureSessionHeaders
import me.rerere.ai.util.encodeBase64
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.parseErrorDetail
import me.rerere.ai.util.redactSecrets
import me.rerere.ai.util.stringSafe
import me.rerere.ai.util.toHeaders
import me.rerere.common.android.Logging
import me.rerere.common.http.await
import me.rerere.common.http.jsonObjectOrNull
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Clock

private const val TAG = "ResponseAPI"
private val USER_CANCELLATION_MARKERS = listOf(
    "canceled by user",
    "cancelled by user",
    "user_canceled",
    "user_cancelled",
)

private fun isCancellationFailure(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }
        .take(8)
        .any { cause ->
            cause is CancellationException ||
                USER_CANCELLATION_MARKERS.any { marker ->
                    cause.message?.contains(marker, ignoreCase = true) == true
                }
        }

/**
 * A transport failure from a Response API stream. The output marker protects callers from
 * replaying a request after text or a tool call has already reached the conversation.
 */
internal class ResponseStreamFailureException(
    val receivedMeaningfulOutput: Boolean,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * A provider-side Response API error delivered as an SSE event. These errors are already a
 * complete business response, so replaying the exact same request is both noisy and harmful
 * (a context-length error, for example, can never succeed without changing the input).
 */
class ResponseStreamErrorException(
    val code: String?,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

private fun JsonObject.responseErrorObject(): JsonObject? =
    this["error"]?.jsonObject
        ?: this["response"]?.jsonObject?.get("error")?.jsonObject

private fun JsonObject.responseErrorText(name: String): String? = runCatching {
    this[name]?.jsonPrimitive?.contentOrNull
        ?: this["response"]?.jsonObject?.get(name)?.jsonPrimitive?.contentOrNull
        ?: this.responseErrorObject()?.get(name)?.jsonPrimitive?.contentOrNull
}.getOrNull()?.takeIf { it.isNotBlank() }

/** Convert `error`/`response.failed` SSE payloads into a useful, non-retryable exception. */
internal fun parseResponseStreamError(
    payload: JsonObject,
    eventType: String?,
): ResponseStreamErrorException {
    val code = payload.responseErrorText("code")
    val message = runCatching {
        val error = payload["error"] ?: payload["response"]?.jsonObject?.get("error")
        error?.parseErrorDetail()?.message
    }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: payload.responseErrorText("message")
        ?: code
        ?: "unknown provider error"
    val eventLabel = eventType?.takeIf { it.isNotBlank() } ?: "error"
    val detail = buildString {
        append("Response API $eventLabel")
        if (code != null && !message.contains(code, ignoreCase = true)) {
            append(" [$code]")
        }
        append(": ")
        append(message)
    }
    return ResponseStreamErrorException(code = code, message = detail)
}

internal fun shouldRetryResponseStream(
    failure: Throwable,
    retryAttempt: Long,
    maxRetries: Int,
): Boolean {
    if (retryAttempt >= maxRetries.coerceAtLeast(0).toLong()) return false
    if (failure is ResponseStreamErrorException) return false
    // Some gateways return the context error as a non-2xx response body instead of an SSE
    // event. Preserve the error for ChatService's compaction path and never replay it blindly.
    if (generateSequence(failure) { it.cause }.take(8).any { cause ->
            val text = (cause.message.orEmpty() + " " + cause.toString())
                .lowercase()
                .replace('_', ' ')
            "context length exceeded" in text ||
                "context_length_exceeded" in text ||
                "maximum context length" in text
        }) {
        return false
    }
    if ((failure as? ResponseStreamFailureException)?.receivedMeaningfulOutput == true) {
        return false
    }
    // Response API can surface HTTP, decoding, callback, and other provider failures using
    // different exception types. Retry every failure except cancellation requested by the user
    // (or cancellation propagated from the parent coroutine).
    return !isCancellationFailure(failure)
}

// Same wording ChatCompletionsAPI uses when downgrading an image to text for a
// model without image input support; reused here for consistency.
private const val IMAGE_UNSUPPORTED_PLACEHOLDER =
    "[Image output omitted: current model does not support image input]"

class ResponseAPI(
    private val client: OkHttpClient,
    private val keyRoulette: KeyRoulette = KeyRoulette.default()
) : OpenAIImpl {
    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = withContext(Dispatchers.IO) {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = false,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.responsesPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
            )
            .addHeader("Content-Type", "application/json")
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeaders(providerSetting.baseUrl, params.sessionId)
            .build()

        if (Logging.isDebugLoggingEnabled()) {
            Log.i(TAG, "generateText: ${json.encodeToString(redactSecrets(requestBody))}")
        }

        // await() waits for the response headers; reading the body can still block.
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to get response: ${response.code} ${response.body.string()}")
            }

            val bodyStr = response.body.string()
            Log.i(TAG, "generateText: $bodyStr")
            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            parseResponseOutput(bodyJson)
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = callbackFlow {
        val requestBody = buildRequestBody(
            providerSetting = providerSetting,
            messages = messages,
            params = params,
            stream = true,
        )
        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.responsesPath}")
            .headers(params.customHeaders.toHeaders())
            .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))
            .addHeader(
                "Authorization",
                "Bearer ${keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())}"
            )
            .configureReferHeaders(providerSetting.baseUrl)
            .configureSessionHeaders(providerSetting.baseUrl, params.sessionId)
            .build()

        if (Logging.isDebugLoggingEnabled()) {
            Log.i(TAG, "streamText: ${json.encodeToString(redactSecrets(requestBody))}")
        }

        val completionReceived = AtomicBoolean(false)
        val canceledByCollector = AtomicBoolean(false)
        val receivedMeaningfulOutput = AtomicBoolean(false)

        val decoder = ResponseApiStreamDecoder()

        fun sendChunks(chunks: Iterable<StreamChunk>) {
            chunks.forEach { chunk ->
                trySend(chunk).onFailure { e ->
                    Log.w(TAG, "onEvent: chunk dropped (${e?.message})")
                }
            }
        }

        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                Log.d(TAG, "onEvent: $id/$type $data")
                if (data.trim() == "[DONE]") {
                    try {
                        sendChunks(decoder.accept(SseEvent(id = id, event = type, data = data)).chunks)
                    } catch (e: Throwable) {
                        Log.w(TAG, "onEvent: failed to finish decoder for [DONE]", e)
                    }
                    completionReceived.set(true)
                    close()
                    return
                }
                // A business error is already a complete response from the provider (context
                // length, moderation refusal, etc.) - classify it before handing the raw event
                // to the decoder so it surfaces as a non-retryable ResponseStreamErrorException
                // instead of a generic decode failure.
                val json = runCatching {
                    json.parseToJsonElement(data).jsonObject
                }.getOrElse { error ->
                    close(
                        ResponseStreamFailureException(
                            receivedMeaningfulOutput = receivedMeaningfulOutput.get(),
                            message = "Response stream contained invalid JSON: ${error.message.orEmpty()}",
                            cause = error,
                        )
                    )
                    return
                }
                val eventType = json["type"]?.jsonPrimitive?.contentOrNull ?: type
                if (eventType == "error" || eventType == "response.failed") {
                    close(parseResponseStreamError(json, eventType))
                    return
                }
                try {
                    val result = decoder.accept(SseEvent(id = id, event = type, data = data))
                    if (result.chunks.any { it !is StreamChunk.Usage && it !is StreamChunk.Finish }) {
                        receivedMeaningfulOutput.set(true)
                    }
                    sendChunks(result.chunks)
                    if (result.completed) {
                        completionReceived.set(true)
                        close()
                    }
                } catch (e: Throwable) {
                    close(
                        ResponseStreamFailureException(
                            receivedMeaningfulOutput = receivedMeaningfulOutput.get(),
                            message = "Failed to decode stream event: ${e.message.orEmpty()}",
                            cause = e,
                        )
                    )
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (canceledByCollector.get() || completionReceived.get()) return

                var exception: Throwable = t ?: IOException(
                    "Response stream failed${response?.code?.let { " (HTTP $it)" }.orEmpty()}"
                )

                Log.w(TAG, "onFailure: ${t?.javaClass?.name} ${t?.message} / $response", t)

                val bodyRaw = response?.body?.stringSafe()
                try {
                    if (!bodyRaw.isNullOrBlank()) {
                        val bodyElement = Json.parseToJsonElement(bodyRaw)
                        Log.d(TAG, "onFailure: error body $bodyElement")
                        val detail = bodyElement.parseErrorDetail()
                        val code = (bodyElement as? JsonObject)?.responseErrorText("code")
                        exception = if (code != null && !detail.message.orEmpty().contains(code, ignoreCase = true)) {
                            IOException("$code: ${detail.message.orEmpty()}", detail)
                        } else {
                            detail
                        }
                        Log.i(TAG, "onFailure: $exception")
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "onFailure: failed to parse from $bodyRaw", e)
                }
                if (receivedMeaningfulOutput.get()) {
                    exception = ResponseStreamFailureException(
                        receivedMeaningfulOutput = true,
                        message = "Response stream failed after partial output: ${exception.message.orEmpty()}",
                        cause = exception,
                    )
                }
                close(exception)
            }

            override fun onClosed(eventSource: EventSource) {
                if (canceledByCollector.get() || completionReceived.get()) {
                    sendChunks(decoder.onClosed())
                    close()
                    return
                }
                close(
                    ResponseStreamFailureException(
                        receivedMeaningfulOutput = receivedMeaningfulOutput.get(),
                        message = if (receivedMeaningfulOutput.get()) {
                            "Response stream closed before completion after partial output"
                        } else {
                            "Response stream closed unexpectedly before response.completed or [DONE]"
                        },
                    )
                )
            }
        }

        val eventSource = EventSources.createFactory(client)
            .newEventSource(request, listener)

        awaitClose {
            canceledByCollector.set(true)
            eventSource.cancel()
        }
        // trySend 在缓冲满时会静默丢弃 delta，导致回复中间缺字 (#1295)，因此缓冲必须无界
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    fun createRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject = buildRequestBody(providerSetting, messages, params, stream)

    internal fun buildRequestBody(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams,
        stream: Boolean
    ): JsonObject {
        val host = providerSetting.baseUrl.toHttpUrl().host
        val capabilities = resolveResponseProviderCapabilities(host)
        return buildJsonObject {
            put("model", params.model.modelId)
            put("stream", stream)
            put("store", false)

            if (isModelAllowTemperature(params.model)) {
                if (params.temperature != null) put("temperature", params.temperature)
                if (params.topP != null) put("top_p", params.topP)
            }
            if (params.maxTokens != null) put("max_output_tokens", params.maxTokens)

            // system instructions
            if (messages.any { it.role == MessageRole.SYSTEM }) {
                val parts = messages.first { it.role == MessageRole.SYSTEM }.parts
                put(
                    "instructions",
                    parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text })
            }

            // messages
            put("input", buildMessages(messages, supportInputModalities = params.model.inputModalities))

            // reasoning
            if (params.model.abilities.contains(ModelAbility.REASONING)) {
                val level = params.reasoningLevel
                put("reasoning", buildJsonObject {
                    if (capabilities.supportsReasoningSummary) {
                        put("summary", "auto")
                    }
                    if (level != ReasoningLevel.AUTO) {
                        put("effort", level.effort)
                    }
                })
                if (capabilities.supportEncryptedContent) {
                    put("include", buildJsonArray {
                        add("reasoning.encrypted_content")
                    })
                }
            }

            // tools
            // Response API 的 tools 是扁平数组, 函数工具和内置工具可以共存, 必须写在同一个 key 下,
            // 否则后写入的会覆盖前者
            val useFunctionTools =
                params.model.abilities.contains(ModelAbility.TOOL) && params.tools.isNotEmpty()
            if (useFunctionTools || params.model.tools.isNotEmpty()) {
                putJsonArray("tools") {
                    if (useFunctionTools) {
                        params.tools.forEach { tool ->
                            add(buildJsonObject {
                                put("type", "function")
                                put("name", tool.name)
                                put("description", tool.description)
                                put(
                                    "parameters",
                                    json.encodeToJsonElement(
                                        tool.parameters()
                                    )
                                )
                            })
                        }
                    }
                    // built-in tools
                    params.model.tools.forEach { builtInTool ->
                        when (builtInTool) {
                            BuiltInTools.Search -> {
                                add(buildJsonObject {
                                    put("type", "web_search")
                                })
                            }

                            BuiltInTools.UrlContext -> {} // not supported

                            BuiltInTools.ImageGeneration -> {
                                add(buildJsonObject {
                                    put("type", "image_generation")
                                    put("model", "gpt-image-2")
                                })
                            }
                        }
                    }
                }
            }
        }.mergeCustomBody(params.customBody)
    }

    fun buildMessages(
        messages: List<UIMessage>,
        supportInputModalities: List<Modality> = listOf(Modality.TEXT, Modality.IMAGE),
    ) = buildJsonArray {
        messages
            .filter { message ->
                message.role != MessageRole.SYSTEM && (
                    message.isValidToUpload() || message.parts.any { part ->
                        part is UIMessagePart.Reasoning &&
                            part.metadataAs<OpenAIReasoningMetadata>()?.encryptedContent != null
                    }
                )
            }
            .forEach { message ->
                if (message.role == MessageRole.ASSISTANT) {
                    addAssistantItems(message, supportInputModalities)
                } else {
                    addUserItems(message, supportInputModalities)
                }
            }
    }

    private fun JsonArrayBuilder.addAssistantItems(message: UIMessage, supportInputModalities: List<Modality>) {
        val groups = groupPartsByToolBoundary(message.parts)
        val contentBuffer = mutableListOf<UIMessagePart>()

        for (group in groups) {
            when (group) {
                is PartGroup.Content -> {
                    val emittedReasoningIds = mutableSetOf<String>()
                    group.parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Reasoning -> {
                                val reasoningMetadata = part.metadataAs<OpenAIReasoningMetadata>()
                                val reasoningId = reasoningMetadata?.reasoningId
                                if (reasoningId != null && !emittedReasoningIds.add(reasoningId)) {
                                    return@forEach
                                }
                                // 先输出累积的文本/图片内容
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer, supportInputModalities)
                                    contentBuffer.clear()
                                }
                                // 输出 reasoning item
                                val reasoningParts = if (reasoningId == null) {
                                    listOf(part)
                                } else {
                                    group.parts.filterIsInstance<UIMessagePart.Reasoning>().filter {
                                        it.metadataAs<OpenAIReasoningMetadata>()?.reasoningId == reasoningId
                                    }
                                }
                                add(buildJsonObject {
                                    put("type", "reasoning")
                                    reasoningId?.let { put("id", it) }
                                    put("summary", buildJsonArray {
                                        reasoningParts
                                            .filter { it.reasoningType == ReasoningType.SUMMARY_TEXT }
                                            .filter { it.reasoning.isNotEmpty() }
                                            .forEach {
                                                add(buildJsonObject {
                                                    put("type", "summary_text")
                                                    put("text", it.reasoning)
                                                })
                                            }
                                    })
                                    val encryptedContent = reasoningMetadata?.encryptedContent
                                    val content = reasoningParts
                                        .filter { it.reasoningType == ReasoningType.REASONING_TEXT }
                                        .filter { it.reasoning.isNotEmpty() }
                                    if (encryptedContent == null && content.isNotEmpty()) {
                                        put("content", buildJsonArray {
                                            content.forEach {
                                                add(buildJsonObject {
                                                    put("type", "reasoning_text")
                                                    put("text", it.reasoning)
                                                })
                                            }
                                        })
                                    }
                                    encryptedContent?.let {
                                        put("encrypted_content", it)
                                    }
                                })
                            }

                            is UIMessagePart.Image -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer, supportInputModalities)
                                    contentBuffer.clear()
                                }
                                addContentItem(MessageRole.USER, listOf(part), supportInputModalities)
                            }

                            is UIMessagePart.Text -> {
                                contentBuffer.add(part)
                            }

                            is UIMessagePart.ServerTool -> {
                                if (contentBuffer.isNotEmpty()) {
                                    addContentItem(MessageRole.ASSISTANT, contentBuffer, supportInputModalities)
                                    contentBuffer.clear()
                                }
                                addServerToolItem(part)
                            }

                            else -> {}
                        }
                    }
                }

                is PartGroup.Tools -> {
                    // 先输出累积的内容
                    if (contentBuffer.isNotEmpty()) {
                        addContentItem(MessageRole.ASSISTANT, contentBuffer, supportInputModalities)
                        contentBuffer.clear()
                    }

                    // 同一批并发工具调用需先输出全部 function_call，再输出对应结果。
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call")
                            put("call_id", tool.toolCallId)
                            put("name", tool.toolName)
                            // 使用 inputAsJson() 归一化，避免流式中断导致的残缺 JSON 被发送
                            put("arguments", tool.inputAsJson().toString())
                        })
                    }
                    group.tools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", tool.toolCallId)
                            val hasImage = tool.output.any { it is UIMessagePart.Image }
                            val supportsImageInput = Modality.IMAGE in supportInputModalities
                            if (hasImage && supportsImageInput) {
                                putJsonArray("output") {
                                    tool.output.forEach { part ->
                                        when (part) {
                                            is UIMessagePart.Image -> add(buildJsonObject {
                                                part.encodeBase64().onSuccess { encoded ->
                                                    put("type", "input_image")
                                                    put("image_url", encoded.base64)
                                                }.onFailure {
                                                    it.printStackTrace()
                                                    put("type", "input_text")
                                                    put("text", "Error: Failed to encode image to base64")
                                                }
                                            })
                                            is UIMessagePart.Text -> add(buildJsonObject {
                                                put("type", "input_text")
                                                put("text", part.text)
                                            })
                                            else -> {}
                                        }
                                    }
                                }
                            } else if (hasImage) {
                                // Text-only model: fold the image(s) into the placeholder
                                // text, mirroring ChatCompletionsAPI's toToolResultContent,
                                // so the model still sees that a tool produced an image
                                // without leaking image data it can't accept.
                                put(
                                    "output",
                                    tool.output.mapNotNull { part ->
                                        when (part) {
                                            is UIMessagePart.Text -> part.text
                                            is UIMessagePart.Image -> IMAGE_UNSUPPORTED_PLACEHOLDER
                                            else -> null
                                        }
                                    }.joinToString("\n")
                                )
                            } else {
                                put(
                                    "output",
                                    tool.output.filterIsInstance<UIMessagePart.Text>()
                                        .joinToString("\n") { it.text }
                                )
                            }
                        })
                        // Image lift: function_call_output is text-only, so a tool that
                        // returns UIMessagePart.Image (take_screenshot, take_photo, etc.)
                        // would otherwise be invisible to vision-capable models. Inject
                        // those images as a follow-up user content item. Skipped for
                        // text-only models: the function_call_output above already emits
                        // the placeholder text for those images, so nothing is lost.
                        val toolImages = if (Modality.IMAGE in supportInputModalities) {
                            tool.output.filterIsInstance<UIMessagePart.Image>()
                        } else {
                            emptyList()
                        }
                        if (toolImages.isNotEmpty()) {
                            addContentItem(
                                MessageRole.USER,
                                buildList {
                                    add(UIMessagePart.Text("[Tool ${tool.toolName} produced the image(s) below.]"))
                                    addAll(toolImages)
                                },
                                supportInputModalities
                            )
                        }
                    }
                }
            }
        }

        // 输出剩余内容
        if (contentBuffer.isNotEmpty()) {
            addContentItem(MessageRole.ASSISTANT, contentBuffer, supportInputModalities)
        }
    }

    private fun JsonArrayBuilder.addServerToolItem(tool: UIMessagePart.ServerTool) {
        val metadata = tool.metadataAs<ServerToolMetadata>()
        val protocol = metadata?.protocol
        if (protocol != null && protocol != ServerToolProtocol.OPENAI_RESPONSES) return

        val rawCall = metadata?.call.takeIf { protocol == ServerToolProtocol.OPENAI_RESPONSES }
        if (rawCall != null) {
            add(rawCall)
            return
        }

        add(buildJsonObject {
            put("type", "${tool.toolName.removeSuffix("_call")}_call")
            put("id", tool.toolCallId)
            put("status", tool.status.toOpenAIStatus())
            tool.input?.let { input ->
                if (tool.toolName.removeSuffix("_call") == "web_search") put("action", input)
                else if (input is JsonObject) input.forEach { (key, value) -> put(key, value) }
                else put("input", input)
            }
            tool.output?.let { put("output", it) }
        })
    }

    private fun JsonArrayBuilder.addUserItems(message: UIMessage, supportInputModalities: List<Modality>) {
        val contentParts = message.parts.filter { it is UIMessagePart.Text || it is UIMessagePart.Image }
        if (contentParts.isNotEmpty()) {
            addContentItem(message.role, contentParts, supportInputModalities)
        }
    }

    private fun JsonArrayBuilder.addContentItem(
        role: MessageRole,
        parts: List<UIMessagePart>,
        supportInputModalities: List<Modality>,
    ) {
        if (parts.isEmpty()) return

        add(buildJsonObject {
            put("role", JsonPrimitive(role.name.lowercase()))

            if (parts.isOnlyTextPart()) {
                put("content", (parts.first() as UIMessagePart.Text).text)
            } else {
                putJsonArray("content") {
                    parts.forEach { part ->
                        when (part) {
                            is UIMessagePart.Text -> {
                                add(buildJsonObject {
                                    put("type", if (role == MessageRole.USER) "input_text" else "output_text")
                                    put("text", part.text)
                                })
                            }

                            is UIMessagePart.Image -> {
                                add(buildJsonObject {
                                    if (Modality.IMAGE !in supportInputModalities) {
                                        put("type", "input_text")
                                        put("text", IMAGE_UNSUPPORTED_PLACEHOLDER)
                                    } else {
                                        part.encodeBase64().onSuccess { encodedImage ->
                                            put("type", "input_image")
                                            put("image_url", encodedImage.base64)
                                        }.onFailure {
                                            Log.w(TAG, "failed to encode image to base64", it)
                                            put("type", "input_text")
                                            put("text", "Error: Failed to encode image to base64")
                                        }
                                    }
                                })
                            }

                            else -> {}
                        }
                    }
                }
            }
        })
    }

    internal fun parseResponseOutput(jsonObject: JsonObject): TextGenerationResult {
        println(jsonObject)
        val outputs = jsonObject["output"]?.jsonArray ?: error("output not found")
        val parts = arrayListOf<UIMessagePart>()

        outputs.forEach { outputItem ->
            val output = outputItem.jsonObject
            val type = output["type"]?.jsonPrimitive?.content ?: error("output type not found")
            when (type) {
                "reasoning" -> {
                    val reasoningMetadata = OpenAIReasoningMetadata(
                        reasoningId = output["id"]?.jsonPrimitive?.contentOrNull,
                        encryptedContent = output["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                    ).toMetadata()
                    val reasoningPartStart = parts.size
                    output["summary"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "summary_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Reasoning(
                                        reasoning = text,
                                        createdAt = Clock.System.now(),
                                        finishedAt = Clock.System.now(),
                                        metadata = reasoningMetadata,
                                        reasoningType = ReasoningType.SUMMARY_TEXT,
                                    )
                                )
                            }
                        }
                    }
                    output["content"]?.jsonArray.orEmpty().map { it.jsonObject }.forEach { part ->
                        if (part["type"]?.jsonPrimitive?.contentOrNull == "reasoning_text") {
                            parts.add(
                                UIMessagePart.Reasoning(
                                    reasoning = part["text"]?.jsonPrimitive?.content ?: error("text not found"),
                                    createdAt = Clock.System.now(),
                                    finishedAt = Clock.System.now(),
                                    metadata = reasoningMetadata,
                                    reasoningType = ReasoningType.REASONING_TEXT,
                                )
                            )
                        }
                    }
                    if (parts.size == reasoningPartStart) {
                        parts.add(
                            UIMessagePart.Reasoning(
                                reasoning = "",
                                createdAt = Clock.System.now(),
                                finishedAt = Clock.System.now(),
                                metadata = reasoningMetadata,
                                reasoningType = ReasoningType.REASONING_TEXT,
                            )
                        )
                    }
                }

                "function_call" -> {
                    val callId = output["call_id"]?.jsonPrimitive?.content ?: error("call_id not found")
                    val name = output["name"]?.jsonPrimitive?.content ?: error("name not found")
                    val arguments =
                        output["arguments"]?.jsonPrimitive?.content ?: error("arguments not found")
                    parts.add(
                        UIMessagePart.Tool(
                            toolCallId = callId,
                            toolName = name,
                            input = arguments,
                            output = emptyList()
                        )
                    )
                }

                "message" -> {
                    val content = output["content"]?.jsonArray ?: error("content not found")
                    content.map { it.jsonObject }.forEach { part ->
                        val partType = part["type"]?.jsonPrimitive?.content ?: error("part type not found")
                        when (partType) {
                            "output_text" -> {
                                val text = part["text"]?.jsonPrimitive?.content ?: error("text not found")
                                parts.add(
                                    UIMessagePart.Text(
                                        text = text
                                    )
                                )
                            }

                            else -> error("unknown part type $partType")
                        }
                    }
                }

                else -> if (isOpenAIServerToolCall(type)) {
                    parts.add(output.toOpenAIServerTool())
                }
            }
        }

        return TextGenerationResult(
            id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "",
            model = jsonObject["model"]?.jsonPrimitive?.contentOrNull ?: "",
            message = UIMessage(
                role = MessageRole.ASSISTANT,
                parts = parts,
            ),
            finishReason = jsonObject["status"]?.jsonPrimitive?.contentOrNull,
            usage = parseTokenUsage(jsonObject["usage"]?.jsonObject)
        )
    }

    private fun parseTokenUsage(jsonObject: JsonObject?): TokenUsage? {
        if (jsonObject == null) return null
        return TokenUsage(
            promptTokens = jsonObject["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            completionTokens = jsonObject["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            totalTokens = jsonObject["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            cachedTokens = jsonObject["input_tokens_details"]?.jsonObjectOrNull?.get("cached_tokens")?.jsonPrimitive?.intOrNull
                ?: 0
        )
    }
}

internal fun isOpenAIServerToolCall(type: String): Boolean =
    type.endsWith("_call") && type !in setOf(
        "function_call",
        "custom_tool_call",
        "computer_call",
        "local_shell_call",
        "shell_call",
        "image_generation_call",
    )

internal fun JsonObject.toOpenAIServerTool(): UIMessagePart.ServerTool {
    val type = get("type")?.jsonPrimitive?.contentOrNull ?: "server_tool_call"
    val protocolFields = setOf("type", "id", "status", "result", "output")
    val input = get("action") ?: JsonObject(filterKeys { it !in protocolFields })
        .takeUnless { it.isEmpty() }
    return UIMessagePart.ServerTool(
        toolCallId = get("id")?.jsonPrimitive?.contentOrNull ?: "",
        toolName = type.removeSuffix("_call"),
        input = input,
        output = get("output") ?: get("result"),
        status = get("status")?.jsonPrimitive?.contentOrNull.toServerToolStatus(),
        metadata = ServerToolMetadata(
            protocol = ServerToolProtocol.OPENAI_RESPONSES,
            call = this,
        ).toMetadata(),
    )
}

internal fun String?.toServerToolStatus(): ServerToolStatus = when (this) {
    "completed" -> ServerToolStatus.COMPLETED
    "failed", "incomplete", "cancelled" -> ServerToolStatus.FAILED
    else -> ServerToolStatus.IN_PROGRESS
}

private fun ServerToolStatus.toOpenAIStatus(): String = when (this) {
    ServerToolStatus.IN_PROGRESS -> "in_progress"
    ServerToolStatus.COMPLETED -> "completed"
    ServerToolStatus.FAILED -> "failed"
}

private fun isModelAllowTemperature(model: Model): Boolean {
    return !ModelRegistry.OPENAI_O_MODELS.match(model.modelId) && !ModelRegistry.GPT_5.match(model.modelId)
}

private fun List<UIMessagePart>.isOnlyTextPart(): Boolean {
    val gonnaSend = filter { it is UIMessagePart.Text || it is UIMessagePart.Image }.size
    val texts = filter { it is UIMessagePart.Text }.size
    return gonnaSend == texts && texts == 1
}

internal data class ResponseProviderCapabilities(
    val supportsReasoningSummary: Boolean = true,
    val supportEncryptedContent: Boolean = true
)

internal fun resolveResponseProviderCapabilities(host: String): ResponseProviderCapabilities {
    return when (host) {
        "ark.cn-beijing.volces.com" -> ResponseProviderCapabilities(
            supportsReasoningSummary = false,
            supportEncryptedContent = false
        )

        else -> ResponseProviderCapabilities()
    }
}
