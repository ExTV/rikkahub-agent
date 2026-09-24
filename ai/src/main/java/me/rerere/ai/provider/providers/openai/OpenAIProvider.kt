package me.rerere.ai.provider.providers.openai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import me.rerere.ai.provider.EmbeddingGenerationParams
import me.rerere.ai.provider.EmbeddingGenerationResult
import me.rerere.ai.provider.ImageEditParams
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureReferHeaders
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val TAG = "OpenAIProvider"

// Same shape as ClaudeProvider.MINIMAX_FALLBACK_MODELS — see comment there for
// why a fallback is needed (Minimax's /v1/models returns `{"object":"","data":null}`
// even with a valid key, despite their published OpenAPI spec). Duplicated rather
// than shared because both providers are otherwise self-contained and a util
// module just for this would be overkill.
private val MINIMAX_FALLBACK_MODELS = listOf(
    "MiniMax-M2.7",
    "MiniMax-M2.7-highspeed",
    "MiniMax-M2.5",
    "MiniMax-M2.5-highspeed",
    "MiniMax-M2.1",
    "MiniMax-M2.1-highspeed",
    "MiniMax-M2",
).map { Model(modelId = it, displayName = it) }

class OpenAIProvider(
    private val client: OkHttpClient,
    context: Context? = null
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = if (context != null) KeyRoulette.lru(context) else KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
            // OpenRouter's /models returns text-output models only by default, which hides the
            // image-only models (FLUX, Recraft, Seedream, ...). Ask for text and image explicitly.
            val modelsUrl = if (providerSetting.baseUrl.contains("openrouter.ai")) {
                "${providerSetting.baseUrl}/models?output_modalities=text,image"
            } else {
                "${providerSetting.baseUrl}/models"
            }
            val request = Request.Builder()
                .url(modelsUrl)
                .addHeader("Authorization", "Bearer $key")
                .get()
                .build()

            val response = client.newCall(request).await()
            val bodyStr = response.body.string()
            if (!response.isSuccessful) {
                error("Failed to get models: ${response.code} $bodyStr")
            }

            val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
            // `as? JsonArray` handles both an absent `data` key (Kotlin null)
            // and a JSON `null` value (`JsonNull`, which is a non-null Kotlin
            // object that throws if you call `.jsonArray` on it).
            val data = bodyJson["data"] as? JsonArray
            if (data == null) {
                // Some providers (Minimax, etc.) return HTTP 200 with an error
                // envelope instead of a 4xx. Surface the error so the user sees
                // why the model list is empty rather than a silent blank sheet.
                val baseResp = bodyJson["base_resp"] as? JsonObject
                val statusCode = baseResp?.get("status_code")?.jsonPrimitive?.intOrNull
                if (statusCode != null && statusCode != 0) {
                    val msg = baseResp["status_msg"]?.jsonPrimitive?.contentOrNull
                    error("Failed to get models: ${msg ?: "status_code=$statusCode"}")
                }
                val errMsg = (bodyJson["error"] as? JsonObject)?.get("message")
                    ?.jsonPrimitive?.contentOrNull
                if (errMsg != null) {
                    error("Failed to get models: $errMsg")
                }
                if (providerSetting.baseUrl.contains("api.minimax.io", ignoreCase = true)) {
                    return@withContext MINIMAX_FALLBACK_MODELS
                }
                error("Failed to get models: response has no `data` field")
            }

            val isOpenRouter = providerSetting.baseUrl.contains("openrouter.ai", ignoreCase = true)
            data.mapNotNull { modelJson ->
                val modelObj = modelJson.jsonObject
                if (isOpenRouter) {
                    // Rich capability + pricing detection from OpenRouter's catalog.
                    openRouterModelFromJson(modelObj)
                } else {
                    val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    Model(
                        modelId = id,
                        displayName = id,
                    )
                }
            }
        }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<StreamChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): TextGenerationResult = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        params: EmbeddingGenerationParams
    ): EmbeddingGenerationResult = withContext(Dispatchers.IO) {
        require(params.input.isNotEmpty()) { "Embedding input cannot be empty" }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())
        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                if (params.input.size == 1) {
                    put("input", params.input.first())
                } else {
                    putJsonArray("input") {
                        params.input.forEach { add(JsonPrimitive(it)) }
                    }
                }
                params.dimensions?.let { put("dimensions", it) }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate embedding: ${response.code} ${response.body.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")
        val model = bodyJson["model"]?.jsonPrimitive?.contentOrNull ?: params.model.modelId

        val embeddings = data.map { embeddingJson ->
            val embeddingArray = embeddingJson.jsonObject["embedding"]?.jsonArray
                ?: error("No embedding in response")
            embeddingArray.map { it.jsonPrimitive.content.toFloat() }
        }

        EmbeddingGenerationResult(
            model = model,
            embeddings = embeddings
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        // OpenRouter has a dedicated Images API for image-only models (FLUX.2, Recraft,
        // Seedream, gpt-image, Qwen-Image, ...) that never speak /chat/completions with
        // modalities:["image","text"]. Fall back to that older chat-completions path only
        // when /images itself 404s/405s (an OpenRouter-compatible proxy without it) - decided
        // once, on the first attempt, and reused for the rest. Neither path takes `n` (see
        // buildOpenRouterImagesRequestBody), so count > 1 is emulated with sequential calls.
        if (providerSetting.baseUrl.contains("openrouter.ai", ignoreCase = true)) {
            var useChatCompletionsFallback = false
            val items = withContext(Dispatchers.IO) {
                collectSequentialImages(params.numOfImages.coerceAtLeast(1)) {
                    if (useChatCompletionsFallback) {
                        generateImageViaChatCompletions(providerSetting, params, key)
                    } else {
                        generateImageViaOpenRouterImages(providerSetting, params, key) ?: run {
                            useChatCompletionsFallback = true
                            generateImageViaChatCompletions(providerSetting, params, key)
                        }
                    }
                }
            }
            items.forEach { emit(it) }
            return@flow
        }

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                put("n", params.numOfImages)
                // xAI rejects the OpenAI `size` parameter with a 400 (upstream issue #1602),
                // so leave it off for Grok and let the endpoint pick its own dimensions.
                val isGrok = providerSetting.baseUrl.contains("x.ai", ignoreCase = true) ||
                    params.model.modelId.contains("grok", ignoreCase = true)
                if (!isGrok) {
                    put(
                        "size", when (params.aspectRatio) {
                            ImageAspectRatio.SQUARE -> "1024x1024"
                            ImageAspectRatio.LANDSCAPE -> "1536x1024"
                            ImageAspectRatio.PORTRAIT -> "1024x1536"
                        }
                    )
                }
            }
                .mergeCustomBody(params.customBody)
        )

        Log.i(TAG, "generateImage: $requestBody")

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to generate image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    /**
     * OpenRouter image generation via the dedicated `POST {baseUrl}/images` endpoint. Returns
     * null (rather than throwing) when the endpoint itself looks missing (405, or a 404 whose
     * body isn't OpenRouter's JSON error shape - e.g. an HTML/empty body from a proxy that
     * never added it), so the caller can fall back to the older `/chat/completions` image
     * path. A per-model 404 ("model not found", a real JSON error body) is surfaced instead.
     */
    private suspend fun generateImageViaOpenRouterImages(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
        key: String,
        inputReferences: List<String> = emptyList(),
    ): List<ImageGenerationItem>? {
        val body = buildOpenRouterImagesRequestBody(
            model = params.model,
            prompt = params.prompt,
            aspectRatio = params.aspectRatio,
            inputReferences = inputReferences,
        ).mergeCustomBody(params.customBody)

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val response = client.newCall(request).await()
        val bodyStr = response.body.string()
        if (shouldFallbackToChatCompletionsImage(response.code, bodyStr)) return null
        if (!response.isSuccessful) {
            error(extractOpenRouterErrorMessage(response.code, bodyStr))
        }
        return parseOpenRouterImagesResponse(bodyStr)
    }

    /** OpenRouter image generation via /chat/completions with modalities:["image","text"]. */
    private suspend fun generateImageViaChatCompletions(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageGenerationParams,
        key: String,
    ): List<ImageGenerationItem> {
        val body = buildOpenRouterChatCompletionsImageBody(
            modelId = params.model.modelId,
            prompt = params.prompt,
            aspectRatio = params.aspectRatio,
        ).mergeCustomBody(params.customBody)

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val response = client.newCall(request).await()
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            error("Failed to generate image: ${response.code} $bodyStr")
        }
        return parseOpenRouterChatCompletionsImageResponse(bodyStr)
    }

    /** OpenRouter image edit via /chat/completions with input images as image_url content parts. */
    private suspend fun editImageViaChatCompletions(
        providerSetting: ProviderSetting.OpenAI,
        params: ImageEditParams,
        key: String,
        inputReferences: List<String>,
    ): List<ImageGenerationItem> {
        val body = buildOpenRouterChatCompletionsImageBody(
            modelId = params.model.modelId,
            prompt = params.prompt,
            aspectRatio = params.aspectRatio,
            inputReferences = inputReferences,
        ).mergeCustomBody(params.customBody)

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}${providerSetting.chatCompletionsPath}")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(body).toRequestBody("application/json".toMediaType()))
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val response = client.newCall(request).await()
        val bodyStr = response.body.string()
        if (!response.isSuccessful) {
            error("Failed to edit image: ${response.code} $bodyStr")
        }
        return parseOpenRouterChatCompletionsImageResponse(bodyStr)
    }

    override suspend fun editImage(
        providerSetting: ProviderSetting,
        params: ImageEditParams
    ): Flow<ImageGenerationItem> = flow {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }
        require(params.images.isNotEmpty()) {
            "At least one image is required"
        }

        val key = keyRoulette.next(providerSetting.apiKey, providerSetting.id.toString())

        // Same dedicated-endpoint-first, chat-completions-fallback rule as generateImage
        // (decided once, on the first attempt), with the source images always sent as
        // input_references (data URIs) - their presence is the edit request itself, not a
        // capability to gate on. `n` is never sent; count > 1 is sequential calls.
        if (providerSetting.baseUrl.contains("openrouter.ai", ignoreCase = true)) {
            val inputReferences = withContext(Dispatchers.IO) {
                params.images.map { path ->
                    val file = File(path)
                    require(file.exists()) { "Image file does not exist: $path" }
                    file.toDataUri()
                }
            }
            val genParams = ImageGenerationParams(
                model = params.model,
                prompt = params.prompt,
                aspectRatio = params.aspectRatio,
                customHeaders = params.customHeaders,
                customBody = params.customBody,
            )
            var useChatCompletionsFallback = false
            val items = withContext(Dispatchers.IO) {
                collectSequentialImages(params.numOfImages.coerceAtLeast(1)) {
                    if (useChatCompletionsFallback) {
                        editImageViaChatCompletions(providerSetting, params, key, inputReferences)
                    } else {
                        generateImageViaOpenRouterImages(providerSetting, genParams, key, inputReferences) ?: run {
                            useChatCompletionsFallback = true
                            editImageViaChatCompletions(providerSetting, params, key, inputReferences)
                        }
                    }
                }
            }
            items.forEach { emit(it) }
            return@flow
        }

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", params.model.modelId)
            .addFormDataPart("prompt", params.prompt)
            .addFormDataPart("n", params.numOfImages.toString())
        bodyBuilder.addFormDataPart(
            "size", when (params.aspectRatio) {
                ImageAspectRatio.SQUARE -> "1024x1024"
                ImageAspectRatio.LANDSCAPE -> "1536x1024"
                ImageAspectRatio.PORTRAIT -> "1024x1536"
            }
        )

        val imageFieldName = if (params.images.size == 1) "image" else "image[]"
        params.images.forEach { path ->
            val imageFile = File(path)
            require(imageFile.exists()) {
                "Image file does not exist: $path"
            }
            require(imageFile.extension.lowercase() in SUPPORTED_EDIT_IMAGE_EXTENSIONS) {
                "Unsupported image file type for OpenAI edit: ${imageFile.extension}"
            }
            bodyBuilder.addFormDataPart(
                imageFieldName,
                imageFile.name,
                imageFile.asRequestBody(imageFile.imageMediaType().toMediaType())
            )
        }

        params.customBody.forEach { customBody ->
            val value = when (val element = customBody.value) {
                is JsonPrimitive -> element.contentOrNull ?: element.toString()
                else -> element.toString()
            }
            bodyBuilder.addFormDataPart(customBody.key, value)
        }

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/edits")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .post(bodyBuilder.build())
            .configureReferHeaders(providerSetting.baseUrl)
            .build()

        val items = withContext(Dispatchers.IO) {
            val response = client.newCall(request).await()
            if (!response.isSuccessful) {
                error("Failed to edit image: ${response.code} ${response.body?.string()}")
            }
            parseImageResponse(response.body.string())
        }

        items.forEach { emit(it) }
    }

    private suspend fun parseImageResponse(bodyStr: String): List<ImageGenerationItem> {
        val body = json.parseToJsonElement(bodyStr).jsonObject
        val defaultFormat = body["output_format"]?.jsonPrimitive?.contentOrNull ?: "png"
        val data = body["data"]?.jsonArray ?: error("No data in image response")
        return data.map { element ->
            val obj = element.jsonObject
            val b64Json = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            if (b64Json != null) {
                val outputFormat = obj["output_format"]?.jsonPrimitive?.contentOrNull ?: defaultFormat
                ImageGenerationItem(
                    data = b64Json,
                    mimeType = outputFormat.toImageMimeType(),
                )
            } else {
                val url = obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("No b64_json or url in image response")
                downloadImageAsBase64(url)
            }
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun downloadImageAsBase64(url: String): ImageGenerationItem {
        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to download generated image: ${response.code} ${response.body.string()}")
        }

        val body = response.body
        val mimeType = body.contentType()?.toString() ?: "image/png"
        val base64 = Base64.encode(body.bytes())

        return ImageGenerationItem(
            data = base64,
            mimeType = mimeType
        )
    }

    private fun File.imageMediaType(): String = when (extension.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun File.toDataUri(): String = "data:${imageMediaType()};base64,${Base64.encode(readBytes())}"

    private fun String.toImageMimeType(): String = when (lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> "image/png"
    }

    companion object {
        private val SUPPORTED_EDIT_IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")
    }
}
