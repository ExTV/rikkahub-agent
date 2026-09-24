package me.rerere.ai.provider.providers.openai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.OpenRouterRouting
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.util.json

/**
 * OpenRouter-specific request-building helpers, isolated from [ChatCompletionsAPI] so the
 * shared OpenAI-compatible path does not bloat. Everything here is only used when the
 * provider host is `openrouter.ai`.
 */

/**
 * Build the OpenRouter `provider` routing object, or null when nothing should be sent
 * (so default load balancing is preserved).
 *
 * [hasToolsOrSchema] forces `require_parameters` so capability-mismatched providers can't
 * be picked and silently drop tools / response_format.
 */
fun buildProviderObject(routing: OpenRouterRouting, hasToolsOrSchema: Boolean): JsonObject? {
    val forceRequire = routing.requireParameters || hasToolsOrSchema
    if (routing.isDefault() && !forceRequire) return null
    return buildJsonObject {
        routing.sort?.let { put("sort", it) }
        if (routing.order.isNotEmpty()) putJsonArray("order") { routing.order.forEach { add(it) } }
        if (routing.only.isNotEmpty()) putJsonArray("only") { routing.only.forEach { add(it) } }
        if (routing.ignore.isNotEmpty()) putJsonArray("ignore") { routing.ignore.forEach { add(it) } }
        // allow_fallbacks is only meaningful alongside order/only
        if ((routing.order.isNotEmpty() || routing.only.isNotEmpty()) && !routing.allowFallbacks) {
            put("allow_fallbacks", false)
        }
        if (forceRequire) put("require_parameters", true)
        routing.dataCollection?.let { put("data_collection", it) }
        if (routing.zdr) put("zdr", true)
        if (routing.quantizations.isNotEmpty()) {
            putJsonArray("quantizations") { routing.quantizations.forEach { add(it) } }
        }
        if (routing.maxPricePrompt != null || routing.maxPriceCompletion != null) {
            putJsonObject("max_price") {
                routing.maxPricePrompt?.let { put("prompt", it) }
                routing.maxPriceCompletion?.let { put("completion", it) }
            }
        }
    }
}

/**
 * Build the top-level `models` fallback array: OpenRouter tries these ids in order when
 * the primary model is down, rate-limited, or refuses on moderation. Null when no usable
 * fallback is configured. The primary id is excluded so it is never retried as its own
 * fallback. https://openrouter.ai/docs/guides/routing/model-fallbacks
 */
fun buildFallbackModelsArray(primaryModelId: String, routing: OpenRouterRouting): JsonArray? {
    val fallbacks = routing.fallbackModels
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != primaryModelId }
        .distinct()
    if (fallbacks.isEmpty()) return null
    return buildJsonArray { fallbacks.forEach { add(it) } }
}

data class ParsedImageDataUri(val mime: String, val base64: String)

private val DATA_URI_REGEX =
    Regex("^data:(image/[a-zA-Z0-9.+-]+);base64,(.+)$", RegexOption.DOT_MATCHES_ALL)

/**
 * Parse any image data URI (png/jpeg/webp/...) into its mime and base64 payload.
 * Returns null for non-data-URIs (e.g. http URLs) or malformed input.
 *
 * Replaces the old hardcoded `substringAfter("data:image/png;base64,")`, which silently
 * returned the whole string for non-png mimes and produced unrenderable bytes.
 */
fun parseImageDataUri(url: String): ParsedImageDataUri? {
    val m = DATA_URI_REGEX.matchEntire(url.trim()) ?: return null
    return ParsedImageDataUri(mime = m.groupValues[1], base64 = m.groupValues[2])
}

/**
 * Map one item from OpenRouter's `GET /models` `data[]` into a [Model] with capabilities
 * and pricing detected from `architecture` / `supported_parameters` / `pricing`, so image
 * models, tool models and reasoning models work on import without manual toggling.
 */
