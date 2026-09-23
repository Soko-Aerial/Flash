package com.transfer.flash.calling

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.transfer.flash.MainActivity
import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.debug.DiscoveryEngineHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * C7 (calling) foreground service — keeps the process alive for the duration of a
 * call with a [Notification.CallStyle] notification (API 31+).
 *
 * ## Lifecycle
 * Started on the RINGING (incoming) / DIALING (outgoing) edge by both the call overlay and
 * [com.transfer.flash.debug.DiscoveryEngineHolder]'s ring collector — the latter because an invite
 * that arrives with the app closed has no activity to start it. Repeated starts are harmless (see
 * the collector guard in [onStartCommand]). Stops itself when the call ends (the engine nulls
 * [FlashCalling.activeCall] after the 2-second ENDED display).
 *
 * ## Ringing
 * This service does **not** ring. Its channel is silent and [FlashCallRinger] — owned by the engine,
 * so it works with no activity and no foreground-service promotion — plays the ringtone and
 * ringback. See [createChannel] for why a channel sound cannot do the job.
 *
 * ## FGS compliance
 * On Android 14+ a `microphone`-typed foreground service is only allowed once
 * [android.Manifest.permission.RECORD_AUDIO] is actually granted AND the app is in an
 * eligible state. An incoming call starts this service while the invite is still
 * RINGING — before the user has answered, so before the permission prompt — so the
 * service claims only the types it currently holds (possibly none) and re-promotes
 * itself with the fuller type on the next state tick once the user has answered.
 *
 * ## Notification.CallStyle
 * On API 31+ the notification uses [Notification.CallStyle] for system-styled
 * call controls (decline/hangup / answer). On older API levels a plain ongoing
 * notification is shown instead.
 */
class FlashCallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var callingSnapshot: FlashCalling? = null
    private var startForegroundCalled = false

    /** The single [FlashCalling.activeCall] collector; see the guard in [onStartCommand]. */
    private var stateJob: Job? = null

    /** FGS type bitmask this service is currently running with; -1 == not promoted yet. */
    private var currentFgsType = -1
    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(this)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Observe the engine's activeCall state and post/update/remove notifications.
        // The engine outlives this service, so we hold a reference to avoid a second
        // lookup on every state tick.
        val calling = callingSnapshot ?: DiscoveryEngineHolder.currentCalling()
        if (calling == null) {
            Log.w(TAG, "No call engine available — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        callingSnapshot = calling

        // One collector per service instance, not per start. Both MainActivity's LaunchedEffect and
        // the engine's ring collector call start() on every ringing-state emission, and a started
        // service is re-delivered to onStartCommand each time — without this guard each redelivery
        // added another collector, so a single state tick posted (and re-promoted) N times over.
        if (stateJob?.isActive != true) {
            stateJob = scope.launch {
                calling.activeCall.collectLatest { state ->
                    if (state != null) {
                        postCallNotification(state)
                    } else {
                        // Call fully cleared (the engine nulled activeCall).
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
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

    @SuppressLint("MissingPermission") // notify() is inside runCatching: a revoked POST_NOTIFICATIONS
    // is logged, never thrown. A pre-check would be wrong below API 33, where that permission does not exist.
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

            // The notification is posted unconditionally: the user must see a ringing or
            // ongoing call even when the platform refuses to promote us to a foreground
            // service (missing runtime grant, ineligible app state).
            runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
                .onFailure { Log.w(TAG, "notify failed", it) }

            promoteToForeground(notification, state)
        }

        /**
         * Enters (or upgrades) the foreground state with a service type the platform will
         * actually accept.
         *
         * Claiming `microphone` before RECORD_AUDIO is granted throws SecurityException on
         * Android 14+, which left the service never promoted at all — no call priority, and
         * a pending `startForegroundService` deadline the system eventually kills the app
         * for. So: claim what is granted, fall back to an untyped FGS, and re-promote when
         * the granted set grows (the user answering the call is exactly that moment).
         */
        private fun promoteToForeground(notification: Notification, state: FlashCallUiState) {
            val desiredType = grantedForegroundServiceType(state)
            if (startForegroundCalled && desiredType == currentFgsType) {
                return
            }
            if (tryStartForeground(notification, desiredType)) {
                startForegroundCalled = true
                currentFgsType = desiredType
                return
            }
            // If typed promotion was refused (e.g. background microphone restriction on Android 14+),
            // fallback to connectedDevice (API 34+) or FGS_TYPE_NONE.
            val fallbackType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                FGS_TYPE_NONE
            }
            if (desiredType != fallbackType && tryStartForeground(notification, fallbackType)) {
                startForegroundCalled = true
                currentFgsType = fallbackType
            }
        }

        private fun tryStartForeground(notification: Notification, type: Int): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, type)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                true
            } catch (e: Exception) {
                // SecurityException (ungranted type), ForegroundServiceStartNotAllowedException
                // (API 31+ background start), IllegalArgumentException (type not in manifest),
                // or an OEM variant.
                Log.w(TAG, "startForeground(type=$type) refused", e)
                false
            }
        }

        /**
         * The subset of the manifest's `microphone|camera|connectedDevice` types this app currently holds
         * the runtime permissions for. Camera is only claimed for video calls — an audio
         * call has no camera in use, and claiming an unused type is itself a violation.
         */
        private fun grantedForegroundServiceType(state: FlashCallUiState): Int {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return FGS_TYPE_NONE
            }
            // During RINGING (call not accepted yet), no audio is being captured.
            // On Android 14+ (API 34), claiming MICROPHONE from background throws SecurityException.
            // We claim CONNECTED_DEVICE while RINGING, which is valid and permitted for background starts.
            if (state.state == FlashCallState.RINGING) {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else {
                    FGS_TYPE_NONE
                }
            }
            var type = FGS_TYPE_NONE
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (state.video && hasPermission(Manifest.permission.CAMERA)) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (type == FGS_TYPE_NONE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            return type
        }

        private fun hasPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

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
                // The body text changes on every state tick (Calling… → Connecting… → in progress);
                // without this each edit re-pops the heads-up banner over the call UI.
                .setOnlyAlertOnce(true)

            val person = Person.Builder().setName(title).build()

            when (state.state) {
                FlashCallState.RINGING -> {
                    builder.setFullScreenIntent(contentIntent, true)
                    val answerActivityIntent = Intent(this, MainActivity::class.java).apply {
                        action = FlashCallActionReceiver.ACTION_ANSWER
                        putExtra(FlashCallActionReceiver.EXTRA_ANSWER_CALL, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    val answerIntent = PendingIntent.getActivity(
                        this,
                        REQUEST_ANSWER,
                        answerActivityIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val declineIntent = PendingIntent.getBroadcast(
                        this,
                        REQUEST_DECLINE,
                        Intent(FlashCallActionReceiver.ACTION_DECLINE).setPackage(packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    // Android CallStyle.forIncomingCall signature: (person, declineIntent, answerIntent)
                    builder.setStyle(
                        Notification.CallStyle.forIncomingCall(person, declineIntent, answerIntent)
                    )
                }
                FlashCallState.ACTIVE, FlashCallState.DIALING, FlashCallState.CONNECTING -> {
                    val hangUpIntent = PendingIntent.getBroadcast(
                        this,
                        REQUEST_HANGUP,
                        Intent(FlashCallActionReceiver.ACTION_HANGUP).setPackage(packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
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
            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_call_mute)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(state.state != FlashCallState.ENDED)
                .setContentIntent(contentIntent)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setOnlyAlertOnce(true)
                // Pre-O there is no channel to silence, so the ringer's exclusivity is asserted here.
                .setSilent(true)

            when (state.state) {
                FlashCallState.RINGING -> {
                    builder.setFullScreenIntent(contentIntent, true)
                    val answerActivityIntent = Intent(this, MainActivity::class.java).apply {
                        action = FlashCallActionReceiver.ACTION_ANSWER
                        putExtra(FlashCallActionReceiver.EXTRA_ANSWER_CALL, true)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    val answerIntent = PendingIntent.getActivity(
                        this,
                        REQUEST_ANSWER,
                        answerActivityIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    val declineIntent = PendingIntent.getBroadcast(
                        this,
                        REQUEST_DECLINE,
                        Intent(FlashCallActionReceiver.ACTION_DECLINE).setPackage(packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    builder.addAction(android.R.drawable.ic_menu_call, "Answer", answerIntent)
                    builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Decline", declineIntent)
                }
                FlashCallState.ACTIVE, FlashCallState.DIALING, FlashCallState.CONNECTING -> {
                    val hangUpIntent = PendingIntent.getBroadcast(
                        this,
                        REQUEST_HANGUP,
                        Intent(FlashCallActionReceiver.ACTION_HANGUP).setPackage(packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hang up", hangUpIntent)
                }
                FlashCallState.ENDED -> {}
            }

            return builder.build()
        }

    /**
     * The call channel is deliberately **silent**: [FlashCallRinger] owns the ring.
     *
     * A channel's sound plays exactly once per notification — looping needs `FLAG_INSISTENT`, which
     * only the system dialer may set — so a channel sound can never be a ringtone, cannot be
     * stopped the instant the call is answered, and cannot honour the ringer mode. Leaving it on top
     * of the ringer would just add a stray ding under the ringtone.
     *
     * Sound and vibration are immutable after a channel is created, and the platform remembers the
     * settings of a channel it has seen before (even a deleted one), so silencing the original
     * `flash_calls` in place is impossible: this uses a new id and deletes the old channel so users
     * who ran an earlier build don't keep a stale duplicate in Settings.
     */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Calls",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Incoming and ongoing voice/video calls"
            // IMPORTANCE_HIGH still produces a heads-up banner with no sound, which is what a call
            // notification needs: visible immediately, audible only via the ringer.
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "CALLSVC"

        /** Silent replacement for [LEGACY_CHANNEL_ID]; see [createChannel] for why the id changed. */
        private const val CHANNEL_ID = "flash_calls_v2"

        /** The pre-ringer channel, which played a one-shot notification ding. Deleted on create. */
        private const val LEGACY_CHANNEL_ID = "flash_calls"
        private const val NOTIFICATION_ID = 42
        private const val REQUEST_ANSWER = 1001
        private const val REQUEST_DECLINE = 1002
        private const val REQUEST_HANGUP = 1003

        /**
         * `ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE` (0), inlined to keep its platform
         * deprecation out of the build log. It is the only type claimable with no runtime
         * permission, and an untyped FGS beats never promoting at all: a service started
         * with `startForegroundService` that never reaches `startForeground` is killed.
         */
        private const val FGS_TYPE_NONE = 0

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