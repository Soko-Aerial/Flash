package com.transfer.flash.calling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.transfer.flash.MainActivity
import com.transfer.flash.core.calling.CallCoordinator
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.debug.DiscoveryEngineHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * C7 (calling) foreground service — keeps the process alive for the duration of a
 * call with a [Notification.CallStyle] notification (API 31+).
 *
 * ## Lifecycle
 * Started by the call overlay when a call transitions to RINGING (incoming) or
 * DIALING (outgoing). Stops itself when the call ends (the coordinator nulls
 * [CallCoordinator.activeCall] after the 2-second ENDED display).
 *
 * ## FGS compliance
 * The service is started while the app is foreground (the user taps call / answers
 * from notification), which satisfies Android 12+ while-in-use restrictions for
 * [android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE] and
 * [android.Manifest.permission.FOREGROUND_SERVICE_CAMERA].
 *
 * ## Notification.CallStyle
 * On API 31+ the notification uses [Notification.CallStyle] for system-styled
 * call controls (decline/hangup / answer). On older API levels a plain ongoing
 * notification is shown instead.
 */
class FlashCallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var coordinatorSnapshot: CallCoordinator? = null
    private var startForegroundCalled = false
    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(this)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Observe the coordinator's activeCall state and post/update/remove notifications.
        // The coordinator outlives this service, so we hold a reference to avoid a second
        // lookup on every state tick.
        val coordinator = coordinatorSnapshot ?: DiscoveryEngineHolder.currentCallCoordinator()
        if (coordinator == null) {
            Log.w(TAG, "No call coordinator available — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        coordinatorSnapshot = coordinator

        scope.launch {
            coordinator.activeCall.collectLatest { state ->
                if (state != null) {
                    postCallNotification(state)
                } else {
                    // Call fully cleared (coordinator nulled _activeCall).
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun postCallNotification(state: FlashCallUiState) {
        val title = state.peerName
        val body = when (state.state) {
            FlashCallState.DIALING -> "Calling…"
            FlashCallState.RINGING -> "Incoming call…"
            FlashCallState.CONNECTING -> "Connecting…"
            FlashCallState.ACTIVE -> "Call in progress"
            FlashCallState.ENDED -> "Call ended"
        }

        // Tap opens the app (MainActivity). FLAG_UPDATE_CURRENT refreshes the existing
        // pending intent so tapping the notification always brings the call screen to front.
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

            val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                buildSPlusNotification(title, body, state, contentIntent)
            } else {
                buildPreSNotification(title, body, state, contentIntent)
            }

            if (startForegroundCalled) {
                // Already foreground: just update the notification content/actions.
                runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
                    .onFailure { Log.w(TAG, "notify failed", it) }
                return
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                startForegroundCalled = true
            } catch (e: Exception) {
                // ForegroundServiceStartNotAllowedException (API 31+) or OEM variants.
                Log.w(TAG, "startForeground refused", e)
                // Don't stopSelf — the call should continue even without FGS priority.
            }
        }

        @RequiresApi(Build.VERSION_CODES.S)
        private fun buildSPlusNotification(
            title: String,
            body: String,
            state: FlashCallUiState,
            contentIntent: PendingIntent,
        ): Notification {
            val builder = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_call_mute)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(state.state != FlashCallState.ENDED)
                .setContentIntent(contentIntent)
                .setCategory(Notification.CATEGORY_CALL)

            val person = Person.Builder().setName(title).build()

            when (state.state) {
                FlashCallState.RINGING -> {
                    val answerIntent = PendingIntent.getBroadcast(
                        this,
                        REQUEST_ANSWER,
                        Intent(FlashCallActionReceiver.ACTION_ANSWER).setPackage(packageName),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                    val declineIntent = PendingIntent.getBroadcast(
                        this,
                        REQUEST_DECLINE,
                        Intent(FlashCallActionReceiver.ACTION_DECLINE).setPackage(packageName),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                    builder.setStyle(
                        Notification.CallStyle.forIncomingCall(person, answerIntent, declineIntent)
                    )
                }
                FlashCallState.ACTIVE, FlashCallState.DIALING, FlashCallState.CONNECTING -> {
                    val hangUpIntent = PendingIntent.getBroadcast(
                        this,
                        REQUEST_HANGUP,
                        Intent(FlashCallActionReceiver.ACTION_HANGUP).setPackage(packageName),
                        PendingIntent.FLAG_IMMUTABLE,
                    )
                    builder.setStyle(
                        Notification.CallStyle.forOngoingCall(person, hangUpIntent)
                    )
                }
                FlashCallState.ENDED -> {
                    // No CallStyle for ended calls — just informational ongoing notification.
                }
            }

            return builder.build()
        }

        @Suppress("DEPRECATION")
        private fun buildPreSNotification(
            title: String,
            body: String,
            state: FlashCallUiState,
            contentIntent: PendingIntent,
        ): Notification {
            return NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_call_mute)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(state.state != FlashCallState.ENDED)
                .setContentIntent(contentIntent)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .build()
        }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming and ongoing voice/video calls"
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CALLSVC"
        private const val CHANNEL_ID = "flash_calls"
        private const val NOTIFICATION_ID = 42
        private const val REQUEST_ANSWER = 1001
        private const val REQUEST_DECLINE = 1002
        private const val REQUEST_HANGUP = 1003

        /** Start the call FGS — must be called while the app is foreground. */
        fun start(context: Context) {
            val intent = Intent(context.applicationContext, FlashCallService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure { error ->
                Log.w(TAG, "Unable to start call foreground service", error)
            }
        }

        /** Stop the call FGS. */
        fun stop(context: Context) {
            context.stopService(Intent(context, FlashCallService::class.java))
        }
    }
}