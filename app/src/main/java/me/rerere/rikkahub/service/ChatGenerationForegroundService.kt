package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ChatGenerationFgs"

/**
 * Keeps an interactive generation alive after the UI is backgrounded. It owns no generation
 * state; [ChatService] starts it before a request begins and stops it after the final task ends.
 */
class ChatGenerationForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A stop request can race a subsequent Send. The shared desired-state flag makes a
        // delayed stale stop a no-op instead of tearing down the foreground service for the
        // newer generation.
        if (!shouldRun.get()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            releaseWakeLock()
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (!startForegroundCompat()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startForegroundCompat(): Boolean = try {
        val notification = NotificationCompat.Builder(this, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_live_update_title))
            .setContentText(getString(R.string.app_name))
            .setContentIntent(buildLaunchPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (error: Exception) {
        // Generation keeps running on the app scope when an OEM rejects the FGS request. The
        // failure is logged because the device's battery policy can then still interrupt it.
        Log.w(TAG, "Unable to start chat-generation foreground service", error)
        false
    }

    private fun buildLaunchPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        packageManager.getLaunchIntentForPackage(packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun acquireWakeLock() {
        val lock = wakeLock ?: getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "rikkahub:chat_generation")
            ?.also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
            ?: return
        if (!lock.isHeld) lock.acquire()
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }.onFailure { Log.w(TAG, "Unable to release chat-generation wake lock", it) }
        wakeLock = null
    }

    companion object {
        private const val ACTION_RECONCILE = "me.rerere.rikkahub.action.RECONCILE_CHAT_GENERATION_FGS"
        private const val NOTIFICATION_ID = 2002
        private val shouldRun = AtomicBoolean(false)

        fun start(context: Context) {
            shouldRun.set(true)
            runCatching {
                ContextCompat.startForegroundService(
                    context.applicationContext,
                    Intent(context, ChatGenerationForegroundService::class.java).apply {
                        action = ACTION_RECONCILE
                    },
                )
            }.onFailure { Log.w(TAG, "Unable to request chat-generation foreground service", it) }
        }

        fun stop(context: Context) {
            shouldRun.set(false)
            val serviceIntent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_RECONCILE
            }
            runCatching {
                // This delivers an ordered state reconciliation to an already-running FGS.
                // It avoids stopService() racing a new start and killing its service instance.
                context.applicationContext.startService(serviceIntent)
            }.recoverCatching {
                // If the platform refuses a normal background start, there is no active work
                // left according to the tracker, so a direct stop is safe as a last resort.
                context.applicationContext.stopService(serviceIntent)
            }.onFailure { Log.w(TAG, "Unable to stop chat-generation foreground service", it) }
        }
    }
}
