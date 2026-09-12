package com.transfer.flash.di

import android.content.Context
import android.util.Log
import com.transfer.flash.core.common.perf.AndroidDeviceProfile
import com.transfer.flash.core.common.perf.FlashPerformanceClassifier
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.perf.FlashPerformanceVerdict
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.debug.DiscoveryEngineHolder
import com.transfer.flash.identity.AppIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Production composition seam over the WebSocket mesh stack (Phase-4 UI wiring, path A).
 *
 * The full transport stack — [FlashNetwork] + [FlashChatRepository] + [FlashTransferRepository]
 * plus the inbound/outbound framing collectors — is assembled inside [DiscoveryEngineHolder]
 * (historically reachable only from the Dev Console and background service). This facade is the
 * single injectable entry point that boots that holder from the app lifecycle and republishes its
 * subsystems, so the Compose shell can bind to real data instead of the sample/hardcoded state.
 *
 * Booting is idempotent: [DiscoveryEngineHolder.ensureStarted] serializes start/stop under its own
 * mutex and returns the already-running engine on repeat calls, so the Dev Console, the background
 * service, and this facade all share the same singletons — there is never a second WS server or a
 * duplicate NSD engine.
 *
 * The subsystem accessors are null until [ready] flips true; callers observe [ready] and read the
 * accessors only once booted (the holder sets `current*()` before `ensureStarted` returns, so a
 * `ready == true` observation always sees non-null subsystems).
 */
