package me.rerere.rikkahub.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "RikkaAccService"
private const val GESTURE_TIMEOUT_MS = 5_000L
private const val LOG_RING_SIZE = 50

data class ActionLogEntry(
    val type: String,
    val paramsSummary: String,
    val success: Boolean,
    val timestampMs: Long,
)

class RikkaAccessibilityService : AccessibilityService() {

    private val gestureExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "RikkaAcc-Gesture").apply { isDaemon = true }
    }
    private val gestureHandlerThread = HandlerThread("RikkaAcc-Callback").apply { start() }
    private val gestureHandler = Handler(gestureHandlerThread.looper)

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private val _lastActions = MutableStateFlow<List<ActionLogEntry>>(emptyList())
    val lastActions = _lastActions.asStateFlow()

    // Uptime of the last window state/content change event. ScreenState.awaitQuiet polls
    // this to detect UI settle after an action.
    @Volatile
    var lastWindowEventUptime: Long = 0L
        private set

    // Serialises overlapping gesture-dispatch callers (the OS rejects overlapping
    // dispatchGesture calls). Mutex.withLock releases correctly when a waiter is
    // cancelled mid-wait, which the previous ticket counter did not.
    private val gestureMutex = Mutex()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _running.value = true
        Log.i(TAG, "AccessibilityService connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        _running.value = false
        _lastActions.value = emptyList()
        Log.i(TAG, "AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        _running.value = false
        gestureHandlerThread.quitSafely()
        gestureExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // The service config only subscribes to typeWindowStateChanged|typeWindowContentChanged,
        // so any event delivery means the UI moved.
        if (event != null) {
            lastWindowEventUptime = android.os.SystemClock.uptimeMillis()
        }
        // Phase 12 — feed foreground-app transitions to the workflow trigger dispatcher.
        // We only care about TYPE_WINDOW_STATE_CHANGED and only when the package name is
        // present. The dispatcher itself de-dupes (skips no-op transitions) and dispatches
        // off-thread, so this stays fast on the AccessibilityService dispatcher.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank()) {
                me.rerere.rikkahub.workflow.trigger.AppForegroundDispatcher.onForegroundChange(pkg)
            }
        }
    }

    override fun onInterrupt() {
        // Required override; no-op.
    }

    fun appendLog(entry: ActionLogEntry) {
        val current = _lastActions.value
        val next = (current + entry).takeLast(LOG_RING_SIZE)
        _lastActions.value = next
    }

    /**
     * Dispatches a gesture and suspends until it completes / cancels / times out (5s).
     */
    suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean =
        gestureMutex.withLock {
            withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val callback = object : GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription) {
                            if (cont.isActive) cont.resume(true)
                        }
                        override fun onCancelled(d: GestureDescription) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    val dispatched = dispatchGesture(gesture, callback, gestureHandler)
                    if (!dispatched && cont.isActive) cont.resume(false)
                }
            } ?: false
        }

    fun buildTapPath(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    fun buildSwipePath(sx: Float, sy: Float, ex: Float, ey: Float): Path = Path().apply {
        moveTo(sx, sy)
        lineTo(ex, ey)
    }

    /**
     * Walk the active window's node tree depth-first (preorder, children in order).
     * `filter` decides whether to emit a node; traversal stops once `cap` nodes have been
     * emitted. traversalIndex counts every node seen (1-based), independent of the filter.
     * Depth is capped at 60 to survive pathological trees (deep WebView/Compose nesting).
     *
     * recycle=true recycles every visited non-root node on API < 33 after its subtree is
     * done (a no-op on 33+ where nodes are not pooled). Callers passing recycle=true MUST
     * NOT retain nodes past the emit callback; serialise or copy inside emit.
     *
     * Returns (emitted, totalSeen, truncated).
     */
    fun traverseTree(
        root: AccessibilityNodeInfo,
        filter: (AccessibilityNodeInfo, depth: Int) -> Boolean,
        cap: Int,
        emit: (AccessibilityNodeInfo, depth: Int, traversalIndex: Int) -> Unit,
        recycle: Boolean = false,
    ): Triple<Int, Int, Boolean> {
        val maxDepth = 60
        var emitted = 0
        var seen = 0
        var truncated = false
        val canRecycle = recycle && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        stack.addLast(root to 0)
        while (stack.isNotEmpty()) {
            val (n, depth) = stack.removeLast()
            seen++
            if (filter(n, depth)) {
                emit(n, depth, seen)
                emitted++
                if (emitted >= cap) truncated = true
            }
            if (!truncated && depth < maxDepth) {
                for (i in n.childCount - 1 downTo 0) {
                    val child = n.getChild(i) ?: continue
                    stack.addLast(child to depth + 1)
                }
            }
            if (canRecycle && n !== root) {
                @Suppress("DEPRECATION")
                n.recycle()
            }
            if (truncated) break
        }
        if (canRecycle) {
            // Drain children queued but never visited (truncation / depth-cap leftovers).
            while (stack.isNotEmpty()) {
                val (n, _) = stack.removeLast()
                if (n !== root) {
                    @Suppress("DEPRECATION")
                    n.recycle()
                }
            }
        }
        return Triple(emitted, seen, truncated)
    }

    /**
     * Walks up the parent chain looking for a clickable node. Returns the original if it's
     * already clickable, or the nearest clickable ancestor, or null if none exists.
     */
    fun resolveClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    /**
     * Captures a screenshot of the given display. Suspends until callback fires; returns
     * ScreenshotOutcome.Success(softwareBitmap) or Failure(reason). The success bitmap is a
     * software bitmap (ARGB_8888) — caller MUST call bitmap.recycle() when done to free
     * native memory. Returns Failure("api_too_low") on Android < 11 (API 30).
     */
    suspend fun captureScreenshot(displayId: Int): ScreenshotOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return ScreenshotOutcome.Failure("api_too_low")
        }
        return suspendCancellableCoroutine { cont ->
            takeScreenshot(
                displayId,
                gestureExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        try {
                            val bmp = try {
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            } finally {
                                result.hardwareBuffer.close()
                            }
                            if (cont.isActive) {
                                cont.resume(
                                    if (bmp != null) ScreenshotOutcome.Success(bmp)
                                    else ScreenshotOutcome.Failure("bitmap_decode_failed")
                                )
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resume(ScreenshotOutcome.Failure("exception:${t.message}"))
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        val reason = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "rate_limited"
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "no_access"
                            ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "internal_error"
                            else -> "error_code_$errorCode"
                        }
                        if (cont.isActive) cont.resume(ScreenshotOutcome.Failure(reason))
                    }
                }
            )
        }
    }

    sealed class ScreenshotOutcome {
        data class Success(val bitmap: Bitmap) : ScreenshotOutcome()
        data class Failure(val reason: String) : ScreenshotOutcome()
    }

    companion object {
        @Volatile
        var instance: RikkaAccessibilityService? = null
            private set
    }
}
