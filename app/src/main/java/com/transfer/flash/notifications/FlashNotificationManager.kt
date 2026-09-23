package com.transfer.flash.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.transfer.flash.MainActivity
import com.transfer.flash.R
import kotlin.concurrent.Volatile

/**
 * Bug 7: posts system notifications for inbound chat traffic (text messages and
 * accepted/auto-accepted attachments). Design doc: `docs/ui/notification-ui.md` (DESIGNED).
 *
 * ## Wiring
 * `DiscoveryEngineHolder` passes the repository's `onInboundTextMessage` /
 * `onInboundAttachment` callbacks through to [showMessage] / [showAttachment]. The
 * repository only fires them for rows Room actually INSERTED (insert result != -1), so
 * replayed frames after a reconnect can never double-notify.
 *
 * ## Suppression
 * [appForeground] + [openConversationId] are process-level state maintained by
 * `MainActivity` (onStart/onStop) and the shell's conversation open/close path. A message
 * for the conversation the user is currently reading on a foregrounded app is silent.
 *
 * ## Tap behavior
 * Tapping opens [MainActivity] with [EXTRA_CONVERSATION_ID]; the activity routes it into
 * the Compose shell which opens that conversation. No trampoline, no broadcast — a plain
 * activity PendingIntent with `FLAG_IMMUTABLE`.
 */
object FlashNotificationManager {

    private const val TAG = "NOTIFY"
    private const val CHANNEL_ID = "flash_messages"
    private const val NOTIFICATION_ID_BASE = 200
    /** Fixed slot for PTT pings: outside the per-conversation id range, collapses repeats. */
    private const val PTT_NOTIFICATION_ID = 310
    /** Tap-to-talk prompt slot (Phase 3): a backgrounded hardware press the app surfaces. */
    private const val PTT_TAP_TO_TALK_ID = 311

    /** Set true in MainActivity.onStart, false in onStop. */
    @Volatile
    var appForeground: Boolean = false

    /** The conversation currently open on screen, or null. Maintained by the shell. */
    @Volatile
    var openConversationId: String? = null

    fun showMessage(
        context: Context,
        conversationId: String,
        senderName: String?,
        text: String,
        groupTitle: String? = null,
    ) {
        val content = messageNotificationContent(conversationId, senderName, text, groupTitle)
        post(context, conversationId, content.title, content.body)
    }

    fun showAttachment(
        context: Context,
        conversationId: String,
        senderName: String?,
        fileName: String,
        mimeType: String,
        groupTitle: String? = null,
    ) {
        val content = attachmentNotificationContent(
            conversationId,
            senderName,
            fileName,
            mimeType,
            groupTitle,
        )
        post(context, conversationId, content.title, content.body)
    }

