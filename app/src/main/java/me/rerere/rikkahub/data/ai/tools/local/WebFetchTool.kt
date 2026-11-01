package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.net.hostIsBlockedLiteral
import me.rerere.rikkahub.data.ai.net.withEgressGuard
import me.rerere.search.extract.ExtractMode
import me.rerere.search.extract.WebExtractor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset

private const val WEB_FETCH_TIMEOUT_MS = 30_000L
internal const val WEB_FETCH_BODY_CAP = 8 * 1024  // 8 KB

/** Cap for extracted prose. Higher than the raw cap because prose is all signal. */
internal const val WEB_FETCH_EXTRACT_CAP = 32 * 1024

internal enum class FetchExtract { RAW, ARTICLE, TEXT, LINKS, METADATA }

internal fun parseExtractModeOrNull(raw: String?): FetchExtract? = when (raw?.trim()?.lowercase()) {
    null, "", "raw" -> FetchExtract.RAW
    "article" -> FetchExtract.ARTICLE
    "text" -> FetchExtract.TEXT
    "links" -> FetchExtract.LINKS
    "metadata" -> FetchExtract.METADATA
    else -> null
}

internal fun parseExtractMode(raw: String?): FetchExtract =
    parseExtractModeOrNull(raw) ?: FetchExtract.RAW

private fun FetchExtract.toExtractMode(): ExtractMode = when (this) {
    FetchExtract.ARTICLE -> ExtractMode.ARTICLE
    FetchExtract.TEXT -> ExtractMode.TEXT
    FetchExtract.LINKS -> ExtractMode.LINKS
    FetchExtract.METADATA -> ExtractMode.METADATA
    FetchExtract.RAW -> ExtractMode.TEXT // unreachable; RAW never reaches the extractor
}

/**
 * Build the response envelope for an extraction-mode fetch. An extraction that yields no
 * text is an error, not a 200 with an empty string: a silent empty body is exactly how a
 * caller ends up believing it read a page it never read.
 *
 * [bodyTruncated] means the raw HTML itself hit the read cap before it was fully read; it
 * forces `truncated` true (and surfaces `body_truncated`) so a partial read is never
 * reported as a complete one, even when the extracted-text window was not exhausted.
 */
internal fun buildExtractEnvelope(
    status: Int,
    ok: Boolean,
    finalUrl: String,
    html: String,
    contentType: String?,
    mode: FetchExtract,
    maxChars: Int,
    startIndex: Int,
    bodyTruncated: Boolean,
    headers: Map<String, String>?,
): String {
    val page = WebExtractor.extract(
        html = html,
        baseUrl = finalUrl,
        mode = mode.toExtractMode(),
        maxChars = maxChars,
        startIndex = startIndex,
    )

    val nothingUseful = mode != FetchExtract.METADATA &&
        mode != FetchExtract.LINKS &&
        page.text.isBlank() &&
        startIndex == 0

    if (nothingUseful) {
        return buildJsonObject {
            put("error", "empty_extraction")
            put("status", status)
            put("final_url", finalUrl)
            put(
                "detail",
                "The page was fetched but no article text could be extracted from it.",
            )
            put(
                "recovery",
                "Retry with extract_mode='raw' to inspect the markup, or open the page with " +
                    "the browser tools if it renders its content with JavaScript.",
            )
        }.toString()
    }

    return buildJsonObject {
        put("status", status)
        put("ok", ok)
        put("final_url", finalUrl)
        put("extract_mode", mode.name.lowercase())
        page.title?.let { put("title", it) }
        page.siteName?.let { put("site_name", it) }
        page.description?.let { put("description", it) }
        page.language?.let { put("language", it) }
        if (mode == FetchExtract.LINKS) {
            put("links", buildJsonArray {
                page.links.forEach { link ->
                    add(buildJsonObject {
                        put("href", link.href)
                        put("text", link.text)
                    })
                }
            })
        } else {
            put("text", page.text)
        }
        put("truncated", page.truncated || bodyTruncated)
        put("body_truncated", bodyTruncated)
        page.nextStartIndex?.let { put("next_start_index", it) }
        headers?.let { h ->
            put("headers", buildJsonObject { h.forEach { (k, v) -> put(k, v) } })
        }
    }.toString()
}

/**
 * Lightweight HTTP GET/POST tool so workflows / the LLM can fetch a URL without driving the
 * full in-app browser or shelling out to Termux+curl. Backed by the DI [OkHttpClient]
 * singleton (already NetworkChangeMonitor-registered), rebuilt per call with [withEgressGuard]
 * so private / loopback / link-local targets are refused, whether named by hostname or IP
 * literal, on every redirect hop. 30 s hard timeout via [withTimeoutOrNull]. Optionally
 * extracts readable content instead of raw body.
 */
