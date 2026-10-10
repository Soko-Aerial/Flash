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
import com.transfer.flash.core.messaging.ptt.PttFloorState
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.MainActivity
import com.transfer.flash.notifications.FlashNotificationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        keepAlive = TranssionHiberKeepAlive(
            context = this,
            onTierChanged = { oldTier, newTier ->
                Log.i(TAG, "Transsion Hiber keep-alive tier changed: $oldTier -> $newTier")
                val transfers = DiscoveryEngineHolder.currentTransfers()?.activeTransfers?.value ?: emptyList()
                updateTransferNotification(transfers)
            },
        )
        keepAlive?.start()
        scope.launch {
            runCatching {
                DiscoveryEngineHolder.ensureStarted(applicationContext)
                DiscoveryEngineHolder.currentSwarm()?.reevaluate()
            }.onFailure { Log.w(TAG, "engine start failed in background service", it) }
        }
        if (startAsForeground()) {
            promotionRefused.set(false)
        } else {
            promotionRefused.set(true)
            Log.w(TAG, "Foreground promotion refused (background start restriction) — retaining service instance in background; promotion will be retried upon screen-on / network availability")
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
        val plan = TimeoutStopPlan.of(allTransfers, isSwarmRow = { id -> transfers?.isExternalRow(id.value) == true })
        for (transfer in plan.toCancel) {
            // Not [scope]: stopSelf() → onDestroy() cancels it before these could run.
            timeoutCleanupScope.launch { runCatching { transfers?.cancelTransfer(transfer.id) } }
        }
        for (transfer in plan.toPause) {
            timeoutCleanupScope.launch { runCatching { transfers?.pauseForSystem(transfer.id, "SERVICE_TIMEOUT") } }
        }
        stopSelf()
        super.onTimeout(startId, fgsType)
    }

    private fun buildIdleNotification(activeTransfersCount: Int = 0): Notification {
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
        val tapIntent = PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val bodyText = if (activeTransfersCount > 0) {
            "$text • $activeTransfersCount active transfer(s)"
        } else {
            text
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(bodyText)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentIntent(tapIntent)
            .setProgress(0, 0, false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startAsForeground(): Boolean {
        FlashNotificationManager.initChannels(this)
        val notification: Notification = buildIdleNotification()
        val fgsType = resolveForegroundServiceType()
        Log.i(TAG, "Calling startForeground with NOTIFICATION_ID=$NOTIFICATION_ID, fgsType=$fgsType")
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
            true
        } catch (error: Exception) {
            Log.e(TAG, "startForeground refused", error)
            false
        }
    }

    private fun observeSessionActivity() {
        scope.launch {
            DiscoveryEngineHolder.transfersFlow.collectLatest { repo: FlashTransferRepository? ->
                val transfersFlow: StateFlow<List<FlashTransfer>> = repo?.activeTransfers ?: MutableStateFlow(emptyList<FlashTransfer>())
                while (isActive && DiscoveryEngineHolder.currentNetwork() == null) {
                    delay(300)
                }
                val network = DiscoveryEngineHolder.currentNetwork()
                val calling = DiscoveryEngineHolder.currentCalling()
                val ptt = DiscoveryEngineHolder.currentPttSession()

                val sessionsFlow = network?.activeSessions ?: MutableStateFlow(emptyMap())
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

                    val isActive = hasActiveTransfers || hasConnectedPeers || isCallActive || isPttActive
                    Log.i(TAG, "Session activity evaluated: active=$isActive (activeTransfers=$hasActiveTransfers count=${transfers.size}, peers=${sessions.size}, call=$isCallActive, ptt=$isPttActive)")
                    isActive
                }.collect { isSessionActive ->
                    keepAlive?.setSessionActive(isSessionActive)
                }
            }
        }
    }

    private fun resolveForegroundServiceType(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        if (keepAlive?.currentTier == TranssionHiberKeepAlive.KeepAliveTier.AUDIO &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        Log.i(TAG, "resolveForegroundServiceType: SDK_INT=${Build.VERSION.SDK_INT}, types=$types, tier=${keepAlive?.currentTier}")
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

            if (prevState == null) {
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
        if (active.size == lastActiveTransfersCount && currentMode == lastDiscoveryMode && isForeground) {
            return
        }
        lastActiveTransfersCount = active.size
        lastDiscoveryMode = currentMode

        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification = buildIdleNotification(activeTransfersCount = active.size)
        if (isForeground) {
            manager.notify(NOTIFICATION_ID, notification)
        } else {
            if (startAsForeground()) {
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
            activeInstance?.keepAlive?.reevaluateTier()
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
