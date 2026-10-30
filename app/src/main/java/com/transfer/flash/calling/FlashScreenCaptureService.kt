package com.transfer.flash.calling

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.transfer.flash.MainActivity
import com.transfer.flash.debug.DiscoveryEngineHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The `mediaProjection` foreground service ADR-102 needs on Android 14+ (docs/calling/SCREEN-SHARE-DESIGN.md
 * section 8). Android refuses `getMediaProjection` / `createVirtualDisplay` unless a foreground service of this type
 * is already running, so the capturer's host starts this before it opens the projection and stops it as soon as the
 * projection is closed.
 *
 * Its notification is the second way to stop presenting (Android 15 QPR1+ also has the system's status-bar chip,
 * which ends the projection itself and reaches the session as a revocation): the Stop action asks the live call to
 * stop sharing, which closes the capture and then this service.
 */
class FlashScreenCaptureService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val promoted = try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            // SecurityException (permission not declared/refused), ForegroundServiceStartNotAllowedException, or an OEM
            // variant. The capturer must not open the projection then; it reports "sharing could not start".
            Log.w(TAG, "mediaProjection foreground service refused", t)
            false
        }
        promotedFlag = promoted
        if (!promoted) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP && !stopRequested) {
            stopRequested = true
            scope.launch {
                runCatching { DiscoveryEngineHolder.currentCalling()?.stopScreenShare() }
                    .onFailure { Log.w(TAG, "stop sharing failed", it) }
                // The capture closes through the session (which stops this service too); this is the safety net for a
                // session that is already gone.
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        promotedFlag = false
        stopRequested = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, FlashScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Sharing your screen")
            .setContentText("Your screen is visible in the call")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen sharing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while your screen is shared in a call"
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SCREENCAP"
        private const val CHANNEL_ID = "flash_screen_share"
        private const val NOTIFICATION_ID = 43
        private const val REQUEST_STOP = 1010
        private const val ACTION_STOP = "com.transfer.flash.calling.STOP_SCREEN_SHARE"

        /** How long the capturer waits for the promotion before it gives up and reports a failed share. */
        private const val PROMOTE_TIMEOUT_MS = 3_000L

        @Volatile
        private var promotedFlag: Boolean = false

        @Volatile
        private var stopRequested: Boolean = false

        /**
         * Starts the service and suspends until it is in the foreground (or [PROMOTE_TIMEOUT_MS] passes). The caller
         * must be in the foreground itself (the person just tapped Share), which is what Android requires for this
         * service type.
         */
        suspend fun startAndAwait(context: Context): Boolean {
            val app = context.applicationContext
            promotedFlag = false
            stopRequested = false
            val started = runCatching {
                ContextCompat.startForegroundService(app, Intent(app, FlashScreenCaptureService::class.java))
                true
            }.onFailure { Log.w(TAG, "could not start the projection service", it) }.getOrDefault(false)
            if (!started) return false
            return withTimeoutOrNull(PROMOTE_TIMEOUT_MS) {
                while (!promotedFlag) delay(20)
                true
            } ?: false
        }

        fun stop(context: Context) {
            promotedFlag = false
            runCatching { context.applicationContext.stopService(Intent(context.applicationContext, FlashScreenCaptureService::class.java)) }
        }
    }
}
