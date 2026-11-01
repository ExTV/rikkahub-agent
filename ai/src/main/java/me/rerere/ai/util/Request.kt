package me.rerere.ai.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.common.android.Logging
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.internal.http.RealResponseBody

fun List<CustomHeader>.toHeaders(): Headers {
    return Headers.Builder().apply {
        this@toHeaders
            .filter { it.name.isNotBlank() }
            .forEach {
                add(it.name, it.value)
            }
    }.build()
}

fun Request.Builder.configureReferHeaders(url: String): Request.Builder {
    val httpUrl = url.toHttpUrl()
    return when (httpUrl.host) {
        "aihubmix.com" -> {
            addHeader("APP-Code", "DKHA9468")
        }

        "openrouter.ai" -> {
            this
                .addHeader("X-Title", "RikkaHub")
                .addHeader("HTTP-Referer", "https://rikka-ai.com")
        }

        else -> this
    }
}

fun Request.Builder.configureSessionHeaders(url: String, sessionId: String?): Request.Builder = apply {
    if (sessionId != null) {
        header("X-Session-ID", sessionId)
        if (url.toHttpUrl().host == "opencode.ai") {
            header("x-opencode-session", sessionId)
        }
    }
}

fun ResponseBody.stringSafe(): String? {
    return when (this) {
        is RealResponseBody -> string()
        else -> null
    }
}

fun JsonObject.mergeCustomBody(bodies: List<CustomBody>): JsonObject {
    if (bodies.isEmpty()) return this

    val content = toMutableMap()
    bodies.forEach { body ->
        if (body.key.isNotBlank()) {
            // 如果已存在相同键且两者都是JsonObject，则需要递归合并
            val existingValue = content[body.key]
            val newValue = body.value

            if (existingValue is JsonObject && newValue is JsonObject) {
                // 递归合并两个JsonObject
                content[body.key] = mergeJsonObjects(existingValue, newValue)
            } else {
                // 直接替换或添加
                content[body.key] = newValue
            }
        }
    }
    return JsonObject(content)
}

/**
 * 递归合并两个JsonObject
 */
private fun mergeJsonObjects(base: JsonObject, overlay: JsonObject): JsonObject {
    val result = base.toMutableMap()

    for ((key, value) in overlay) {
        val baseValue = result[key]

        result[key] = if (baseValue is JsonObject && value is JsonObject) {
            // 如果两者都是JsonObject，递归合并
            mergeJsonObjects(baseValue, value)
        } else {
            // 否则使用新值替换旧值
            value
        }
    }

    return JsonObject(result)
}

/**
 * Keys Google's Schema proto actually accepts (verified against googleapis/java-genai
 * `types/Schema.java`, which mirrors the proto). Anything else - `$ref`, `$schema`, `$defs`,
 * `deprecated`, `x-google-*`, `oneOf`, `allOf`, ... - triggers
 * `Invalid JSON payload received. Unknown name "..."` 400s from Google, so unlike the old
 * blacklist this replaced, this is an allowlist: everything not named here is dropped.
 *
 * `enum` and `format` are deliberately excluded to keep behavior parity with the blacklist
 * this replaces.
 */
private val GEMINI_SCHEMA_ALLOWED_KEYS = setOf(
    "anyOf", "default", "description", "example", "items", "maximum", "maxItems",
    "maxLength", "maxProperties", "minimum", "minItems", "minLength", "minProperties",
    "nullable", "pattern", "properties", "propertyOrdering", "required", "title", "type",
)

private const val GEMINI_SCHEMA_REF_DEPTH_CAP = 8
private const val GEMINI_SCHEMA_SANITIZER_TAG = "SchemaSanitizer"

/**
 * Sanitizes a JSON Schema (as produced by MCP servers or hand-written tool schemas) into the
 * subset Google's Schema proto accepts. Recurses only where subschemas actually live
 * (`properties` values, `items`, `anyOf` elements); `default`/`example`/`required`/`enum` are
 * copied verbatim, never descended into.
 *
 * Also resolves local `$ref`s (`#/$defs/...`, `#/definitions/...`) against the root schema,
 * folds `oneOf` into `anyOf`, merges single-element `allOf` into the parent (dropping
 * multi-element `allOf` instead), and normalizes array-valued `type` (e.g. `["string","null"]`)
 * into a scalar `type` plus `nullable`.
 */
