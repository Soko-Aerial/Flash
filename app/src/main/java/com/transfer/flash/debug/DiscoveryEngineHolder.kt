@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.debug

import com.transfer.flash.core.persistence.db.runInWriteTransaction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.Manifest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.messaging.RealFlashChatRepository
import com.transfer.flash.core.messaging.protocol.ChatTextFrameCodec
import com.transfer.flash.core.messaging.protocol.DirectChatFamily
import com.transfer.flash.core.messaging.protocol.DirectMessageActionCodec
import com.transfer.flash.core.messaging.protocol.GroupFrameCodec
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.messaging.protocol.PttFrameCodec
import com.transfer.flash.core.messaging.protocol.PttAudioFrame
import com.transfer.flash.core.messaging.protocol.PttSessionCodec
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.ws.WsFlashNetwork
import com.transfer.flash.core.network.ws.WsSession
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.TofuPinVerifier
import com.transfer.flash.core.network.tls.requireTransportSecurity
import com.transfer.flash.core.security.crypto.E2eFrameCodec
import com.transfer.flash.core.security.crypto.FlashFingerprint
import com.transfer.flash.core.security.crypto.KeystoreFlashCrypto
import com.transfer.flash.core.security.crypto.SecureBinaryFrameCodec
import com.transfer.flash.core.security.trust.AndroidPreferencesTrustStore
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import com.transfer.flash.core.calling.CallCoordinator
import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.core.calling.FlashWebRtcEngine
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.calling.FlashCallRinger
import com.transfer.flash.calling.FlashCallService
import com.transfer.flash.pairing.PairingCoordinator
import com.transfer.flash.core.ptt.FlashPtt
import com.transfer.flash.core.ptt.PttPressOutcome
import com.transfer.flash.core.ptt.PttSessionEngine
import com.transfer.flash.ptt.PttSessionService
import com.transfer.flash.core.messaging.ptt.PttFloorState
import com.transfer.flash.net.AutoConnectGate
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.engine.store.RoomTransferStore
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.IncrementalSha256
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.Sha256
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.policy.FileRandomAccessSinkHandle
import com.transfer.flash.core.transfer.policy.RandomAccessChunkSink
import com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle
import com.transfer.flash.core.transfer.policy.TransferReconnectResumePolicy
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.identity.AppIdentity
import com.transfer.flash.MainActivity
import com.transfer.flash.notifications.FlashNotificationManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.source

/**
 * Process-wide engine holder for the debug Dev Console and background service.
 *
 * Utilizes [WsFlashNetwork] as the full-duplex WebSocket mesh network layer,
 * wired to [RealFlashChatRepository] for instant messaging and [RealFlashTransferRepository]
 * with [ReceivePipeline] for chunked binary file transfers.
 *
 * Received files land at `<external-files>/FlashReceived/<transferId>/<safeFileName>` written
 * via random-access handles at exact chunk offsets (`index * chunkSize`), which is REQUIRED
 * for correct out-of-order multi-stream assembly (ADR-015).
 */
object DiscoveryEngineHolder {

    private const val TAG_DISCOVERY = "DISCOVERY"
    private const val TAG_TRANSFER = "TRANSFER"
    private const val TAG_CHAT = "CHAT"
    private const val TAG_WS = "WS"
    private const val RECEIVED_FILES_DIRECTORY = "FlashReceived"

    /** Explicit Dev Console test payload URI — intentionally streams generated bytes. */
    private const val TEST_PAYLOAD_URI = "file:///dummy/test_payload.bin"

    /**
     * How long auto-resume waits after a session comes up before re-offering. Long enough for
     * `WsFlashNetwork.registerSession` to have closed the loser of a two-way dial, short next to the
     * seconds the link was already down.
     */
    private const val SETTLE_BEFORE_RESUME_MS = 750L

    private const val XFER_PREFIX = "FLASH_XFER"
    private const val PAIR_PREFIX = "FLASH_PAIR"

    /**
     * Calling-signaling prefix (C7 / ADR-025). Held here as a prefix the holder recognizes itself
     * rather than going through `CallFrameCodec.decode`, so the inbound gate and the module's own
     * `onInboundText` never decode the same frame twice per inbound text frame.
     */
    private const val CALL_PREFIX = "FLASH_CALL"

    /**
     * Zello PTT hook emitted by tydtech-firmware clip mics (see docs/android-platform-notes.md).
     * The same press also emits scanner/lowercase/uppercase variants — this receiver listens
     * to the Zello down action ONLY so one press fans out exactly once. Single-press v1:
     * the up action is intentionally not observed.
     */
    private const val PTT_DOWN_ACTION = "com.zello.ptt.down"
    /**
     * Minimum gap between two accepted PTT presses; the OEM emits one down per click. The inbound
     * ping dedup set that used to live here is gone with the rest of the duplicate ping pipeline —
     * `FlashPtt` owns it (see [com.transfer.flash.core.ptt.PttSessionEngine]).
     */
    private const val PTT_DEBOUNCE_MS = 800L

    @Volatile
    private var composite: CompositeDiscovery? = null

    @Volatile
    private var network: WsFlashNetwork? = null

    @Volatile
    private var transferRepo: FlashTransferRepository? = null

    @Volatile
    private var chatRepo: FlashChatRepository? = null

    @Volatile
    private var dataServer: com.transfer.flash.core.network.datachannel.DataChannelServer? = null

    @Volatile
    private var pairing: PairingCoordinator? = null

    @Volatile
    private var trustStoreRef: AndroidPreferencesTrustStore? = null

    /**
     * Live WebRTC calling engine (C7 / ADR-025). Constructed in [startEngineLocked], torn down in
     * [stopAll].
     *
     * Held as the module's public contract, not as the concrete `CallCoordinator`: every caller in
     * the app — inbound routing, the signaling-lifecycle collector, [currentCalling], the ringing
     * service and the notification action receiver — uses interface members only, so the concrete
     * type is needed nowhere but the construction site itself.
     */
    @Volatile
    private var calling: FlashCalling? = null

    /**
     * Owned here rather than by [FlashCallService] or the UI: a call has to ring even when the app
     * is closed (no activity) and even if the platform refuses to promote the call service to the
     * foreground. The engine outlives both, so the ring follows the call state machine for as long
     * as signaling exists. Released only in [stopAll].
     */
    @Volatile
    private var callRinger: FlashCallRinger? = null

    private var binderJob: Job? = null
    private var autoConnectJob: Job? = null
    private var callRingJob: Job? = null

    /**
     * Attempt-bounding gate for the auto-connect sweep, hoisted to a field so the screen-on
     * re-arm ([onScreenOn]) shares the same gate state as the periodic loop instead of racing
     * a second, independent gate.
     */
    @Volatile
    private var autoConnectGate: AutoConnectGate? = null

    /** Local device id, cached so [onScreenOn] can run a sweep without re-reading identity. */
    @Volatile
    private var localDeviceId: String? = null

    /** Local display name, cached for the PTT ping frame's senderName field. */
    @Volatile
    private var localDeviceName: String? = null

    /**
     * Attempt budget for restarting roam-killed sends, hoisted to a field so the budget survives
     * individual session-up edges — a per-edge instance would grant an unbounded number of attempts,
     * which is the thing the policy exists to prevent (ERROR-035).
     */
    private val reconnectResume = TransferReconnectResumePolicy()

    /**
     * Application context, cached for the engine's lifetime so the hooks that fire *outside* a
     * caller-supplied context — [onScreenOn]'s foreground-promotion retry and the Wi-Fi rejoin
     * hook handed to [WsFlashNetwork] — can reach [FlashBackgroundService]. Cleared by [stopAll];
     * an application context is a process-lifetime singleton, so holding it leaks nothing.
     */
    @Volatile
    private var appContextRef: Context? = null

    /**
     * Screen-on / user-present re-arm receiver, registered for the ENGINE's lifetime (ERROR-031).
     *
     * It used to live in [FlashBackgroundService], registered in `onCreate` and unregistered in
     * `onDestroy`. That service destroys its own instance when Android 12+ refuses its foreground
     * promotion while deliberately leaving the engine running — so the refused path tore down the
     * engine's only way to notice the screen coming back, leaving it with no foreground service AND
     * no re-arm. Exactly the same reasoning that moved the power locks here.
     *
     * These broadcasts cannot be declared in the manifest, so runtime registration is the only
     * option. Held from [startEngineLocked] until [stopAll].
     */
    @Volatile
    private var screenReceiver: BroadcastReceiver? = null

    /**
     * Hardware PTT receiver, registered for the ENGINE's lifetime like [screenReceiver].
     * An Activity-registered receiver dies with the UI; PTT must keep working backgrounded.
     * Implicit OEM broadcasts cannot be manifest-declared on Android 8+, so runtime
     * registration here is the only option. Held from [startEngineLocked] until [stopAll].
     */
    @Volatile
    private var pttReceiver: BroadcastReceiver? = null

    /** Last accepted hardware PTT down edge, for [PTT_DEBOUNCE_MS] press debouncing. */
    @Volatile
    private var lastPttDownMs: Long = 0L

    /**
     * Live PTT voice-session driver (ADR-032). Constructed in [startEngineLocked] once pairing
     * exists; torn down in [stopAll]. Null outside the engine lifetime — every call site treats
     * null as "engine not started" and degrades (tap-to-talk surface).
     *
     * The module owns ping decode/dedup and the session floor; this host only owns the mic grant,
     * the hardware receiver, the foreground service and the notification surface.
     */
    @Volatile
    private var pttEngine: FlashPtt? = null

    // Bug 3: auto-download settings, mirrored from FlashSettingsDataStore by AppEngine
    @Volatile
    var autoDownloadVoice: Boolean = true
    @Volatile
    var autoDownloadImage: Boolean = true
    @Volatile
    var autoDownloadVideo: Boolean = false
    @Volatile
    var autoDownloadFile: Boolean = false

    /**
     * ERROR-031 / D8: "Prioritise voice quality", mirrored from FlashSettingsDataStore by AppEngine
     * and read by [CallCoordinator] once a second for the life of a call.
     *
     * A mirrored `var` rather than a Flow handed to `core:calling`, for the same reason the
     * auto-download flags above are: the calling module must not know that DataStore exists
     * (ADR-024), and the coordinator only ever needs the value at the instant it asks.
     */
    @Volatile
    var prioritiseVoiceQuality: Boolean = true

    /**
     * ERROR-033: the device's performance tier, mirrored from `FlashSettingsDataStore` by
     * `AppEngine` (which resolves an unset/"auto" preference by classifying the hardware).
     *
     * A mirrored `var` read through a lambda for the same ADR-024 reason as
     * [prioritiseVoiceQuality]: `core:calling` and `core:network` must not know DataStore exists.
     *
     * Defaults to [FlashPerformanceMode.HIGH] — i.e. the stack's pre-tiering behaviour — so that
     * anything reading this before the mirror is installed behaves exactly as it did before this
     * feature existed. A `LOW` default would silently degrade every device during boot.
     *
     * Read per use, never captured: pinning a tier from Settings takes effect on the next call
     * and the next connection without a restart.
     */
    @Volatile
    var performanceMode: FlashPerformanceMode = FlashPerformanceMode.HIGH

    /**
     * Voice-call quiet flag: true while a call is ACTIVE, driven by the [CallCoordinator.activeCall]
     * collector next to the ringer wiring. While set, the auto-connect sweep is skipped and
     * discovery drops to ECO (see [setCallActive]): both exist to keep mDNS chatter and TCP dial
     * bursts off the half-duplex radio carrying Opus/RTP on low-end devices. Read per sweep tick,
     * never captured, so a mid-call state change takes effect on the next tick at the latest.
     */
    @Volatile
    private var callActive = false

    /**
     * Bug 3: invoked by [handleInboundBinary] after [RealFlashTransferRepository.onIncomingOffered]
     * so the auto-download policy can auto-accept known MIME types without user interaction.
     * Set inside [ensureStarted] to capture the local [acceptOffer] lambda.
     */
    @Volatile
    var onIncomingOffer: ((String) -> Unit)? = null

    @Volatile
    private var sendXferControl: ((peerDeviceId: String, action: String, transferId: String) -> Unit)? = null

    // #16: recreatable so stopAll can cancel every collector/session job launched on it. A cancelled
    // CoroutineScope stays cancelled, so ensureStarted swaps in a fresh one when restarting.
    private var appScope = newAppScope()

    private fun newAppScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var networkRestartJob: Job? = null

    /**
     * CPU wake lock keeping the mesh alive across screen-off / Doze. Without it the CPU is throttled,
     * so the WebSocket keepalive pings stall and inbound frames are processed far too late.
     *
     * ERROR-026: these locks belong to the ENGINE's lifetime, not to a
     * [FlashBackgroundService] instance. The service used to own them, which meant its
     * foreground-refused path (`startForeground` rejected on a sticky restart while backgrounded,
     * then `stopSelf`) released them in `onDestroy` while deliberately leaving the engine running
     * in-process — disarming both power locks in precisely the situation they exist for. Held from
     * [ensureStarted] until [stopAll].
     */
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Keeps the Wi-Fi radio fully powered while backgrounded. In power-save the radio parks between
     * beacons and physically drops packets our sockets and mDNS multicast reception depend on — a
     * loss no software watchdog can forgive, unlike a late keepalive tick.
     * `WIFI_MODE_FULL_LOW_LATENCY` (API 29+) additionally biases the radio toward low latency.
     * See [wakeLock] for why the engine owns this rather than the service.
     */
    @Volatile
    private var wifiLock: WifiManager.WifiLock? = null

    /** Serializes start/stop so racing callers cannot leak duplicate NSD engines/servers. */
    private val lifecycleMutex = Mutex()

    fun current(): CompositeDiscovery? = composite

    fun currentNetwork(): FlashNetwork? = network

    fun currentTransfers(): FlashTransferRepository? = transferRepo

    fun currentChats(): FlashChatRepository? = chatRepo

