package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.net.hostIsBlockedLiteral
import me.rerere.rikkahub.data.ai.net.withEgressGuard
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

private const val WEB_EXTRACT_TIMEOUT_MS = 30_000L

/**
 * Read a web page as text. A thin wrapper over the same extraction path as
 * `web_fetch(extract_mode=...)`; it exists as its own tool because a model looking to
 * *read* a page reaches for a reading verb, not for a transport primitive.
 */
fun webExtractTool(client: OkHttpClient): Tool = Tool(
    name = "web_extract",
    description = """
        Read a web page and return its readable content with navigation, ads and boilerplate
        removed. mode: 'article' (default, main prose), 'text' (all body text), 'links',
        or 'metadata'. max_chars caps the result (default 32768); when truncated=true pass
        next_start_index back as start_index to continue reading. Use this instead of
        web_fetch when you want to read a page rather than inspect its markup. Pages that
        build their content with JavaScript may return empty_extraction, use the browser
        tools for those. Returns {status, final_url, title, text, truncated,
        next_start_index} or {error, detail, recovery}.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "The http:// or https:// URL to read")
                })
                put("mode", buildJsonObject {
                    put("type", "string")
                    put("description", "article (default), text, links, or metadata")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "Maximum characters of text to return")
                })
                put("start_index", buildJsonObject {
                    put("type", "integer")
                    put("description", "Resume offset; pass next_start_index from a truncated result")
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
                fmErrEnvelope("bad_url", "url must start with http:// or https://"),
            )
        }
        // OkHttp routes a literal-IP host straight to the socket without consulting the
        // GuardedDns wrapper, so refuse a private/loopback/link-local literal deterministically
        // here; withEgressGuard's interceptor still covers literal redirect hops.
        url.toHttpUrlOrNull()?.host?.let { host ->
            if (hostIsBlockedLiteral(host)) {
                return@Tool fmTextPart(
                    buildJsonObject {
                        put("error", "blocked_address")
                        put("detail", "blocked_private_address: $host")
                        put(
                            "recovery",
                            "This tool refuses private, loopback and link-local addresses.",
                        )
                    }.toString()
                )
            }
        }

        val mode = parseExtractModeOrNull(obj["mode"]?.jsonPrimitive?.contentOrNull ?: "article")
            ?: return@Tool fmTextPart(
                fmErrEnvelope("bad_mode", "mode must be one of article, text, links, metadata"),
            )
        if (mode == FetchExtract.RAW) {
            return@Tool fmTextPart(
                fmErrEnvelope(
                    "bad_mode",
                    "web_extract does not serve raw markup; use web_fetch with extract_mode='raw'",
                ),
            )
        }

        val startIndex = obj["start_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val maxChars = obj["max_chars"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?.coerceIn(1, WEB_FETCH_EXTRACT_CAP) ?: WEB_FETCH_EXTRACT_CAP

        val guarded = client.withEgressGuard()

        val result = withTimeoutOrNull(WEB_EXTRACT_TIMEOUT_MS) {
            try {
                guarded.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                    val (raw, bodyTruncated) = readBounded(resp.body.byteStream(), WEB_FETCH_EXTRACT_CAP * 8)
                    val contentType = resp.header("Content-Type")
                    buildExtractEnvelope(
                        status = resp.code,
                        ok = resp.isSuccessful,
                        finalUrl = resp.request.url.toString(),
                        html = decodeBody(raw, raw.size, contentType),
                        contentType = contentType,
                        mode = mode,
                        maxChars = maxChars,
                        startIndex = startIndex,
                        bodyTruncated = bodyTruncated,
                        headers = null,
                    )
                }
            } catch (e: java.io.InterruptedIOException) {
                // OkHttp's callTimeout (set in withEgressGuard) fires this when a call, including
                // a trickling read, runs past the advertised 30s limit; withTimeoutOrNull cannot
                // catch this itself since the blocking execute() call has no suspension point.
                buildJsonObject {
                    put("error", "timeout")
                    put("detail", "Request exceeded the 30s limit.")
                    put("recovery", "The host is slow or unreachable; try a different URL.")
                }.toString()
            } catch (e: IOException) {
                val blocked = e.message?.contains("blocked_private_address") == true
                buildJsonObject {
                    put("error", if (blocked) "blocked_address" else "network_error")
                    put("detail", e.message ?: e::class.java.simpleName)
                    put(
                        "recovery",
                        if (blocked) {
                            "This tool refuses private, loopback and link-local addresses."
                        } else {
                            "Check connectivity and that the host is reachable, then retry."
                        },
                    )
                }.toString()
            }
        } ?: buildJsonObject {
            put("error", "timeout")
            put("detail", "Request exceeded the 30s limit.")
            put("recovery", "The host is slow or unreachable; try a different URL.")
        }.toString()

        fmTextPart(result)
    },
)
