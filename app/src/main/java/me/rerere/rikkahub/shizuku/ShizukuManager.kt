package me.rerere.rikkahub.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.BuildConfig
import rikka.shizuku.Shizuku

private const val TAG = "ShizukuManager"

/** Package name of the Shizuku manager app, the only reliable "installed" proxy without a
 *  live binder (the service itself can keep running after the app is force-stopped on some
 *  ROMs, but if the package is gone there is nothing to grant permission from).
 *  `moe.shizuku.manager` is only the Java namespace / permission prefix, never an installed
 *  package; the real application id is `moe.shizuku.privileged.api` (verified with
 *  `pm list packages`). */
private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

/** Arbitrary request code: this app only ever makes one kind of Shizuku permission request. */
private const val PERMISSION_REQUEST_CODE = 8730

private const val BIND_TIMEOUT_MS = 10_000L

/** Extra slack on top of the caller's requested command timeout for the AIDL round-trip
 *  itself, so a remote process that somehow ignored its own timeout can't hang [exec]
 *  forever. */
private const val CALL_TIMEOUT_SLACK_MS = 5_000L

/**
 * Parse the raw JSON string [ShizukuUserService.exec] returns over the AIDL boundary back
 * into a [JsonObject]. A malformed response (crashed mid-write, wrong service version)
 * degrades to a structured `shizuku_bad_response` error carrying the raw text, rather than
 * throwing out of [ShizukuManager.exec]. Pure and Android-free, so it is unit-testable
 * without a device.
 */
internal fun parseExecResponse(raw: String): JsonObject =
    runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrElse {
        buildJsonObject {
            put("error", "shizuku_bad_response")
            put("raw", raw)
        }
    }

/**
 * Outcome of a bind attempt against [IShizukuUserService], see [ShizukuManager.ensureBound].
 * Distinguishes *why* a bind failed instead of collapsing every case to `null` (#45: a thrown
 * exception, a died binding and a plain timeout used to be reported identically as
 * `shizuku_bind_failed`).
 */
internal sealed interface BindResult {
    data class Connected(val service: IShizukuUserService) : BindResult

    sealed interface Failure : BindResult {
        val phase: String

        /** `Shizuku.bindUserService` threw synchronously. */
        data class BindThrew(val throwable: Throwable) : Failure {
            override val phase = "bind_threw"
        }

        /** [ServiceConnection.onBindingDied] fired before [ServiceConnection.onServiceConnected],
         *  or the latter fired with a null/dead binder (the remote process died between connect
         *  and the first `pingBinder()`) -- either way there is no usable service. */
        data object BindingDied : Failure {
            override val phase = "binding_died"
        }

        /** No connection callback arrived within [BIND_TIMEOUT_MS]. */
        data object Timeout : Failure {
            override val phase = "bind_timeout"
        }
    }
}

/**
 * Build the `shizuku_bind_failed` envelope for a failed bind attempt, carrying `phase` (which
 * of the three failure modes it was) and, for [BindResult.Failure.BindThrew], a `reason` naming
 * the throwable's class and message. Never a stack trace: that goes to logcat only, see
 * [ShizukuManager.startBind]. Pure, so unit-testable without the Shizuku SDK.
 */
internal fun bindFailedResponse(failure: BindResult.Failure): JsonObject = buildJsonObject {
    put("error", "shizuku_bind_failed")
    put(
        "recovery",
        "Could not bind the Shizuku user service. Retry; if it keeps failing, restart the Shizuku service and re-grant permission from Settings -> Shizuku.",
    )
    put("phase", failure.phase)
    if (failure is BindResult.Failure.BindThrew) {
        put("reason", "${failure.throwable::class.java.simpleName}: ${failure.throwable.message}")
    }
}

/**
 * Thin wrapper around the static [Shizuku] SDK object (dev.rikka.shizuku:api 13.1.5) plus the
 * bind/unbind lifecycle for [IShizukuUserService]. `Shizuku.newProcess` is private in this
 * artifact (verified with `javap -p`), so [exec] is built on `Shizuku.bindUserService` against
 * our own AIDL service instead, see [ShizukuUserService].
 *
 * A stateless `object` wrapping a global SDK singleton, mirroring
 * [me.rerere.rikkahub.data.ai.tools.local.TermuxIntegration] in shape: tool factories and the
 * settings page call it directly with a [Context], no DI needed.
 */
object ShizukuManager {

    // --- status --------------------------------------------------------------------------

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun status(context: Context): ShizukuStatus = ShizukuStatusMapper.compute(
        installed = isInstalled(context),
        binderAlive = isBinderAlive(),
        permissionGranted = isPermissionGranted(),
    )

    /**
     * Fires Shizuku's own consent flow. No-op if the binder isn't alive (there is nothing to
     * ask). The result arrives asynchronously via a registered
     * [Shizuku.OnRequestPermissionResultListener], not an Activity callback: callers must
     * register one (see [addRequestPermissionResultListener]) before calling this.
     *
     * Called from an explicit tap on the Settings -> Shizuku screen, and from the assistant's
     * Shizuku local-tool toggle when it's switched on, never at app start or on first chat.
     */
    fun requestPermission() {
        if (!isBinderAlive()) return
        runCatching { Shizuku.requestPermission(PERMISSION_REQUEST_CODE) }
    }

