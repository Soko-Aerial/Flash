@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.debug

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.ptt.floor.PttFloorState
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.MainActivity
import com.transfer.flash.R
import com.transfer.flash.notifications.FlashNotificationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
    private var keepAlive: TranssionHiberKeepAlive? = null
    private val previousTransferStates = ConcurrentHashMap<String, FlashTransferState>()
    private val lastProgressPostMs = ConcurrentHashMap<String, Long>()
    private val lastProgressPercent = ConcurrentHashMap<String, Int>()
    private var lastActiveTransfersCount = -1
    private var lastDiscoveryMode: com.transfer.flash.core.discovery.core.FlashDiscoveryMode? = null
    private var isFirstTransferCollection = true
    private var isForeground = false
    private var currentFgsTypes = 0

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        // Android gives startForegroundService() five seconds to reach startForeground(). On the
        // Infinix this used to lose that race to the Transsion keep-alive binder work and kill the
        // process with ForegroundServiceDidNotStartInTimeException (phone log 2026-10-26 10:29:53).
        // Promote first; everything else may wait.
        if (startAsForeground()) {
            promotionRefused.set(false)
        } else {
            promotionRefused.set(true)
            Log.w(TAG, "Foreground promotion refused (background start restriction) — retaining service instance in background; promotion will be retried upon screen-on / network availability")
        }
        keepAlive = TranssionHiberKeepAlive(
            context = this,
            onTierChanged = { oldTier, newTier ->
                Log.i(TAG, "Transsion Hiber keep-alive tier changed: $oldTier -> $newTier")
                val transfers = DiscoveryEngineHolder.currentTransfers()?.activeTransfers?.value ?: emptyList()
                updateTransferNotification(transfers)
            },
        )
        // Keep the OEM hibernation binder off the main thread as well: start() may probe system
        // settings and must never sit between startForeground() and the rest of onCreate().
        scope.launch { runCatching { keepAlive?.start() } }
        scope.launch {
            runCatching {
                DiscoveryEngineHolder.ensureStarted(applicationContext)
                DiscoveryEngineHolder.currentSwarm()?.reevaluate()
            }.onFailure { Log.w(TAG, "engine start failed in background service", it) }
        }

        observeSessionActivity()

        // Observe active transfers via transfersFlow to survive engine restarts and post completions/failures
        scope.launch {
            DiscoveryEngineHolder.transfersFlow.collectLatest { repo: FlashTransferRepository? ->
                repo?.activeTransfers?.collect { transfers: List<FlashTransfer> ->
                    handleTransfersChanged(transfers)
                }
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
        // C-02: a transfer the platform paused (foreground-service time limit) continues once the service runs again.
        if (isForeground) {
            scope.launch { runCatching { DiscoveryEngineHolder.currentTransfers()?.resumeSystemPaused() } }
        }
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
        previousTransferStates.clear()
        lastProgressPostMs.clear()
        lastProgressPercent.clear()
        lastActiveTransfersCount = -1
        lastDiscoveryMode = null
        isFirstTransferCollection = true
        isForeground = false
        keepAlive?.stop()
        keepAlive = null
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
        val allTransfers = transfers?.activeTransfers?.value ?: emptyList()
        val plan = TimeoutStopPlan.of(allTransfers)
        for (transfer in plan.toPause) {
            // Not [scope]: stopSelf() → onDestroy() cancels it before these could run.
            timeoutCleanupScope.launch { runCatching { transfers?.pauseForSystem(transfer.id, "SERVICE_TIMEOUT") } }
        }
        stopSelf()
        super.onTimeout(startId, fgsType)
    }

    private fun buildIdleNotification(activeTransfersCount: Int = 0): Notification {
        val mode = DiscoveryEngineHolder.currentDiscoveryMode()
        val content = FlashBackgroundNotificationCopy.idleContent(
            mode = mode,
            activeTransfersCount = activeTransfersCount,
        )
        val tapIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setSmallIcon(R.drawable.ic_notification_flash)
            .setContentIntent(tapIntent)
            .setProgress(0, 0, false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startAsForeground(transfers: List<FlashTransfer> = emptyList()): Boolean {
        FlashNotificationManager.initChannels(this)
        val activeCount = transfers.count {
            it.state == FlashTransferState.Transferring || it.state == FlashTransferState.Verifying
        }
        val notification: Notification = buildIdleNotification(activeTransfersCount = activeCount)
        val fgsType = resolveForegroundServiceType(transfers)
        Log.i(TAG, "Calling startForeground with NOTIFICATION_ID=$NOTIFICATION_ID, fgsType=$fgsType")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            currentFgsTypes = fgsType
            isForeground = true
            true
        } catch (error: Exception) {
            Log.e(TAG, "startForeground refused", error)
            false
        }
    }

    private var idleStopJob: Job? = null

    /**
     * The last "does the mesh have work" answer. It is deliberately re-read when the grace period expires: the answer
     * that armed the timer is [IDLE_STOP_GRACE_MS] old by then, and a peer or the hub may have connected since.
     */
    @Volatile
    private var lastSessionActive: Boolean = true

    private fun observeSessionActivity() {
        scope.launch {
            combine(
                DiscoveryEngineHolder.transfersFlow,
                DiscoveryEngineHolder.networkFlow,
            ) { repo, network ->
                Pair(repo, network)
            }.collectLatest { (repo, network) ->
                val transfersFlow: StateFlow<List<FlashTransfer>> = repo?.activeTransfers ?: MutableStateFlow(emptyList())
                val sessionsFlow = network?.activeSessions ?: MutableStateFlow(emptyMap())
                val calling = DiscoveryEngineHolder.currentCalling()
                val ptt = DiscoveryEngineHolder.currentPttSession()

                val callFlow = calling?.activeCall ?: MutableStateFlow(null)
                val pttFlow = ptt?.state ?: MutableStateFlow(PttFloorState.Idle)

                combine(transfersFlow, sessionsFlow, callFlow, pttFlow) { transfers, sessions, callState, pttState ->
                    val hasActiveTransfers = transfers.any {
                        it.state == FlashTransferState.Transferring ||
                            it.state == FlashTransferState.Verifying ||
                            it.state == FlashTransferState.Queued
                    }
                    val hasConnectedPeers = sessions.isNotEmpty()
                    val isCallActive = callState != null && callState.state != FlashCallState.ENDED
                    val isPttActive = pttState !is PttFloorState.Idle
                    val isHubConnected = DiscoveryEngineHolder.currentEnterpriseHub()?.relay?.state?.value?.isOnline == true

                    val isActive = hasActiveTransfers || hasConnectedPeers || isCallActive || isPttActive || isHubConnected
                    Log.i(TAG, "Session activity evaluated: active=$isActive (activeTransfers=$hasActiveTransfers count=${transfers.size}, peers=${sessions.size}, call=$isCallActive, ptt=$isPttActive, hub=$isHubConnected)")
                    isActive
                }.collect { isSessionActive ->
                    keepAlive?.setSessionActive(isSessionActive)
                    evaluateServiceIdleStop(isSessionActive)
                }
            }
        }
    }

    /**
     * Stops the connectedDevice foreground service when 0 peers, 0 transfers, and 0 hub connection exist,
     * unless the user explicitly enabled "Keep listening in the background" (OPT-O13-06, ADR-232).
     *
     * Called on every session-activity change and again from [reevaluateIdleStop] when the app leaves the foreground.
     * A round that ends without stopping — the app was on screen, or the user has opted in — must leave the service
     * able to arm again, so the guard tests [Job.isActive] rather than `== null`: a finished job is rearmable, and a
     * sequence as ordinary as "open Flash with nobody nearby, then leave it" needs exactly that second round.
     */
    private fun evaluateServiceIdleStop(isSessionActive: Boolean) {
        lastSessionActive = isSessionActive
        // Through the holder's accessor, not the mirror field: a START_STICKY regeneration starts this
        // service in a process where AppEngine never ran, so the mirror is empty and only the copy the
        // settings store left on disk knows whether the user opted in (ADR-232).
        val keepListening = DiscoveryEngineHolder.keepListeningInBackground(this)
        if (isSessionActive || keepListening) {
            idleStopJob?.cancel()
            idleStopJob = null
            return
        }

        if (idleStopJob?.isActive == true) return
        idleStopJob = scope.launch {
            delay(IDLE_STOP_GRACE_MS)
            // The live state decides, not the emission that armed the timer: a peer, a transfer or the hub relay may
            // have arrived in the meantime, and stopping under them would drop what they are waiting for.
            val stillIdle = !lastSessionActive &&
                !DiscoveryEngineHolder.keepListeningInBackground(applicationContext) &&
                DiscoveryEngineHolder.currentEnterpriseHub()?.relay?.state?.value?.isOnline != true &&
                DiscoveryEngineHolder.currentNetwork()?.activeSessions?.value.isNullOrEmpty() &&
                !isForegroundInteractive()
            if (stillIdle) {
                Log.i(TAG, "Stopping connectedDevice foreground service: 0 peers, 0 transfers, 0 hub connection (OPT-O13-06 / ADR-232)")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                Log.i(TAG, "Idle grace expired but work reappeared; keeping the connectedDevice foreground service")
            }
        }
    }

    private fun isForegroundInteractive(): Boolean = runCatching {
        val state = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(state)
        state.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }.getOrDefault(false)

    private fun resolveForegroundServiceType(transfers: List<FlashTransfer> = emptyList()): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val hasActiveTransfers = transfers.any {
            it.state == FlashTransferState.Transferring || it.state == FlashTransferState.Verifying
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (hasActiveTransfers) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
        }
        if (keepAlive?.currentTier == TranssionHiberKeepAlive.KeepAliveTier.AUDIO &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        Log.i(TAG, "resolveForegroundServiceType: SDK_INT=${Build.VERSION.SDK_INT}, types=$types, activeTransfers=$hasActiveTransfers, tier=${keepAlive?.currentTier}")
        return types
    }

    private fun handleTransfersChanged(transfers: List<FlashTransfer>) {
        if (isFirstTransferCollection) {
            isFirstTransferCollection = false
            for (transfer in transfers) {
                previousTransferStates[transfer.id.value] = transfer.state
                if (transfer.state == FlashTransferState.Transferring || transfer.state == FlashTransferState.Verifying) {
                    FlashNotificationManager.showTransferProgress(this, transfer)
                } else if (transfer.state == FlashTransferState.Offered) {
                    FlashNotificationManager.showTransferOffered(this, transfer)
                }
            }
            updateTransferNotification(transfers)
            return
        }

        for (transfer in transfers) {
            val id = transfer.id.value
            val prevState = previousTransferStates[id]
            previousTransferStates[id] = transfer.state

            val percent = if (transfer.bytesTotal > 0L) {
                ((transfer.bytesDone * 100) / transfer.bytesTotal).toInt().coerceIn(0, 100)
            } else 0

            if (prevState == null && transfer.restored) {
                // Read back from the store after a restart (C-09): listed in Transfers, not announced again.
                Unit
            } else if (prevState == null) {
                when (transfer.state) {
                    FlashTransferState.Completed -> {
                        FlashNotificationManager.showTransferCompleted(this, transfer)
                    }
                    FlashTransferState.Failed -> {
                        FlashNotificationManager.showTransferFailed(this, transfer)
                    }
                    FlashTransferState.Transferring, FlashTransferState.Verifying -> {
                        lastProgressPostMs[id] = System.currentTimeMillis()
                        lastProgressPercent[id] = percent
                        FlashNotificationManager.showTransferProgress(this, transfer)
                    }
                    FlashTransferState.Paused -> {
                        FlashNotificationManager.showTransferPaused(this, transfer)
                    }
                    FlashTransferState.Offered -> {
                        FlashNotificationManager.showTransferOffered(this, transfer)
                    }
                    else -> Unit
                }
            } else if (prevState != transfer.state) {
                when (transfer.state) {
                    FlashTransferState.Completed -> {
                        lastProgressPostMs.remove(id)
                        lastProgressPercent.remove(id)
                        FlashNotificationManager.showTransferCompleted(this, transfer)
                    }
                    FlashTransferState.Failed -> {
                        lastProgressPostMs.remove(id)
                        lastProgressPercent.remove(id)
                        FlashNotificationManager.showTransferFailed(this, transfer)
                    }
                    FlashTransferState.Cancelled -> {
                        lastProgressPostMs.remove(id)
                        lastProgressPercent.remove(id)
                        FlashNotificationManager.clearTransfer(this, id)
                    }
                    FlashTransferState.Paused -> {
                        lastProgressPostMs.remove(id)
                        lastProgressPercent.remove(id)
                        FlashNotificationManager.showTransferPaused(this, transfer)
                    }
                    FlashTransferState.Offered -> {
                        lastProgressPostMs.remove(id)
                        lastProgressPercent.remove(id)
                        FlashNotificationManager.showTransferOffered(this, transfer)
                    }
                    FlashTransferState.Transferring, FlashTransferState.Verifying -> {
                        lastProgressPostMs[id] = System.currentTimeMillis()
                        lastProgressPercent[id] = percent
                        FlashNotificationManager.showTransferProgress(this, transfer)
                    }
                    FlashTransferState.Queued -> {
                        // Waiting (peer away, network, Android): an ongoing bar frozen at the last percentage, with a
                        // Cancel button, would be false information (audit B-07). The Transfers tab shows the wait.
                        lastProgressPostMs.remove(id)
                        lastProgressPercent.remove(id)
                        FlashNotificationManager.clearTransfer(this, id)
                    }
                    else -> Unit
                }
            } else if (transfer.state == FlashTransferState.Transferring || transfer.state == FlashTransferState.Verifying) {
                // Throttle progress updates to at most once per 500ms or on percent change,
                // avoiding Binder IPC flood and system notification drops
                val now = System.currentTimeMillis()
                val lastPost = lastProgressPostMs[id] ?: 0L
                val lastPercent = lastProgressPercent[id] ?: -1
                if (now - lastPost >= 500L || percent != lastPercent) {
                    lastProgressPostMs[id] = now
                    lastProgressPercent[id] = percent
                    FlashNotificationManager.showTransferProgress(this, transfer)
                }
            }
        }

        val currentIds = transfers.map { it.id.value }.toSet()
        val removedIds = previousTransferStates.keys.filter { it !in currentIds }
        for (removedId in removedIds) {
            FlashNotificationManager.clearTransfer(this, removedId)
            previousTransferStates.remove(removedId)
            lastProgressPostMs.remove(removedId)
            lastProgressPercent.remove(removedId)
        }

        updateTransferNotification(transfers)
    }

    private fun updateTransferNotification(transfers: List<FlashTransfer>) {
        val active = transfers.filter {
            it.state == FlashTransferState.Transferring || it.state == FlashTransferState.Verifying
        }
        val currentMode = DiscoveryEngineHolder.currentDiscoveryMode()
        val neededTypes = resolveForegroundServiceType(transfers)
        if (active.size == lastActiveTransfersCount && currentMode == lastDiscoveryMode && isForeground && neededTypes == currentFgsTypes) {
            return
        }
        lastActiveTransfersCount = active.size
        lastDiscoveryMode = currentMode

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildIdleNotification(activeTransfersCount = active.size)
        if (isForeground) {
            if (neededTypes != currentFgsTypes) {
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIFICATION_ID, notification, neededTypes)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    currentFgsTypes = neededTypes
                }.onFailure {
                    manager.notify(NOTIFICATION_ID, notification)
                }
            } else {
                manager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            if (startAsForeground(transfers)) {
                promotionRefused.set(false)
            } else {
                manager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    companion object {
        private const val TAG = "SERVICE"
        private const val CHANNEL_ID = "flash_discovery_bg"
        private const val NOTIFICATION_ID = 41
        private const val IDLE_STOP_GRACE_MS = 30_000L

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

        fun reevaluateKeepAlive() {
            activeInstance?.keepAlive?.onAppForegrounded()
        }

        /**
         * Re-asks the idle-stop question after the app leaves the foreground.
         *
         * [evaluateServiceIdleStop] only runs when the *mesh* changes, and going to the background is not such a change,
         * so without this call the ordinary sequence "open Flash with nobody nearby, then leave it" never reaches the
         * idle stop at all: the first round happened while the app was on screen and declined to stop (OPT-O13-06).
         */
        fun reevaluateIdleStop() {
            val current = activeInstance ?: return
            current.evaluateServiceIdleStop(current.lastSessionActive)
        }

        fun setSessionActive(active: Boolean) {
            activeInstance?.keepAlive?.setSessionActive(active)
        }

        fun isSessionActive(): Boolean =
            activeInstance?.keepAlive?.isSessionActive ?: false

        fun currentKeepAliveTier(): TranssionHiberKeepAlive.KeepAliveTier =
            activeInstance?.keepAlive?.currentTier ?: TranssionHiberKeepAlive.KeepAliveTier.NONE
    }
}

internal data class FlashIdleNotificationContent(
    val title: String,
    val body: String,
)

internal object FlashBackgroundNotificationCopy {
    fun idleContent(
        mode: com.transfer.flash.core.discovery.core.FlashDiscoveryMode,
        activeTransfersCount: Int = 0,
    ): FlashIdleNotificationContent {
        val (title, baseText) = when (mode) {
            com.transfer.flash.core.discovery.core.FlashDiscoveryMode.STANDARD ->
                "Flash is discoverable" to "Nearby devices can find and connect to this phone."
            com.transfer.flash.core.discovery.core.FlashDiscoveryMode.GHOST ->
                "Flash is hidden" to "Searching nearby — other devices cannot see this phone."
            com.transfer.flash.core.discovery.core.FlashDiscoveryMode.ECO ->
                "Flash is saving battery" to "Battery-saving nearby discovery is on."
            com.transfer.flash.core.discovery.core.FlashDiscoveryMode.BOOST ->
                "Flash is in fast discovery" to "High-responsiveness nearby discovery is on."
            com.transfer.flash.core.discovery.core.FlashDiscoveryMode.RECEIVE_KIOSK ->
                "Flash is ready to receive" to "Waiting for incoming files from paired devices."
        }
        val body = if (activeTransfersCount > 0) {
            val transferLabel = if (activeTransfersCount == 1) {
                "1 active transfer"
            } else {
                "$activeTransfersCount active transfers"
            }
            "$baseText • $transferLabel"
        } else {
            baseText
        }
        return FlashIdleNotificationContent(title = title, body = body)
    }
}
