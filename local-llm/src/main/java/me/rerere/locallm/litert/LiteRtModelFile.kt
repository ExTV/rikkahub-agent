package me.rerere.locallm.litert

import android.util.Log
import java.io.File

private const val TAG = "LiteRtModelFile"

/**
 * Reads the context ceiling a `.litertlm` file declares about itself.
 *
 * # Why this exists
 *
 * `EngineConfig.maxNumTokens` is not a request the engine validates — it is a promise the
 * engine believes. Hand it a number larger than the KV cache the file was actually built
 * for and prefill runs off the end of that cache and faults inside the native executor:
 * `SIGSEGV` on the execution thread, no exception, process gone. It cannot be caught, only
 * avoided, so the real ceiling has to be known before the engine is configured.
 *
 * The curated [LiteRtModelDefaults] table cannot be the authority here. It is hand-written,
 * it was wrong (`qwen3_0_6b_mixed_int4` was listed at 4096 against a real ceiling of 2048),
 * and it says nothing at all about a model installed from a pasted URL. The file knows.
 *
 * # Format
 *
 * A `.litertlm` is `"LITERTLM"`, a version triple, then a FlatBuffer section table, then
 * the sections themselves. One section holds an `LlmMetadataProto`, whose field 5 is the
 * token limit and whose field 7 is the model's Jinja chat template.
 *
 * Rather than depend on the FlatBuffer schema (which is versioned, unpublished for this
 * container, and would need a generated parser), this scans the header for `(end, begin)`
 * offset pairs and then *validates each candidate by parsing it*: a span is only accepted
 * as the metadata once it decodes as a protobuf carrying a chat template. Wrong guesses
 * fail to parse and are skipped, so the scan cannot silently return a number that came
 * from some other section.
 *
 * Field 5 is optional. Files that omit it (the Gemma 4 family, and the `ekvNNNN`-named
 * builds that carry the ceiling in their filename instead) return null, and the caller
 * falls back to the filename marker and then to the curated table.
 */
internal object LiteRtModelFile {

    private val MAGIC = "LITERTLM".toByteArray(Charsets.US_ASCII)

    /** How much of the file head to inspect. Every observed metadata section starts at
     *  16 KiB and runs well under 32 KiB, so this covers them with room to spare. */
    private const val HEAD_BYTES = 64 * 1024

    /** Search window for the section table, and the largest span worth parsing. */
    private const val HEADER_SCAN_BYTES = 8 * 1024
    private const val MAX_SECTION_BYTES = 2_000_000L

    /** Protobuf field numbers within `LlmMetadataProto`. */
    private const val FIELD_MAX_TOKENS = 5
    private const val FIELD_CHAT_TEMPLATE = 7

    /** Bounds a declared ceiling has to fall in to be believable. */
    private val PLAUSIBLE_TOKENS = 128..(1 shl 20)

    /**
     * The token ceiling [file] declares, or null when it declares none or cannot be read.
     * Never throws: an unreadable or unexpected file just means "unknown ceiling".
     */
    fun readDeclaredMaxTokens(file: File): Int? = runCatching {
        val head = ByteArray(HEAD_BYTES)
        val read = file.inputStream().use { it.read(head) }
        if (read < 64 || !head.startsWith(MAGIC)) return@runCatching null

        val fileLength = file.length()
        val limit = minOf(HEADER_SCAN_BYTES, read - 16)
        // Sections are recorded as a pair of absolute u64 offsets. Collect every pair that
        // could plausibly be one, smallest first: the metadata sits ahead of the weights.
        val spans = sortedSetOf<Pair<Long, Long>>(compareBy({ it.first }, { it.second }))
        var offset = 24
        while (offset < limit) {
            val end = head.readLongLe(offset)
            val begin = head.readLongLe(offset + 8)
            val span = end - begin
            if (begin > 0 && span in 1..MAX_SECTION_BYTES && end <= fileLength) {
                spans.add(begin to end)
            }
            offset += 4
        }

        for ((begin, end) in spans) {
            if (end > read) continue // beyond the head we read; not the metadata section
            val fields = parseTopLevelFields(head, begin.toInt(), end.toInt()) ?: continue
            // Only trust a span that really is the metadata: it must carry the chat
            // template. Without this check an unrelated section that happens to decode
            // could donate a bogus "ceiling".
            val template = fields[FIELD_CHAT_TEMPLATE] as? ByteArray ?: continue
            if (!template.containsJinja()) continue
            val declared = fields[FIELD_MAX_TOKENS] as? Long ?: return@runCatching null
            return@runCatching declared.toInt().takeIf { it in PLAUSIBLE_TOKENS }
        }
        null
    }.onFailure {
        Log.w(TAG, "readDeclaredMaxTokens failed for ${file.name}", it)
    }.getOrNull()

    /**
     * Decode the top-level protobuf fields in `bytes[from, to)`. Returns null if the range
     * is not well-formed protobuf, which is how a wrongly-guessed span gets rejected.
     * Varints come back as [Long], length-delimited fields as [ByteArray]; only the first
     * occurrence of a field is kept, which is all this needs.
     */
    private fun parseTopLevelFields(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): Map<Int, Any>? {
        val fields = mutableMapOf<Int, Any>()
        var i = from
        while (i < to) {
            val (key, afterKey) = bytes.readVarint(i, to) ?: return null
            i = afterKey
            val fieldNumber = (key ushr 3).toInt()
            if (fieldNumber == 0) return null
            when ((key and 7L).toInt()) {
                0 -> {
                    val (value, next) = bytes.readVarint(i, to) ?: return null
                    fields.putIfAbsent(fieldNumber, value)
                    i = next
                }

                1 -> i += 8
                2 -> {
                    val (length, next) = bytes.readVarint(i, to) ?: return null
                    if (length < 0 || next + length > to) return null
                    fields.putIfAbsent(fieldNumber, bytes.copyOfRange(next, (next + length).toInt()))
                    i = (next + length).toInt()
                }

                5 -> i += 4
                else -> return null // groups / unknown wire types: not this format
            }
            if (i > to) return null
        }
        return fields
    }

    private fun ByteArray.readVarint(start: Int, limit: Int): Pair<Long, Int>? {
        var value = 0L
        var shift = 0
        var i = start
        while (i < limit) {
            val b = this[i].toInt() and 0xff
            i++
            value = value or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return value to i
            shift += 7
            if (shift > 63) return null
        }
        return null
    }

    private fun ByteArray.readLongLe(at: Int): Long {
        var value = 0L
        for (i in 7 downTo 0) {
            value = (value shl 8) or (this[at + i].toLong() and 0xff)
        }
        return value
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** Jinja delimiter every observed chat template contains. */
    private fun ByteArray.containsJinja(): Boolean {
        for (i in 0 until size - 1) {
            if (this[i] == '{'.code.toByte() && this[i + 1] == '%'.code.toByte()) return true
        }
        return false
    }
}
