package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression test for the upstream-backup restore path: an assistant config exported by a build
 * with a different tool set (e.g. upstream RikkaHub, which has `screen_time`) must not abort the
 * whole settings restore. [LenientLocalToolListSerializer] drops tool types this build does not
 * define while keeping the known ones, and leaves encoding untouched.
 */
class LenientLocalToolListSerializerTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun decode_dropsUnknownToolTypes_keepsKnownOnesInOrder() {
        // `screen_time` is an upstream-only tool the fork removed; the rest are fork tools.
        val decoded = json.decodeFromString(
            LenientLocalToolListSerializer,
            """[{"type":"time_info"},{"type":"tts"},{"type":"screen_time"},{"type":"ask_user"}]""",
        )
        assertEquals(
            listOf(LocalToolOption.TimeInfo, LocalToolOption.Tts, LocalToolOption.AskUser),
            decoded,
        )
    }

    @Test
    fun decode_allUnknown_yieldsEmptyListNotCrash() {
        val decoded = json.decodeFromString(
            LenientLocalToolListSerializer,
            """[{"type":"screen_time"},{"type":"some_future_tool"}]""",
        )
        assertEquals(emptyList<LocalToolOption>(), decoded)
    }

    @Test
    fun encode_matchesStrictListSerializer() {
        val tools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Clipboard)
        val lenient = json.encodeToString(LenientLocalToolListSerializer, tools)
        val strict = json.encodeToString(ListSerializer(LocalToolOption.serializer()), tools)
        assertEquals(strict, lenient)
    }

    @Test
    fun `web_fetch and web_extract are dropped, rest of the list survives`() {
        // web_fetch/web_extract moved from per-assistant LocalToolOption entries to a single
        // global setting, so they are no longer defined subtypes. An assistant persisted before
        // this change may still carry them in its tool list; the restore must not abort and must
        // keep the other, still-known entries in order.
        val decoded = json.decodeFromString(
            LenientLocalToolListSerializer,
            """[{"type":"time_info"},{"type":"web_fetch"},{"type":"web_extract"},{"type":"ask_user"}]""",
        )

        assertEquals(
            listOf(LocalToolOption.TimeInfo, LocalToolOption.AskUser),
            decoded,
        )
    }

    @Test
    fun `settings containing only removed web_fetch and web_extract decode to an empty list`() {
        val decoded = json.decodeFromString(
            LenientLocalToolListSerializer,
            """[{"type":"web_fetch"},{"type":"web_extract"}]""",
        )

        assertEquals(emptyList<LocalToolOption>(), decoded)
    }
}