    // --- listeners: settings page wires these into a DisposableEffect --------------------

    fun addBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.addBinderReceivedListenerSticky(listener)
    }

    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.removeBinderReceivedListener(listener)
    }

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.addBinderDeadListener(listener)
    }

    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.removeBinderDeadListener(listener)
    }

    fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    // --- shizuku_exec: bind the AIDL user service and run a command ----------------------

    private val bindLock = Mutex()
    private var service: IShizukuUserService? = null
    private var connection: ServiceConnection? = null

    @Volatile
    private var bindWaiter: CompletableDeferred<BindResult>? = null

    @Volatile
    private var cachedArgs: Shizuku.UserServiceArgs? = null

    private fun argsFor(context: Context): Shizuku.UserServiceArgs = cachedArgs ?: Shizuku.UserServiceArgs(
        ComponentName(context.packageName, ShizukuUserService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shizuku")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE.toIntOrNull() ?: 1)
        .also { cachedArgs = it }

    /**
     * Run [command] with Shizuku's privileges (the shell UID). [timeoutMs] bounds the command
     * itself, enforced inside [ShizukuUserService], which destroys the process and returns a
     * partial-output envelope on timeout. This function adds [CALL_TIMEOUT_SLACK_MS] on top as
     * a belt-and-suspenders bound on the AIDL round-trip, in case the remote process hangs
     * beyond its own timeout (e.g. it was OOM-killed mid-write, or the binder died).
     *
     * Returns a structured error (see [ShizukuStatusMapper]) instead of throwing when Shizuku
     * isn't ready, the bind fails, or the call itself hangs, callers pass this straight back
     * as the tool result.
     */
    suspend fun exec(context: Context, command: String, timeoutMs: Int): JsonObject {
        ShizukuStatusMapper.errorFor(status(context))?.let { return it }
        val version = runCatching { Shizuku.getVersion() }.getOrNull()
            ?: return checkNotNull(ShizukuStatusMapper.errorFor(ShizukuStatus.NOT_RUNNING))
        if (version < 10) {
            return buildJsonObject {
                put("error", "shizuku_version_too_old")
                put("recovery", "This Shizuku server is too old to support bindUserService (needs API 10+). Update the Shizuku app.")
            }
        }
        val api = when (val bindResult = ensureBound(context)) {
            is BindResult.Connected -> bindResult.service
            is BindResult.Failure -> return bindFailedResponse(bindResult)
        }
        val raw = withTimeoutOrNull(timeoutMs + CALL_TIMEOUT_SLACK_MS) {
            runInterruptible(Dispatchers.IO) {
                runCatching { api.exec(command, timeoutMs) }
                    .onFailure { Log.e(TAG, "user service exec() threw", it) }
                    .getOrNull()
            }
        }
        if (raw == null) {
            resetBinding()
            return buildJsonObject {
                put("error", "shizuku_call_failed")
                put("recovery", "The Shizuku user service did not respond in time. It may have died; retry the call.")
            }
        }
        return parseExecResponse(raw)
    }

    private suspend fun ensureBound(context: Context): BindResult {
        bindLock.withLock { service?.let { return BindResult.Connected(it) } }
        val deferred: CompletableDeferred<BindResult>
        val needBind: Boolean
        bindLock.withLock {
            service?.let { return BindResult.Connected(it) }
            if (bindWaiter == null) {
                bindWaiter = CompletableDeferred()
                needBind = true
            } else {
                needBind = false
            }
            deferred = bindWaiter!!
        }
        if (needBind) startBind(context, deferred)
        return withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() } ?: BindResult.Failure.Timeout
    }

    private fun startBind(context: Context, deferred: CompletableDeferred<BindResult>) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val api = if (binder != null && binder.pingBinder()) {
                    IShizukuUserService.Stub.asInterface(binder)
                } else null
                runCatching {
                    runBlocking {
                        bindLock.withLock {
                            service = api
                            bindWaiter = null
                        }
                    }
                }
                if (!deferred.isCompleted) {
                    deferred.complete(
                        if (api != null) BindResult.Connected(api) else BindResult.Failure.BindingDied
                    )
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                Log.w(TAG, "shizuku user service disconnected")
                runCatching { runBlocking { resetBinding() } }
            }

            override fun onBindingDied(name: ComponentName?) {
                Log.w(TAG, "shizuku user service binding died")
                runCatching { runBlocking { resetBinding() } }
                if (!deferred.isCompleted) deferred.complete(BindResult.Failure.BindingDied)
            }
        }
        val result = runCatching { Shizuku.bindUserService(argsFor(context), conn) }
        if (result.isSuccess) {
            connection = conn
        } else {
            val t = result.exceptionOrNull()!!
            Log.e(TAG, "bindUserService threw", t)
            runCatching { runBlocking { bindLock.withLock { bindWaiter = null } } }
            if (!deferred.isCompleted) deferred.complete(BindResult.Failure.BindThrew(t))
        }
    }

    private suspend fun resetBinding() = bindLock.withLock {
        val conn = connection
        val args = cachedArgs
        service = null
        connection = null
        bindWaiter = null
        if (conn != null && args != null) {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
        }
    }
}
