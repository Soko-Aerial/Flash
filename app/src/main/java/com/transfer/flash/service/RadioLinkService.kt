package com.transfer.flash.service

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
import com.transfer.flash.MainActivity
import com.transfer.flash.R
import com.transfer.flash.core.radio.service.RadioServiceNotification

/**
 * Android Foreground Service maintaining process liveness for connected hardware radio gateways (Plan R §4 & §6).
 *
 * Compliance:
 * - Manifest type: foregroundServiceType="connectedDevice"
 * - API 34+ (Android 14/15): Passes ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE to startForeground().
 * - Displays honest notification of connection state via [com.transfer.flash.core.radio.service.RadioServicePolicy].
 * - Stops immediately when device disconnects or retries cease.
 */
public class RadioLinkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopForegroundService()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Radio Gateway Active"
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Radio link connected"

        val notification = buildNotification(title, text)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to startForeground for RadioLinkService", e)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildNotification(title: String, text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_flash)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(tapIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Radio Gateway",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of connected hardware radio TNC gateways"
                setShowBadge(false)
            }
            manager?.createNotificationChannel(channel)
        }
    }

    public companion object {
        private const val TAG = "RadioLinkService"
        private const val CHANNEL_ID = "flash_radio_gateway_channel"
        private const val NOTIFICATION_ID = 4001

        public const val ACTION_START: String = "com.transfer.flash.radio.START_SERVICE"
        public const val ACTION_STOP: String = "com.transfer.flash.radio.STOP_SERVICE"
        public const val EXTRA_TITLE: String = "extra_title"
        public const val EXTRA_TEXT: String = "extra_text"

        public fun start(context: Context, notification: RadioServiceNotification) {
            val intent = Intent(context, RadioLinkService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, notification.title)
                putExtra(EXTRA_TEXT, notification.text)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * stopService, not a start with a stop action: starting a service only to stop it throws from the
         * background on Android 8+ and creates the service when it is not running. Stopping a service that is
         * not running does nothing, and destroying a foreground service removes its notification.
         */
        public fun stop(context: Context) {
            context.stopService(Intent(context, RadioLinkService::class.java))
        }
    }
}