    fun currentPairing(): PairingCoordinator? = pairing

    fun currentCalling(): FlashCalling? = calling

    fun currentPttSession(): FlashPtt? = pttEngine

    fun beginVoiceNote(): String? = pttEngine?.acquireVoiceNoteLease() ?: UUID.randomUUID().toString()

    fun endVoiceNote(leaseId: String) {
        pttEngine?.releaseVoiceNoteLease(leaseId)
    }

    fun currentFriendlyName(): String? = localDeviceName

    /** True if the background mesh network has booted and is currently running. */
    fun isRunning(): Boolean = network != null

    private const val PREFS_DISCOVERY_MODE = "flash_discovery_mode"
    private const val KEY_MODE = "mode"

    private val _discoveryMode = MutableStateFlow(FlashDiscoveryMode.STANDARD)
    val discoveryMode: kotlinx.coroutines.flow.StateFlow<FlashDiscoveryMode> = _discoveryMode

    @Volatile
    var userDiscoveryMode: FlashDiscoveryMode = FlashDiscoveryMode.STANDARD
        private set

    fun currentDiscoveryMode(): FlashDiscoveryMode = userDiscoveryMode

    suspend fun setDiscoveryMode(mode: FlashDiscoveryMode, context: Context? = appContextRef) {
        userDiscoveryMode = mode
        _discoveryMode.value = mode
        val ctx = context ?: appContextRef
        ctx?.let { saveDiscoveryMode(it, mode) }
        val engine = composite ?: return
        if (!callActive) {
            engine.setMode(mode)
        }
    }

    private fun loadSavedDiscoveryMode(context: Context): FlashDiscoveryMode {
        val prefs = context.getSharedPreferences(PREFS_DISCOVERY_MODE, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_MODE, FlashDiscoveryMode.STANDARD.name) ?: FlashDiscoveryMode.STANDARD.name
        return runCatching { FlashDiscoveryMode.valueOf(name) }.getOrDefault(FlashDiscoveryMode.STANDARD)
    }

    private fun saveDiscoveryMode(context: Context, mode: FlashDiscoveryMode) {
        val prefs = context.getSharedPreferences(PREFS_DISCOVERY_MODE, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    fun updateFriendlyName(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        localDeviceName = trimmed
        (network as? WsFlashNetwork)?.localFriendlyName = trimmed
        val engine = composite
        val port = (network as? WsFlashNetwork)?.serverPort ?: 0
        val id = localDeviceId
        if (engine != null && port > 0 && id != null) {
            val newIdentity = FlashAdvertisedIdentity(
                deviceId = FlashDeviceId(id),
                friendlyName = trimmed,
                deviceModel = android.os.Build.MODEL ?: "unknown",
                protocolVersion = 2,
                capabilities = setOf(FlashDeviceKind.CAP_MOBILE),
            )
            engine.updateIdentity(newIdentity)
            appScope.launch {
                if (engine.state.value.isAdvertising) {
                    engine.stopAdvertising()
                    engine.startAdvertising(port)
                }
            }
        }
    }

    /**
     * Boots the whole stack once and returns the live discovery engine; later calls no-op.
     *
     * Runs [NonCancellable] on purpose (ERROR-026). Startup binds the WebSocket server socket,
     * registers the NSD advertisement and opens the encrypted database BEFORE it publishes
     * [composite], and every one of those side effects outlives the caller's coroutine scope. A
     * caller whose scope died mid-startup — [FlashBackgroundService] calling `stopSelf` when Android
     * 12+ refuses its foreground promotion, or an activity being destroyed — used to abort setup
     * partway, leaving a bound socket, a live NSD registration and an open database behind while the
     * holder still reported "not started". The next call then built a SECOND stack beside the
     * orphan. Cancellation cannot help here: only finishing setup keeps the holder's state and the
     * process's real resources in agreement. [stopAll] is the way to tear the engine down.
     */
    suspend fun ensureStarted(context: Context): CompositeDiscovery =
        withContext(NonCancellable) { startEngineLocked(context) }

    private suspend fun startEngineLocked(context: Context): CompositeDiscovery = lifecycleMutex.withLock {
        composite?.let { return it }
        // #16: a prior stopAll cancels appScope; a cancelled scope never runs new coroutines, so
        // start fresh before launching this session's collectors.
        if (!appScope.isActive) appScope = newAppScope()
        val appContext = context.applicationContext
        appContextRef = appContext
        // Power locks first: everything below (socket bind, NSD registration, first sessions) needs
        // an awake CPU and a fully-powered radio, and they now outlive any single service instance.
        // Audit S3: TLS is mandatory and is built BEFORE any side effect (power locks, receivers,
        // sockets), so a failure throws out of ensureStarted leaving nothing half-started; AppEngine
        // renders it as the start error with a retry. There is no plaintext fallback.
        val trustStore = AndroidPreferencesTrustStore(appContext)
        val crypto = KeystoreFlashCrypto(appContext)
        val tlsOptions = requireTransportSecurity(
            onAttemptFailed = { attempt, error -> Log.w(TAG_WS, "TLS setup attempt $attempt failed: ${error.message}") },
        ) {
            crypto.selfSignedCertificate()
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, null)
            val pinVerifier = TofuPinVerifier(
                lookupPin = { peerId -> trustStore.getPin(FlashDeviceId(peerId)) },
                recordPin = { peerId, pin -> trustStore.savePin(FlashDeviceId(peerId), pin) },
            )
            TlsOptions(
                pinVerifier = pinVerifier,
                keyManagers = kmf.keyManagers,
            )
        }
        acquirePowerLocks(appContext)
        registerScreenReceiver(appContext)
        registerPttReceiver(appContext)
        val identity0 = AppIdentity(appContext)
        val identity = FlashAdvertisedIdentity(
            deviceId = FlashDeviceId(
                value = identity0.deviceId.ifBlank { UUID.randomUUID().toString() },
            ),
            friendlyName = identity0.friendlyName.ifBlank { "Flash Device" },
            deviceModel = android.os.Build.MODEL ?: "unknown",
            protocolVersion = 2,
            capabilities = setOf(FlashDeviceKind.CAP_MOBILE),
        )

        Log.i(TAG_DISCOVERY, "Starting Flash discovery with deviceId=${identity.deviceId.value} friendlyName=${identity.friendlyName}")
        localDeviceId = identity.deviceId.value
        localDeviceName = identity.friendlyName

        val transport = com.transfer.flash.core.discovery.nsd.NsdTransport(
            context = appContext,
            apiLevel = com.transfer.flash.core.discovery.nsd.BuildNsdApiLevel,
            directory = com.transfer.flash.core.discovery.core.StandardEndpointDirectory(),
            sweep = { _ -> emptyList() },
        )

        // ADDITIVE second LAN transport (UDP multicast with self-announcement).
        //
        // NSD is NOT replaced, and this is not a fallback: Android↔Android discovery over NSD works
        // and keeps working exactly as it did. What the multicast transport adds is the two things
        // DNS-SD structurally cannot provide, both of which cost this project a debugging session:
        //
        //  - **Identity and address arrive in the same datagram.** There is no resolve step whose
        //    failure leaves a peer with a correct address and no device id — the `txtKeys=[] txtBytes=1`
        //    hollow-ServiceInfo state that made the desktop log "Dropping mDNS endpoint without
        //    device_id" every 30 s and never dial the phone.
        //  - **A lease bounds liveness.** Every peer re-announces on a cadence, so a peer that stops
        //    (app killed, radio off) is gone from the list in ~60 s. mDNS has no periodic positive
        //    signal, so it can only wait for a cache TTL nobody controls — an hour by JmDNS default —
        //    which is why the phone kept showing a desktop that had been closed.
        //
        // CompositeDiscovery dedups the two by device id, so a peer both transports see appears once,
        // and it fails soft: if this transport cannot bind (Wi-Fi off, cellular only) it degrades and
        // repairs itself rather than failing the composite's startAll.
        val multicastTransport = com.transfer.flash.core.discovery.multicast.MulticastTransport(
            socketFactory =
                com.transfer.flash.core.discovery.multicast.AndroidMulticastSocketFactory(appContext),
            directory = com.transfer.flash.core.discovery.core.StandardEndpointDirectory(),
        )
        val engine = CompositeDiscovery(transports = listOf(transport, multicastTransport))

        // Trust store is shared by chat (peer-name resolution), pairing (persisted trust), and TLS TOFU pinning.
        trustStoreRef = trustStore

        var boundServerPort = 0
        val networkImpl = WsFlashNetwork(
            context = appContext,
            localDeviceId = identity.deviceId.value,
            localFriendlyName = identity.friendlyName,
            tlsOptions = tlsOptions,
            // ERROR-031 / D7: a Wi-Fi rejoin is one of the few moments a foreground-service
            // promotion that was refused while backgrounded can succeed, and core:network is
            // already watching for it (its own watcher is `internal`, so :app cannot observe the
            // same edge without a second, duplicate ConnectivityManager callback). No-op unless a
            // promotion actually was refused.
            onUsableNetwork = {
                FlashBackgroundService.retryPromotionIfRefused(appContext)
                networkRestartJob?.cancel()
                networkRestartJob = appScope.launch {
                    Log.i(TAG_DISCOVERY, "Wi-Fi network connected/reconnected: restarting discovery instantly")
                    engine.restartDiscovery()
                    if (boundServerPort > 0) {
                        engine.startAdvertising(boundServerPort)
                    }
                }
            },
            // ERROR-033: keepalive cadence and the reconnect ceiling come from the device's tier.
            // A lambda, not a value: pinning a tier from Settings must reach the next connection
            // and the next redial without restarting the engine.
            transportProfile = { performanceMode.transport },
        )
        binderJob = DiscoveryRouteBinder.observe(appScope, engine.discoveredEndpoints, networkImpl)

        // Start WebSocket network server
        val netStartResult = networkImpl.start(0)
        val serverPort = (netStartResult as? FlashResult.Success)?.value ?: 0
        boundServerPort = serverPort
        check(serverPort > 0) { "Network server failed to start: ${(netStartResult as? FlashResult.Failure)?.error}" }

        val initialMode = loadSavedDiscoveryMode(appContext)
        userDiscoveryMode = initialMode
        _discoveryMode.value = initialMode
        engine.setMode(initialMode)
        val result = engine.startAll(serverPort, identity)
        // A partial transport failure must NOT abort the bring-up — see the sibling comment in
        // DesktopEngine.assemble(). `startAll` aggregates advertising+browsing across EVERY
        // transport and returns Failure if ANY one failed, while the transports that started keep
        // running. Throwing here (this was a `check`) killed everything after it — the database
        // open, the receive infrastructure, the session collectors and the auto-connect sweep —
        // because of a single radio. It is the same coupling that made the desktop show a peer in
        // its roster with `active sessions=[]` and "Couldn't reach …" on tap.
        (result as? FlashResult.Failure)?.let { failure ->
            Log.w(
                TAG_DISCOVERY,
                "Discovery startAll reported a partial failure; continuing with the transports " +
                    "that started — ${failure.error}",
            )
        }

        // Full-database encryption via SQLCipher with a keystore-wrapped passphrase; explicit
        // migrations, NO destructive fallback (C1.7 / D2) — a schema bump migrates data instead of
        // wiping it, and the on-disk DB is unreadable without this device's keystore.
        // Audit B7: shares the facade's recovery path (and its single passphrase provider), so a lost
        // keystore key quarantines the unopenable DB instead of crash-looping every launch.
        val db = com.transfer.flash.core.engine.store.EncryptedDatabaseRecovery.openRecoveringLostKey(appContext)

        // ---- receive-side infrastructure (must precede the send factory wiring) ----
        val receivedDir = receivedFilesRoot(appContext).apply { mkdirs() }
        val openHandles = ConcurrentHashMap<String, RandomAccessSinkHandle>()
        val incomingMeta = ConcurrentHashMap<String, ChunkFrame.FileStart>()
        // transferId -> absolute path of the received file on disk, so completed inbound transfers
        // (and the chat attachment rows they back) can be opened/shared via FileProvider.
        val receivedPaths = ConcurrentHashMap<String, String>()
        // peerDeviceId -> in-flight inbound transferIds, so a session/data-channel drop can fail and
        // clean up every stranded receive for that peer (#4). Sets are concurrent-safe.
        val incomingByPeer = ConcurrentHashMap<String, MutableSet<String>>()
        // Late-bound so the data-channel server listener (built below, before the cleanup closure
        // exists) can forward connection-closed events to the same inbound-cleanup path (#4).
        var onPeerConnectionClosed: ((String) -> Unit)? = null

        /** Per-peer resolved data-port offset from its advertised WS port (probed once). */
        val dataPortCache = ConcurrentHashMap<String, Int>()

        /**
         * Per-peer "no data server" expiry (epoch ms). A full 1..20 probe sweep that finds
         * nothing means the peer runs no `DataChannelServer` — desktops never do (JVM has no
         * counterpart), and re-probing on every channel open / retry turned each desktop send
         * into minutes of 4s timeouts before WS fallback (ERROR-062). Entries expire so a peer
         * that gains a server (rebuild/upgrade) is re-probed; a success clears the entry.
         */
        val noDataServerUntil = ConcurrentHashMap<String, Long>()
        val NO_DATA_SERVER_TTL_MS = 10 * 60 * 1000L

        // Late-bound so the receive pipeline's resume seam (built before the repo) can read the
        // repo's in-memory receiver done-set synchronously (#20).
        var transferForResume: RealFlashTransferRepository? = null

        val receivePipeline = ReceivePipeline(
            sink = { _, _ -> Log.w(TAG_TRANSFER, "Legacy shared sink invoked — expected per-transfer sinkFactory") },
            sinkFactory = { start ->
                val safeRelativePath = sanitizeRelativePath(start.fileName.ifBlank { "received.bin" })
                val safeId = sanitizePathComponent(start.transferId)
                val canonicalRoot = receivedDir.canonicalFile
                val destDir = File(canonicalRoot, safeId).canonicalFile
                val dest = File(destDir, safeRelativePath).canonicalFile
                // SENTINEL: Path traversal guard — canonical containment under destDir
                require(dest.path.startsWith(destDir.path + File.separator)) {
                    "Path traversal escape detected for transferId=${start.transferId}, fileName=${start.fileName}"
                }
                dest.parentFile?.mkdirs()
                receivedPaths[start.transferId] = dest.absolutePath
                val handle = FileRandomAccessSinkHandle(dest, start.totalBytes)
                openHandles[start.transferId] = handle
                Log.i(TAG_TRANSFER, "Receiver destination opened file=${dest.absolutePath} totalBytes=${start.totalBytes} chunkSize=${start.chunkSize}")
                RandomAccessChunkSink(handle, start.chunkSize)
            },
            emitSessionStarted = true,
            // #5: hold every fresh inbound transfer as an OFFER — no destination file is created
            // and no chunk is written until the user accepts (acceptSession resolves the sink).
            requireAcceptance = true,
            // #20: seed a resumed FILE_START's bit-vector from the persisted receiver done-set.
            resumeIndexesProvider = { start ->
                transferForResume?.receiverDoneIndexes(start.transferId) ?: emptyList()
            },
        )

        // Real N-socket multistream: dedicated plain-TCP data channels next to the WS port.
        val router = DataChannelRouter(receivePipeline, openHandles, incomingMeta, receivedPaths, incomingByPeer)
        val dcServer = com.transfer.flash.core.network.datachannel.DataChannelServer(
            localDeviceId = identity.deviceId.value,
            listener = object : com.transfer.flash.core.network.datachannel.DataChannelServer.Listener {
                override fun onFrame(
                    senderDeviceId: String,
                    channelId: Int,
                    payload: ByteArray,
                    reply: (ByteArray) -> Boolean,
                ) {
                    router.onBytes("dc:$channelId", senderDeviceId, payload, reply)
                }

                override fun onConnectionClosed(peerDeviceId: String?, channelId: Int) {
                    // A dropped data channel strands any inbound transfer mid-flight (#4); route the
                    // peer through the same cleanup the WS-session teardown uses.
                    peerDeviceId?.let { pid -> onPeerConnectionClosed?.invoke(pid) }
                }
            },
        )
        val dataPort = runCatching {
            dcServer.start(preferredPort = serverPort + 1)
        }.getOrElse {
            Log.w(TAG_WS, "DataChannelServer failed to bind: ${it.message}")
            0
        }
        if (dataPort > 0) Log.i(TAG_WS, "Real multistream data channels listening on port=$dataPort")

        // Late-bound reference so the stream factory can route inbound ACKs before assignment.
        var transferRef: RealFlashTransferRepository? = null

        val transferImpl = RealFlashTransferRepository(
            streamChannelFactory = { channelId, peerDeviceId ->
                // Real multistream: dedicated TCP data channel to the intended peer.
                // Falls back to the main WebSocket (multiplexed) when the peer's data server
                // is unreachable/old build — dispatcher semantics are identical either way.
                // F1: a NAMED peer with no session fails the stream; the old
                // `?: active.values.firstOrNull()` leaked group-addressed transfers (whose id
                // is a groupId, never a session key) to an arbitrary connected peer.
                val active = networkImpl.activeSessions.value
                val targeted = peerDeviceId?.let { id -> active[FlashDeviceId(id)] }
                val wsSession = (if (peerDeviceId == null) {
                    targeted ?: active.values.firstOrNull()
                } else {
                    targeted
                }) as? WsSession

                val resolvedPeerId = peerDeviceId ?: wsSession?.peerDeviceId?.value
                val sessionKey = resolvedPeerId?.let { trustStore.getSessionKey(FlashDeviceId(it)) }

                fun wsFallback(): StreamChannel? {
                    if (wsSession == null) {
                        Log.w(TAG_TRANSFER, "StreamChannel[$channelId] no session for peer=$peerDeviceId")
                        return null
                    }
                    return object : StreamChannel {
                        override val id: Int = channelId
                        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                            val toSend = if (sessionKey != null) {
                                SecureBinaryFrameCodec.encrypt(frameBytes, sessionKey)
                            } else {
                                frameBytes
                            }
                            return wsSession.connection.sendBinary(toSend)
                        }
                    }
                }

                if (dataPort <= 0 || peerDeviceId == null) return@RealFlashTransferRepository wsFallback()
                val endpoint = networkImpl.endpointOf(peerDeviceId) ?: return@RealFlashTransferRepository wsFallback()

                // Probe peer's data port: convention is wsPort+1..+20 (server binds its own).
                // Two short-circuits straight to WS fallback (ERROR-062 — a full sweep is up to
                // 20 × 4s per channel open, all of it pure timeout against a peer with no server):
                // 1. DESKTOP peers (discovery caps) run no DataChannelServer — the JVM has no
                //    counterpart — so probing is doomed by construction. PHONE/UNKNOWN still probe.
                // 2. A peer whose last full sweep found nothing, until the negative-cache TTL.
                // A later success clears the negative entry (see below).
                val nowMs = System.currentTimeMillis()
                val peerKind = engine.discoveredEndpoints.value
                    .firstOrNull { it.deviceId.value == peerDeviceId }?.deviceKind
                val negativeUntil = noDataServerUntil[peerDeviceId] ?: 0L
                if (peerKind == com.transfer.flash.core.common.model.FlashDeviceKind.DESKTOP) {
                    Log.i(TAG_TRANSFER, "StreamChannel[$channelId] peer is DESKTOP (caps) — no data server by construction; WS fallback")
                    return@RealFlashTransferRepository wsFallback()
                }
                if (negativeUntil > nowMs) {
                    Log.i(TAG_TRANSFER, "StreamChannel[$channelId] peer had no data server recently — skipping probe; WS fallback")
                    return@RealFlashTransferRepository wsFallback()
                }
                var probeOffset = dataPortCache[peerDeviceId]
                var channel: com.transfer.flash.core.network.datachannel.DataChannelClient.DataSendChannel? = null
                val offsets = listOfNotNull(probeOffset) + (1..20).filter { it != probeOffset }
                for (offset in offsets) {
                    val candidate = com.transfer.flash.core.network.datachannel.DataChannelClient.connect(
                        host = endpoint.first,
                        port = endpoint.second + offset,
                        targetDeviceId = peerDeviceId,
                        channelId = channelId,
                        onFrame = { bytes ->
                            // Inbound on OUR outbound channel = receiver ACKs/COMPLETE.
                            val key = trustStore.getSessionKey(FlashDeviceId(peerDeviceId))
                            val decrypted = if (SecureBinaryFrameCodec.isSecureFrame(bytes)) {
                                if (key != null) SecureBinaryFrameCodec.decryptOrNull(bytes, key) else null
                            } else {
                                bytes
                            }
                            if (decrypted != null) {
                                transferRef?.onInboundFrame(decrypted)
                            }
                        },
                        onClosed = { },
                        localDeviceId = identity.deviceId.value,
                    )
                    if (candidate != null) {
                        dataPortCache[peerDeviceId] = offset
                        noDataServerUntil.remove(peerDeviceId)
                        channel = candidate
                        Log.i(TAG_TRANSFER, "StreamChannel[$channelId] real socket → ${endpoint.first}:${endpoint.second + offset}")
                        break
                    }
                }
                if (channel == null) {
                    // Full sweep failed: remember so retries/re-offers (and the other channels
                    // of this transfer) skip straight to WS fallback until the TTL expires.
                    noDataServerUntil[peerDeviceId] = System.currentTimeMillis() + NO_DATA_SERVER_TTL_MS
                }

                channel?.let { dc ->
                    object : StreamChannel {
                        override val id: Int = channelId
                        override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                            val toSend = if (sessionKey != null) {
                                SecureBinaryFrameCodec.encrypt(frameBytes, sessionKey)
                            } else {
                                frameBytes
                            }
                            return dc.send(toSend)
                        }
                    }
                } ?: run {
                    Log.w(TAG_TRANSFER, "StreamChannel[$channelId] data-channel connect failed; WS fallback")
                    wsFallback()
                }
            },
            fileSourceOpener = { uriString ->
                // Phase 13B-2: FileSourceOpener.open() returns okio.Source; openSource() still
                // yields the ContentResolver's InputStream, bridged here with `.source()`.
                openSource(uriString, appContext).source()
            },
            store = RoomTransferStore(db.transferDao(), db.transferChunkDao()),
            // #5: park every outbound send after FILE_START until the receiver accepts (a RESUME).
            // A compliant sender streams nothing pre-accept, so no chunk is ever lost to the gate.
            requireReceiverAcceptance = true,
            isPeerEncrypted = { peerId -> trustStore.getSessionKey(FlashDeviceId(peerId)) != null },
            performanceMode = { performanceMode },
        )