fun openRouterModelFromJson(modelObj: JsonObject): Model? {
    val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return null
    val name = modelObj["name"]?.jsonPrimitive?.contentOrNull ?: id

    val arch = modelObj["architecture"] as? JsonObject
    fun strList(obj: JsonObject?, key: String): List<String> =
        (obj?.get(key) as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

    val inMods = strList(arch, "input_modalities")
    val outMods = strList(arch, "output_modalities")
    val supported = strList(modelObj, "supported_parameters")
    val pricing = modelObj["pricing"] as? JsonObject

    val inputModalities = buildList {
        add(Modality.TEXT)
        if ("image" in inMods) add(Modality.IMAGE)
    }.distinct()
    val outputModalities = buildList {
        add(Modality.TEXT)
        if ("image" in outMods) add(Modality.IMAGE)
    }.distinct()
    val abilities = buildList {
        if ("tools" in supported || "tool_choice" in supported) add(ModelAbility.TOOL)
        if ("reasoning" in supported || "include_reasoning" in supported) add(ModelAbility.REASONING)
    }

    // Models that can output images are typed IMAGE so they appear in the image-generation
    // model picker (which filters strictly by ModelType.IMAGE); others stay CHAT. Recraft's
    // *-vector models only output SVG, which Android can't rasterize, so they're excluded
    // from the image picker even though OpenRouter lists "image" in their output_modalities.
    val type = if ("image" in outMods && !isSvgOnlyImageModel(id)) ModelType.IMAGE else ModelType.CHAT

    return Model(
        modelId = id,
        displayName = name,
        type = type,
        inputModalities = inputModalities,
        outputModalities = outputModalities,
        abilities = abilities,
        contextLength = modelObj["context_length"]?.jsonPrimitive?.intOrNull,
        supportedParameters = supported,
        // OpenRouter pricing values are strings of USD-per-token.
        pricePromptPerToken = pricing?.get("prompt")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
        priceCompletionPerToken = pricing?.get("completion")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
    )
}

/**
 * OpenRouter's Recraft `*-vector` models only output SVG, which Android's decoders can't
 * rasterize. [outputFormats], when known (e.g. an `/images/models` `supported_parameters
 * .output_format` enum), is treated as SVG-only when it's non-empty and every entry is
 * "svg". `/models` (the only endpoint the app calls today) has no such enum, so the id
 * suffix is what actually detects these in practice.
 */
fun isSvgOnlyImageModel(id: String, outputFormats: List<String> = emptyList()): Boolean {
    if (id.endsWith("-vector", ignoreCase = true)) return true
    if (outputFormats.isNotEmpty() && outputFormats.all { it.equals("svg", ignoreCase = true) }) return true
    return false
}

/**
 * Build the body for `POST {baseUrl}/images`, OpenRouter's dedicated image-generation
 * endpoint, used for image-only models (FLUX.2, Recraft, Seedream, gpt-image, Qwen-Image,
 * Grok Imagine, Krea, MAI-Image, ...) that don't speak the `/chat/completions` image path.
 *
 * `n` is never sent: `/models`' `supported_parameters` describes the chat-completions path,
 * not this endpoint (live data shows FLUX.2 models listing only `["seed"]`, gpt-image models
 * listing chat params like `frequency_penalty`, and none listing `n` or `aspect_ratio`), so
 * gating on it would drop these for nearly every model. Multi-image requests are instead
 * emulated by the caller issuing one sequential call per image. `aspect_ratio` is always sent
 * when the caller has one (OpenRouter's docs say providers clamp to their supported subset).
 * [inputReferences] (data URIs of source images, for image-to-image edits) are always sent
 * when present, since their absence/presence *is* the edit request.
 * https://openrouter.ai/docs/guides/overview/multimodal/image-generation
 */
fun buildOpenRouterImagesRequestBody(
    model: Model,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    inputReferences: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    put("prompt", prompt)
    put(
        "aspect_ratio", when (aspectRatio) {
            ImageAspectRatio.SQUARE -> "1:1"
            ImageAspectRatio.LANDSCAPE -> "16:9"
            ImageAspectRatio.PORTRAIT -> "9:16"
        }
    )
    if (inputReferences.isNotEmpty()) {
        putJsonArray("input_references") {
            inputReferences.forEach { uri ->
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject { put("url", uri) })
                })
            }
        }
    }
}

/**
 * Runs [block] up to [count] times (each call is expected to produce one image's worth of
 * results), collecting everything that succeeds. `/images` has no universal `n` (see
 * [buildOpenRouterImagesRequestBody]), so count > 1 is emulated with sequential single-image
 * calls; if a later call fails after at least one earlier one already succeeded, the partial
 * results are returned instead of discarding already-generated images. A failure on the very
 * first call still throws, since there's nothing to fall back to. Cancellation always
 * propagates regardless of what's already been collected.
 */
suspend fun <T> collectSequentialImages(count: Int, block: suspend () -> List<T>): List<T> {
    val collected = mutableListOf<T>()
    repeat(count) {
        try {
            collected += block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (collected.isNotEmpty()) return collected
            throw e
        }
    }
    return collected
}

