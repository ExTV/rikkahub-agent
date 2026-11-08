package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "GenerateImageTool"

fun createGenerateImageTool(
    context: Context,
    settingsStore: SettingsStore,
    okHttpClient: OkHttpClient,
    json: Json,
): Tool = Tool(
    name = "generate_image",
    description = """
        Generate studio-grade images based on a descriptive visual prompt.
        Uses context-aware aspect ratios: '9:16' for mobile UI/mockups and vertical art, '16:9' for landscapes and desktop banners, '1:1' for avatars/icons, '2:3' for posters.
        count specifies the number of image variations to generate (1 to 4, default 1).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Detailed, descriptive visual prompt for the image generation engine")
                })
                put("aspect_ratio", buildJsonObject {
                    put("type", "string")
                    put("enum", buildJsonArray {
                        add(JsonPrimitive("auto"))
                        add(JsonPrimitive("1:1"))
                        add(JsonPrimitive("16:9"))
                        add(JsonPrimitive("9:16"))
                        add(JsonPrimitive("2:3"))
                        add(JsonPrimitive("3:2"))
                    })
                    put("description", "Target aspect ratio (default: 'auto')")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of image variations to generate (1 to 4, default 1)")
                })
                put("model", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional model name override")
                })
            },
            required = listOf("prompt"),
        )
    },
    needsApproval = { false },
    execute = { args ->
        val params = args.jsonObject
        val prompt = params["prompt"]?.jsonPrimitive?.content ?: error("prompt is required")
        val rawAspectRatio = params["aspect_ratio"]?.jsonPrimitive?.content ?: "auto"
        val count = params["count"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 4) ?: 1
        val modelOverride = params["model"]?.jsonPrimitive?.content

        val settings = settingsStore.settingsFlow.first()

        // Resolve aspect ratio size string
        val effectiveRatio = if (rawAspectRatio == "auto") {
            val lowerPrompt = prompt.lowercase()
            when {
                lowerPrompt.contains("mobile") || lowerPrompt.contains("phone") || lowerPrompt.contains("portrait") -> "9:16"
                lowerPrompt.contains("desktop") || lowerPrompt.contains("landscape") || lowerPrompt.contains("banner") -> "16:9"
                lowerPrompt.contains("poster") -> "2:3"
                else -> "1:1"
            }
        } else {
            rawAspectRatio
        }

        val size = when (effectiveRatio) {
            "16:9" -> "1792x1024"
            "9:16" -> "1024x1792"
            "2:3" -> "1024x1536"
            "3:2" -> "1536x1024"
            else -> "1024x1024"
        }

        // Find image provider
        val targetModel = if (modelOverride != null) {
            settings.providers.flatMap { it.models }.firstOrNull { it.name.equals(modelOverride, ignoreCase = true) }
        } else {
            settings.providers.findModelById(settings.imageGenerationModelId)
                ?: settings.providers.flatMap { it.models }.firstOrNull { it.type == ModelType.IMAGE || Modality.IMAGE in it.outputModalities }
        }

        val provider = targetModel?.let { settings.providers.findProvider(it.providerId) }
            ?: settings.providers.firstOrNull { it is ProviderSetting.OpenAI && it.enabled }

        val apiKey = provider?.apiKey.orEmpty()
        val baseUrl = when (provider) {
            is ProviderSetting.OpenAI -> provider.baseUrl.trimEnd('/')
            else -> "https://api.openai.com/v1"
        }

        val requestUrl = "$baseUrl/images/generations"
        val requestBodyJson = buildJsonObject {
            put("prompt", prompt)
            put("n", count)
            put("size", size)
            targetModel?.let { put("model", it.name) }
        }.toString()

        val req = Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        val imageUrls = mutableListOf<String>()

        try {
            okHttpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    val root = json.parseToJsonElement(body).jsonObject
                    val dataArray = root["data"]?.jsonArray
                    dataArray?.forEach { elem ->
                        val obj = elem.jsonObject
                        val url = obj["url"]?.jsonPrimitive?.content
                        val b64 = obj["b64_json"]?.jsonPrimitive?.content
                        if (!url.isNullOrBlank()) {
                            imageUrls.add(url)
                        } else if (!b64.isNullOrBlank()) {
                            imageUrls.add("data:image/png;base64,$b64")
                        }
                    }
                } else {
                    Log.w(TAG, "generate_image request failed HTTP ${resp.code}: $body")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "generate_image network error", e)
        }

        if (imageUrls.isEmpty()) {
            listOf(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("error", "image_generation_failed")
                        put("detail", "Failed to generate image variations for prompt: $prompt")
                    }.toString()
                )
            )
        } else {
            val resultParts = mutableListOf<UIMessagePart>()
            imageUrls.forEach { url ->
                resultParts.add(UIMessagePart.Image(url))
            }
            resultParts.add(
                UIMessagePart.Text(
                    buildJsonObject {
                        put("success", true)
                        put("count", imageUrls.size)
                        put("aspect_ratio", effectiveRatio)
                        put("size", size)
                        put("images", buildJsonArray {
                            imageUrls.forEach { add(JsonPrimitive(it)) }
                        })
                    }.toString()
                )
            )
            resultParts
        }
    }
)
