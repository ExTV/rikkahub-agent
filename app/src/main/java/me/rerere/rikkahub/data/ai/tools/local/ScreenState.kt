package me.rerere.rikkahub.data.ai.tools.local

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.service.RikkaAccessibilityService

// Pure core of the post-action screen-state envelope. Everything in this section is
// JVM-testable: no framework calls, no Context.

/** FNV-1a 64-bit over the UTF-8 bytes of each part, order sensitive, with a separator
 *  fold between parts so ["ab","c"] and ["a","bc"] hash differently. */
internal fun fnv1a64(parts: Iterable<String>): Long {
    var hash = -0x340d631b7bdddcdbL // 0xcbf29ce484222325, FNV offset basis
    val prime = 0x100000001b3L
    for (part in parts) {
        for (b in part.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (b.toLong() and 0xff)
            hash *= prime
        }
        hash = hash xor 0x1f
        hash *= prime
    }
    return hash
}

/** The facts about one on-screen window that the surface heuristics need. */
internal data class SurfaceFacts(
    val pkg: String,
    val type: Int,
    val top: Int,
    val bottom: Int,
)

/** The notification shade (or quick settings) is a SystemUI-owned TYPE_SYSTEM window
 *  covering more than half the display height. Status bar alone is far smaller. */
internal fun shadeOpen(windows: List<SurfaceFacts>, displayHeight: Int): Boolean =
    windows.any {
        it.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
            it.pkg.contains("systemui") &&
            (it.bottom - it.top) > displayHeight / 2
    }

internal fun imeVisible(windows: List<SurfaceFacts>): Boolean =
    windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

/** Parses the "windowId:traversalIndex" node_id emitted by read_window_tree.
 *  Traversal indices are 1-based (the service counts nodes from 1). */
internal fun parseNodeId(raw: String): Pair<Int, Int>? {
    val parts = raw.split(":")
    if (parts.size != 2) return null
    val windowId = parts[0].toIntOrNull() ?: return null
    val traversalIndex = parts[1].toIntOrNull() ?: return null
    if (traversalIndex < 1) return null
    return windowId to traversalIndex
}

/** Negative coordinates are rejected earlier by the tools; this catches the far edges. */
internal fun coordsOutOfBounds(x: Double, y: Double, displayW: Int, displayH: Int): Boolean =
    x >= displayW || y >= displayH

/**
 * Waits until [quietMs] elapse with no window event, or [timeoutMs] total. [floor] is the
 * uptime at which the action was dispatched: the quiet window is measured from
 * max(lastEvent(), floor) so a stale lastEvent cannot satisfy the wait instantly.
 * Returns true if the UI went quiet, false on timeout.
 */
internal suspend fun awaitQuiet(
    quietMs: Long,
    timeoutMs: Long,
    now: () -> Long,
    lastEvent: () -> Long,
    floor: Long,
): Boolean {
    val start = now()
    while (true) {
        val n = now()
        if (n - maxOf(lastEvent(), floor) >= quietMs) return true
        if (n - start >= timeoutMs) return false
        delay(25)
    }
}

// Framework half: capture from the live service. Kept out of the pure core above so the
// core stays JVM-testable.

private const val SETTLE_QUIET_MS = 300L
private const val SETTLE_TIMEOUT_MS = 2_000L
private const val HASH_NODE_CAP = 500

internal fun surfaceFactsOf(svc: RikkaAccessibilityService): List<SurfaceFacts> =
    try {
        svc.windows.orEmpty().map { w ->
            val r = Rect()
            w.getBoundsInScreen(r)
            SurfaceFacts(
                pkg = w.root?.packageName?.toString().orEmpty(),
                type = w.type,
                top = r.top,
                bottom = r.bottom,
            )
        }
    } catch (t: Throwable) {
        Log.w("ScreenState", "getWindows failed: ${t.message}")
        emptyList()
    }

