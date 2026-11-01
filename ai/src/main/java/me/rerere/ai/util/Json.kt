package me.rerere.ai.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

@PublishedApi
internal val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Keys whose string values are sensitive enough that the raw value MUST NOT land in
 * logcat: tool args/results and full request bodies land in `Log.i` for debugging, and
 * without redaction things like a `save_ssh_host` call's `private_key` / `password` or
 * a `telegram_set_token` call's `token` would print verbatim — readable by other apps
 * holding READ_LOGS on OEM-bugged ROMs, and by `bugreport`/`dumpsys`. The match is
 * case-insensitive against the key name and applies regardless of nesting depth.
 */
private val SECRET_KEY_PATTERN: Regex =
    Regex("(?:^|_)(password|passphrase|secret|token|apikey|api[_-]?key|privatekey|private[_-]?key|key)$",
        RegexOption.IGNORE_CASE)

/**
 * Walk [element] and replace any string primitive whose KEY matches [SECRET_KEY_PATTERN]
 * with the string `"***"`. Numbers, booleans, nulls, and non-secret strings pass through
 * unchanged.
 */
fun redactSecrets(element: JsonElement, key: String? = null): JsonElement {
    val isSecret = key != null && SECRET_KEY_PATTERN.containsMatchIn(key)
    return when (element) {
        is JsonPrimitive ->
            if (isSecret && element.isString) JsonPrimitive("***") else element
        is JsonObject -> JsonObject(element.mapValues { (k, v) -> redactSecrets(v, k) })
        is JsonArray -> buildJsonArray { element.forEach { add(redactSecrets(it, key)) } }
    }
}