/**
 * Whether a non-2xx from `POST {baseUrl}/images` means the endpoint doesn't exist there (an
 * OpenRouter-compatible proxy that never added the dedicated Images API) and the older
 * `/chat/completions` image path should be tried instead, rather than a real request error.
 *
 * This only runs against `openrouter.ai`, where `/images` exists, so a 404 there is normally
 * OpenRouter's own "model not found" error (a JSON body with an `error` field) and must be
 * surfaced, not swallowed into a confusing chat-completions failure. The historical
 * endpoint-missing case (a proxy that never added `/images`) returns an HTML or empty body
 * instead, which is what actually triggers the fallback. 405 always falls back, since no
 * legitimate per-model error uses it here.
 */
fun shouldFallbackToChatCompletionsImage(responseCode: Int, bodyStr: String): Boolean {
    if (responseCode == 405) return true
    if (responseCode != 404) return false
    val hasJsonError = runCatching {
        json.parseToJsonElement(bodyStr).jsonObject["error"] != null
    }.getOrDefault(false)
    return !hasJsonError
}

/** Extract OpenRouter's `error.message` from a non-2xx body, falling back to the raw body. */
fun extractOpenRouterErrorMessage(responseCode: Int, bodyStr: String): String {
    val message = runCatching {
        json.parseToJsonElement(bodyStr).jsonObject["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()
    return "Failed to generate image: $responseCode ${message ?: bodyStr}"
}

/**
 * Parse `POST {baseUrl}/images`'s non-stream response: `data[].{b64_json, media_type}`.
 * SVG items (`image/svg+xml`, from Recraft `*-vector` models reached by an explicit model
 * id despite being excluded from the picker) are skipped since Android can't rasterize them;
 * if every item was SVG that's surfaced as an error instead of an empty gallery.
 */
fun parseOpenRouterImagesResponse(bodyStr: String): List<ImageGenerationItem> {
    val body = json.parseToJsonElement(bodyStr).jsonObject
    val data = body["data"]?.jsonArray ?: error("No data in image response")
    var svgSkipped = 0
    val items = data.mapNotNull { element ->
        val obj = element.jsonObject
        val b64 = obj["b64_json"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val mediaType = obj["media_type"]?.jsonPrimitive?.contentOrNull ?: "image/png"
        if (mediaType.equals("image/svg+xml", ignoreCase = true)) {
            svgSkipped++
            return@mapNotNull null
        }
        ImageGenerationItem(data = b64, mimeType = mediaType)
    }
    if (items.isEmpty()) {
        if (svgSkipped > 0) {
            error("This model only returned SVG images, which can't be displayed. Pick a different image model.")
        }
        error("No image returned from OpenRouter")
    }
    return items
}

/**
 * Build the body for the `/chat/completions` image fallback (`modalities: ["image","text"]`),
 * used when the dedicated Images API isn't available. [inputReferences] (data URIs), when
 * non-empty, are attached as `image_url` content parts for image-to-image edits.
 */
fun buildOpenRouterChatCompletionsImageBody(
    modelId: String,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    inputReferences: List<String> = emptyList(),
): JsonObject = buildJsonObject {
    put("model", modelId)
    putJsonArray("messages") {
        add(buildJsonObject {
            put("role", "user")
            if (inputReferences.isEmpty()) {
                put("content", prompt)
            } else {
                putJsonArray("content") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", prompt)
                    })
                    inputReferences.forEach { uri ->
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", uri) })
                        })
                    }
                }
            }
        })
    }
    putJsonArray("modalities") {
        add("image")
        add("text")
    }
    put("image_config", buildJsonObject {
        put(
            "aspect_ratio", when (aspectRatio) {
                ImageAspectRatio.SQUARE -> "1:1"
                ImageAspectRatio.LANDSCAPE -> "16:9"
                ImageAspectRatio.PORTRAIT -> "9:16"
            }
        )
    })
}

/** Parse `/chat/completions`'s `choices[0].message.images[].image_url.url` data URIs into items. */
fun parseOpenRouterChatCompletionsImageResponse(bodyStr: String): List<ImageGenerationItem> {
    val message = json.parseToJsonElement(bodyStr).jsonObject["choices"]?.jsonArray
        ?.getOrNull(0)?.jsonObject?.get("message")?.jsonObject
        ?: error("No choices in image response")
    val images = message["images"]?.jsonArray ?: JsonArray(emptyList())
    val items = images.mapNotNull { img ->
        val url = img.jsonObject["image_url"]?.jsonObject?.get("url")
            ?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val parsed = parseImageDataUri(url) ?: return@mapNotNull null
        ImageGenerationItem(data = parsed.base64, mimeType = parsed.mime)
    }
    if (items.isEmpty()) {
        val text = message["content"]?.jsonPrimitive?.contentOrNull
        error(
            "No image returned. The model may not support image output or returned text only." +
                (text?.takeIf { it.isNotBlank() }?.let { " Model said: $it" } ?: "")
        )
    }
    return items
}
