package com.transfer.flash.ptt

import android.annotation.SuppressLint
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.transfer.flash.MainActivity
import com.transfer.flash.R
import com.transfer.flash.core.messaging.ptt.PttFloorState
import com.transfer.flash.core.ptt.PttSessionStats
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Phase 2: foreground-service host for a live PTT voice session (ADR-032).
 *
 * Keeps the process alive while talking/listening and posts the ongoing session
 * notification (chronometer seconds + RTT/loss + Stop/Leave action). Deliberately NOT
 * [com.transfer.flash.calling.FlashCallService]: `Notification.CallStyle` answer/decline
 * semantics are wrong for a half-duplex floor, and PTT receivers need the
 * `mediaPlayback` type while the talker needs `microphone` — one service claims the
 * subset its current role holds (same granted-type pattern as the call service).
 *
 * Lifecycle: [DiscoveryEngineHolder][com.transfer.flash.debug.DiscoveryEngineHolder]
 * starts this on the Idle→session edge and stops it on return to Idle (START_NOT_STICKY:
 * session truth lives in [com.transfer.flash.core.ptt.PttSessionEngine], never here). Stats
 * refresh faster than the
 * notification may churn, so the content flow is sampled to 1 Hz — the chronometer keeps
 * seconds ticking between updates.
 *
 * Background-start caveat (documented, not solved here): a press that fires while the
 * app is backgrounded cannot promote a `microphone` service on API 34+ (SecurityException
 * → caught → session runs unprotected until Phase 3 foregrounds the press). Receiver-side
 * `mediaPlayback` promotion has no such gate.
 */
public class PttSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var stateJob: Job? = null
    private var currentFgsType = -1
    private var startForegroundCalled = false

    private val notifications: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(this)
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = com.transfer.flash.debug.DiscoveryEngineHolder.currentPttSession()
        if (engine == null) {
            Log.w(TAG, "No PTT engine — stopping service")
            stopSelf()
            return START_NOT_STICKY
        }
        if (stateJob?.isActive != true) {
            stateJob = scope.launch {
                combine(engine.state, engine.stats.sample(STATS_SAMPLE_MS)) { state, stats ->
                    state to stats
                }.collectLatest { (state, stats) -> render(state, stats) }
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
    private fun render(state: PttFloorState, stats: PttSessionStats?) {
        if (state is PttFloorState.Idle) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val talking = state as? PttFloorState.Talking
        val listening = state as? PttFloorState.Listening
        if (talking == null && listening == null) return
        val startedAtMs = talking?.startedAtMs ?: listening!!.startedAtMs
        val content = pttSessionContent(
            talking = talking != null,
            peerName = listening?.holderName,
            memberCount = stats?.members ?: 0,
            rttMs = stats?.rttMs,
            lossPercent = stats?.lossPercent ?: 0f,
        )
        val stopIntent = PendingIntent.getBroadcast(
            this,
            REQUEST_STOP,
            Intent(PttSessionActionReceiver.ACTION_STOP).setPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val tapIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_flash)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setWhen(startedAtMs)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setContentIntent(tapIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                if (talking != null) "Stop" else "Leave",
                stopIntent,
            )
            .build()

        // Posted unconditionally: the session must be visible (and stoppable) even when
        // the platform refuses foreground promotion (backgrounded press, missing grant).
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "notify failed", it) }

        promoteToForeground(notification, talking != null)
    }

    /**
     * Enters (or upgrades) the foreground state with a service type the platform will
     * actually accept. Talker claims `microphone` only while RECORD_AUDIO is granted;
     * listener claims `mediaPlayback` (no runtime grant needed for the type); anything
     * else falls back exactly like the call service.
     */
    private fun promoteToForeground(notification: Notification, talking: Boolean) {
        val desiredType = grantedForegroundServiceType(talking)
        if (startForegroundCalled && desiredType == currentFgsType) return
        if (tryStartForeground(notification, desiredType)) {
            startForegroundCalled = true
            currentFgsType = desiredType
            return
        }
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
            Log.w(TAG, "startForeground(type=$type) refused", e)
            false
        }
    }

    private fun grantedForegroundServiceType(talking: Boolean): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return FGS_TYPE_NONE
        var type = FGS_TYPE_NONE
        if (talking &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            hasPermission(Manifest.permission.RECORD_AUDIO)
        ) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (!talking) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        if (type == FGS_TYPE_NONE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        }
        return type
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * The session channel is deliberately HIGH + silent: IMPORTANCE_HIGH still produces
     * the heads-up banner a new session needs while `setSound(null)` + no vibration
     * keep it from ever ringing like a call (same rationale as `flash_calls_v2`).
     */
    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "PTT sessions",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Live push-to-talk sessions: elapsed time, latency and stop control"
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    public companion object {
        private const val TAG = "PTTSVC"
        private const val CHANNEL_ID = "flash_ptt"
        private const val NOTIFICATION_ID = 43
        private const val REQUEST_STOP = 2001
        private const val STATS_SAMPLE_MS = 1000L

        /**
         * `ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE` (0), inlined to keep its platform
         * deprecation out of the build log (same trick as the call service).
         */
        private const val FGS_TYPE_NONE = 0

        /** Start the session FGS. Best-effort: a backgrounded press may be refused (API 31+). */
        public fun start(context: Context) {
            val intent = Intent(context.applicationContext, PttSessionService::class.java)
            runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }.onFailure { error ->
                Log.w(TAG, "Unable to start PTT session foreground service", error)
            }
        }

        public fun stop(context: Context) {
            context.stopService(Intent(context, PttSessionService::class.java))
        }
    }
}

/** Pure session-notification copy (unit-tested; the service adds icons/actions). */
internal data class PttNotificationContent(
    val title: String,
    val body: String,
)

internal fun pttSessionContent(
    talking: Boolean,
    peerName: String?,
    memberCount: Int,
    rttMs: Long?,
    lossPercent: Float,
): PttNotificationContent {
    val bits = mutableListOf("Live")
    if (talking) {
        if (memberCount > 0) bits += "$memberCount listening"
    } else {
        bits += "${(lossPercent * 100).roundToInt()}% loss"
    }
    rttMs?.let { bits += "$it ms" }
    val title = if (talking) {
        "PTT — You're talking"
    } else {
        "PTT — ${peerName?.ifBlank { null } ?: "Peer"}"
    }
    return PttNotificationContent(title, bits.joinToString(" • "))
}