    /** Clears the notification for a conversation (e.g. the user just opened it). */
    fun clearConversation(context: Context, conversationId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationIdFor(conversationId))
        }
    }

    /**
     * PTT ping alert (v1): a paired device pressed its hardware PTT button. Single shared
     * slot — repeat pings collapse into one notification. Tap opens the app with no
     * conversation target. Best-effort like [post]: never crashes the receive path.
     */
    @SuppressLint("MissingPermission") // notify() is inside runCatching: a revoked POST_NOTIFICATIONS
    // is logged, never thrown. A pre-check would be wrong below API 33, where that permission does not exist.
    fun showPttPing(context: Context, senderName: String?) {
        val name = senderName?.ifBlank { null } ?: "Paired device"
        val appContext = context.applicationContext
        createChannel(appContext)
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            PTT_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_flash)
            .setContentTitle("PTT ping")
            .setContentText("$name pressed the PTT button")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(PTT_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "Failed to post PTT ping notification", it) }
    }

    /**
     * Tap-to-talk prompt (Phase 3): a hardware press arrived while the app was backgrounded
     * or unpermitted, so the session could not start directly. Tapping foregrounds the app
     * with [com.transfer.flash.core.ptt.PttSessionEngine.EXTRA_PTT_PRESS], and the shell
     * completes the press (permission prompt included). Best-effort like every post here.
     */
    @SuppressLint("MissingPermission") // notify() is inside runCatching: a revoked POST_NOTIFICATIONS
    // is logged, never thrown. A pre-check would be wrong below API 33, where that permission does not exist.
    fun showPttTapToTalk(context: Context) {
        val appContext = context.applicationContext
        createChannel(appContext)
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(com.transfer.flash.core.ptt.PttSessionEngine.EXTRA_PTT_PRESS, true)
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            PTT_TAP_TO_TALK_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_flash)
            .setContentTitle("PTT button pressed")
            .setContentText("Tap to start talking")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(PTT_TAP_TO_TALK_ID, notification)
        }.onFailure { Log.w(TAG, "Failed to post PTT tap-to-talk notification", it) }
    }

    /** Clears the tap-to-talk prompt (session started, or the shell consumed the press). */
    fun clearPttTapToTalk(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(PTT_TAP_TO_TALK_ID)
        }
    }

    @SuppressLint("MissingPermission") // notify() is inside runCatching: a revoked POST_NOTIFICATIONS
    // is logged, never thrown. A pre-check would be wrong below API 33, where that permission does not exist.
    private fun post(context: Context, conversationId: String, title: String, body: String) {
        // Suppression rule (see class doc): reading that exact conversation right now.
        if (appForeground && openConversationId == conversationId) return

        val appContext = context.applicationContext
        createChannel(appContext)

        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        // Stable per-conversation request code so a re-post refreshes the same PendingIntent
        // (FLAG_UPDATE_CURRENT) instead of stacking a second one.
        val contentIntent = PendingIntent.getActivity(
            appContext,
            notificationIdFor(conversationId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_flash)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            // Collapses this peer's previous notification instead of stacking N per conversation.
            .build()

        // Android 13+ can revoke POST_NOTIFICATIONS at any time; notify() then throws
        // SecurityException. Best-effort by design — never crash the receive path.
        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationIdFor(conversationId), notification)
        }.onFailure { Log.w(TAG, "Failed to post message notification", it) }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Incoming messages and files from nearby devices"
        }
        manager.createNotificationChannel(channel)
    }

    /** Stable id per conversation: same peer updates their own notification; peers stack. */
    private fun notificationIdFor(conversationId: String): Int =
        NOTIFICATION_ID_BASE + (conversationId.hashCode() and 0x7FFFFFFF) % 1000

    /** Intent extra carrying the conversation to open on notification tap. */
    const val EXTRA_CONVERSATION_ID = "com.transfer.flash.EXTRA_CONVERSATION_ID"
}

internal data class FlashNotificationContent(
    val title: String,
    val body: String,
)

internal fun messageNotificationContent(
    conversationId: String,
    senderName: String?,
    text: String,
    groupTitle: String?,
): FlashNotificationContent {
    val sender = senderName?.ifBlank { null } ?: conversationId
    val group = groupTitle?.ifBlank { null }
    return FlashNotificationContent(
        title = group ?: sender,
        body = if (group != null) "$sender: $text" else text,
    )
}

internal fun attachmentNotificationContent(
    conversationId: String,
    senderName: String?,
    fileName: String,
    mimeType: String,
    groupTitle: String?,
): FlashNotificationContent {
    val sender = senderName?.ifBlank { null } ?: conversationId
    val group = groupTitle?.ifBlank { null }
    val attachment = "${attachmentKind(mimeType)}: $fileName"
    return FlashNotificationContent(
        title = group ?: sender,
        body = if (group != null) "$sender: $attachment" else attachment,
    )
}

private fun attachmentKind(mimeType: String): String = when {
    mimeType.startsWith("image/") -> "Photo"
    mimeType.startsWith("video/") -> "Video"
    mimeType.startsWith("audio/") -> "Voice message"
    else -> "File"
}
