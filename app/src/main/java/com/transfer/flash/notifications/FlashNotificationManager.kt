package com.transfer.flash.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.transfer.flash.MainActivity
import com.transfer.flash.R
import com.transfer.flash.core.transfer.TransferFailureText
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import java.io.File
import java.util.Locale
import kotlin.concurrent.Volatile

/**
 * Revamped system notifications manager for Flash.
 *
 * Handles:
 * - Inbound chat traffic (messages and attachments)
 * - File transfers: live progress in foreground service, swipeable completed/failed alerts with direct open
 * - PTT sessions & alerts: ping alerts, tap-to-talk, reliable dismissal upon stop
 * - Pairing requests: heads-up prompt when backgrounded
 *
 * ## Suppression
 * [appForeground] + [openConversationId] are process-level state maintained by
 * `MainActivity` (onStart/onStop) and the shell's conversation open/close path. A message
 * for the conversation the user is currently reading on a foregrounded app is silent.
 */
object FlashNotificationManager {

    private const val TAG = "NOTIFY"

    // Unified notification channels
    const val CHANNEL_MESSAGES = "flash_messages"
    const val CHANNEL_TRANSFERS = "flash_transfers"
    const val CHANNEL_PTT = "flash_ptt"
    const val CHANNEL_PAIRING = "flash_pairing"
    const val CHANNEL_CALLS = "flash_calls_v2"
    private const val LEGACY_CHANNEL_CALLS = "flash_calls"
    const val CHANNEL_DISCOVERY = "flash_discovery_bg"

    // Backward compatibility alias
    private const val CHANNEL_ID = CHANNEL_MESSAGES

    // Notification ID ranges
    const val NOTIFICATION_ID_BASE = 2000
    /** Fixed slot for PTT pings: collapses repeat pings. */
    const val PTT_NOTIFICATION_ID = 310
    /** Tap-to-talk prompt slot (Phase 3): a backgrounded hardware press the app surfaces. */
    const val PTT_TAP_TO_TALK_ID = 311
    /** Pairing request notification ID */
    const val PAIRING_NOTIFICATION_ID = 320
    /** Base ID for individual active/completed/failed transfer notifications */
    const val TRANSFER_NOTIFICATION_BASE = 3000
    const val TRANSFER_NOTIFICATION_SLOTS = 1000

    /** Actions */
    const val ACTION_VIEW_TRANSFERS = "com.transfer.flash.action.VIEW_TRANSFERS"
    const val ACTION_VIEW_NEARBY = "com.transfer.flash.action.VIEW_NEARBY"
    const val ACTION_OPEN_FILE = "com.transfer.flash.action.OPEN_FILE"

    /** Intent extras */
    const val EXTRA_CONVERSATION_ID = "com.transfer.flash.EXTRA_CONVERSATION_ID"
    const val EXTRA_PEER_DEVICE_ID = "com.transfer.flash.EXTRA_PEER_DEVICE_ID"
    const val EXTRA_TRANSFER_ID = "extra_transfer_id"
    const val EXTRA_FILE_PATH = "extra_file_path"

    /** Set true in MainActivity.onStart, false in onStop. */
    @Volatile
    var appForeground: Boolean = false

    /** The conversation currently open on screen, or null. Maintained by the shell. */
    @Volatile
    var openConversationId: String? = null

    private val channelsInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Creates and registers all app notification channels on Android 8.0+ (API 26+).
     * Safe to call repeatedly (idempotent; guarded by [channelsInitialized] to avoid system IPC thrashing).
     */
    fun initChannels(context: Context) {
        if (channelsInitialized.get()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_CALLS) }

