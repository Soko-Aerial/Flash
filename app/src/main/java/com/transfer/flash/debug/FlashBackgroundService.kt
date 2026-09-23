package com.transfer.flash.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background discovery host and active dataSync transfer foreground service.
 *
 * Foreground service types declared in manifest:
 * - `connectedDevice`: discovery and mesh keepalive
 * - `dataSync`: active background file transfer execution (Android 14+)
 *
 * Started from [com.transfer.flash.MainActivity.onStart] while the app is user-visible, which
 * satisfies Android 12+ foreground-service start restrictions. It deliberately remains running
 * after the activity stops so discovery and established mesh sessions survive in the background.
 */
class FlashBackgroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        scope.launch {
            runCatching { DiscoveryEngineHolder.ensureStarted(applicationContext) }
                .onFailure { Log.w(TAG, "engine start failed in background service", it) }
        }
        if (startAsForeground()) {
            promotionRefused.set(false)
        } else {
            promotionRefused.set(true)
            Log.w(TAG, "Foreground promotion refused (background start restriction) — retaining service instance in background; promotion will be retried upon screen-on / network availability")
        }

        // Observe active transfers to update ongoing notification with progress, speed, ETA, and cancel action
        scope.launch {
            var transferRepo: FlashTransferRepository? = null
            while (transferRepo == null && isActive) {
                transferRepo = DiscoveryEngineHolder.currentTransfers()
                if (transferRepo == null) delay(500)
            }
            transferRepo?.activeTransfers?.collect { transfers ->
                updateTransferNotification(transfers)
            }
        }

        // Observe discovery mode changes to update notification accordingly
        scope.launch {
            DiscoveryEngineHolder.discoveryMode.collect {
                val transfers = DiscoveryEngineHolder.currentTransfers()?.activeTransfers?.value ?: emptyList()
                updateTransferNotification(transfers)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_TRANSFER) {
            val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)
            if (transferId != null) {
                scope.launch {
                    val transfers = DiscoveryEngineHolder.currentTransfers()
                    transfers?.cancelTransfer(FlashTransferId(transferId))
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (activeInstance == this) {
            activeInstance = null
        }
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Android 15+ (API 35) dataSync six-hour limit. The platform contract is `stopSelf()` within a
     * few seconds, else `RemoteServiceException` ("did not stop within its timeout"); re-typing the
     * service via `startForeground` is not a documented way out
     * (developer.android.com/develop/background-work/services/fgs/timeout, checked 2026-09-22).
     * The engine and its power locks live in [DiscoveryEngineHolder], not here, so stopping the
     * service does not take the mesh down; promotion is retried on the next [start].
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service timeout reached: startId=$startId, fgsType=$fgsType")
        val transfers = DiscoveryEngineHolder.currentTransfers()
        transfers?.activeTransfers?.value?.forEach { transfer ->
            if (transfer.state == FlashTransferState.Transferring) {
                // Not [scope]: stopSelf() → onDestroy() cancels it before these could run.
                timeoutCleanupScope.launch { runCatching { transfers.cancelTransfer(transfer.id) } }
            }
        }
        stopSelf()
        super.onTimeout(startId, fgsType)
    }

    private fun buildIdleNotification(): Notification {
        val mode = DiscoveryEngineHolder.currentDiscoveryMode()
        val (title, text) = when (mode) {
            com.transfer.flash.core.discovery.core.FlashDiscoveryMode.STANDARD ->
                "Flash is discoverable" to "Nearby devices can find and reach this phone."
            com.transfer.flash.core.discovery.core.FlashDiscoveryMode.GHOST ->
                "Flash is hidden" to "Browse only — nearby devices cannot see this phone."
            com.transfer.flash.core.discovery.core.FlashDiscoveryMode.ECO ->
                "Flash is in eco mode" to "Battery-saving discovery active."
            com.transfer.flash.core.discovery.core.FlashDiscoveryMode.BOOST ->
                "Flash is in boost mode" to "High-responsiveness discovery active."
            com.transfer.flash.core.discovery.core.FlashDiscoveryMode.RECEIVE_KIOSK ->
                "Flash is in kiosk mode" to "Advertising kiosk availability."
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Flash nearby presence",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification: Notification = buildIdleNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (error: Exception) {
            Log.e(TAG, "startForeground refused", error)
            false
        }
    }

    private fun updateTransferNotification(transfers: List<FlashTransfer>) {
        val active = transfers.filter { it.state == FlashTransferState.Transferring }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (active.isEmpty()) {
            val notification = buildIdleNotification()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (t: Throwable) {
                manager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            val primary = active.first()
            val percent = if (primary.bytesTotal > 0) {
                ((primary.bytesDone * 100) / primary.bytesTotal).toInt().coerceIn(0, 100)
            } else 0
            val verb = if (primary.direction == FlashTransferDirection.Sending) "Sending" else "Receiving"
            val title = "$verb ${primary.fileName} — $percent%"
            val speedText = formatTransferSpeed(primary.speedBytesPerSec)
            val etaText = formatTransferEta(primary.etaSeconds)
            val subtitle = listOfNotNull(
                speedText.takeIf { it.isNotBlank() },
                etaText.takeIf { it.isNotBlank() },
                if (active.size > 1) "+${active.size - 1} more" else null,
            ).joinToString(" • ").ifBlank { "$percent% completed" }

            val cancelIntent = Intent(this, FlashBackgroundService::class.java).apply {
                action = ACTION_CANCEL_TRANSFER
                putExtra(EXTRA_TRANSFER_ID, primary.id.value)
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                primary.id.value.hashCode(),
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val cancelAction = Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent,
            ).build()

            val notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, percent, false)
                .addAction(cancelAction)
                .setOngoing(true)
                .build()

            try {
                val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else 0

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, fgsType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (t: Throwable) {
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun formatTransferSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        val mb = bytesPerSec.toDouble() / (1024 * 1024)
        return if (mb >= 1.0) {
            String.format(java.util.Locale.US, "%.1f MB/s", mb)
        } else {
            val kb = bytesPerSec.toDouble() / 1024
            String.format(java.util.Locale.US, "%.0f KB/s", kb)
        }
    }

    private fun formatTransferEta(etaSeconds: Long): String {
        if (etaSeconds <= 0) return ""
        val mins = etaSeconds / 60
        val secs = etaSeconds % 60
        return if (mins > 0) "${mins}m ${secs}s left" else "${secs}s left"
    }

    companion object {
        private const val TAG = "SERVICE"
        private const val CHANNEL_ID = "flash_discovery_bg"
        private const val NOTIFICATION_ID = 41

        const val ACTION_CANCEL_TRANSFER = "com.transfer.flash.action.CANCEL_TRANSFER"
        const val EXTRA_TRANSFER_ID = "extra_transfer_id"

        private val promotionRefused = AtomicBoolean(false)
        private val timeoutCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile
        private var activeInstance: FlashBackgroundService? = null

        /** True while a service instance exists — i.e. something else owns the engine's lifetime. */
        fun isActive(): Boolean = activeInstance != null

        fun start(context: Context) {
            val intent = Intent(context.applicationContext, FlashBackgroundService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure { error ->
                Log.e(TAG, "Unable to start background mesh foreground service", error)
            }
        }

        fun retryPromotionIfRefused(context: Context) {
            if (!promotionRefused.get()) return
            Log.i(TAG, "Retrying refused foreground promotion")
            val current = activeInstance
            if (current != null && current.startAsForeground()) {
                promotionRefused.set(false)
                Log.i(TAG, "Foreground promotion succeeded on active instance")
            } else {
                start(context)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlashBackgroundService::class.java))
        }
    }
}