fun JsonElement.sanitizeForGeminiSchema(): JsonElement {
    val sanitized = sanitizeGeminiSchemaNode(root = this, node = this, depth = 0, refPath = emptySet())
    val isObjectSchema = sanitized is JsonObject &&
        (sanitized["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content == "object"
    if (isObjectSchema) return sanitized

    Logging.log(
        GEMINI_SCHEMA_SANITIZER_TAG,
        "root schema is not an object schema (${if (this !is JsonObject) "input was not a JSON object" else "no object type"}), falling back to {type: object, properties: {}}"
    )
    return buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {})
    }
}

private fun sanitizeGeminiSchemaNode(
    root: JsonElement,
    node: JsonElement,
    depth: Int,
    refPath: Set<String>,
): JsonElement {
    if (node !is JsonObject) return node

    // A local $ref replaces the whole subschema; resolve (or fall back) before anything else.
    val ref = (node["\$ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (ref != null) {
        val fallback = buildJsonObject { put("type", "object") }
        if (depth >= GEMINI_SCHEMA_REF_DEPTH_CAP || ref in refPath) {
            Logging.log(
                GEMINI_SCHEMA_SANITIZER_TAG,
                "dropped \$ref '$ref' (${if (ref in refPath) "cyclic" else "exceeded depth cap $GEMINI_SCHEMA_REF_DEPTH_CAP"}), falling back to {type: object}"
            )
            return fallback
        }
        val resolved = resolveLocalGeminiRef(root, ref)
        if (resolved == null) {
            Logging.log(GEMINI_SCHEMA_SANITIZER_TAG, "dropped unresolvable \$ref '$ref', falling back to {type: object}")
            return fallback
        }
        return sanitizeGeminiSchemaNode(root, resolved, depth + 1, refPath + ref)
    }

    // allOf with exactly one element merges into the parent (parent keys win); its own $ref (if
    // any - e.g. the common `{"allOf":[{"$ref":"#/$defs/X"}]}` sibling-keyword pattern) is
    // resolved first so X's content isn't silently discarded. 2+ elements just drops the
    // keyword and logs it, since it is not in the allowlist and won't be copied to the output
    // anyway.
    val allOf = node["allOf"] as? JsonArray
    val merged: JsonObject = when {
        allOf != null && allOf.size == 1 -> {
            val allOfElement = (allOf[0] as? JsonObject)?.let { resolveGeminiAllOfRef(root, it, depth, refPath) }
            if (allOfElement != null) JsonObject(allOfElement.toMap() + node.toMap()) else node
        }

        allOf != null && allOf.size > 1 -> {
            Logging.log(
                GEMINI_SCHEMA_SANITIZER_TAG,
                "dropped allOf with ${allOf.size} elements (multi-element allOf is unsupported), keeping the rest of the schema"
            )
            node
        }

        else -> node
    }

    // oneOf renames to anyOf, merging with any existing anyOf.
    val oneOf = merged["oneOf"] as? JsonArray
    val anyOfSource: JsonArray? = if (oneOf != null) {
        JsonArray((merged["anyOf"] as? JsonArray)?.toList().orEmpty() + oneOf.toList())
    } else {
        merged["anyOf"] as? JsonArray
    }

    val content = linkedMapOf<String, JsonElement>()
    for (key in GEMINI_SCHEMA_ALLOWED_KEYS) {
        when (key) {
            "anyOf" -> anyOfSource?.let { arr ->
                content["anyOf"] = JsonArray(arr.map { sanitizeGeminiSchemaNode(root, it, depth, refPath) })
            }

            "properties" -> (merged["properties"] as? JsonObject)?.let { properties ->
                content["properties"] = JsonObject(
                    properties.mapValues { (_, value) -> sanitizeGeminiSchemaNode(root, value, depth, refPath) }
                )
            }

            "items" -> when (val itemsValue = merged["items"]) {
                null -> {}
                is JsonArray -> if (itemsValue.isEmpty()) {
                    Logging.log(GEMINI_SCHEMA_SANITIZER_TAG, "dropped empty tuple-form items array")
                } else {
                    Logging.log(
                        GEMINI_SCHEMA_SANITIZER_TAG,
                        "normalized tuple-form items (${itemsValue.size} elements) to a single schema from its first element"
                    )
                    content["items"] = sanitizeGeminiSchemaNode(root, itemsValue[0], depth, refPath)
                }

                else -> content["items"] = sanitizeGeminiSchemaNode(root, itemsValue, depth, refPath)
            }

            "type" -> {} // handled below: a JSON-Schema type array needs normalizing first

            else -> merged[key]?.let { content[key] = it }
        }
    }

    when (val typeValue = merged["type"]) {
        is JsonArray -> {
            val types = typeValue.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            types.firstOrNull { it != "null" }?.let { content["type"] = JsonPrimitive(it) }
            if (types.contains("null")) content["nullable"] = JsonPrimitive(true)
        }

        is JsonPrimitive -> content["type"] = typeValue
        else -> {}
    }

    // A node with no explicit type still needs one when its shape implies it, since a bare
    // `{"properties": ...}` or `{"items": ...}` is not a valid draft-2020-12 schema on its own.
    // A schema with neither (e.g. a bare anyOf wrapper) is legitimately typeless - leave it be.
    if (!content.containsKey("type")) {
        when {
            content.containsKey("properties") -> content["type"] = JsonPrimitive("object")
            content.containsKey("items") -> content["type"] = JsonPrimitive("array")
        }
    }

    return JsonObject(content)
}

/**
 * Resolves a single allOf element's own `$ref` chain (if it has one) against the root schema,
 * mirroring the top-level `$ref` handling in [sanitizeGeminiSchemaNode] (depth cap, cycle
 * guard, fallback to `{"type":"object"}`) so a wrapped-ref element inlines its target instead
 * of being merged in as a bare, unresolved `$ref` key that then gets silently dropped by the
 * allowlist.
 */
private fun resolveGeminiAllOfRef(
    root: JsonElement,
    element: JsonObject,
    depth: Int,
    refPath: Set<String>,
): JsonObject {
    val ref = (element["\$ref"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return element
    val fallback = buildJsonObject { put("type", "object") }
    if (depth >= GEMINI_SCHEMA_REF_DEPTH_CAP || ref in refPath) {
        Logging.log(
            GEMINI_SCHEMA_SANITIZER_TAG,
            "dropped allOf element's \$ref '$ref' (${if (ref in refPath) "cyclic" else "exceeded depth cap $GEMINI_SCHEMA_REF_DEPTH_CAP"}), falling back to {type: object}"
        )
        return fallback
    }
    val resolved = resolveLocalGeminiRef(root, ref) as? JsonObject
    if (resolved == null) {
        Logging.log(GEMINI_SCHEMA_SANITIZER_TAG, "dropped allOf element's unresolvable \$ref '$ref', falling back to {type: object}")
        return fallback
    }
    return resolveGeminiAllOfRef(root, resolved, depth + 1, refPath + ref)
}

private fun resolveLocalGeminiRef(root: JsonElement, ref: String): JsonElement? {
    val (container, path) = when {
        ref.startsWith("#/\$defs/") -> "\$defs" to ref.removePrefix("#/\$defs/")
        ref.startsWith("#/definitions/") -> "definitions" to ref.removePrefix("#/definitions/")
        else -> return null // non-local $ref: unresolvable, caller falls back
    }
    var current: JsonElement = (root as? JsonObject)?.get(container) ?: return null
    for (segment in path.split("/")) {
        if (segment.isEmpty()) continue
        current = (current as? JsonObject)?.get(segment) ?: return null
    }
    return current
}
