package me.rerere.llamacpp

import org.json.JSONObject

data class ParsedToolCall(val id: String, val name: String, val arguments: String)

data class ChatDelta(
    val textDelta: String,
    val reasoningDelta: String,
    val completedToolCalls: List<ParsedToolCall>,
    val textReset: Boolean = false,
    val reasoningReset: Boolean = false,
)

/**
 * Converts successive whole-message parses into incremental deltas.
 *
 * llama.cpp re-parses the entire generated text each time, so every parse restates
 * everything seen so far. The chat surface needs only what is new.
 */
class ChatDeltaTracker {

    private var emittedText = ""
    private var emittedReasoning = ""
    private val emittedCalls = mutableSetOf<String>()

    /**
     * Consumes one whole-message parse and returns what changed since the last call.
     *
     * [ChatDelta.textReset] and [ChatDelta.reasoningReset]: when a reset flag is true,
     * the consumer MUST discard whatever it has accumulated for that channel and start
     * again from this delta's value, instead of appending it. This exists because a
     * streaming re-parse can reclassify text it already reported: most commonly, a
     * reasoning model's `<think>` block is first reported as content until the closing
     * tag arrives, at which point the parser reassigns that text to reasoning and
     * content restarts from the real answer. A plain append-only delta cannot express
     * "what I told you before is no longer true", so the channel resets instead.
     *
     * A tool call's arguments are emitted the first time they parse as complete JSON: a
     * top-level JSON object cannot parse until its closing brace arrives, so completeness
     * already means the arguments are finished. No stability check across repeated
     * parses is needed to confirm that.
     *
     * The caller MUST pass [isPartial] = false exactly once, on the final parse. When it
     * is false, any not-yet-emitted call whose arguments never became complete JSON (the
     * model was cut off mid-call, e.g. by max_tokens) is flushed with its raw, incomplete
     * arguments string instead of being dropped silently, so a truncated call fails
     * loudly in the tool executor rather than the assistant appearing to do nothing. A
     * call with no arguments at all is not flushed, since there is nothing to surface.
     */
    fun consume(parsedJson: String, isPartial: Boolean = true): ChatDelta {
        val obj = JSONObject(parsedJson)
        val content = obj.optString("content", "")
        val reasoning = obj.optString("reasoning_content", "")

        val (textDelta, newEmittedText, textReset) = advance(emittedText, content)
        val (reasoningDelta, newEmittedReasoning, reasoningReset) = advance(emittedReasoning, reasoning)
        emittedText = newEmittedText
        emittedReasoning = newEmittedReasoning

        val completed = mutableListOf<ParsedToolCall>()
        val calls = obj.optJSONArray("tool_calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val call = calls.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: continue
                // optString(key, fallback) only falls back when the key is absent or
                // JSON null, not when it is present but blank. A model that emits an
                // empty "id" for every call would otherwise collapse them all onto the
                // same generated id, silently dropping every call after the first.
                val id = call.optString("id", "").ifBlank { "call_$i" }
                if (emittedCalls.contains(id)) {
                    continue
                }
                val arguments = fn.optString("arguments", "")
                val complete = isCompleteJson(arguments)
                val shouldEmit = complete || (!isPartial && arguments.isNotBlank())
                if (!shouldEmit) {
                    continue
                }

                emittedCalls += id
                completed += ParsedToolCall(id, fn.optString("name", ""), arguments)
            }
        }

        return ChatDelta(textDelta, reasoningDelta, completed, textReset, reasoningReset)
    }

    /**
     * The new text minus what was already emitted, the watermark to carry into the next
     * call, and whether the channel reset. When the new content extends what was already
     * emitted, this is the ordinary case: the suffix is the delta, the watermark advances
     * to the new content, no reset. When it does not extend (a re-parse that shrank or
     * diverged), the previously emitted text is no longer a valid prefix of the truth, so
     * the entire new content is emitted as the delta, the watermark becomes the new
     * content, and the channel resets. Freezing the watermark instead (never emitting
     * again once a non-extension is seen) was tried and rejected: it turns a single
     * non-extending parse into permanent silent content loss for that channel, which is
     * worse than the duplication it was meant to prevent.
     */
    private fun advance(previouslyEmitted: String, current: String): Triple<String, String, Boolean> = when {
        current == previouslyEmitted -> Triple("", previouslyEmitted, false)
        current.startsWith(previouslyEmitted) -> Triple(current.substring(previouslyEmitted.length), current, false)
        else -> Triple(current, current, true)
    }

    private fun isCompleteJson(value: String): Boolean {
        if (value.isBlank()) return false
        return runCatching { JSONObject(value) }.isSuccess
    }
}