fun webFetchTool(client: OkHttpClient): Tool = Tool(
    name = "web_fetch",
    description = """
        Fetch a URL over HTTP(S) and optionally extract readable content from it.
        extract_mode: 'raw' (default, unprocessed body), 'article' (main prose, use this to
        read a page), 'text' (all body text), 'links', or 'metadata'. Raw returns markup that
        is mostly not content, so pass 'article' when you want to read a page. max_chars caps
        the returned text (default 32768 when extracting, 8192 for raw); when truncated=true
        pass next_start_index back as start_index to continue. method is GET (default) or
        POST. Response headers are omitted unless include_headers=true. Private, loopback and
        link-local addresses are refused. Returns {status, ok, final_url, extract_mode, title,
        text, truncated, next_start_index} or {error, detail, recovery}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The http:// or https:// URL to fetch")
                })
                put("extract_mode", buildJsonObject {
                    put("type", "string")
                    put("description", "raw (default), article (main prose, use to read a page), text, links, or metadata")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum characters of text to return")
                })
                put("start_index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Resume offset; pass next_start_index from a truncated result")
                })
                put("include_headers", buildJsonObject {
                    put("type", "boolean")
                    put("description", "Include HTTP response headers (default false)")
                })
                put("method", buildJsonObject {
                    put("type", "string")
                    put("description", "GET (default) or POST")
                })
                put("headers", buildJsonObject {
                    put("type", "object")
                    put("description", "Optional request headers as a name->value object")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional request body string (POST only)")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { input ->
        val obj = input.jsonObject
        val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim()
        if (url.isNullOrBlank()) {
            return@Tool fmTextPart(fmErrEnvelope("missing_url", "url is required"))
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "bad_url")
                    put("detail", "url must start with http:// or https://")
                    put("recovery", "Pass an absolute http(s) URL.")
                }.toString()
            )
        }
        // OkHttp routes a literal-IP host straight to the socket without consulting the
        // GuardedDns wrapper, so refuse a private/loopback/link-local literal deterministically
        // here; the interceptor in withEgressGuard still covers literal redirect hops.
        url.toHttpUrlOrNull()?.host?.let { host ->
            if (hostIsBlockedLiteral(host)) {
                return@Tool fmTextPart(
                    buildJsonObject {
                        put("error", "blocked_address")
                        put("detail", "blocked_private_address: $host")
                        put(
                            "recovery",
                            "This tool refuses private, loopback and link-local addresses. Use a public URL.",
                        )
                    }.toString()
                )
            }
        }
        val method = obj["method"]?.jsonPrimitive?.contentOrNull?.trim()?.uppercase() ?: "GET"
        if (method != "GET" && method != "POST") {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "bad_method")
                    put("detail", "method must be GET or POST, got $method")
                    put("recovery", "Use method=GET or method=POST.")
                }.toString()
            )
        }
        val bodyStr = obj["body"]?.jsonPrimitive?.contentOrNull

        val modeRaw = obj["extract_mode"]?.jsonPrimitive?.contentOrNull
        val mode = parseExtractModeOrNull(modeRaw)
            ?: return@Tool fmTextPart(
                fmErrEnvelope(
                    "bad_extract_mode",
                    "extract_mode must be one of raw, article, text, links, metadata",
                ),
            )
        val includeHeaders = obj["include_headers"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
        val startIndex = obj["start_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val defaultCap = if (mode == FetchExtract.RAW) WEB_FETCH_BODY_CAP else WEB_FETCH_EXTRACT_CAP
        val maxChars = obj["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?.coerceIn(1, defaultCap) ?: defaultCap

        val request = try {
            val builder = Request.Builder().url(url)
            (obj["headers"] as? kotlinx.serialization.json.JsonObject)?.forEach { (name, value) ->
                value.jsonPrimitive.contentOrNull?.let { builder.header(name, it) }
            }
            if (method == "POST") {
                builder.post((bodyStr ?: "").toRequestBody())
            } else {
                builder.get()
            }
            builder.build()
        } catch (e: IllegalArgumentException) {
            return@Tool fmTextPart(
                buildJsonObject {
                    put("error", "bad_request")
                    put("detail", e.message ?: "Could not build request")
                    put("recovery", "Check the URL and header names for invalid characters.")
                }.toString()
            )
        }

        // Guarded client: refuses private / loopback / link-local targets on every hop,
        // whether named by hostname or IP literal.
        val guarded = client.withEgressGuard()

        val result = withTimeoutOrNull(WEB_FETCH_TIMEOUT_MS) {
            try {
                guarded.newCall(request).execute().use { resp ->
                    // Read the body through a bounded buffer instead of resp.body.bytes(),
                    // which would pull the whole (possibly multi-GB) response into memory.
                    // Read at most CAP+1 bytes: the extra byte tells us more remained.
                    // Raw is capped tight; extraction reads much more markup than the prose it
                    // yields (roughly 8x), so it gets a larger byte budget before it truncates.
                    val (raw, bodyTruncated) = readBounded(
                        resp.body.byteStream(),
                        if (mode == FetchExtract.RAW) WEB_FETCH_BODY_CAP else WEB_FETCH_EXTRACT_CAP * 8,
                    )
                    val contentType = resp.header("Content-Type")
                    val decoded = decodeBody(raw, raw.size, contentType)
                    val headerMap = if (includeHeaders) {
                        resp.headers.associate { (n, v) -> n to v }
                    } else {
                        null
                    }

                    if (mode == FetchExtract.RAW) {
                        buildJsonObject {
                            put("status", resp.code)
                            put("ok", resp.isSuccessful)
                            put("final_url", resp.request.url.toString())
                            put("extract_mode", "raw")
                            put("body", decoded)
                            put("body_truncated", bodyTruncated)
                            headerMap?.let { h ->
                                put("headers", buildJsonObject { h.forEach { (k, v) -> put(k, v) } })
                            }
                        }.toString()
                    } else {
                        buildExtractEnvelope(
                            status = resp.code,
                            ok = resp.isSuccessful,
                            finalUrl = resp.request.url.toString(),
                            html = decoded,
                            contentType = contentType,
                            mode = mode,
                            maxChars = maxChars,
                            startIndex = startIndex,
                            bodyTruncated = bodyTruncated,
                            headers = headerMap,
                        )
                    }
                }
            } catch (e: java.io.InterruptedIOException) {
                // OkHttp's callTimeout (set in withEgressGuard) fires this when a call, including
                // a trickling read, runs past the advertised 30s limit; withTimeoutOrNull cannot
                // catch this itself since the blocking execute() call has no suspension point.
                buildJsonObject {
                    put("error", "timeout")
                    put("detail", "Request exceeded the 30s limit.")
                    put("recovery", "The host is slow or unreachable; try a smaller request or a different URL.")
                }.toString()
            } catch (e: IOException) {
                val blocked = e.message?.contains("blocked_private_address") == true
                buildJsonObject {
                    put("error", if (blocked) "blocked_address" else "network_error")
                    put("detail", e.message ?: e::class.java.simpleName)
                    put(
                        "recovery",
                        if (blocked) {
                            "This tool refuses private, loopback and link-local addresses. Use a public URL."
                        } else {
                            "Check connectivity and that the host is reachable, then retry."
                        },
                    )
                }.toString()
            }
        } ?: buildJsonObject {
            put("error", "timeout")
            put("detail", "Request exceeded the 30s limit.")
            put("recovery", "The host is slow or unreachable; try a smaller request or a different URL.")
        }.toString()

        fmTextPart(result)
    },
)

/**
 * Read at most [cap] bytes from [ins], plus one probe byte to detect overflow. Returns the
 * accumulated bytes (up to cap+1) and a truncated flag set when the stream had more than
 * [cap] bytes. Bounds memory regardless of Content-Length or a missing/lying one.
 */
internal fun readBounded(ins: InputStream, cap: Int): Pair<ByteArray, Boolean> {
    val out = ByteArrayOutputStream(minOf(cap, 8 * 1024))
    val buf = ByteArray(8192)
    // Stop once we have cap+1 bytes: the (cap+1)th byte is enough to flag truncation
    // without buffering the rest of the response.
    val limit = cap.toLong() + 1
    var total = 0L
    while (total < limit) {
        val want = minOf(buf.size.toLong(), limit - total).toInt()
        val read = ins.read(buf, 0, want)
        if (read < 0) break
        out.write(buf, 0, read)
        total += read
    }
    val bytes = out.toByteArray()
    return bytes to (bytes.size > cap)
}

private val CHARSET_RE = Regex("""charset\s*=\s*["']?([^"';\s]+)""", RegexOption.IGNORE_CASE)

/**
 * Decode the first [len] bytes of [raw] using the charset declared in [contentType],
 * falling back to UTF-8 when it is absent, malformed, or unsupported on this device.
 * Decoding everything as UTF-8 mangles every non-UTF-8 page.
 */
internal fun decodeBody(raw: ByteArray, len: Int, contentType: String?): String {
    val charset = contentType
        ?.let { CHARSET_RE.find(it)?.groupValues?.getOrNull(1) }
        ?.let { name -> runCatching { Charset.forName(name.trim()) }.getOrNull() }
        ?: Charsets.UTF_8
    return String(raw, 0, minOf(len, raw.size), charset)
}
