package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.WEB_SERVER_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.web.WebServerManager
import org.koin.android.ext.android.inject

private const val TAG = "WebServerService"

class WebServerService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.WEB_SERVER_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.WEB_SERVER_STOP"
        const val EXTRA_PORT = "port"
        const val EXTRA_LOCALHOST_ONLY = "localhost_only"
        const val NOTIFICATION_ID = 2001

        /**
         * Whether the state observer should stop the service for a terminal error.
         *
         * [startId] is the id carried on the emitted state; [baselineStartId] is the id
         * that was already current when this observer subscribed. WebServerManager is a
         * Koin single, so its StateFlow keeps a failed attempt's terminal state (error set,
         * isLoading false) around after the service instance that saw it is gone, and a
         * fresh ACTION_START's collector replays that stale value as its first emission.
         * Comparing ids - not "did this collector observe an isLoading=true emission
         * first" - is required because WebServerManager.start()'s isLoading=true write and
         * its terminal error/success write happen back to back with no suspension point
         * between them, so on a shared Main-dispatcher StateFlow the collector can be
         * scheduled only after both writes have happened and never see the intermediate
         * isLoading=true state at all (StateFlow conflates); the id still changes on the
         * final emission regardless.
         */
        fun shouldStopOnError(error: String?, isLoading: Boolean, startId: Long, baselineStartId: Long): Boolean =
            error != null && !isLoading && startId != baselineStartId
    }

    private val webServerManager: WebServerManager by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 8080)
                val localhostOnly = intent.getBooleanExtra(EXTRA_LOCALHOST_ONLY, false)
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startObservingState()
                webServerManager.start(port = port, localhostOnly = localhostOnly)
            }

            ACTION_STOP -> {
                webServerManager.stop()
                serviceScope.launch {
                    settingsStore.update { it.copy(webServerEnabled = false) }
                }
                // 不立即 stopSelf，等状态流检测到停止后再结束
            }

            null -> {
                // 兜底：intent 为 null 时根据设置决定是否启动
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                serviceScope.launch {
                    val settings = settingsStore.settingsFlowRaw.first()
                    if (settings.webServerEnabled) {
                        startObservingState()
                        webServerManager.start(
                            port = settings.webServerPort,
                            localhostOnly = settings.webServerLocalhostOnly
                        )
                    } else {
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildStartingNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildStartingNotification())
            }
            true
        } catch (e: Exception) {
            // 部分 OEM ROM (如 realme UI/ColorOS) 会在系统侧拒绝 FGS 类型权限，
            // 即使 Manifest 已声明 FOREGROUND_SERVICE_SPECIAL_USE 也会抛 SecurityException
            Log.e(TAG, "Failed to start foreground service", e)
            webServerManager.reportError("Failed to start foreground service: ${e.message}")
            false
        }
    }

    private fun startObservingState() {
        // Check `isActive`, not just non-null: after a previous observer finishes (server
        // stopped → stopSelf → collect returns), the Job reference is still set but
        // completed. With the old != null check, a fresh ACTION_START on the same service
        // instance never re-observed. Tied to onDestroy cancelling the scope, this was
        // safe in practice but the check should match what we actually mean.
        if (stateObserverJob?.isActive == true) return
        // Read synchronously, before the collector subscribes or webServerManager.start() is
        // called: this is the id left over from whatever attempt (if any) last wrote to the
        // shared state, and every state this attempt's start() writes will carry a new id.
        val baselineStartId = webServerManager.state.value.startId
        stateObserverJob = serviceScope.launch {
            var wasRunning = false
            webServerManager.state.collect { state ->
                when {
                    state.isRunning -> {
                        wasRunning = true
                        val host = if (state.localhostOnly) "localhost" else (state.address ?: "localhost")
                        val url = "http://$host:${state.port}"
                        updateNotification(buildRunningNotification(url))
                    }

                    // A start() failure (port already in use, FGS type rejected by the OEM,
                    // etc.) never sets isRunning=true, so the wasRunning-gated branch below
                    // never fires and the "starting" notification would otherwise stay pinned
                    // forever with no way for the user to dismiss it. Gated on the state's
                    // startId (see shouldStopOnError) so a fresh collector doesn't treat a
                    // stale error left over from a previous failed attempt as this attempt's
                    // result.
                    shouldStopOnError(state.error, state.isLoading, state.startId, baselineStartId) -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }

                    wasRunning && !state.isRunning && !state.isLoading -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private fun updateNotification(notification: android.app.Notification) {
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildLaunchPendingIntent() = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildStartingNotification() = NotificationCompat.Builder(this, WEB_SERVER_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(R.drawable.small_icon)
        .setContentTitle(getString(R.string.notification_channel_web_server))
        .setContentText(getString(R.string.notification_web_server_starting))
        .setContentIntent(buildLaunchPendingIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    private fun buildRunningNotification(url: String): android.app.Notification {
        val stopIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, WEB_SERVER_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_web_server_running))
            .setContentText(url)
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, getString(R.string.notification_web_server_stop), stopPendingIntent)
            .build()
    }
}
