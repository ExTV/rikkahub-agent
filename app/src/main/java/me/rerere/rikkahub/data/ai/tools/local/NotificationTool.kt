package me.rerere.rikkahub.data.ai.tools.local

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.ai.tools.ToolInvocationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val CHANNEL_ID = "rikkahub_ai_tool"
private const val CHANNEL_NAME = "AI tool notifications"

// PendingIntent request codes keyed by conversation id. String.hashCode() collides too
// easily for a FLAG_UPDATE_CURRENT PendingIntent (a tap could open the wrong
// conversation); a per-process registry guarantees distinct codes across different
// conversation ids while staying stable for the same id, so updates refresh in place.
private val conversationRequestCodes = ConcurrentHashMap<String, Int>()
private val nextConversationRequestCode = AtomicInteger(0)

private fun requestCodeForConversation(convId: String): Int =
    conversationRequestCodes.computeIfAbsent(convId) { nextConversationRequestCode.incrementAndGet() }

private fun ensureChannel(context: Context) {
    val channel = NotificationChannelCompat.Builder(
        CHANNEL_ID,
        NotificationManager.IMPORTANCE_DEFAULT
    )
        .setName(CHANNEL_NAME)
        .build()
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
}

fun notificationTool(
    context: Context,
    invocationContext: ToolInvocationContext = ToolInvocationContext.EMPTY,
    streamer: InteractiveToolStreamer = InteractiveToolStreamer.NoOp,
): Tool = Tool(
    name = "post_notification",
    description = """
        Post an Android notification on behalf of the user.
        Use sparingly — notifications are intrusive.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Notification title")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional notification body text")
                })
                put("id", buildJsonObject {
                    put("type", "integer")
                    put("description", "Optional notification id; defaults to a fresh auto-generated id")
                })
            },
            required = listOf("title")
        )
    },
    execute = {
        wakeScreenIfNeeded(context)
        val params = it.jsonObject
        val title = params["title"]?.jsonPrimitive?.contentOrNull
            ?: error("title is required")
        val body = params["body"]?.jsonPrimitive?.contentOrNull
        val idParam = params["id"]?.jsonPrimitive?.intOrNull
        val id = idParam?.coerceIn(0, Int.MAX_VALUE)
            ?: (System.currentTimeMillis() / 1000).toInt()

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            return@Tool listOf(
                UIMessagePart.Text(
                    buildJsonObject { put("error", "notification permission not granted") }.toString()
                )
            )
        }

        ensureChannel(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
        if (body != null) {
            builder.setContentText(body)
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        val convId = invocationContext.callerConversationId
        if (!convId.isNullOrBlank()) {
            val intent = Intent(context, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", convId)
            }
            val pi = PendingIntent.getActivity(
                context,
                requestCodeForConversation(convId),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(pi)
        }

        val payload = try {
            manager.notify(id, builder.build())
            buildJsonObject {
                put("success", true)
                put("id", id)
            }
        } catch (_: SecurityException) {
            buildJsonObject { put("error", "notification permission not granted") }
        }
        streamer.streamIfHeadless(invocationContext, "PostNotification: ${title.take(50)}")
        listOf(UIMessagePart.Text(payload.toString()))
    }
)