/** Hash of the filtered node tree; null when there is no active window. */
internal fun treeHash(svc: RikkaAccessibilityService): Long? {
    val root = svc.rootInActiveWindow ?: return null
    val parts = mutableListOf<String>()
    val rect = Rect()
    svc.traverseTree(
        root = root,
        filter = ::defaultFilter,
        cap = HASH_NODE_CAP,
        emit = { n, _, _ ->
            n.getBoundsInScreen(rect)
            parts.add(
                "${n.className}|${n.text}|${n.contentDescription}|" +
                    "${rect.left},${rect.top},${rect.right},${rect.bottom}"
            )
        },
        recycle = true,
    )
    return fnv1a64(parts)
}

/**
 * Compact screen-state object. screenChanged: true/false when both hashes were captured,
 * null to omit the field (observation tools, capture failure). False-y fields are omitted
 * to save tokens; "display" is always present so the model knows the coordinate space.
 */
internal fun screenStateJson(
    svc: RikkaAccessibilityService,
    screenChanged: Boolean?,
    settleTimedOut: Boolean = false,
): JsonObject {
    val root = try { svc.rootInActiveWindow } catch (t: Throwable) { null }
    val windows = surfaceFactsOf(svc)
    val dm = svc.resources.displayMetrics
    val pm = svc.getSystemService(Context.POWER_SERVICE) as? PowerManager
    val km = svc.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    return buildJsonObject {
        put("package", JsonPrimitive(root?.packageName?.toString() ?: ""))
        root?.window?.title?.toString()?.takeIf { it.isNotEmpty() }?.let {
            put("window", JsonPrimitive(it))
        }
        if (shadeOpen(windows, dm.heightPixels)) put("shade_open", JsonPrimitive(true))
        if (imeVisible(windows)) put("ime_visible", JsonPrimitive(true))
        if (pm?.isInteractive == false) put("screen_on", JsonPrimitive(false))
        if (km?.isKeyguardLocked == true) put("keyguard_locked", JsonPrimitive(true))
        put("display", buildJsonObject {
            put("w", JsonPrimitive(dm.widthPixels))
            put("h", JsonPrimitive(dm.heightPixels))
        })
        if (screenChanged != null) put("screen_changed", JsonPrimitive(screenChanged))
        if (settleTimedOut) put("settle", JsonPrimitive("timeout"))
    }
}

/**
 * Wraps an action body: captures a before tree-hash, runs the action, waits for the UI to
 * go quiet, captures after-state and attaches it under "after". Error results ("error"
 * key present) pass through untouched. Never throws; envelope capture failures degrade to
 * screen_changed=null.
 */
internal suspend fun withActionEnvelope(
    svc: RikkaAccessibilityService,
    act: suspend (RikkaAccessibilityService) -> JsonObject,
): JsonObject {
    val before = try { treeHash(svc) } catch (t: Throwable) {
        Log.w("ScreenState", "before-hash failed: ${t.message}")
        null
    }
    val actionStart = SystemClock.uptimeMillis()
    val result = try {
        act(svc)
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Log.w("ScreenState", "action threw: ${t.message}", t)
        buildJsonObject {
            put("error", "tool_exception")
            put("message", t.message ?: t::class.java.simpleName)
        }
    }
    if (result.containsKey("error")) return result
    val quiet = awaitQuiet(
        quietMs = SETTLE_QUIET_MS,
        timeoutMs = SETTLE_TIMEOUT_MS,
        now = { SystemClock.uptimeMillis() },
        lastEvent = { svc.lastWindowEventUptime },
        floor = actionStart,
    )
    val after = try { treeHash(svc) } catch (t: Throwable) {
        Log.w("ScreenState", "after-hash failed: ${t.message}")
        null
    }
    val changed = if (before != null && after != null) before != after else null
    val state = screenStateJson(svc, screenChanged = changed, settleTimedOut = !quiet)
    return JsonObject(result + ("after" to state))
}