@Singleton
class AppEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:AppScope private val scope: CoroutineScope,
) {
    private val _ready = MutableStateFlow(false)

    /** Flips true once [DiscoveryEngineHolder.ensureStarted] has fully booted the stack. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _startError = MutableStateFlow<Throwable?>(null)

    /**
     * Non-null when the most recent [start] attempt failed (e.g. discovery rejected before
     * permissions are granted). The shell can surface this instead of the app crashing; a later
     * successful [start] clears it. Permission gating / retry is the caller's concern (Phase 4).
     */
    val startError: StateFlow<Throwable?> = _startError.asStateFlow()

    private val startMutex = Mutex()

    /** Real Room-backed chat repository, or null before [start] completes. */
    val chats: FlashChatRepository? get() = DiscoveryEngineHolder.currentChats()

    /** Real chunked-transfer repository, or null before [start] completes. */
    val transfers: FlashTransferRepository? get() = DiscoveryEngineHolder.currentTransfers()

    /** Live WebSocket mesh network, or null before [start] completes. */
    val network: FlashNetwork? get() = DiscoveryEngineHolder.currentNetwork()

    /** Composite discovery engine, or null before [start] completes. */
    val discovery: CompositeDiscovery? get() = DiscoveryEngineHolder.current()

    /**
     * Manual reconnect behind the chat connection banner's Retry button: restarts discovery
     * browsing and forces an immediate auto-connect sweep of every discovered peer without a live
     * session. Returns false when the stack has not booted yet (nothing to retry).
     */
    fun reconnectNow(): Boolean = DiscoveryEngineHolder.reconnectNow()

    /** Device-to-device pairing coordinator (Nearby Pair/Chat + trust store), or null before [start]. */
    val pairing: com.transfer.flash.pairing.PairingCoordinator? get() = DiscoveryEngineHolder.currentPairing()

    /** WebRTC voice/video calling contract (C7 / ADR-025), or null before [start]. */
    val calls: com.transfer.flash.core.calling.FlashCalling? get() = DiscoveryEngineHolder.currentCalling()

    // Local identity is read from the same persisted store the holder advertises with, so the
    // Nearby "this device" card matches what peers actually see.
    //
    // Resolved exactly once. The store mints *and persists* both values on its first read, so this
    // is available and correct long before start() runs — and nothing in the app ever rewrites it
    // (updateFriendlyName has no production caller), so it is process-stable. Caching the pair here
    // keeps the settings and Nearby models off a SharedPreferences lookup plus two allocations on
    // every recomposition; they used to call through on each read.
    private val localIdentity: Pair<String, String> by lazy {
        AppIdentity(context).let { it.deviceId to it.friendlyName }
    }

    /** This device's stable id (matches the discovery-advertised id in the normal, non-blank case). */
    val localDeviceId: String get() = localIdentity.first

    /** This device's advertised friendly name. */
    val localFriendlyName: String get() = DiscoveryEngineHolder.currentFriendlyName() ?: localIdentity.second

    /**
     * #14: persisted user settings (theme / haptics / dynamic accent / background transfers /
     * display name). Backed by Preferences DataStore in the app files dir; a process-wide singleton
     * per file (constructing a second for the same file throws), so it lives on this @Singleton.
     */
    val settingsStore: com.transfer.flash.core.persistence.settings.FlashSettingsDataStore by lazy {
        com.transfer.flash.core.persistence.settings.FlashSettingsDataStore(
            produceFile = { java.io.File(context.filesDir, "flash_settings.preferences_pb") },
            scope = scope,
        )
    }

    /** #14: real app version from the package manifest (replaces the hardcoded "dev" About line). */
    val appVersionName: String
        get() = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "dev"

    /**
     * ERROR-033: what this device's hardware says its tier should be, classified once per process.
     *
     * Lazy rather than eager because the read walks `MediaCodecList`, which is cheap but not free,
     * and nothing needs it until the first UI composition or the first call. Not persisted: a
     * device that gains a capability, or an OEM update that fixes an under-reported `totalMem`,
     * should be re-read on the next launch rather than remembered forever.
     */
    val detectedPerformance: FlashPerformanceVerdict by lazy {
        FlashPerformanceClassifier.classify(AndroidDeviceProfile.read(context)).also {
            Log.i(
                TAG_PERF,
                "Performance tier detected=${it.mode} reason=${it.reason} " +
                    "video=${it.mode.video.label} ptime=${it.mode.voice.ptimeMs}ms",
            )
        }
    }

    /**
     * The tier actually in force: the user's pin if they made one, otherwise [detectedPerformance].
     *
     * Eagerly started so the value is available to the theme on the very first composition — a
     * tier that arrived one frame late would animate once and then stop, which reads as a glitch.
     * The seed is the detected tier, so the only window in which this can be "wrong" is between
     * process start and DataStore's first emission, and in that window it is wrong in the
     * direction of the hardware rather than of a stale pin.
     *
     * `by lazy` so that constructing this @Singleton stays free of the codec-list walk; [start]
     * touches it from a background coroutine, which in practice is what pays for it.
     */
    val performanceMode: StateFlow<FlashPerformanceMode> by lazy {
        settingsStore.performanceMode
            .map { pinned -> pinned ?: detectedPerformance.mode }
            .stateIn(scope, SharingStarted.Eagerly, detectedPerformance.mode)
    }

    /**
     * Idempotently boots the transport stack on [scope]. Safe to call from every composition /
     * lifecycle entry — the first call wins, later calls no-op once [ready] is set. Boot failures
     * are captured into [startError] rather than thrown, so a discovery-permission rejection can
     * never crash the app from this launch path; permission gating is handled by the caller (Phase 4).
     */
    fun start() {
        scope.launch {
            startMutex.withLock {
                if (_ready.value) return@withLock
                // ERROR-034: clear a previous failure *before* retrying, not only on success. The
                // chat tab renders its error state off this flow and offers a retry that calls back
                // in here; leaving the stale Throwable set would keep the error visible for the
                // whole retry, making the button look inert.
                _startError.value = null
                // ERROR-033: resolve the tier BEFORE the engine is built. Everything downstream
                // reads it through a lambda, so a late value would still be picked up, but
                // WsFlashNetwork logs its keepalive cadence at construction and a call placed in
                // the first second should not be the one that gets HIGH-tier defaults by accident.
                // This is also what pays for the codec-list walk on a background thread rather
                // than on the first composition.
                DiscoveryEngineHolder.performanceMode = performanceMode.value
                val result = runCatching { DiscoveryEngineHolder.ensureStarted(context) }
                result
                    .onSuccess {
                        // Bug 3: mirror auto-download settings into the holder so the
                        // auto-accept policy in handleInboundBinary can read them without
                        // a reference to the DataStore.
                        scope.launch {
                            settingsStore.autoDownloadVoice.collect { DiscoveryEngineHolder.autoDownloadVoice = it }
                        }
                        scope.launch {
                            settingsStore.autoDownloadImage.collect { DiscoveryEngineHolder.autoDownloadImage = it }
                        }
                        scope.launch {
                            settingsStore.autoDownloadVideo.collect { DiscoveryEngineHolder.autoDownloadVideo = it }
                        }
                        scope.launch {
                            settingsStore.autoDownloadFile.collect { DiscoveryEngineHolder.autoDownloadFile = it }
                        }
                        // ERROR-031 / D8: same mirroring for the call-quality preference, read by
                        // CallCoordinator through a lambda so core:calling stays persistence-free.
                        scope.launch {
                            settingsStore.prioritiseVoiceQuality.collect {
                                DiscoveryEngineHolder.prioritiseVoiceQuality = it
                            }
                        }
                        // ERROR-033: same mirroring for the performance tier, read by
                        // CallCoordinator and WsFlashNetwork through lambdas.
                        scope.launch {
                            performanceMode.collect { DiscoveryEngineHolder.performanceMode = it }
                        }
                        _startError.value = null
                        _ready.value = true
                    }
                    .onFailure { _startError.value = it }
            }
        }
    }

    private companion object {
        const val TAG_PERF = "FlashPerf"
    }
}