        val chatImpl = RealFlashChatRepository(
            localDeviceId = identity.deviceId.value,
            localDisplayName = identity.friendlyName,
            conversationDao = db.conversationDao(),
            messageDao = db.messageDao(),
            outboxDao = db.outboxDao(),
            receiptDao = db.receiptDao(),
            draftDao = db.draftDao(),
            recentSearchDao = db.recentSearchDao(),
            reactionDao = db.reactionDao(),
            groupMemberDao = db.groupMemberDao(),
            groupDeliveryDao = db.groupDeliveryDao(),
            runInTransaction = { block -> db.runInWriteTransaction(block) },
            isTrustedPeer = { peerId -> trustStore.isTrusted(peerId) },
            isChannelEncrypted = { peerId -> trustStore.getSessionKey(peerId) != null },
            // Online indicator: a peer is online iff it has a live session. activeSessions is keyed
            // by the peer's FlashDeviceId, and a conversationId IS that peer id, so the repo can key
            // presence directly off this id set.
            onlinePeerIds = networkImpl.activeSessions.map { sessions ->
                sessions.keys.mapTo(HashSet()) { it.value }
            },
            // A conversationId is the peer's device UUID; resolve it to the paired friendly name so
            // the chat list / header show the real name instead of the raw id.
            peerNameResolver = { id -> trustStore.getTrustedPeers()[FlashDeviceId(id)] },
            // B4: join live transfer progress onto chat attachment rows so a chat bubble mirrors the
            // Transfers tab (image thumbnail / file card with progress). Completed transfers drop out
            // of activeTransfers; applyAttachment then falls back to the row's stored path so the
            // attachment stays visible and openable.
            attachmentProgress = transferImpl.activeTransfers.map { transfers ->
                transfers.associate { t ->
                    t.id.value to com.transfer.flash.core.messaging.model.FlashAttachmentProgress(
                        progress = if (t.bytesTotal > 0L) {
                            (t.bytesDone.toFloat() / t.bytesTotal.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                        status = when (t.state) {
                            com.transfer.flash.core.transfer.model.FlashTransferState.Completed,
                            com.transfer.flash.core.transfer.model.FlashTransferState.Verifying ->
                                com.transfer.flash.core.messaging.model.FlashFileTransferStatus.Downloaded
                            com.transfer.flash.core.transfer.model.FlashTransferState.Failed,
                            com.transfer.flash.core.transfer.model.FlashTransferState.Cancelled ->
                                com.transfer.flash.core.messaging.model.FlashFileTransferStatus.Failed
                            com.transfer.flash.core.transfer.model.FlashTransferState.Offered ->
                                com.transfer.flash.core.messaging.model.FlashFileTransferStatus.AwaitingAcceptance
                            com.transfer.flash.core.transfer.model.FlashTransferState.Paused ->
                                com.transfer.flash.core.messaging.model.FlashFileTransferStatus.Paused
                            else ->
                                com.transfer.flash.core.messaging.model.FlashFileTransferStatus.Transferring
                        },
                        localPath = t.localPath ?: t.sourceUri,
                        speedMbps = t.speedBytesPerSec / 1_000_000f,
                        etaSeconds = t.etaSeconds.toInt(),
                    )
                }
            },
            // Bug 7: inbound-event callbacks → system notifications. The repo fires these
            // only for rows Room actually inserted, so replayed frames can't double-notify;
            // FlashNotificationManager additionally suppresses the conversation the user
            // is reading right now (foreground + open thread).
            onInboundTextMessageWithGroupTitle = { conversationId, senderName, text, groupTitle ->
                FlashNotificationManager.showMessage(
                    appContext,
                    conversationId,
                    senderName,
                    text,
                    groupTitle,
                )
            },
            onInboundAttachmentWithGroupTitle = { conversationId, senderName, fileName, mimeType, groupTitle ->
                FlashNotificationManager.showAttachment(
                    appContext,
                    conversationId,
                    senderName,
                    fileName,
                    mimeType,
                    groupTitle,
                )
            },
            transportSink = { targetDeviceId, wireFrame ->
                val session = networkImpl.activeSessions.value[FlashDeviceId(targetDeviceId)] as? WsSession
                if (session == null) {
                    Log.w(TAG_CHAT, "Failed to dispatch chat wireFrame: no active session for $targetDeviceId")
                    return@RealFlashChatRepository false
                }
                // Field-encoded text framing (colon-safe; message text may contain any characters).
                // The five direct-chat families encode through the shared codec (slice 3);
                // DeleteForEveryone keeps its own (DirectMessageActionCodec).
                val frameText = when (wireFrame) {
                    is MessageWireFrame.DeleteForEveryone -> DirectMessageActionCodec.encode(wireFrame)
                    is MessageWireFrame.TextMessage,
                    is MessageWireFrame.DeliveryReceipt,
                    is MessageWireFrame.ReadReceipt,
                    is MessageWireFrame.ReactionFrame,
                    is MessageWireFrame.TypingFrame -> ChatTextFrameCodec.encode(wireFrame)
                        ?: return@RealFlashChatRepository false
                }
                // Audit B1: this sink is `suspend`, so the keystore unseal, the encryption and the
                // blocking socket write SUSPEND onto IO instead of blocking the caller. It used to
                // runBlocking on the main thread, which froze the UI into an ANR whenever a peer's
                // TCP window was full.
                val sessionKey = withContext(Dispatchers.IO) { trustStore.getSessionKey(targetDeviceId) }
                val wirePayload = if (sessionKey != null) {
                    E2eFrameCodec.encryptToWireFrame(frameText, sessionKey)
                } else {
                    frameText
                }
                val sent = withContext(Dispatchers.IO) {
                    runCatching { session.connection.sendText(wirePayload) }.getOrDefault(false)
                }
                Log.i(TAG_CHAT, "Dispatched chat frame to $targetDeviceId (encrypted=${sessionKey != null}, success=$sent)")
                sent
            },
            groupTransportSink = { targetDeviceId, wireFrame ->
                val session = networkImpl.activeSessions.value[FlashDeviceId(targetDeviceId)] as? WsSession
                    ?: return@RealFlashChatRepository false
                val encoded = GroupFrameCodec.encode(wireFrame)
                session.connection.sendTextAsync(encoded)
                true
            },
        )

        // Sync peer friendly name across paired devices when peer connects with an updated name
        appScope.launch {
            networkImpl.activeSessions.collect { sessions ->
                sessions.values.forEach { session ->
                    val peerId = session.peerDeviceId
                    val reportedName = session.peer.friendlyName.trim()
                    if (reportedName.isNotBlank() && trustStore.isTrusted(peerId)) {
                        val knownName = trustStore.getTrustedPeers()[peerId]
                        if (knownName != reportedName) {
                            Log.i(TAG_DISCOVERY, "Syncing updated friendly name for trusted peer ${peerId.value}: '$knownName' -> '$reportedName'")
                            trustStore.trustPeer(peerId, reportedName)
                            db.conversationDao().updateDirectTitle(peerId.value, reportedName)
                            // Also update the member's display name in every group they belong to.
                            db.groupMemberDao()?.updateMemberDisplayName(peerId.value, reportedName)
                        }
                    }
                }
            }
        }

        // ---- pairing (C2/C4): persistent identity fingerprint + trust store, glued to the
        // pure DefaultFlashPairingProtocol by PairingCoordinator. Frames ride the same WS text
        // framing as chat/receipts/transfer control, under the FLASH_PAIR prefix. Trust persists
        // to SharedPreferences (AndroidPreferencesTrustStore), so paired peers survive restarts.
        val localFingerprintHex =
            FlashFingerprint.formatHexGroups(FlashFingerprint.fingerprint(crypto.identityPublicKeyEncoded))
        // (trustStore constructed above, shared with the chat repo.)
        // One ephemeral ECDH key reused for the lifetime of this engine, used to derive AES-256 session keys.
        val ephemeralKeyPair = crypto.generateEphemeralEcdhKeyPair()
        val ephemeralPublicKey = ephemeralKeyPair.publicKeyEncoded
        val pairingCoordinator = PairingCoordinator(
            localFingerprintHex = localFingerprintHex,
            localDeviceId = identity.deviceId.value,
            localName = identity.friendlyName,
            localModel = identity.deviceModel,
            ephemeralPublicKey = ephemeralPublicKey,
            trustStore = trustStore,
            scope = appScope,
            sendToPeer = { peerId, text ->
                // MUST be non-blocking: beginPair is invoked from the UI (main) dispatcher, and a
                // blocking socket write there throws NetworkOnMainThreadException — which WsConnection
                // catches as a write failure and CLOSES the session, tearing down the link the
                // handshake needs. sendTextAsync queues the write on the connection's own IO scope.
                // The boolean reports reachability (is there a live session?), which is exactly the
                // signal beginPair uses for its "Couldn't reach…" feedback.
                val session = networkImpl.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
                if (session != null) {
                    session.connection.sendTextAsync(text)
                    Log.i(TAG_WS, "Pairing sendToPeer id=$peerId queued (hasSession=true)")
                    true
                } else {
                    Log.w(
                        TAG_WS,
                        "Pairing sendToPeer id=$peerId has NO session; " +
                            "active sessions=${networkImpl.activeSessions.value.keys.map { it.value }}",
                    )
                    false
                }
            },
            crypto = crypto,
            ephemeralKeyPair = ephemeralKeyPair,
        )

        // ---- calling (C7 / ADR-025 / UI-050): WebRTC voice/video, signaling over the same WS
        // mesh text frames under the FLASH_CALL prefix. sendFrame is non-blocking (sendTextAsync),
        // exactly like the pairing path: call control runs on the UI dispatcher when the user taps
        // call/accept/decline and must never block on a socket write from the main thread.
        //
        // The destination peer is EXPLICIT (second lambda arg): call frames carry only callId +
        // our own `from` on the wire, so routing by frame.from would send every frame to ourselves.
        // The coordinator resolves the peer (live session peer, or a busy-decline's new inviter).
        //
        // Installs the audio device module first. WebRtc.configure() throws once a
        // PeerConnectionFactory exists, so this is the last safe moment: after it, the ADM
        // is permanent for the process. Best-effort — a device that refuses still gets a call
        // on libwebrtc's defaults.
        //
        // LOW-tier devices skip the low-latency ADM: the small playout buffer underruns on a
        // weak HAL and each underrun NetEQ-stretches into a latency spike, which jitters more
        // than the default ADM's stable buffering costs. Read once here because the ADM is
        // process-wide — a later tier change takes effect on the next process start.
        FlashWebRtcEngine.configureOnce(
            appContext,
            lowLatencyPlayout = performanceMode != FlashPerformanceMode.LOW,
        )
        val callCoordinator = CallCoordinator(
            localDeviceId = identity.deviceId.value,
            localName = identity.friendlyName,
            scope = appScope,
            // Group Phase 0 trust closure: only paired peers can place or receive calls.
            isTrustedPeer = { peerId -> trustStore.isTrusted(peerId) },
            // Read per sample, not captured once: flipping the switch mid-call has to take effect
            // on that call, not the next one.
            prioritiseVoice = { prioritiseVoiceQuality },
            // ERROR-033: capture size, Opus packetization and the call-recovery windows all come
            // from the device's tier. Read per call for the same reason as above.
            performanceMode = { performanceMode },
            peerNameResolver = { peerId ->
                trustStore.getTrustedPeers()[FlashDeviceId(peerId)]
                    ?: engine.discoveredEndpoints.value.firstOrNull { it.deviceId.value == peerId }?.friendlyName
            },
            sendFrame = { frame, peerId ->
                val encoded = CallFrameCodec.encode(frame)
                var session = networkImpl.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
                if (session == null && (frame is CallWireFrame.Invite || frame is CallWireFrame.GroupInvite)) {
                    // Audit B2: `sendFrame` is suspend; suspend for the session instead of blocking
                    // the caller (a Call tap) for up to 2 s.
                    withTimeoutOrNull(2000L) {
                        networkImpl.activeSessions.first { sessions ->
                            sessions.containsKey(FlashDeviceId(peerId))
                        }
                    }
                    session = networkImpl.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
                }
                if (session != null) {
                    session.connection.sendTextAsync(encoded)
                    Log.i(TAG_WS, "Call sendFrame action=${frame.javaClass.simpleName} peer=$peerId (hasSession=true)")
                    true
                } else {
                    Log.i(TAG_WS, "Call sendFrame action=${frame.javaClass.simpleName} peer=$peerId no session")
                    false
                }
            },
            // Every terminated call becomes a row in the peer's thread (UI-050). No wire frame is
            // involved: both devices already hold direction, duration and end reason locally, so
            // each writes its own row — which is also why a declined or missed call still appears.
            // Fires on whichever thread ended the call (possibly a WebRTC callback thread), and
            // recordCallEvent only launches on the repository's IO scope, so it never blocks.
            onCallLog = { entry ->
                Log.i(
                    TAG_WS,
                    "Call log peer=${entry.peerId} video=${entry.video} " +
                        "reason=${entry.endReason} durationMs=${entry.durationMs}",
                )
                chatImpl.recordCallEvent(
                    peerDeviceId = entry.peerId,
                    callId = entry.callId,
                    peerName = entry.peerName,
                    outgoing = entry.direction == FlashCallDirection.OUTGOING,
                    video = entry.video,
                    durationMs = entry.durationMs,
                    endedAt = entry.endedAt,
                )
            },
        )

        // Observe active WebSocket sessions for incoming chat messages and binary file chunks.
        // Collector jobs are tracked per session and cancelled when the session leaves the map
        // (prevents zombie collectors double-handling frames after reconnect/glare).
        val sessionJobs = ConcurrentHashMap<WsSession, Job>()

        // Receive-pause/cancel gate: while an inbound transfer is paused, binary collection
        // suspends BEFORE pulling the next frame; the bounded channel fills and TCP backpressure
        // throttles legacy peers. Same-build peers also receive the FLASH_XFER wire control
        // frame (below), which pauses their dispatcher at the application level — required for
        // dedicated data channels where WS backpressure doesn't apply.
        //
        // Tracked as a SET of paused transfer ids, not a boolean: with two inbound transfers
        // paused, resuming one used to ungate the socket for both.
        val pausedIntakeIds = MutableStateFlow<Set<String>>(emptySet())

        // Single idempotent teardown for one inbound transfer: close+drop the sink handle, forget
        // its metadata, drop the pipeline session, un-gate intake, unregister it from its peer, and
        // mark the transfer row failed (#4). Safe to call for an unknown/already-cleaned id.
        val cleanupInbound: (String, String) -> Unit = { transferId, reason ->
            receivePipeline.cancelSession(transferId)
            openHandles.remove(transferId)?.let { handle -> runCatching { handle.close() } }
            incomingMeta.remove(transferId)
            receivedPaths.remove(transferId)
            pausedIntakeIds.update { it - transferId }
            incomingByPeer.values.forEach { it.remove(transferId) }
            transferImpl.onIncomingFailed(transferId, reason)
        }

        // Fail every stranded inbound transfer for a peer whose transport just dropped (#4).
        val failInboundForPeer: (String, String) -> Unit = { peerId, reason ->
            incomingByPeer.remove(peerId)?.toList()?.forEach { transferId ->
                Log.i(TAG_TRANSFER, "Peer $peerId dropped — failing inbound transferId=$transferId ($reason)")
                cleanupInbound(transferId, reason)
            }
        }
        onPeerConnectionClosed = { peerId -> failInboundForPeer(peerId, "data channel closed") }

        // Sends one FLASH_XFER control frame to a peer's WebSocket session (ADR-018). Shared by the
        // outgoingControl collector and the offer-accept path (which must RESUME the sender only
        // AFTER the local sink is resolved, so it cannot go through the fire-and-forget flow).
        val sendXfer: (String, String, String) -> Unit = { peerId, action, transferId ->
            val session = networkImpl.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
            if (session == null) {
                Log.w(TAG_TRANSFER, "Cannot deliver XFER $action: no session for $peerId")
            } else {
                val frame = FlashTextFraming.encodeFields(
                    XFER_PREFIX,
                    listOf("action" to action, "transferId" to transferId),
                )
                val ok = session.connection.sendText(frame)
                Log.i(TAG_TRANSFER, "XFER $action → $peerId (sent=$ok) transferId=$transferId")
            }
        }
        sendXferControl = sendXfer

        // Accepts a pending inbound OFFER (#5): resolve the deferred sink FIRST (so no early chunk
        // is dropped), surface it as Transferring + a chat bubble, then RESUME the parked sender.
        val acceptOffer: (String) -> Unit = { transferId ->
            val meta = incomingMeta[transferId]
            val pid = transferImpl.activeTransfers.value.find { it.id.value == transferId }?.peerDeviceId
            if (meta == null) {
                Log.w(TAG_TRANSFER, "Accept for unknown offer transferId=$transferId")
            } else if (receivePipeline.acceptSession(transferId)) {
                transferImpl.onIncomingStarted(
                    transferId = transferId,
                    fileId = meta.fileId,
                    fileName = meta.fileName,
                    totalBytes = meta.totalBytes,
                    peerName = pid ?: "",
                    peerDeviceId = pid,
                    localPath = receivedPaths[transferId],
                )
                // #1: mint the inbound attachment bubble now that the user has accepted.
                pid?.let {
                    chatImpl.onInboundAttachment(
                        peerDeviceId = it,
                        transferId = transferId,
                        fileName = meta.fileName,
                        mimeType = guessMimeType(meta.fileName),
                        sizeBytes = meta.totalBytes,
                    )
                }
                // Release the parked sender AFTER the sink exists.
                pid?.let { sendXfer(it, RealFlashTransferRepository.ACTION_RESUME, transferId) }
                Log.i(TAG_TRANSFER, "Accepted offer transferId=$transferId → streaming")
            } else {
                Log.w(TAG_TRANSFER, "acceptSession no-op transferId=$transferId (already open?)")
            }
        }

        // Bug 3: auto-download policy. AppEngine mirrors the per-MIME toggles into
        // autoDownloadVoice/Image/Video/File; when an offer arrives whose MIME category is enabled,
        // accept it immediately (same path as a manual Accept). Otherwise the offer stays pending
        // for the user to accept/decline in the chat bubble.
        onIncomingOffer = { transferId ->
            val meta = incomingMeta[transferId]
            if (meta != null) {
                val mime = guessMimeType(meta.fileName)
                val auto = when {
                    mime.startsWith("audio/") -> autoDownloadVoice
                    mime.startsWith("image/") -> autoDownloadImage
                    mime.startsWith("video/") -> autoDownloadVideo
                    else -> autoDownloadFile
                }
                if (auto) {
                    Log.i(TAG_TRANSFER, "Auto-accepting '${meta.fileName}' (mime=$mime) transferId=$transferId")
                    acceptOffer(transferId)
                }
            }
        }

        // Declines a pending inbound OFFER (#5): drop the never-materialized session and local
        // bookkeeping. The repo already marked the row Cancelled and emitted a CANCEL to the sender.
        val declineOffer: (String) -> Unit = { transferId ->
            receivePipeline.declineSession(transferId)
            incomingMeta.remove(transferId)
            receivedPaths.remove(transferId)
            pausedIntakeIds.update { it - transferId }
            incomingByPeer.values.forEach { it.remove(transferId) }
            Log.i(TAG_TRANSFER, "Declined offer transferId=$transferId")
        }

        appScope.launch {
            transferImpl.incomingControl.collect { control ->
                when (control.action) {
                    RealFlashTransferRepository.ACTION_PAUSE -> {
                        pausedIntakeIds.update { it + control.transferId }
                        Log.i(TAG_TRANSFER, "Incoming intake PAUSED transferId=${control.transferId} paused=${pausedIntakeIds.value.size}")
                    }
                    RealFlashTransferRepository.ACTION_RESUME -> {
                        pausedIntakeIds.update { it - control.transferId }
                        Log.i(TAG_TRANSFER, "Incoming intake RESUMED transferId=${control.transferId} paused=${pausedIntakeIds.value.size}")
                    }
                    RealFlashTransferRepository.ACTION_CANCEL -> {
                        // Remote cancel: tear down sink + pipeline session, un-gate intake, and mark
                        // the row failed via the shared inbound-cleanup path.
                        cleanupInbound(control.transferId, "cancelled by peer")
                        Log.i(TAG_TRANSFER, "Incoming transfer CANCELLED transferId=${control.transferId}")
                    }
                    RealFlashTransferRepository.ACTION_ACCEPT -> acceptOffer(control.transferId)
                    RealFlashTransferRepository.ACTION_DECLINE -> declineOffer(control.transferId)
                }
            }
        }

        // Wire-level control plane: deliver repo control intents to the counterpart peer as
        // FLASH_XFER text frames over its WebSocket session (ADR-018).
        appScope.launch {
            transferImpl.outgoingControl.collect { control ->
                val peerId = control.peerDeviceId ?: return@collect
                val session = networkImpl.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
                if (session == null) {
                    Log.w(TAG_TRANSFER, "Cannot deliver XFER ${control.action}: no session for $peerId")
                    return@collect
                }
                val frame = FlashTextFraming.encodeFields(
                    XFER_PREFIX,
                    listOf(
                        "action" to control.action,
                        "transferId" to control.transferId,
                    ),
                )
                val ok = session.connection.sendText(frame)
                Log.i(TAG_TRANSFER, "XFER ${control.action} → $peerId (sent=$ok) transferId=${control.transferId}")
            }
        }

        appScope.launch {
            networkImpl.activeSessions.collect { sessions ->
                sessionJobs.keys.filterNot { it in sessions.values }.forEach { stale ->
                    sessionJobs.remove(stale)?.cancel()
                    // The WS session is the authoritative "peer gone" signal: fail every inbound
                    // transfer still in flight for it so no handle/row is stranded (#4).
                    failInboundForPeer(stale.peerDeviceId.value, "peer disconnected")
                    // A live call cannot carry ICE without its signaling session, but a mesh roam
                    // takes that session down as a matter of course and the dialer redials it in
                    // seconds. Open a recovery window rather than ending the call (ERROR-033).
                    callCoordinator.onSignalingLost(stale.peerDeviceId.value)
                    Log.d(TAG_WS, "Cancelled collectors for stale session peer=${stale.peer.friendlyName}")
                }
                sessions.values.forEach { session ->
                    if (session is WsSession && !sessionJobs.containsKey(session)) {
                        // Announce our identity fingerprint so the peer can derive the shared
                        // pairing code the moment it taps Pair (see PairingWireCodec.Inbound.Hello).
                        Log.i(TAG_WS, "Session up peer=${session.peer.friendlyName} id=${session.peerDeviceId.value} — sending pairing hello")
                        pairingCoordinator.onSessionUp(session.peerDeviceId.value)
                        // Bug 5: a peer session is up (connect/reconnect) — flush the durable
                        // outbox now so messages queued while this peer was offline send. The peer
                        // id additionally makes that member's group deliveries retryable.
                        chatImpl.notifyPeerSessionUp(session.peerDeviceId.value)
                        // F3: ask the returning peer to push any group messages it holds that we
                        // lack (holder-coordinated catch-up, FLASH_GSYNC).
                        chatImpl.sendGroupSyncRequests(session.peerDeviceId.value)
                        // F7: heal a membership frame this peer may have missed while it was offline -
                        // membership frames have no delivery table, so a dropped Add/State is never retried.
                        chatImpl.reconcileGroupMembership(session.peerDeviceId.value)
                        // Closes any recovery window the matching onSignalingLost opened, so a roam
                        // that resolved in two seconds does not cost the full grace period, and the
                        // ICE restart offer has a channel to travel on (ERROR-033).
                        callCoordinator.onSignalingRestored(session.peerDeviceId.value)
                        // Restart sends this peer's last disconnect killed. Byte-accurate resume
                        // already existed and nothing called it, so a roam mid-transfer meant a
                        // Failed row until a human tapped retry (ERROR-035).
                        appScope.launch {
                            resumeRoamKilledSends(transferImpl, networkImpl, session.peerDeviceId.value)
                        }
                        sessionJobs[session] = appScope.launch {
                            launch {
                                session.incomingText.collect { text ->
                                    handleInboundText(
                                        chatImpl,
                                        transferImpl,
                                        pairingCoordinator,
                                        callCoordinator,
                                        session.peerDeviceId.value,
                                        text,
                                    )
                                }
                            }
                            launch {
                                while (true) {
                                    // Block BEFORE pulling the next frame while paused
                                    // (channel fills → WS read loop blocks → TCP backpressure).
                                    pausedIntakeIds.first { it.isEmpty() }
                                    val data = runCatching { session.awaitBinaryFrame() }
                                        .getOrElse { break } // channel closed: session gone
                                    handleInboundBinary(
                                        transferImpl = transferImpl,
                                        receivePipeline = receivePipeline,
                                        openHandles = openHandles,
                                        incomingMeta = incomingMeta,
                                        receivedPaths = receivedPaths,
                                        incomingByPeer = incomingByPeer,
                                        peerLabel = session.peer.friendlyName,
                                        peerDeviceId = session.peerDeviceId.value,
                                        data = data,
                                        reply = { bytes -> session.connection.sendBinary(bytes) },
                                        onAttachmentStarted = { pid, transferId, fileName, totalBytes ->
                                            chatImpl.onInboundAttachment(
                                                peerDeviceId = pid,
                                                transferId = transferId,
                                                fileName = fileName,
                                                mimeType = guessMimeType(fileName),
                                                sizeBytes = totalBytes,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        composite = engine
        network = networkImpl
        transferRepo = transferImpl
        chatRepo = chatImpl
        pairing = pairingCoordinator
        this.calling = callCoordinator
        // PTT voice session (ADR-032 Phase 1): all transport access is lazy lambdas over
        // holder fields, so construction order within this function does not matter.
        val ptt = PttSessionEngine(
            localId = { localDeviceId },
            localName = { localDeviceName },
            isTrustedPeer = { peerId -> trustStore.isTrusted(peerId) },
            snapshotMembers = {
                val selfId = localDeviceId
                val trusted = pairing?.trustedPeers?.value?.mapTo(HashSet()) { it.id }
                    ?: emptySet()
                network?.activeSessions?.value?.keys?.mapNotNull { deviceId ->
                    deviceId.value.takeIf { it != selfId && it in trusted }
                } ?: emptyList()
            },
            sendControl = { peerId, text ->
                val session = network?.activeSessions?.value?.get(FlashDeviceId(peerId)) as? WsSession
                if (session != null) {
                    runCatching { session.connection.sendText(text) }
                        .onFailure { Log.w(TAG_WS, "PTT control send failed peer=$peerId", it) }
                        .getOrDefault(false)
                } else {
                    Log.w(TAG_WS, "PTT control dropped: no session for $peerId")
                    false
                }
            },
            // Blocking socket write by contract: the engine calls this only from its
            // sender loop on Dispatchers.IO, never from the capture thread or main.
            sendAudio = { peerId, bytes ->
                val session = network?.activeSessions?.value?.get(FlashDeviceId(peerId)) as? WsSession
                if (session == null) {
                    Log.w(TAG_WS, "PTT audio dropped: no session for $peerId")
                } else {
                    runCatching { session.connection.sendBinary(bytes) }
                        .onFailure { Log.w(TAG_WS, "PTT audio send failed peer=$peerId", it) }
                }
            },
            hasMicPermission = ::hasMicPermission,
            isCallActive = { callActive },
            audioRateHz = { if (performanceMode == FlashPerformanceMode.LOW) 8000 else 16000 },
        )
        pttEngine = ptt
        // Surface session notices in logs as well as the Phase 3 overlay toast collector.
        appScope.launch { ptt.notices.collect { Log.i(TAG_WS, "PTT notice: $it") } }
        // Inbound ping decode/dedup/fan-out lives in the module now; this host only decides how an
        // accepted ping surfaces. Foreground stays log-only (the Phase 3 overlay owns the screen
        // then), backgrounded posts the tap-to-talk notification, exactly as before.
        appScope.launch {
            ptt.pings.collect { ping ->
                val ctx = appContextRef
                if (ctx != null && !FlashNotificationManager.appForeground) {
                    FlashNotificationManager.showPttPing(ctx, ping.senderName)
                }
                Log.i(TAG_WS, "PTT ping from '${ping.senderName}' id=${ping.fromDeviceId} eventId=${ping.eventId}")
            }
        }
        // Phase 2: session lifetime drives the foreground service (live notification +
        // process priority). START_NOT_STICKY there: session truth lives in the engine.
        appScope.launch {
            ptt.state.collect { sessionState ->
                val ctx = appContextRef ?: return@collect
                if (sessionState is PttFloorState.Idle) PttSessionService.stop(ctx)
                else {
                    FlashNotificationManager.clearPttTapToTalk(ctx)
                    PttSessionService.start(ctx)
                }
            }
        }
        transferRef = transferImpl
        router.transfer = transferImpl
        router.onAttachmentStarted = { pid, transferId, fileName, totalBytes ->
            chatImpl.onInboundAttachment(
                peerDeviceId = pid,
                transferId = transferId,
                fileName = fileName,
                mimeType = guessMimeType(fileName),
                sizeBytes = totalBytes,
            )
        }
        transferForResume = transferImpl
        // Warm the receiver done-set so a resumed inbound FILE_START seeds its bit-vector (#20).
        appScope.launch { runCatching { transferImpl.preloadReceiverProgress() } }
        dataServer = dcServer

        // Proactively hold a full-duplex session with every discovered peer, dialing from both ends so
        // whichever side's discovery resolves first gets the session up; all traffic then rides that
        // one session and the loser's dial costs nothing. Glare is resolved by
        // WsFlashNetwork.registerSession; AutoConnectGate bounds attempts so an unreachable peer is
        // retried, not hammered.
        //
        // This used to be justified by "the SoftAP/gateway device cannot open a TCP connection to a
        // client station". That is not an Android or Linux rule — the host is the gateway and has a
        // directly connected route. Host-to-client dials failed because WsTransferClient bound them to
        // the first Wi-Fi network CM listed, which a tethered client is never on-link for
        // (ERROR-035, fixed in Ipv4Routing). Dialing both ways is still correct; the reason changed.
        autoConnectJob = appScope.launch {
            val gate = AutoConnectGate()
            autoConnectGate = gate
            localDeviceId = identity.deviceId.value
            while (isActive) {
                // Voice-call quiet: while a call is ACTIVE the radio belongs to Opus/RTP, so no
                // dial bursts. The tick itself keeps running so the sweep resumes on its normal
                // cadence the moment the call ends.
                if (!callActive) {
                    runAutoConnectSweep(engine, networkImpl, identity.deviceId.value, gate)
                }
                delay(AUTO_CONNECT_SWEEP_MS)
            }
        }

        // Dial the MOMENT a peer is discovered, not up to a tick later.
        //
        // Pairing needs a live session, and `beginPair` waits only 3 s for the peer's hello
        // (`FINGERPRINT_WAIT_MS`) while this sweep ticks every `AUTO_CONNECT_SWEEP_MS` (5 s). A user
        // who taps Pair as soon as the row appears therefore loses a race that started before they
        // could see it and gets `Couldn't reach …`, which reads as a pairing bug — measured exactly
        // that way on the desktop on 2026-09-14 (peer found, `active sessions=[]`, no dial line at
        // all). The same window exists here, and the phone is the other half of a two-sided failure:
        // whichever device taps first is the one that loses it.
        //
        // `runAutoConnectSweep` is reused rather than reimplemented, so this trigger inherits the
        // `AutoConnectGate` attempt-bounding and the `isReconnectInFlight` / `hasLiveSession`
        // guards: an emission for a peer we already have, or just tried, is a no-op. `discoveredEndpoints`
        // is a StateFlow over a 5 s sweep, so this is at most a few sweeps per minute — it changes
        // *when* the first dial happens, not how often retries do.
        appScope.launch {
            engine.discoveredEndpoints.collect {
                if (!callActive) {
                    runAutoConnectSweep(engine, networkImpl, identity.deviceId.value, autoConnectGate ?: return@collect)
                }
            }
        }

        // C7 ringing: the engine — not the UI, not FlashCallService — drives the ringer and the
        // call notification, because an invite that arrives with the app closed still has to ring.
        // Ownership here also means the ring survives a refused foreground-service promotion: the
        // ringtone is a plain MediaPlayer on the ring stream and needs no FGS at all.
        //
        // `collect`, not `collectLatest`: onCallState is a cheap synchronized state machine and
        // dropping an intermediate emission could drop the very edge that stops the ring.
        // FlashCallService.start is called on the ringing edges only; the service stops itself when
        // activeCall goes null, and it de-duplicates repeated starts internally.
        callRinger = FlashCallRinger(appContext)
        callRingJob = appScope.launch {
            callCoordinator.activeCall.collect { state ->
                callRinger?.onCallState(state)
                if (state?.state == FlashCallState.DIALING || state?.state == FlashCallState.RINGING) {
                    FlashCallService.start(appContext)
                }
                val callOwnsAudio = state != null && state.state != FlashCallState.ENDED
                setCallActive(callOwnsAudio, engine)
            }
        }

        // MainActivity.onStart owns foreground-service launch while the app is user-visible.
        // Starting it here after asynchronous engine setup can violate Android 12+ background-start rules.
        return composite!!
    }

    /**
     * One pass of the proactive auto-connect sweep: dials every discovered peer that lacks a
     * live session, bounded by [AutoConnectGate]. Extracted from the periodic loop so the
     * screen-on re-arm ([onScreenOn]) can force an immediate sweep instead of waiting up to
     * [AUTO_CONNECT_SWEEP_MS] for the next tick. Each dial is launched on [appScope] and ends
     * its own gate entry, exactly as the original inline loop did.
     */
    private fun runAutoConnectSweep(
        engine: CompositeDiscovery,
        networkImpl: WsFlashNetwork,
        localId: String,
        gate: AutoConnectGate,
    ) {
        val endpoints = engine.discoveredEndpoints.value
        for (ep in endpoints) {
            val id = ep.deviceId.value
            if (id == localId) continue
            // ERROR-031: ask whether the peer's session is actually carrying traffic, not whether the
            // registry happens to hold one. A session whose socket died without its watchdog noticing
            // used to suppress this sweep indefinitely — the dot stayed Online, every send "succeeded"
            // into the dead socket, and only a force-stop cleared it.
            val hasSession = networkImpl.hasLiveSession(id)
            // ERROR-023 dedup: if the #18 reconnect engine is already backoff-dialing this peer
            // right now, don't fire a redundant dial from the sweep at the same moment — two
            // simultaneous outbound dials to the same peer only widen the glare window.
            if (networkImpl.isReconnectInFlight(id)) continue
            if (!gate.tryBegin(id, hasSession, System.currentTimeMillis())) continue
            appScope.launch {
                Log.i(TAG_WS, "Auto-connect dialing peer=${ep.friendlyName} id=$id at ${ep.hostAddress}:${ep.port}")
                val result = runCatching { networkImpl.connectManual(ep.hostAddress, ep.port) }.getOrNull()
                val ok = result is FlashResult.Success
                Log.i(TAG_WS, "Auto-connect result peer=$id success=$ok")
                gate.end(id)
            }
        }

        // Hotspot host auto-probe: tethered clients cannot discover the host via NSD because Android
        // SoftAP drops multicast mDNS packets. Probe default IPv4 gateways on active LAN networks.
        val context = appContextRef
        if (context != null) {
            val gateways = runCatching {
                com.transfer.flash.core.network.util.LocalNetworkAddresses(context).ipv4Gateways()
            }.getOrDefault(emptyList())
            for (gw in gateways) {
                val gwGateId = "gateway:$gw"
                val hasGwSession = networkImpl.activeSessions.value.values.any { session ->
                    val ep = networkImpl.endpointOf(session.peerDeviceId.value)
                    ep?.first == gw
                }
                if (!hasGwSession && !networkImpl.isReconnectInFlight(gwGateId) &&
                    gate.tryBegin(gwGateId, false, System.currentTimeMillis())
                ) {
                    appScope.launch {
                        Log.i(TAG_WS, "Auto-connect dialing gateway at $gw:0 (hotspot host probe)")
                        val result = runCatching { networkImpl.connectManual(gw, 0) }.getOrNull()
                        val ok = result is FlashResult.Success
                        Log.i(TAG_WS, "Auto-connect result gateway $gw success=$ok")
                        gate.end(gwGateId)
                    }
                }
            }
        }
    }

    /**
     * Call/PTT audio-exclusion transitions, driven by the [CallCoordinator.activeCall] collector. On
     * the first non-ended call edge, live PTT is torn down and new capture is refused. Discovery
     * and transfer telemetry also enter call-quiet mode; on the null/ENDED edge all are restored.
     * Starting the quiet policy before ACTIVE is deliberate: outbound media setup and accepted
     * calls can acquire the mic during DIALING/CONNECTING, so waiting for ACTIVE admits a capture
     * race. The ECO→STANDARD switch wakes an in-flight idle gap immediately.
     *
     * `runCatching` guards the mode switch, never the flag: this runs inside the `collect` loop
     * that also drives the ringer, and a throwing transport must not kill that loop. The flag is
     * set first so the sweep gate holds even if the mode switch fails.
     */
    private suspend fun setCallActive(active: Boolean, engine: CompositeDiscovery) {
        if (active == callActive) return
        callActive = active
        // Mic exclusivity (ADR-032): a starting call tears any PTT session down; the press
        // path refuses while a call is active, so the floor can never fight the call for
        // the mic. Voice-message capture uses the same engine-owned gate in MainActivity.
        if (active) pttEngine?.onCallStarted()
        (transferRepo as? RealFlashTransferRepository)?.voiceCallActive = active
        runCatching {
            engine.setMode(if (active) FlashDiscoveryMode.ECO else userDiscoveryMode)
        }.onFailure { t ->
            Log.w(TAG_WS, "Call-quiet setMode(${if (active) "ECO" else userDiscoveryMode.name}) failed: ${t.message}")
        }
    }

    /**
     * Screen-on / user-present re-arm, driven by the engine-scoped [screenReceiver].
     *
     * Screen-off + Doze can silently drop WebSocket sessions and stall mDNS reception even with
     * the engine's wake/Wi-Fi locks held. When the screen returns we (1) restart discovery
     * browsing so returning peers are re-found, and (2) force an immediate auto-connect sweep so
     * discovered/known peers are re-dialed at once rather than after the next periodic tick.
     *
     * It also retries a foreground-service promotion that was previously refused (ERROR-031 / D7).
     * That retry is best-effort by nature: a runtime-registered `ACTION_SCREEN_ON` is **not** one of
     * Android 12+'s foreground-service-start exemptions, so the attempt only succeeds when the app
     * holds another one — in practice the battery-optimisation exemption, which is why Settings now
     * surfaces it. `startAsForeground()` already swallows a refusal, so a failed retry costs nothing
     * and the flag stays armed for the next opportunity.
     *
     * Safe to call when the engine has not started (no-op).
     */
    fun onScreenOn() {
        appContextRef?.let { FlashBackgroundService.retryPromotionIfRefused(it) }
        reArm("Screen-on")
    }

    /**
     * Manual re-arm behind the conversation's connection-banner Retry button.
     *
     * Same work as [onScreenOn]: the reasons a peer looks offline are identical whether the screen
     * just came back or the user got tired of waiting — a stalled browse, or a dropped session that
     * the backoff engine has not re-dialed yet. `FlashNetwork.retryConnection()` is not the entry
     * point here: the WS mesh implementation inherits its `false` default (only the legacy LAN stack
     * overrides it), so it would report "nothing to retry" for every peer.
     *
     * @return false when the engine has not booted yet, so the caller can tell the user that
     *   tapping again is pointless rather than silently doing nothing.
     */
    fun reconnectNow(): Boolean = reArm("Manual retry")

    /**
     * Shared body of [onScreenOn] / [reconnectNow]; [reason] only tags the log line.
     *
     * Uses restartDiscovery(), not startDiscovery(): the latter delegates to the transport's
     * idempotent startBrowsing(), which returns immediately while the transport still *believes*
     * it is browsing — precisely the state a Doze-stalled radio is in. That made this re-arm a
     * silent no-op exactly when it was needed. restartDiscovery() tears the browse down and
     * re-arms it, so the platform re-delivers every service still present.
     */
    private fun reArm(reason: String): Boolean {
        val engine = composite ?: return false
        val networkImpl = network ?: return false
        val gate = autoConnectGate ?: return false
        val localId = localDeviceId ?: return false
        appScope.launch {
            Log.i(TAG_DISCOVERY, "$reason: restarting discovery browsing and forcing auto-connect sweep")
            runCatching { engine.restartDiscovery() }
                .onFailure { Log.w(TAG_DISCOVERY, "$reason discovery restart failed", it) }
            runAutoConnectSweep(engine, networkImpl, localId, gate)
        }
        return true
    }

    /**
     * Restarts outbound transfers that this peer's previous disconnect failed (ERROR-035).
     *
     * Called on the session-up edge, which is the only moment a resume can succeed: `relaunchSend`
     * needs a live session to carry the re-offer, and the re-offer reproduces the original
     * `wireFileId`/`sourceUri` so the receiver treats it as a continuation and keeps the chunks it has
     * already verified. Nothing else in the app ever triggers that automatically.
     *
     * [SETTLE_BEFORE_RESUME_MS] exists because both ends dial and `WsFlashNetwork.registerSession`
     * closes the loser; a re-offer issued into the losing session fails and spends an attempt from a
     * budget meant for real network failures. Waiting past glare resolution and re-checking the
     * session costs a fraction of a second on a path that has already been down for seconds.
     */
    private suspend fun resumeRoamKilledSends(
        transfers: RealFlashTransferRepository,
        networkImpl: WsFlashNetwork,
        peerDeviceId: String,
    ) {
        delay(SETTLE_BEFORE_RESUME_MS)
        if (networkImpl.activeSessions.value[FlashDeviceId(peerDeviceId)] == null) {
            Log.d(TAG_TRANSFER, "Skipping auto-resume for $peerDeviceId: session did not survive glare")
            return
        }
        val snapshot = transfers.activeTransfers.value
        reconnectResume.retainOnly(snapshot.mapTo(HashSet(snapshot.size)) { it.id })
        val toResume = reconnectResume.onPeerSessionUp(peerDeviceId, snapshot)
        if (toResume.isEmpty()) return
        Log.i(TAG_TRANSFER, "Session up for $peerDeviceId: auto-resuming ${toResume.size} failed send(s)")
        toResume.forEach { transferId ->
            val result = runCatching { transfers.resumeTransfer(transferId) }
            val attempt = reconnectResume.attemptsFor(transferId)
            result
                .onSuccess { outcome ->
                    Log.i(
                        TAG_TRANSFER,
                        "Auto-resume attempt $attempt transferId=${transferId.value} " +
                            "ok=${outcome is FlashResult.Success}",
                    )
                }
                .onFailure { error ->
                    Log.w(TAG_TRANSFER, "Auto-resume attempt $attempt threw transferId=${transferId.value}", error)
                }
        }
    }

    /**
     * Resolves a send-side source stream. The dummy test URI intentionally streams generated
     * deterministic bytes (Dev Console 10MB benchmark); real content URIs must open cleanly —
     * failures THROW so a broken source can never silently transfer garbage.
     */
    private fun openSource(uriString: String, appContext: Context): InputStream {
        if (uriString == TEST_PAYLOAD_URI) {
            return DeterministicPayloadInputStream(TOTAL_TEST_BYTES)
        }
        require(uriString.startsWith("content://") || uriString.startsWith("file://")) {
            "Unsupported source descriptor: $uriString"
        }
        return appContext.contentResolver.openInputStream(android.net.Uri.parse(uriString))
            ?: throw IOException("Content resolver returned null stream for $uriString")
    }

    /**
     * True when [text] is a `FLASH_CALL …` calling-signaling frame. The recognition half of the
     * calling branch in [handleInboundText]; see [CALL_PREFIX] for why it is a prefix test.
     */
    private fun isCallFrameText(text: String): Boolean =
        FlashTextFraming.parseFields(text, CALL_PREFIX) != null

    private suspend fun handleInboundText(
        chatImpl: RealFlashChatRepository,
        transferImpl: RealFlashTransferRepository,
        pairing: PairingCoordinator,
        calling: FlashCalling,
        peerDeviceId: String,
        text: String,
    ) {
        // Calling signaling first — the most latency-sensitive frame class, and the module owns its
        // decode: `onInboundText` returns true for every FLASH_CALL frame it consumed, false when
        // the text is not a call frame at all. The `CallFrameCodec.decode(text) != null` pre-check
        // that used to sit here decoded the same frame twice for nothing.
        //
        // Fail-closed like the other families below: a *recognized* call frame is never handed to
        // the chat, pairing or PTT handlers even when the module had nothing to do with it (a stale
        // call id, a group query with no live call).
        if (isCallFrameText(text)) {
            if (!calling.onInboundText(peerDeviceId, text)) {
                Log.w(TAG_WS, "Call frame dropped (nothing to route it to) peer=$peerDeviceId")
            }
            return
        }
        // PTT next (ADR-032): ping and session control share one entry point, and the module owns
        // decode, dedup, the fail-closed transport binding/trust check and the floor reduction. It
        // answers true for recognized-but-rejected frames too, so a PTT frame can never fall
        // through into the group/chat families below. The drop branch is `else` on purpose: an
        // engine that is running already parsed both prefixes, and this is the path every inbound
        // chat frame takes.
        val ptt = pttEngine
        if (ptt != null) {
            if (ptt.onInboundText(peerDeviceId, text)) return
        } else if (PttFrameCodec.decode(text) != null || PttSessionCodec.decode(text) != null) {
            Log.w(TAG_WS, "PTT frame dropped (engine not started)")
            return
        }
        // Group frames next: their prefixes (FLASH_GROUP/GMSG/GRCPT/GREAD/GSYNC/GMEDIA) are
        // disjoint from the direct chat families below, and the repository enforces trust +
        // membership itself.
        GroupFrameCodec.decode(text)?.let { frame ->
            Log.i(TAG_WS, "Inbound group frame ${frame.javaClass.simpleName} from id=$peerDeviceId")
            chatImpl.onInboundGroupWireFrame(peerDeviceId, frame)
            return
        }
        if (FlashTextFraming.parseFields(text, PAIR_PREFIX) != null) {
            Log.i(TAG_WS, "Inbound pairing frame from id=$peerDeviceId")
            pairing.onInbound(peerDeviceId, text)
            return
        }
        val plainText = if (E2eFrameCodec.isSecuredFrame(text)) {
            val sessionKey = trustStoreRef?.getSessionKey(peerDeviceId)
            if (sessionKey != null) {
                E2eFrameCodec.decryptWireFrame(text, sessionKey) ?: run {
                    Log.w(TAG_CHAT, "Failed to decrypt FLASH_SEC frame from $peerDeviceId")
                    return
                }
            } else {
                Log.w(TAG_CHAT, "Received FLASH_SEC frame from $peerDeviceId with no stored session key; dropping")
                return
            }
        } else {
            // Audit S1b: the direct-chat family is ALWAYS encrypted by the sender once a session key
            // exists, so a plaintext one from a keyed peer is a downgrade — drop it.
            if (DirectChatFamily.matches(text) && trustStoreRef?.getSessionKey(peerDeviceId) != null) {
                Log.w(TAG_CHAT, "Dropped plaintext direct-chat frame from keyed peer $peerDeviceId (downgrade)")
                return
            }
            text
        }

        DirectMessageActionCodec.decode(plainText)?.let { frame ->
            chatImpl.onInboundWireFrame(frame, transportPeerId = peerDeviceId)
            return
        }
        // Direct-chat text family through the shared codec (slice 3). Invalid-but-recognized
        // frames drop, exactly as before — they must not fall through into the transfer family
        // below. transportPeerId is passed for ALL five families: the codec decodes the direct-chat
        // family only (group frames travel a separate path), so for legit traffic the frame author
        // IS the transport peer — and PR #11's fail-closed spoof guards (`transportPeerId != null`
        // checks) only fire when it is non-null. Passing null here would silently neutralize them.
        when (val decoded = ChatTextFrameCodec.decode(plainText, System.currentTimeMillis(), peerDeviceId)) {
            is ChatTextFrameCodec.DecodeResult.Frame -> {
                val frame = decoded.frame
                chatImpl.onInboundWireFrame(
                    frame,
                    transportPeerId = peerDeviceId,
                )
                return
            }
            ChatTextFrameCodec.DecodeResult.RecognizedButInvalid -> return
            null -> Unit
        }
        val xferFields = FlashTextFraming.parseFields(text, XFER_PREFIX)
        if (xferFields != null) {
            val action = xferFields["action"] ?: return
            val transferId = xferFields["transferId"] ?: return
            transferImpl.onRemoteTransferControl(transferId, action)
            return
        }
        Log.w(TAG_CHAT, "Received unrecognized text frame (${text.length} chars)")
    }

    private fun handleInboundBinary(
        transferImpl: RealFlashTransferRepository,
        receivePipeline: ReceivePipeline,
        openHandles: ConcurrentHashMap<String, RandomAccessSinkHandle>,
        incomingMeta: ConcurrentHashMap<String, ChunkFrame.FileStart>,
        receivedPaths: ConcurrentHashMap<String, String>,
        incomingByPeer: ConcurrentHashMap<String, MutableSet<String>>,
        peerLabel: String,
        peerDeviceId: String?,
        data: ByteArray,
        reply: (ByteArray) -> Boolean,
        /**
         * Invoked once per inbound transfer when its file header arrives, so the chat layer can
         * mint an inbound attachment bubble (#1). Null for the data-channel router path, which has
         * no chat repo reference; the WS session collector supplies it.
         */
        onAttachmentStarted: ((peerDeviceId: String, transferId: String, fileName: String, totalBytes: Long) -> Unit)? = null,
    ) {
        Log.d(TAG_TRANSFER, "Received binary frame: ${data.size} bytes from $peerLabel")
        // PTT voice audio first (ADR-032): PTT1 magic is disjoint from the transfer pipeline's
        // FLSH, so this pre-check costs one 4-byte compare — and it returns unconditionally, so
        // PTT audio can never reach the transfer parser even with no engine started.
        if (PttAudioFrame.isPttAudio(data)) {
            pttEngine?.onInboundBinary(peerDeviceId, data)
            return
        }

        val sessionKey = peerDeviceId?.let { trustStoreRef?.getSessionKey(FlashDeviceId(it)) }
        val frameData = if (SecureBinaryFrameCodec.isSecureFrame(data)) {
            if (sessionKey == null) {
                Log.w(TAG_TRANSFER, "Received encrypted binary frame from $peerDeviceId but no session key exists")
                return
            }
            val decrypted = SecureBinaryFrameCodec.decryptOrNull(data, sessionKey)
            if (decrypted == null) {
                Log.w(TAG_TRANSFER, "Failed to decrypt binary frame from $peerDeviceId (tampered or wrong key)")
                return
            }
            decrypted
        } else {
            data
        }

        val secureReply: (ByteArray) -> Boolean = { replyBytes ->
            val toSend = if (sessionKey != null) {
                SecureBinaryFrameCodec.encrypt(replyBytes, sessionKey)
            } else {
                replyBytes
            }
            reply(toSend)
        }

        // 1. First route to active senders (ACKs or COMPLETE from receiver)
        val consumedBySender = try {
            transferImpl.onInboundFrame(frameData)
        } catch (t: Throwable) {
            Log.w(TAG_TRANSFER, "Failed to route inbound frame to sender: ${t.message}")
            false
        }
        if (consumedBySender) {
            Log.d(TAG_TRANSFER, "Inbound binary frame consumed by sender dispatcher")
            return
        }

        // 2. If not consumed by a sender, route to receiver pipeline
        val events = try {
            receivePipeline.onFrame(frameData)
        } catch (e: Throwable) {
            Log.w(TAG_TRANSFER, "Failed to process inbound binary frame: ${e.message}")
            return
        }
        for (event in events) {
            when (event) {
                is ReceiveEvent.SessionStarted -> {
                    val frame = event.frame
                    // Offer-arrival line: without it an inbound offer is invisible in logcat until
                    // the user accepts/declines, which reads as "nothing arrives". (Desktop got the
                    // same line; 2026-09-16 phone→desktop image/video failure investigation.)
                    Log.i(TAG_TRANSFER, "Inbound file offer tid=${frame.transferId} name='${frame.fileName}' bytes=${frame.totalBytes} from peer=$peerDeviceId")
                    incomingMeta[frame.transferId] = frame
                    // Register under the peer so a transport drop can fail this transfer (#4).
                    peerDeviceId?.let { pid ->
                        incomingByPeer.getOrPut(pid) {
                            java.util.Collections.newSetFromMap(ConcurrentHashMap())
                        }.add(frame.transferId)
                    }
                    // If this transfer was already completed locally, reply COMPLETE immediately so the sender
                    // stops re-offering, and do not redownload or overwrite the local file.
                    val existing = transferImpl.activeTransfers.value.firstOrNull { it.id.value == frame.transferId }
                    val existingPath = receivedPaths[frame.transferId] ?: existing?.localPath
                    val alreadyCompleted = (existing != null && existing.state == com.transfer.flash.core.transfer.model.FlashTransferState.Completed) ||
                        (existingPath != null && File(existingPath).exists() && File(existingPath).length() == frame.totalBytes)

                    if (alreadyCompleted) {
                        Log.i(TAG_TRANSFER, "Transfer '${frame.fileName}' transferId=${frame.transferId} already completed locally — replying COMPLETE")
                        secureReply(ChunkFrame.serialize(ChunkFrame.Complete(frame.transferId, frame.fileId, verified = true)))
                        peerDeviceId?.let { pid ->
                            sendXferControl?.invoke(pid, RealFlashTransferRepository.ACTION_RESUME, frame.transferId)
                        }
                        continue
                    }

                    // A re-offer of a transfer this device already accepted is a RETRY, not a new
                    // offer: the previous attempt's session was torn down with the transport (see
                    // cleanupInbound), so the sender's relaunch arrives as a fresh FILE_START and
                    // parks on the #5 acceptance gate with a deferred sink — every chunk dropped,
                    // no ACKs, the row frozen. The user already said yes, so resolve the sink
                    // immediately instead of prompting a second time. (Cancelled is excluded by
                    // isResumableInboundRetry: a declined offer is never auto-accepted.)
                    if (transferImpl.isResumableInboundRetry(frame.transferId)) {
                        val opened = receivePipeline.acceptSession(frame.transferId)
                        transferImpl.onIncomingStarted(
                            transferId = frame.transferId,
                            fileId = frame.fileId,
                            fileName = frame.fileName,
                            totalBytes = frame.totalBytes,
                            peerName = peerLabel,
                            peerDeviceId = peerDeviceId,
                            localPath = receivedPaths[frame.transferId],
                        )
                        // Release the sender if it parked on requireReceiverAcceptance during relaunch
                        peerDeviceId?.let { pid ->
                            sendXferControl?.invoke(pid, RealFlashTransferRepository.ACTION_RESUME, frame.transferId)
                        }
                        Log.i(TAG_TRANSFER, "Re-offer of accepted transfer '${frame.fileName}' from $peerLabel — resuming (sinkResolved=$opened)")
                        continue
                    }
                    // #5: surface as a pending OFFER (Offered state, no sink/file, no chat bubble
                    // yet). Acceptance (incomingControl ACTION_ACCEPT) resolves the sink, flips it
                    // Transferring, mints the attachment bubble, and RESUMEs the parked sender.
                    transferImpl.onIncomingOffered(
                        transferId = frame.transferId,
                        fileId = frame.fileId,
                        fileName = frame.fileName,
                        totalBytes = frame.totalBytes,
                        peerName = peerLabel,
                        peerDeviceId = peerDeviceId,
                    )
                    // Mint the inbound attachment bubble in chat immediately so the receiver sees
                    // the offer card with Accept/Decline options right away.
                    peerDeviceId?.let { pid ->
                        onAttachmentStarted?.invoke(pid, frame.transferId, frame.fileName, frame.totalBytes)
                    }
                    // Bug 3: auto-download hook. AppEngine sets onIncomingOffer to accept the offer
                    // only when the file's MIME category is enabled in settings (voice/image default
                    // on; video/file default off). When null or policy says no, it stays Offered.
                    onIncomingOffer?.invoke(frame.transferId)
                    Log.i(TAG_TRANSFER, "Offered '${frame.fileName}' (${frame.totalBytes} bytes, ${frame.totalChunks} chunks) from $peerLabel — awaiting accept")
                }
                is ReceiveEvent.AckBatchReady -> {
                    Log.d(TAG_TRANSFER, "Receiver emitting ACK batch with ${event.frame.indexes.size} indexes")
                    // #20: persist the confirmed indexes so a post-restart resume skips them.
                    transferImpl.onIncomingChunkConfirmed(event.frame.transferId, event.frame.indexes)
                    updateIncomingProgress(transferImpl, receivePipeline, incomingMeta, event.frame.transferId)
                    secureReply(ChunkFrame.serialize(event.frame))
                }
                is ReceiveEvent.Completed -> {
                    val transferId = event.frame.transferId
                    val handle = openHandles.remove(transferId)
                    handle?.flush()
                    handle?.close()
                    incomingByPeer.values.forEach { it.remove(transferId) }
                    val path = receivedPaths.remove(transferId)
                    val expectedHex = incomingMeta.remove(transferId)?.fileSha256Hex
                    // #19: whole-file digest recheck against the manifest hash, layered on the
                    // pipeline's per-chunk verify-before-write. Chunk hashes already guarantee each
                    // piece's integrity; this catches assembly/offset faults or manifest/content
                    // divergence before the file is surfaced as trusted.
                    val wholeFileVerified = event.frame.verified && verifyWholeFile(path, expectedHex)
                    transferImpl.onIncomingCompleted(
                        transferId,
                        wholeFileVerified,
                        localPath = path,
                    )
                    Log.i(TAG_TRANSFER, "Receiver completed transferId=$transferId chunkVerified=${event.frame.verified} wholeFileVerified=$wholeFileVerified")
                    secureReply(ChunkFrame.serialize(event.frame))
                }
                is ReceiveEvent.Rejected -> {
                    // Late ACK/COMPLETE arriving after our own dispatcher resolved is benign
                    // noise on a symmetric mesh — debug level; everything else warns.
                    if (event.reason == com.transfer.flash.core.transfer.chunked.RejectReason.UNEXPECTED_DIRECTION) {
                        Log.d(TAG_TRANSFER, "Late/unmatched frame ignored: reason=UNEXPECTED_DIRECTION transferId=${event.transferId}")
                    } else {
                        Log.w(TAG_TRANSFER, "Receiver rejected chunk frame: reason=${event.reason} transferId=${event.transferId} index=${event.index}")
                    }
                }
            }
        }
    }

    /**
     * Transport-agnostic inbound ChunkFrame routing used by BOTH the WebSocket session collector
     * and the dedicated data-channel server (real multistream).
     */
    class DataChannelRouter internal constructor(
        private val receivePipeline: ReceivePipeline,
        private val openHandles: ConcurrentHashMap<String, RandomAccessSinkHandle>,
        private val incomingMeta: ConcurrentHashMap<String, ChunkFrame.FileStart>,
        private val receivedPaths: ConcurrentHashMap<String, String>,
        private val incomingByPeer: ConcurrentHashMap<String, MutableSet<String>>,
    ) {
        /** Late-bound because the repo is constructed after this router in ensureStarted. */
        @Volatile
        var transfer: RealFlashTransferRepository? = null

        @Volatile
        var onAttachmentStarted: ((peerDeviceId: String, transferId: String, fileName: String, totalBytes: Long) -> Unit)? = null

        fun onBytes(peerLabel: String, peerDeviceId: String?, bytes: ByteArray, reply: (ByteArray) -> Boolean) {
            val impl = transfer ?: return
            handleInboundBinary(
                transferImpl = impl,
                receivePipeline = receivePipeline,
                openHandles = openHandles,
                incomingMeta = incomingMeta,
                receivedPaths = receivedPaths,
                incomingByPeer = incomingByPeer,
                peerLabel = peerLabel,
                peerDeviceId = peerDeviceId,
                data = bytes,
                reply = reply,
                onAttachmentStarted = onAttachmentStarted,
            )
        }
    }

    /**
     * Streams the fully-assembled destination file through SHA-256 and compares it in constant time
     * against the manifest's declared whole-file digest (#19). Returns false when either input is
     * missing/invalid or the file cannot be read, so an unverifiable transfer surfaces as
     * unverified rather than being silently trusted.
     */
    private fun verifyWholeFile(path: String?, expectedHex: String?): Boolean {
        if (path.isNullOrBlank() || expectedHex.isNullOrBlank() || !Sha256.isValidHex(expectedHex)) {
            return false
        }
        return runCatching {
            val acc = IncrementalSha256()
            File(path).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    acc.update(buffer, 0, read)
                }
            }
            Sha256.hexEqualsConstantTime(acc.digestHex(), Sha256.normalizeHex(expectedHex))
        }.getOrElse { e ->
            Log.w(TAG_TRANSFER, "Whole-file verify failed to read $path: ${e.message}")
            false
        }
    }

    /** Recomputes verified bytes for an inbound transfer from the pipeline's done-set. */
    private fun updateIncomingProgress(
        transferImpl: RealFlashTransferRepository,
        receivePipeline: ReceivePipeline,
        incomingMeta: ConcurrentHashMap<String, ChunkFrame.FileStart>,
        transferId: String,
    ) {
        val start = incomingMeta[transferId] ?: return
        val done = receivePipeline.doneIndexes(transferId) ?: return
        var bytes = 0L
        for (index in done) {
            bytes += minOf(start.chunkSize.toLong(), start.totalBytes - index.toLong() * start.chunkSize)
        }
        transferImpl.onIncomingProgress(transferId, bytes)
    }

    /**
     * The single app-owned root used by the receive pipeline and the Settings storage surface.
     * Keeping path construction here prevents the host scanner/deleter from drifting to a guessed
     * directory. Callers may inspect or clear only descendants of this returned root.
     */
    fun receivedFilesRoot(context: Context): File =
        File(context.applicationContext.getExternalFilesDir(null), RECEIVED_FILES_DIRECTORY)

    /** Strips anything that could escape the intended directory (path-traversal guard, AGENTS.md §19). */
    private fun sanitizePathComponent(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._ ()-]"), "_").trim('.').ifBlank { "unnamed" }.take(120)

    /**
     * Sanitizes a relative file path (potentially with subdirectories from a folder transfer)
     * while strictly guarding against path traversal (AGENTS.md §19).
     * Replaces any forbidden characters per component and strips any '.' or '..' segments.
     */
    private fun sanitizeRelativePath(raw: String): String {
        val normalized = raw.replace('\\', '/').trim().trimStart('/')
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return "unnamed"
        val safeSegments = mutableListOf<String>()
        for (seg in segments) {
            if (seg == "." || seg == "..") continue
            val sanitized = sanitizePathComponent(seg)
            if (sanitized.isNotBlank() && sanitized != "." && sanitized != "..") {
                safeSegments.add(sanitized)
            }
        }
        return if (safeSegments.isEmpty()) "unnamed" else safeSegments.joinToString(File.separator)
    }

    /**
     * Best-effort MIME from a file name extension, used to render an inbound attachment bubble as
     * an image/video/audio card vs. a generic file card (#1). Falls back to octet-stream.
     */
    private fun guessMimeType(fileName: String): String {
        // Locale.ROOT, not getDefault(): the extension is machine data matched against the
        // lowercase ASCII literals below. Turkish getDefault() folds 'I' to dotless 'ı', which
        // would silently break "TIFF"/"GIF"/"MIDI". Same fix as core/engine Flash.kt.
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            "bmp" -> "image/bmp"
            "mp4", "m4v" -> "video/mp4"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "3gp" -> "video/3gpp"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "ogg", "oga" -> "audio/ogg"
            "wav" -> "audio/wav"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * Acquires the CPU + Wi-Fi power locks for the engine's lifetime. Idempotent: both locks are
     * non-reference-counted, and an already-held lock is left alone, so a second [ensureStarted]
     * (or a service restart) cannot stack acquisitions. See [wakeLock] / [wifiLock].
     */
    private fun acquirePowerLocks(context: Context) {
        if (wakeLock?.isHeld != true) {
            wakeLock = runCatching {
                context.getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.onFailure { Log.w(TAG_WS, "Wake lock unavailable", it) }.getOrNull()
        }
        if (wifiLock?.isHeld != true) {
            val wifiMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = runCatching {
                context.getSystemService(WifiManager::class.java)
                    .createWifiLock(wifiMode, WIFI_LOCK_TAG)
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.onFailure { Log.w(TAG_WS, "Wi-Fi lock unavailable", it) }.getOrNull()
        }
        Log.i(TAG_WS, "Power locks held for mesh: wake=${wakeLock?.isHeld == true} wifi=${wifiLock?.isHeld == true}")
    }

    /** Releases both power locks. Only [stopAll] calls this — a stopping service must not. */
    private fun releasePowerLocks() {
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        runCatching { wifiLock?.let { if (it.isHeld) it.release() } }
        wifiLock = null
    }

    /**
     * Registers the screen-on / user-present re-arm for the engine's lifetime. Idempotent: an
     * already-registered receiver is left alone, so a second [ensureStarted] (or a service restart)
     * cannot stack registrations. See [screenReceiver] for why this is not the service's job.
     */
    private fun registerScreenReceiver(context: Context) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        Log.i(TAG_WS, "Screen-on (${intent.action}) — re-arming discovery + auto-connect")
                        onScreenOn()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // NOT_EXPORTED: both actions are protected system broadcasts, and API 34+ requires an
        // explicit export flag for every runtime registration.
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { screenReceiver = receiver }
            .onFailure { Log.w(TAG_WS, "Screen-on receiver registration failed", it) }
    }

    /** Counterpart of [registerScreenReceiver]; only [stopAll] calls it. */
    private fun unregisterScreenReceiver() {
        val receiver = screenReceiver ?: return
        screenReceiver = null
        runCatching { appContextRef?.unregisterReceiver(receiver) }
    }

    /**
     * Registers the hardware PTT receiver for the engine's lifetime. Idempotent, mirroring
     * [registerScreenReceiver]: a second [ensureStarted] cannot stack registrations.
     */
    private fun registerPttReceiver(context: Context) {
        if (pttReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == PTT_DOWN_ACTION) {
                    val now = System.currentTimeMillis()
                    if (now - lastPttDownMs < PTT_DEBOUNCE_MS) {
                        Log.d(TAG_WS, "PTT down debounced")
                        return
                    }
                    lastPttDownMs = now
                    val eng = pttEngine
                    val sessionState = eng?.state?.value
                    if (eng != null && sessionState != null && sessionState !is PttFloorState.Idle) {
                        // Toggle-off / busy-deny need no mic and no visibility: always direct,
                        // so stopping works from any state (background included).
                        eng.onPttButton()
                        Log.i(TAG_WS, "Hardware PTT down in session — toggle routed")
                        return
                    }
                    if (eng != null && FlashNotificationManager.appForeground && hasMicPermission()) {
                        // Foreground + permitted: direct start, capture is legal right now.
                        // The legacy ping stays the no-peers fallback (and the adb-test path).
                        when (eng.onPttButton()) {
                            PttPressOutcome.ACCEPTED ->
                                Log.i(TAG_WS, "Hardware PTT down — session press accepted")
                            PttPressOutcome.NO_PEERS -> {
                                Log.i(TAG_WS, "Hardware PTT down — no session peers, ping fallback")
                                eng.postNotice("No paired devices online")
                                broadcastPttPing(skipDebounce = true)
                            }
                            PttPressOutcome.NO_MIC ->
                                Log.w(TAG_WS, "Hardware PTT down — refused (mic permission); wire silent")
                            PttPressOutcome.CALL_ACTIVE ->
                                Log.w(TAG_WS, "Hardware PTT down — refused (call active); wire silent")
                            PttPressOutcome.VOICE_NOTE_ACTIVE ->
                                Log.w(TAG_WS, "Hardware PTT down — refused (voice note active); wire silent")
                        }
                        return
                    }
                    // Backgrounded, unpermitted, or pre-boot: surface the app — the shell
                    // completes the press (permission prompt included). Capture from here is
                    // illegal on API 34+ and silent on API 28+, so the session must never
                    // start on this path.
                    Log.i(TAG_WS, "Hardware PTT down — surfacing app for press completion")
                    context?.let { surfaceAppForPttPress(it) }
                        ?: Log.w(TAG_WS, "PTT press dropped: no context to surface app")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(PTT_DOWN_ACTION)
        }
        // NOT_EXPORTED: the Zello hook is an implicit OEM broadcast; API 34+ requires an
        // explicit export flag for every runtime registration.
        runCatching { ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onSuccess { pttReceiver = receiver }
            .onFailure { Log.w(TAG_WS, "PTT receiver registration failed", it) }
    }

    /** Counterpart of [registerPttReceiver]; only [stopAll] calls it. */
    private fun unregisterPttReceiver() {
        val receiver = pttReceiver ?: return
        pttReceiver = null
        runCatching { appContextRef?.unregisterReceiver(receiver) }
    }

    private fun hasMicPermission(): Boolean =
        appContextRef?.let {
            ContextCompat.checkSelfPermission(it, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        } == true

    /**
     * Surfaces the app after a press that could not start directly (Phase 3). The
     * activity launch is best-effort — a background start may be silently blocked
     * (API 29+) — so the tap-to-talk notification below is the guaranteed path: a
     * notification tap always foregrounds.
     */
    private fun surfaceAppForPttPress(context: Context) {
        val launch = Intent(context.applicationContext, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PttSessionEngine.EXTRA_PTT_PRESS, true)
        }
        runCatching { context.startActivity(launch) }
            .onFailure { Log.w(TAG_WS, "PTT surface-app launch failed", it) }
        FlashNotificationManager.showPttTapToTalk(context)
    }

    /**
     * Sends one PTT ping to every paired + online peer (v1: single press event) — the adb-test
     * path and the no-peers fallback of [registerPttReceiver].
     *
     * Safe to call from [BroadcastReceiver.onReceive] (main thread): debouncing is a volatile
     * timestamp check and the fan-out is dispatched to [appScope]. The frame itself is built by
     * `FlashPtt.sendPing()`, which is blocking by contract like the transport sink behind it — so
     * this wrapper exists to keep the encode/fan-out (and its trust filter) in one place, in the
     * module, while the press stays main-safe. Fire-and-forget: no outbox row, no retry.
     */
    fun broadcastPttPing(skipDebounce: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!skipDebounce && now - lastPttDownMs < PTT_DEBOUNCE_MS) {
            Log.d(TAG_WS, "PTT down debounced")
            return
        }
        lastPttDownMs = now
        appScope.launch {
            val sent = pttEngine?.sendPing() ?: false
            if (!sent) Log.i(TAG_WS, "PTT ping: no paired+online peers")
        }
    }

    /** Stops advertising/browsing, sessions, and releases the ephemeral port. Idempotent. */
    suspend fun stopAll() = lifecycleMutex.withLock {
        val currentEngine: CompositeDiscovery?
        val currentNetwork: WsFlashNetwork?
        synchronized(this) {
            currentEngine = composite
            currentNetwork = network
            composite = null
            network = null
            transferRepo = null
            chatRepo = null
            pairing = null
            trustStoreRef = null
            calling = null
        }
        binderJob?.cancel()
        binderJob = null
        autoConnectJob?.cancel()
        autoConnectJob = null
        // Silence the ring before the scope dies: appScope.cancel() below kills the collector, so
        // nothing would ever deliver the stopping edge, and a MediaPlayer nobody holds keeps looping.
        callRingJob?.cancel()
        callRingJob = null
        callRinger?.stop()
        callRinger = null
        autoConnectGate = null
        localDeviceId = null
        localDeviceName = null
        sendXferControl = null
        onIncomingOffer = null
        dataServer?.stop()
        dataServer = null
        currentEngine?.stopAll()
        currentNetwork?.stop()
        // #16: cancel every collector/session job launched on appScope (incoming/outgoing control,
        // activeSessions, per-session readers, auto-connect). ensureStarted recreates the scope.
        appScope.cancel()
        // Nothing left to keep awake, and nothing left to re-arm: this is the only place the
        // engine's power locks are dropped and its screen-on receiver is torn down.
        pttEngine?.shutdown()
        pttEngine = null
        appContextRef?.let { PttSessionService.stop(it) }
        unregisterScreenReceiver()
        unregisterPttReceiver()
        releasePowerLocks()
        appContextRef = null
    }

    const val TOTAL_TEST_BYTES: Long = 10L * 1024 * 1024

    /** Cadence of the background auto-connect sweep; per-peer attempts are gated by [AutoConnectGate]. */
    private const val AUTO_CONNECT_SWEEP_MS = 5_000L

    private const val WAKE_LOCK_TAG = "flash:ws-mesh"
    private const val WIFI_LOCK_TAG = "flash:ws-mesh-wifi"
}

/**
 * Deterministic pseudo-random byte stream for transfer testing. Same seed => same bytes, so a
 * future end-to-end check can compare digests across devices without shipping a test file.
 */
class DeterministicPayloadInputStream(
    private val totalBytes: Long,
    private val seed: Long = 0x464C615348L,
) : InputStream() {

    private val rng = java.util.Random(seed)
    private val buffer = ByteArray(BUFFER_SIZE).also(rng::nextBytes)
    private var remaining: Long = totalBytes
    private var bufferPos: Int = BUFFER_SIZE

    override fun read(): Int {
        if (remaining <= 0) return -1
        if (bufferPos >= BUFFER_SIZE) {
            bufferPos = 0
        }
        remaining--
        return buffer[bufferPos++].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        if (bufferPos >= BUFFER_SIZE) bufferPos = 0
        val count = minOf(len.toLong(), remaining, (BUFFER_SIZE - bufferPos).toLong()).toInt()
        System.arraycopy(buffer, bufferPos, b, off, count)
        bufferPos += count
        remaining -= count
        return count
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
