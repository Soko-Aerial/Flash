package com.transfer.flash.di

import android.content.Context
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** Device-to-device pairing coordinator (Nearby Pair/Chat + trust store), or null before [start]. */
    val pairing: com.transfer.flash.pairing.PairingCoordinator? get() = DiscoveryEngineHolder.currentPairing()

    /** WebRTC voice/video call coordinator (C7 / ADR-025), or null before [start]. */
    val calls: com.transfer.flash.core.calling.CallCoordinator? get() = DiscoveryEngineHolder.currentCallCoordinator()

    // Local identity is read from the same persisted store the holder advertises with, so the
    // Nearby "this device" card matches what peers actually see. Lazy: the store touches prefs.
    private val appIdentity by lazy { AppIdentity(context) }

    /** This device's stable id (matches the discovery-advertised id in the normal, non-blank case). */
    val localDeviceId: String get() = appIdentity.deviceId

    /** This device's advertised friendly name. */
    val localFriendlyName: String get() = appIdentity.friendlyName

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
     * Idempotently boots the transport stack on [scope]. Safe to call from every composition /
     * lifecycle entry — the first call wins, later calls no-op once [ready] is set. Boot failures
     * are captured into [startError] rather than thrown, so a discovery-permission rejection can
     * never crash the app from this launch path; permission gating is handled by the caller (Phase 4).
     */
    fun start() {
        scope.launch {
            startMutex.withLock {
                if (_ready.value) return@withLock
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
                        _startError.value = null
                        _ready.value = true
                    }
                    .onFailure { _startError.value = it }
            }
        }
    }
}