        val messagesChannel = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Incoming messages and files from nearby devices"
        }

        val transfersChannel = NotificationChannel(
            CHANNEL_TRANSFERS,
            "File transfers",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "File transfer progress and completions"
        }

        val pttChannel = NotificationChannel(
            CHANNEL_PTT,
            "PTT sessions",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Live push-to-talk voice sessions and alerts"
            setSound(null, null)
            enableVibration(false)
        }

        val pairingChannel = NotificationChannel(
            CHANNEL_PAIRING,
            "Pairing requests",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming device pairing requests"
        }

        val callsChannel = NotificationChannel(
            CHANNEL_CALLS,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming and ongoing voice and video calls"
            setSound(null, null)
            enableVibration(false)
        }

        val discoveryChannel = NotificationChannel(
            CHANNEL_DISCOVERY,
            "Flash nearby presence",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Background mesh and discovery keepalive"
        }

        manager.createNotificationChannels(
            listOf(messagesChannel, transfersChannel, pttChannel, pairingChannel, callsChannel, discoveryChannel),
        )
        channelsInitialized.set(true)
    }

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

    /**
     * GM-10: Posts notification for an incoming group join request.
     * Collapsed per group using the standard post(context, groupId, ...) pipeline.
     */
    fun showJoinRequest(
        context: Context,
        groupId: String,
        groupName: String,
        applicantName: String,
    ) {
        val title = groupName.ifBlank { "Group" }
        val body = "$applicantName wants to join"
        post(context, groupId, title, body)
    }

    /** M-23: this device was added to a signed group it cannot accept; [message] says why and what to do. */
    fun showGroupOfferRefused(context: Context, groupId: String, groupName: String, message: String) {
        post(context, groupId, groupName.ifBlank { "Group" }, message)
    }

    /** Clears the notification for a conversation (e.g. the user just opened it). */
    fun clearConversation(context: Context, conversationId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationIdFor(conversationId))
        }
    }

    /**
     * Posts or updates an ongoing progress notification on [CHANNEL_TRANSFERS] for an active file transfer.
     * Uses stable id [notificationIdForTransfer] which smoothly morphs into completed/failed upon finish.
     */
    @SuppressLint("MissingPermission")
    fun showTransferProgress(context: Context, transfer: FlashTransfer) {
        val appContext = context.applicationContext
        initChannels(appContext)
        val transferId = transfer.id.value
        val notificationId = notificationIdForTransfer(transferId)
        val isReceiving = transfer.direction == FlashTransferDirection.Receiving
        val content = transferProgressNotificationContent(
            fileName = transfer.fileName,
            bytesDone = transfer.bytesDone,
            bytesTotal = transfer.bytesTotal,
            speedBytesPerSec = transfer.speedBytesPerSec,
            etaSeconds = transfer.etaSeconds,
            isReceiving = isReceiving,
            holdersOnline = transfer.holdersOnline,
            canGoOffline = transfer.canGoOffline,
        )
        val percent = if (transfer.bytesTotal > 0L) {
            ((transfer.bytesDone * 100) / transfer.bytesTotal).toInt().coerceIn(0, 100)
        } else 0

        val requestCodeView = ((notificationId * 2) + 1) and 0x7FFFFFFF
        val viewTransfersIntent = createTransfersTabPendingIntent(appContext, requestCodeView, transferId)

        // Cancelling the origin of a group file tombstones it for every member; that is confirmed in the app,
        // never from a one-tap notification button (audit B-04).
        val cancelPendingIntent = if (transfer.groupOrigin) null else createCancelTransferPendingIntent(appContext, transferId)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setProgress(100, percent, transfer.bytesTotal <= 0L)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(viewTransfersIntent)
        if (cancelPendingIntent != null) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent,
            )
        }

        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        }.onFailure { Log.w(TAG, "Failed to post transfer progress notification", it) }
    }

    /**
     * Posts a dismissable completion notification when a file transfer successfully finishes.
     * Replaces the progress notification with the same [notificationIdForTransfer] and explicitly clears progress.
     * Includes "Open" action (routing through MainActivity to ensure notification dismissal and error handling)
     * and "View Transfers" navigation.
     */
    @SuppressLint("MissingPermission")
    fun showTransferCompleted(context: Context, transfer: FlashTransfer) {
        val appContext = context.applicationContext
        initChannels(appContext)
        val transferId = transfer.id.value
        val notificationId = notificationIdForTransfer(transferId)
        val isReceiving = transfer.direction == FlashTransferDirection.Receiving
        val content = transferCompletedNotificationContent(
            fileName = transfer.fileName,
            bytesTotal = transfer.bytesTotal,
            isReceiving = isReceiving,
        )

        val requestCodeOpen = (notificationId * 2) and 0x7FFFFFFF
        val requestCodeView = ((notificationId * 2) + 1) and 0x7FFFFFFF
        val openIntent = createOpenFilePendingIntent(appContext, requestCodeOpen, transfer.localPath, transferId)
        val viewTransfersIntent = createTransfersTabPendingIntent(appContext, requestCodeView, transferId)
        val contentIntent = openIntent ?: viewTransfersIntent

        val builder = NotificationCompat.Builder(appContext, CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setProgress(0, 0, false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentIntent)

        if (openIntent != null) {
            builder.addAction(
                android.R.drawable.ic_menu_view,
                "Open",
                openIntent,
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_manage,
            "View Transfers",
            viewTransfersIntent,
        )

        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        }.onFailure { Log.w(TAG, "Failed to post transfer completed notification", it) }
    }

    /**
     * Posts a dismissable failure notification when a file transfer fails.
     * Includes quick "Retry" and "View Transfers" action buttons.
     */
    @SuppressLint("MissingPermission")
    fun showTransferFailed(context: Context, transfer: FlashTransfer) {
        val appContext = context.applicationContext
        initChannels(appContext)
        val transferId = transfer.id.value
        val notificationId = notificationIdForTransfer(transferId)
        val isReceiving = transfer.direction == FlashTransferDirection.Receiving
        val content = transferFailedNotificationContent(
            fileName = transfer.fileName,
            errorMessage = transfer.errorMessage,
            isReceiving = isReceiving,
        )

        val requestCodeView = ((notificationId * 2) + 1) and 0x7FFFFFFF
        val contentIntent = createTransfersTabPendingIntent(appContext, requestCodeView, transferId)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setProgress(0, 0, false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(contentIntent)
        // Retry only where it can do something (audit B-06): a hub row fails for a reason a retry cannot change.
        if (transfer.canRetry) {
            builder.addAction(
                android.R.drawable.ic_menu_rotate,
                "Retry",
                createResumeTransferPendingIntent(appContext, transferId),
            )
        }
        val notification = builder
            .addAction(
                android.R.drawable.ic_menu_manage,
                "View Transfers",
                contentIntent,
            )
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, notification)
        }.onFailure { Log.w(TAG, "Failed to post transfer failed notification", it) }
    }

    /**
     * Posts an incoming file transfer offer notification with "Accept" and "Decline" actions.
     * Tapping the notification opens the Transfers tab.
     */
    @SuppressLint("MissingPermission")
    fun showTransferOffered(context: Context, transfer: FlashTransfer) {
        val appContext = context.applicationContext
        initChannels(appContext)
        val transferId = transfer.id.value
        val notificationId = notificationIdForTransfer(transferId)
        val content = transferOfferedNotificationContent(
            fileName = transfer.fileName,
            bytesTotal = transfer.bytesTotal,
            peerName = transfer.peerName,
        )

        val requestCodeView = ((notificationId * 2) + 1) and 0x7FFFFFFF
        val viewTransfersIntent = createTransfersTabPendingIntent(appContext, requestCodeView, transferId)
        val acceptIntent = createAcceptTransferPendingIntent(appContext, transferId)
        val declineIntent = createDeclineTransferPendingIntent(appContext, transferId)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(viewTransfersIntent)
            .addAction(
                android.R.drawable.ic_input_add,
                "Accept",
                acceptIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Decline",
                declineIntent,
            )

        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        }.onFailure { Log.w(TAG, "Failed to post transfer offered notification", it) }
    }

    /**
     * Posts a dismissable paused notification on [CHANNEL_TRANSFERS] when a transfer is paused.
     * Prevents leaving a frozen ongoing progress notification in the shade.
     */
    @SuppressLint("MissingPermission")
    fun showTransferPaused(context: Context, transfer: FlashTransfer) {
        val appContext = context.applicationContext
        initChannels(appContext)
        val transferId = transfer.id.value
        val notificationId = notificationIdForTransfer(transferId)
        val isReceiving = transfer.direction == FlashTransferDirection.Receiving
        val content = transferPausedNotificationContent(
            fileName = transfer.fileName,
            bytesDone = transfer.bytesDone,
            bytesTotal = transfer.bytesTotal,
            isReceiving = isReceiving,
        )
        val percent = if (transfer.bytesTotal > 0L) {
            ((transfer.bytesDone * 100) / transfer.bytesTotal).toInt().coerceIn(0, 100)
        } else 0

        val requestCodeView = ((notificationId * 2) + 1) and 0x7FFFFFFF
        val viewTransfersIntent = createTransfersTabPendingIntent(appContext, requestCodeView, transferId)
        val resumePendingIntent = createResumeTransferPendingIntent(appContext, transferId)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_TRANSFERS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setProgress(100, percent, false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(viewTransfersIntent)
            .addAction(
                android.R.drawable.ic_media_play,
                "Resume",
                resumePendingIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_manage,
                "View Transfers",
                viewTransfersIntent,
            )

        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationId, builder.build())
        }.onFailure { Log.w(TAG, "Failed to post transfer paused notification", it) }
    }

    /** Clears the progress, completion, or failure notification for a specific transfer. */
    fun clearTransfer(context: Context, transferId: String) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(notificationIdForTransfer(transferId))
        }
    }

    /** Clears all transfer notifications across the entire allocated range. */
    fun clearAllTransfers(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        for (i in 0 until TRANSFER_NOTIFICATION_SLOTS) {
            runCatching { manager.cancel(TRANSFER_NOTIFICATION_BASE + i) }
        }
    }

    /**
     * PTT ping alert (v1): a paired device pressed its hardware PTT button. Single shared
     * slot — repeat pings collapse into one notification on [CHANNEL_PTT]. Tap opens the app with no
     * conversation target. Best-effort like [post]: never crashes the receive path.
     */
    @SuppressLint("MissingPermission")
    fun showPttPing(context: Context, senderName: String?) {
        val name = senderName?.ifBlank { null } ?: "Paired device"
        val appContext = context.applicationContext
        initChannels(appContext)
        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            PTT_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_PTT)
            .setSmallIcon(R.drawable.ic_notification_flash)
            .setContentTitle("PTT ping")
            .setContentText("$name pressed the PTT button")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(PTT_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "Failed to post PTT ping notification", it) }
    }

    /** Clears the PTT ping notification. */
    fun clearPttPing(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(PTT_NOTIFICATION_ID)
        }
    }

    /**
     * Tap-to-talk prompt (Phase 3): a hardware press arrived while the app was backgrounded
     * or unpermitted, so the session could not start directly. Tapping foregrounds the app
     * with [com.transfer.flash.core.ptt.PttSessionEngine.EXTRA_PTT_PRESS], and the shell
     * completes the press (permission prompt included). Best-effort like every post here.
     */
    @SuppressLint("MissingPermission")
    fun showPttTapToTalk(context: Context) {
        val appContext = context.applicationContext
        initChannels(appContext)
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
        val notification = NotificationCompat.Builder(appContext, CHANNEL_PTT)
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

    /** Posts notification for an incoming device pairing request. */
    @SuppressLint("MissingPermission")
    fun showPairingRequest(context: Context, peerName: String?, peerDeviceId: String? = null) {
        val appContext = context.applicationContext
        initChannels(appContext)
        val name = peerName?.ifBlank { null } ?: "Nearby device"
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = ACTION_VIEW_NEARBY
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (peerDeviceId != null) {
                putExtra(EXTRA_PEER_DEVICE_ID, peerDeviceId)
            }
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            PAIRING_NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_notification_flash)
            .setContentTitle("Pairing request")
            .setContentText("$name wants to pair with this device")
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(appContext).notify(PAIRING_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "Failed to post pairing notification", it) }
    }

    /** Clears the pairing request notification. */
    fun clearPairingRequest(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context).cancel(PAIRING_NOTIFICATION_ID)
        }
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, conversationId: String, title: String, body: String) {
        // Suppression rule (see class doc): reading that exact conversation right now.
        if (appForeground && openConversationId == conversationId) return

        val appContext = context.applicationContext
        initChannels(appContext)

        val intent = Intent(appContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            notificationIdFor(conversationId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification_flash)
            .setContentTitle(title)
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        runCatching {
            NotificationManagerCompat.from(appContext).notify(notificationIdFor(conversationId), notification)
        }.onFailure { Log.w(TAG, "Failed to post message notification", it) }
    }

    private fun createOpenFilePendingIntent(
        context: Context,
        requestCode: Int,
        localPath: String?,
        transferId: String,
    ): PendingIntent? {
        if (localPath.isNullOrBlank()) return null
        val file = File(localPath)
        if (!file.exists() || !file.isFile) return null
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_FILE
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(EXTRA_TRANSFER_ID, transferId)
            putExtra(EXTRA_FILE_PATH, localPath)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createTransfersTabPendingIntent(context: Context, requestCode: Int, transferId: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_VIEW_TRANSFERS
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (transferId != null) {
                putExtra(EXTRA_TRANSFER_ID, transferId)
            }
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createCancelTransferPendingIntent(context: Context, transferId: String): PendingIntent {
        val cancelIntent = Intent(context, FlashTransferActionReceiver::class.java).apply {
            action = FlashTransferActionReceiver.ACTION_CANCEL_TRANSFER
            putExtra(FlashTransferActionReceiver.EXTRA_TRANSFER_ID, transferId)
        }
        val requestCode = (notificationIdForTransfer(transferId) + 3000) and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createResumeTransferPendingIntent(context: Context, transferId: String): PendingIntent {
        val resumeIntent = Intent(context, FlashTransferActionReceiver::class.java).apply {
            action = FlashTransferActionReceiver.ACTION_RESUME_TRANSFER
            putExtra(FlashTransferActionReceiver.EXTRA_TRANSFER_ID, transferId)
        }
        val requestCode = (notificationIdForTransfer(transferId) + 4000) and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createAcceptTransferPendingIntent(context: Context, transferId: String): PendingIntent {
        val acceptIntent = Intent(context, FlashTransferActionReceiver::class.java).apply {
            action = FlashTransferActionReceiver.ACTION_ACCEPT_TRANSFER
            putExtra(FlashTransferActionReceiver.EXTRA_TRANSFER_ID, transferId)
        }
        val requestCode = (notificationIdForTransfer(transferId) + 5000) and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createDeclineTransferPendingIntent(context: Context, transferId: String): PendingIntent {
        val declineIntent = Intent(context, FlashTransferActionReceiver::class.java).apply {
            action = FlashTransferActionReceiver.ACTION_DECLINE_TRANSFER
            putExtra(FlashTransferActionReceiver.EXTRA_TRANSFER_ID, transferId)
        }
        val requestCode = (notificationIdForTransfer(transferId) + 6000) and 0x7FFFFFFF
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Stable id per conversation: same peer updates their own notification; peers stack. */
    fun notificationIdFor(conversationId: String): Int =
        NOTIFICATION_ID_BASE + (conversationId.hashCode() and 0x7FFFFFFF) % 1000

    private val transferNotificationIds = HashMap<String, Int>()

    /**
     * Stable id per transfer: collapses updates for the same transfer. The hash picks the slot, but a slot already
     * held by another transfer is skipped (audit B-16): two live transfers sharing an id overwrote each other's
     * progress, and a Cancel pressed on one carried the other's transfer id. The assignment lives for the process,
     * which is as long as the notifications it names are managed here.
     */
    fun notificationIdForTransfer(transferId: String): Int = synchronized(transferNotificationIds) {
        transferNotificationIds[transferId]?.let { return it }
        val start = (transferId.hashCode() and 0x7FFFFFFF) % TRANSFER_NOTIFICATION_SLOTS
        val taken = transferNotificationIds.values.toHashSet()
        var slot = start
        var probes = 0
        while (TRANSFER_NOTIFICATION_BASE + slot in taken && probes < TRANSFER_NOTIFICATION_SLOTS) {
            slot = (slot + 1) % TRANSFER_NOTIFICATION_SLOTS
            probes++
        }
        // Every slot taken (a thousand live transfers): fall back to sharing the hashed one.
        val id = TRANSFER_NOTIFICATION_BASE + if (probes < TRANSFER_NOTIFICATION_SLOTS) slot else start
        if (transferNotificationIds.size >= TRANSFER_NOTIFICATION_SLOTS * 4) transferNotificationIds.clear()
        transferNotificationIds[transferId] = id
        id
    }
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

internal fun transferCompletedNotificationContent(
    fileName: String,
    bytesTotal: Long,
    isReceiving: Boolean,
): FlashNotificationContent {
    val title = if (isReceiving) "Download complete" else "Transfer complete"
    val sizeStr = formatNotificationBytes(bytesTotal)
    val body = if (sizeStr.isNotBlank()) "$fileName ($sizeStr)" else fileName
    return FlashNotificationContent(title, body)
}

internal fun transferFailedNotificationContent(
    fileName: String,
    errorMessage: String?,
    isReceiving: Boolean,
): FlashNotificationContent {
    val title = if (isReceiving) "Download failed" else "Transfer failed"
    val body = TransferFailureText.displayReason(errorMessage)?.ifBlank { null } ?: "Failed to transfer $fileName"
    return FlashNotificationContent(title, body)
}

internal fun transferOfferedNotificationContent(
    fileName: String,
    bytesTotal: Long,
    peerName: String?,
): FlashNotificationContent {
    val sender = peerName?.ifBlank { null } ?: "Nearby device"
    val title = "Incoming transfer from $sender"
    val sizeStr = formatNotificationBytes(bytesTotal)
    val body = if (sizeStr.isNotBlank()) "$fileName ($sizeStr)" else fileName
    return FlashNotificationContent(title, body)
}

internal fun transferPausedNotificationContent(
    fileName: String,
    bytesDone: Long,
    bytesTotal: Long,
    isReceiving: Boolean,
): FlashNotificationContent {
    val title = if (isReceiving) "Download paused" else "Transfer paused"
    val percent = if (bytesTotal > 0L) {
        ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
    } else 0
    val doneStr = if (bytesDone <= 0L && bytesTotal > 0L) "0 B" else formatNotificationBytes(bytesDone)
    val totalStr = formatNotificationBytes(bytesTotal)
    val sizeText = if (doneStr.isNotBlank() && totalStr.isNotBlank()) "$doneStr / $totalStr" else ""
    val body = if (sizeText.isNotBlank()) "$fileName ($percent%) • $sizeText" else "$fileName ($percent%)"
    return FlashNotificationContent(title, body)
}

internal fun formatNotificationBytes(bytes: Long): String {
    if (bytes <= 0L) return ""
    val kb = bytes.toDouble() / 1024
    val mb = kb / 1024
    val gb = mb / 1024
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.1f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.US, "%.0f KB", kb)
        else -> "$bytes B"
    }
}

private fun attachmentKind(mimeType: String): String = when {
    mimeType.startsWith("image/") -> "Photo"
    mimeType.startsWith("video/") -> "Video"
    mimeType.startsWith("audio/") -> "Voice message"
    else -> "File"
}

internal fun transferProgressNotificationContent(
    fileName: String,
    bytesDone: Long,
    bytesTotal: Long,
    speedBytesPerSec: Long,
    etaSeconds: Long,
    isReceiving: Boolean,
    holdersOnline: Int = 0,
    canGoOffline: Boolean = false,
): FlashNotificationContent {
    val percent = if (bytesTotal > 0L) {
        ((bytesDone * 100) / bytesTotal).toInt().coerceIn(0, 100)
    } else 0
    val verb = if (isReceiving) "Downloading" else "Sending"
    val title = "$verb $fileName — $percent%"
    val doneStr = if (bytesDone <= 0L && bytesTotal > 0L) "0 B" else formatNotificationBytes(bytesDone)
    val totalStr = formatNotificationBytes(bytesTotal)
    val sizeText = if (doneStr.isNotBlank() && totalStr.isNotBlank()) "$doneStr / $totalStr" else ""
    val speedText = formatNotificationSpeed(speedBytesPerSec)
    val etaText = formatNotificationEta(etaSeconds)
    val swarmDetail = if (!isReceiving && canGoOffline) {
        "You can go offline now"
    } else if (isReceiving && holdersOnline > 1) {
        "Getting it from $holdersOnline devices"
    } else null
    val body = listOfNotNull(
        swarmDetail,
        sizeText.takeIf { it.isNotBlank() },
        speedText.takeIf { it.isNotBlank() },
        etaText.takeIf { it.isNotBlank() },
    ).joinToString(" • ").ifBlank { "$percent% completed" }
    return FlashNotificationContent(title, body)
}

internal fun formatNotificationSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return ""
    val mb = bytesPerSec.toDouble() / (1024 * 1024)
    return if (mb >= 1.0) {
        String.format(Locale.US, "%.1f MB/s", mb)
    } else {
        val kb = bytesPerSec.toDouble() / 1024
        String.format(Locale.US, "%.0f KB/s", kb)
    }
}

internal fun formatNotificationEta(etaSeconds: Long): String {
    if (etaSeconds <= 0L) return ""
    val hours = etaSeconds / 3600
    val mins = (etaSeconds % 3600) / 60
    val secs = etaSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${mins}m left"
        mins > 0 -> "${mins}m ${secs}s left"
        else -> "${secs}s left"
    }
}
