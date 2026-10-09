@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.result.runSuspendCatching
import com.transfer.flash.core.persistence.db.runInWriteTransaction
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.calling.CallCoordinator
import com.transfer.flash.core.calling.FEATURE_VIDEO_UPGRADE
import com.transfer.flash.core.calling.FlashCalling
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import com.transfer.flash.core.discovery.jmdns.JmdnsTransport
import com.transfer.flash.core.discovery.multicast.JvmMulticastSocketFactory
import com.transfer.flash.core.discovery.multicast.MulticastTransport
import com.transfer.flash.core.security.pairing.FlashPairingCoordinator
import com.transfer.flash.core.messaging.EmptyFlashChatRepository
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.messaging.RealFlashChatRepository
import com.transfer.flash.core.messaging.model.FlashAttachmentProgress
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
import com.transfer.flash.core.messaging.protocol.ChatTextFrameCodec
import com.transfer.flash.core.security.crypto.E2eFrameCodec
import com.transfer.flash.core.messaging.protocol.DirectChatFamily
import com.transfer.flash.core.messaging.protocol.DirectMessageActionCodec
import com.transfer.flash.core.messaging.protocol.GroupFrameCodec
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.messaging.protocol.PttAudioFrame
import com.transfer.flash.core.messaging.protocol.PttFrameCodec
import com.transfer.flash.core.messaging.protocol.PttSessionCodec
import com.transfer.flash.core.messaging.util.FlashMimeTypes
import com.transfer.flash.core.persistence.db.FlashDatabase
import com.transfer.flash.core.persistence.db.openEncryptedFlashDatabase
import com.transfer.flash.core.swarm.api.FlashSwarm
import com.transfer.flash.core.swarm.api.FlashSwarmConfig
import com.transfer.flash.core.engine.swarm.SwarmHostBinding
import com.transfer.flash.core.engine.swarm.MessagingSwarmGroupContext
import com.transfer.flash.core.engine.swarm.JvmPieceStorage
import com.transfer.flash.core.engine.swarm.RoomSwarmStateStore
import com.transfer.flash.core.ptt.FlashPtt
import com.transfer.flash.core.ptt.PttAudioPlatform
import com.transfer.flash.core.ptt.PttSessionEngine
import com.transfer.flash.core.ptt.platformPttAudio
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.mode.ConnectionModeController
import com.transfer.flash.core.network.mode.ConnectionModePolicy
import com.transfer.flash.core.network.mode.ConnectionStrategy
import com.transfer.flash.core.network.mode.LinkView
import com.transfer.flash.core.network.planner.AutoConnector
import com.transfer.flash.core.network.planner.ConnectionPlanner
import com.transfer.flash.core.network.presence.PresenceCodec
import com.transfer.flash.core.network.presence.PresenceExchange
import com.transfer.flash.core.network.presence.PresenceLocalView
import com.transfer.flash.core.network.remembered.RememberedRoutes
import com.transfer.flash.core.network.sweep.JvmLocalSubnets
import com.transfer.flash.core.network.sweep.SweepController
import com.transfer.flash.core.network.sweep.SweepSituation
import com.transfer.flash.core.network.sweep.SweepState
import com.transfer.flash.core.network.sweep.TcpHostProbe
import com.transfer.flash.core.engine.MagicFrameRouter
import com.transfer.flash.core.network.ws.JvmWsFlashNetwork
import com.transfer.flash.core.network.ws.WsSession
import com.transfer.flash.core.network.ws.WsTransferServer
import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.RejectReason
import com.transfer.flash.core.transfer.chunked.WholeFileCheck
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.policy.OkioRandomAccessSinkHandle
import com.transfer.flash.core.transfer.policy.RandomAccessChunkSink
import com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.network.tls.FlashCertMaker
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.TofuPinVerifier
import com.transfer.flash.core.network.tls.requireTransportSecurity
import com.transfer.flash.core.security.crypto.FlashFingerprint
import com.transfer.flash.core.security.crypto.PersistedFlashCrypto
import com.transfer.flash.core.security.crypto.SecureBinaryFrameCodec
import com.transfer.flash.core.engine.FlashEngine
import com.transfer.flash.core.engine.FlashDesktop
import com.transfer.flash.core.engine.FlashInboundRouter
import com.transfer.flash.core.engine.FlashPathSanitizer
import com.transfer.flash.core.engine.FlashSessionCoordinator
import com.transfer.flash.core.engine.FlashConfig
import com.transfer.flash.core.engine.FlashReadiness
import com.transfer.flash.ui.settings.FlashThemeMode
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Desktop composition root — the no-Hilt, no-`Context` equivalent of `:app`'s `AppEngine`
 * facade (Phase 21, sub-step 21-2).
 *
 * **Why this is not a `FlashEngine`:** `DefaultFlashEngine` and its `FlashSettingsDataStore`
 * member live in `:core:engine`/`:core:persistence` **androidMain** (Room, Android Keystore,
 * `androidx.datastore`), so no jvm() classpath can see them. Until 09B-2 delivers Room-3 KMP
 * persistence (D5 = C) this facade is a desktop-local class exposing exactly the surface the
 * shell reads — the same members `AppEngine` exposes, minus the Android-only ones (pairing
 * coordinator, calls, PTT, settings DataStore, performance verdict).
 *
 * **What it assembles** — the composition the Phase 16 harness (`core/engine/src/jvmTest/
 * .../interop/DesktopEndpointFixture`) proved working end-to-end on the desktop tier, re-homed
 * from test code into shipped desktop code:
 *
 * - Discovery: `JmdnsTransport` (Phase 14) behind `CompositeDiscovery`, same `_flash-transfer._tcp`
 *   service type and TxtCodec wire format as Android's NSD.
 * - Transport: `JvmWsFlashNetwork` (Phase 15-4) — same `FLASH_WS_HELLO`, protocol version 2.
 * - Transfer: `RealFlashTransferRepository` (commonMain since 13B-3e) with a desktop
 *   `FileSourceOpener` (Okio over `java.io.File`) and stream channels riding the WS session's
 *   binary lane.
 * - Receive: `ReceivePipeline` with the **#5 accept gate** (`requireAcceptance = true` +
 *   deferred `sinkFactory` + RESUME-to-start) and the same path-containment discipline the
 *   production Android composition applies (Sentinel).
 * - Identity/trust: file-backed stores under `~/.flash/` (desktop stand-ins for the
 *   SharedPreferences-backed Android ones; contract-identical — see the Phase 16 KDoc).
 *
 * **Chats are durable since slice 4**: `RealFlashChatRepository` over an encrypted
 * file database under `<stateDir>/chat/flash.db` (the slice-1 seam). Before boot
 * completes [chats] is still the honest empty repository below; the shell already
 * renders that state.
 *
 * **Resume across restart is off** (`store = null`, D5 = C pending): the Phase 16 harness runs
 * the same way, and G7 is already logged BLOCKED ON 09B-2.
 */
public class DesktopEngine(
    /** Root for received files. Default: null (resolves to settings store or ~/FlashReceived). */
    receivedRoot: File? = null,
    /** Root for identity/trust/settings-free state. Default: `DesktopPaths.stateDir()` (`~/.flash`; XDG data folder on Linux). */
    private val stateDir: File = DesktopPaths.stateDir(),
    /**
     * At-rest protection for the identity key (ADR-035). Production leaves the default: Windows DPAPI on Windows, the
     * Secret Service keyring then an owner-only key file on Linux (Linux plan L0/L1, `DesktopVaults`). Tests pass `IdentityKeyVault.PassThrough`; nothing else should.
     */
    identityVault: com.transfer.flash.core.security.identity.IdentityKeyVault =
        DesktopVaults.forCurrentOs(stateDir),
    /**
     * Microphone and speaker for push-to-talk (ADR-058). Production leaves the default, `javax.sound`. Tests pass a
     * fake, because the default opens the real microphone.
     */
    private val pttAudio: PttAudioPlatform = platformPttAudio(),
    /**
     * Display name override (from FlashConfig). **In memory only**: it is what this run advertises, and it is never
     * written to the persisted identity, so a config default cannot overwrite the name the owner chose in Settings.
     * When null/blank the persisted name is used. A rename through [renameLocalDevice] / [updateFriendlyName]
     * persists the new name and ends the override.
     */
    displayName: String? = null,
    /**
     * Inbound offer auto-accept override (from FlashConfig). Applies to **paired peers only** (AGENTS.md section 19):
     * an offer from a peer that is not trusted always waits for the user, whatever this says.
     */
    private val autoAcceptIncoming: Boolean = false,
) : FlashEngine, FlashReadiness {
    public val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ready = MutableStateFlow(false)

    /** True once the stack is assembled and the transport is bound; false before that and again after [close]. */
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _startError = MutableStateFlow<Throwable?>(null)

    /** Why the boot failed, or that the engine was closed before it finished; null while booting or healthy. */
    override val startError: StateFlow<Throwable?> = _startError.asStateFlow()

    /** Set once by [close] / [stop]; a closed engine never becomes ready and cannot be started again. */
    @Volatile
    private var closed = false

    /** Serialises [releaseSubsystems], which both [shutdown] and the boot coroutine's tail may run. */
    private val teardownLock = Any()

    /** The in-memory display-name override (see the constructor's `displayName`); null once the owner renames. */
    @Volatile
    private var nameOverride: String? = displayName?.trim()?.takeIf { it.isNotEmpty() }

    // --- Identity (file-backed; see DesktopIdentityStores.kt) ---
    private val identityStore = DesktopIdentityStore(stateDir)

    /** The name this run presents to peers: the override when there is one, else the persisted name. */
    private fun effectiveFriendlyName(): String = nameOverride ?: identityStore.getIdentity().friendlyName
    private val settingsStore = DesktopSettingsStore(stateDir)
    private val _settings = MutableStateFlow(settingsStore.loadSettings())
    public val settings: StateFlow<DesktopSettings> = _settings.asStateFlow()
    private val _discoveryMode = MutableStateFlow(_settings.value.discoveryMode)
    public val discoveryMode: StateFlow<FlashDiscoveryMode> = _discoveryMode.asStateFlow()

    public fun updateSettings(transform: (DesktopSettings) -> DesktopSettings) {
        val updated = transform(_settings.value)
        _settings.value = updated
        settingsStore.saveSettings(updated)
        val newDir = File(updated.saveLocation).canonicalFile
        if (newDir.path != _canonicalRoot.path) {
            _canonicalRoot = newDir.apply { mkdirs() }
        }
    }

    private val trustStoreImpl = DesktopTrustStore(stateDir)
    public val identity: com.transfer.flash.core.security.identity.FlashIdentity
        get() = identityStore.getIdentity()

    private val _localFriendlyName = MutableStateFlow(effectiveFriendlyName())

    /**
     * This device's display name as something the UI can observe. [localFriendlyName] was a plain read of the
     * store, so nothing in Compose ever learned of a rename: Settings, the sidebar avatar and the Nearby header
     * kept the old name until something unrelated recomposed them.
     */
    public val localFriendlyNameState: StateFlow<String> = _localFriendlyName.asStateFlow()

    /**
     * The desktop identity crypto — Phase 26 (P2, ADR-035): a P-256 identity keypair generated
     * once, DPAPI-protected at rest under `<stateDir>/identity/id-key.bin`, surviving restarts.
     * This is what will make TOFU trust durable and pairing (G2/G6) possible on desktop; the
     * pairing-session coordinator + numeric-comparison dialog that CONSUME it are the remaining
     * 26-3 work. Falls back LOUDLY to an in-memory identity if the vault is unreadable — see
     * [PersistedFlashCrypto]'s degradation contract.
     */
    public val crypto: com.transfer.flash.core.security.crypto.FlashCrypto =
        com.transfer.flash.core.security.crypto.PersistedFlashCrypto(stateDir, identityVault)

    private val ephemeralKeyPair = crypto.generateEphemeralEcdhKeyPair()

    /**
     * Desktop pairing (Phase 26-3, ADR-035): the SAME `DefaultFlashPairingProtocol` and
     * `FLASH_PAIR` wire framing the phone runs, over [trustStore] and the identity above. What
     * makes this durable — and what P2 existed for — is that [crypto]'s keypair survives a
     * restart, so the fingerprint both devices compared stays the same fingerprint.
     *
     * `sendToPeer` mirrors the app host's contract: non-blocking, returns false when there is no
     * live session (the coordinator uses that for its "couldn't reach" feedback).
     */
    public val pairing: FlashPairingCoordinator = FlashPairingCoordinator(
        localFingerprintHex = com.transfer.flash.core.security.crypto.FlashFingerprint.formatHexGroups(
            com.transfer.flash.core.security.crypto.FlashFingerprint.fingerprint(crypto.identityPublicKeyEncoded),
        ),
        localDeviceId = identity.deviceId.value,
        localName = effectiveFriendlyName(),
        localModel = "desktop",
        // One ephemeral ECDH key for the engine's lifetime, used to derive AES-256 session keys.
        ephemeralPublicKey = ephemeralKeyPair.publicKeyEncoded,
        trustStore = trustStoreImpl,
        scope = scope,
        sendToPeer = { peerId, text ->
            val session = networkImpl?.activeSessions?.value?.get(FlashDeviceId(peerId)) as? WsSession
            if (session != null) {
                session.connection.sendTextAsync(text)
                true
            } else {
                // Logged on BOTH branches, like the app host, because this boolean IS the
                // user-visible "Couldn't reach …" — and the interesting half is the set of ids we do
                // hold sessions for. A session registered under the peer's WS-hello id cannot be
                // found by an endpoint id that differs, and a miss that prints only the id it looked
                // for is indistinguishable from "we never connected at all" (2026-09-14: the
                // desktop's discovery found the phone and Pair still said it could not reach it).
                FlashLog.w(
                    TAG_WS,
                    "Pairing sendToPeer id=$peerId has NO session; " +
                        "active sessions=${networkImpl?.activeSessions?.value?.keys?.map { it.value } ?: "none"}",
                    null,
                )
                false
            }
        },
        crypto = crypto,
        ephemeralKeyPair = ephemeralKeyPair,
    )

    // --- Subsystems; non-null once [ready] flips true ---
    //
    // Forwarding proxies handed out by [network] / [discovery] / [transfers] while the real object does not exist
    // yet (or no longer does), so a reference captured before boot starts working after it. Assigning the matching
    // `*Impl` below installs the real object into its proxy (and null puts the Empty stand-in back).
    private val networkProxy = SwitchingNetwork()
    private val discoveryProxy = SwitchingDiscovery()
    private val transferProxy = SwitchingTransferRepository()

    // The newest transport/discovery the boot built, kept after a release nulls the `*Impl` fields: `assemble()` holds
    // them in locals and may still bind them after a close, so the boot's tail must be able to stop them again.
    @Volatile
    private var builtNetwork: JvmWsFlashNetwork? = null

    @Volatile
    private var builtDiscovery: CompositeDiscovery? = null

    @Volatile
    private var networkImpl: JvmWsFlashNetwork? = null
        set(value) {
            field = value
            if (value != null) builtNetwork = value
            networkProxy.install(value)
        }

    @Volatile
    private var discoveryImpl: CompositeDiscovery? = null
        set(value) {
            field = value
            if (value != null) builtDiscovery = value
            discoveryProxy.install(value)
        }

    @Volatile
    private var transferImpl: RealFlashTransferRepository? = null
        set(value) {
            field = value
            transferProxy.install(value)
        }

    @Volatile
    private var chatImpl: RealFlashChatRepository? = null

    @Volatile
    private var chatDb: FlashDatabase? = null
    @Volatile
    private var callsImpl: FlashCalling? = null

    /** Push-to-talk voice sessions (ADR-032, ADR-058); built in [assemble], routed from the inbound text/binary paths. */
    @Volatile
    private var pttImpl: FlashPtt? = null
    private val magicRouter = MagicFrameRouter()

    @Volatile
    private var swarmBinding: SwarmHostBinding? = null

    @Volatile
    private var hostSwarm: FlashSwarm? = null

    /** Attached group swarm file transfer facade (SW-8), or null when not attached. */
    override val swarm: FlashSwarm? get() = swarmBinding?.swarm ?: hostSwarm

    /**
     * Builds and attaches the engine's own swarm: a [SwarmHostBinding] wired to the magic-frame router, the group
     * gate, the piece storage and the Room state store, so it receives the `sw1` frames. Idempotent: a second call
     * returns the swarm already built.
     *
     * Returns null when the engine cannot build one yet (the transport, transfer repository or chat database does
     * not exist before the boot has got far enough) or has been closed.
     *
     * @throws IllegalStateException when a swarm was attached with [attachSwarm] (host-provided): the two are mutually
     * exclusive, because a second binding would answer the same frames as the first. Call [detachSwarm] first.
     */
    override fun attachSwarm(config: FlashSwarmConfig): FlashSwarm? {
        synchronized(this) {
            if (closed) return null
            swarmBinding?.let { return it.swarm }
            check(hostSwarm == null) {
                "A host-provided swarm is already attached; detachSwarm() before asking the engine to build its own."
            }
            val net = networkImpl ?: return null
            val xfer = transferImpl ?: return null
            val chat = chatImpl ?: return null
            val db = chatDb ?: return null
            val gate = chat.groupGate
            val groupContext = MessagingSwarmGroupContext(
                localDeviceId = identity.deviceId.value,
                groupGate = gate,
                groupMemberDao = { db.groupMemberDao() },
                groupCrypto = com.transfer.flash.core.engine.group.FlashGroupCrypto(crypto),
                groupTitleLookup = { db.conversationDao().get(it)?.title },
            )
            val storage = JvmPieceStorage(
                partialDir = File(stateDir, "swarm/partial"),
                destinationDir = canonicalRoot,
            )
            val stateStore = RoomSwarmStateStore(db.swarmDao())
            val binding = SwarmHostBinding(
                config = config,
                localDeviceId = identity.deviceId.value,
                scope = scope,
                magicRouter = magicRouter,
                network = net,
                transferRepository = xfer,
                groupContext = groupContext,
                storage = storage,
                stateStore = stateStore,
                chatRepository = chat,
                isCallActive = { callsImpl?.activeCall?.value?.let { it.state != FlashCallState.ENDED } == true },
                isServingEnabled = { _discoveryMode.value != FlashDiscoveryMode.ECO },
            )
            swarmBinding = binding
            scope.launch {
                while (isActive) {
                    delay(15 * 60 * 1000L)
                    swarmBinding?.swarm?.runRetentionCleanup()
                    swarmBinding?.swarm?.reevaluate()
                }
            }
            return binding.swarm
        }
    }

    /**
     * Registers a host-constructed [FlashSwarm] on this facade **only**. It is *not* wired to the magic-frame router,
     * the group gate or the drivers (the engine cannot reach into a swarm it did not build), so it receives no
     * inbound `sw1` frames from this engine; the host that built it is responsible for feeding it. Use
     * [attachSwarm] with a [FlashSwarmConfig] for a fully wired swarm.
     *
     * Attaching the same instance again is a no-op.
     *
     * @throws IllegalStateException when a different swarm (host-provided or engine-built) is already attached, or the
     * engine is closed. Call [detachSwarm] first.
     */
    override fun attachSwarm(swarm: FlashSwarm) {
        synchronized(this) {
            check(!closed) { "DesktopEngine is closed; cannot attach a swarm." }
            val current = swarmBinding?.swarm ?: hostSwarm
            if (current === swarm) return
            check(current == null) {
                "A swarm is already attached; detachSwarm() before attaching another."
            }
            hostSwarm = swarm
        }
    }

    /**
     * Detaches the swarm. An engine-built binding is detached from the router and stopped; a host-provided swarm is
     * only forgotten (the host built it, the host shuts it down). Idempotent.
     */
    override fun detachSwarm() {
        synchronized(this) {
            swarmBinding?.detach()
            swarmBinding = null
            hostSwarm = null
        }
    }

    public fun peerFeatures(peerId: String): Set<String> =
        networkImpl?.activeSessions?.value?.get(FlashDeviceId(peerId))?.peer?.features.orEmpty()

    public suspend fun prepareSwarmOrigin(
        groupId: String,
        messageId: String,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        uri: String,
    ): Pair<com.transfer.flash.core.swarm.model.SwarmManifest, String>? {
        return swarmBinding?.prepareOrigin(groupId, messageId, fileName, mimeType, sizeBytes, uri)
    }

    private val networkWatcher = com.transfer.flash.core.network.resilience.JvmNetworkWatcher(
        scope = scope,
        onAvailable = {
            FlashLog.i(TAG_DISCOVERY, "Network became available; triggering instant rediscovery and reconnect")
            reconnectNow()
        },
        onLinkChanged = {
            FlashLog.i(TAG_DISCOVERY, "Network link changed; triggering rediscovery and reconnect")
            reconnectNow()
        },
    )

    // --- Persisted desktop preferences (today: the Appearance selection) ---
    //
    // Narrow accessors rather than exposing the store, matching how identity/trust are handled:
    // `DesktopSettingsStore` stays internal so its file format is not part of the engine's surface.
    // Built from the engine's OWN `stateDir`, which is the same reason `receivedDirectory` exists —
    // re-deriving `~/.flash` at the call site is how `clearReceivedFiles` came to write to a folder
    // the engine was not using.

    /** The persisted Appearance selection; [FlashThemeMode.System] when unset. */
    public fun storedThemeMode(): FlashThemeMode = _settings.value.themeMode

    /** Records the Appearance selection so it survives a restart. */
    public fun storeThemeMode(mode: FlashThemeMode) {
        updateSettings { it.copy(themeMode = mode) }
    }

    /** Records the Close-to-Tray preference so it survives a restart. */
    public fun storeCloseToTray(enabled: Boolean) {
        updateSettings { it.copy(closeToTray = enabled) }
    }

    /** Records whether desktop notifications are enabled so it survives a restart. */
    public fun storeShowNotifications(enabled: Boolean) {
        updateSettings { it.copy(showNotifications = enabled) }
    }

    /** Records the desktop UI-scale so it survives a restart (AD-D1). */
    public fun storeUiScale(scale: Float) {
        updateSettings { it.copy(uiScale = scale.coerceIn(0.75f, 1.5f)) }
    }

    /** Updates the discovery mode in memory, applies it to the active discovery transport, and persists it. */
    public fun setDiscoveryMode(mode: FlashDiscoveryMode) {
        _discoveryMode.value = mode
        updateSettings { it.copy(discoveryMode = mode) }
        scope.launch {
            discoveryImpl?.setMode(mode)
        }
    }

    /** Inbound chat text notification hook (for DesktopNotificationManager). */
    public var onInboundMessageNotification: ((conversationId: String, senderName: String?, text: String, groupTitle: String?) -> Unit)? = null

    /** Inbound attachment notification hook (for DesktopNotificationManager). */
    public var onInboundAttachmentNotification: ((conversationId: String, senderName: String?, fileName: String, mimeType: String, groupTitle: String?) -> Unit)? = null

    /** Inbound group join request notification hook (for DesktopNotificationManager / shell). */
    public var onJoinRequestNotification: ((groupId: String, groupTitle: String, requesterName: String) -> Unit)? = null

    /** A member added this device to a group it cannot accept (not paired with the owner): the sentence to show (M-23). */
    public var onGroupOfferRefused: ((groupId: String, groupName: String, message: String) -> Unit)? = null

    /**
     * Chat history. The real repository once [assemble] has built it; the honest empty
     * repository before that (and if the database ever fails to open) — the shell renders
     * both, per ERROR-034.
     *
     * KNOWN LIMIT: unlike [transfers], [network] and [discovery] this is not a forwarding proxy (the chat
     * repository's surface is far larger), so a reference captured before [ready] stays the empty repository.
     * Read it after [ready] (or `FlashEngine.awaitReady()`).
     */
    override val chats: FlashChatRepository get() = chatImpl ?: EmptyFlashChatRepository

    /**
     * Voice/video calling. The shared coordinator once [assemble] has built it; null before
     * that (and the shell renders no call UI until it exists).
     */
    override val calls: FlashCalling? get() = callsImpl

    /**
     * Push-to-talk. The shared [PttSessionEngine] once [assemble] has built it; null before that.
     */
    override val ptt: FlashPtt? get() = pttImpl

    /**
     * Transfers. The real repository once it exists; before that (and after [close]) a forwarding proxy over the
     * [EmptyFlashTransferRepository] stand-in, so a reference captured early starts working at boot (its flows
     * re-emit from the real source) instead of staying empty for ever. Operations that cannot run yet return
     * `Failure`.
     */
    override val transfers: FlashTransferRepository get() = transferImpl ?: transferProxy

    /** Network transport. The real transport once it exists, else a forwarding proxy (see [transfers]). */
    override val network: FlashNetwork get() = networkImpl ?: networkProxy

    /** Discovery. The real discovery once it exists, else a forwarding proxy (see [transfers]). */
    override val discovery: FlashDiscovery get() = discoveryImpl ?: discoveryProxy
    override val trustStore: com.transfer.flash.core.security.trust.FlashTrustStore get() = trustStoreImpl
    public val trust: com.transfer.flash.core.security.trust.FlashTrustStore get() = trustStoreImpl

    override fun attachPtt(
        hasMicPermission: () -> Boolean,
        isCallActive: () -> Boolean,
        audioRateHz: () -> Int,
    ): FlashPtt? {
        synchronized(this) {
            pttImpl?.let { return it }
            val net = networkImpl ?: return null
            val localId = identity.deviceId.value
            val friendlyName = effectiveFriendlyName()
            val ptt = PttSessionEngine(
                localId = { localId },
                localName = { friendlyName },
                isTrustedPeer = { peerId -> trustStoreImpl.isTrusted(FlashDeviceId(peerId)) },
                snapshotMembers = {
                    net.activeSessions.value.keys.mapNotNull { deviceId ->
                        deviceId.value.takeIf { it != localId && trustStoreImpl.isTrusted(deviceId) }
                    }
                },
                sendControl = { peerId, text ->
                    val session = net.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
                    if (session != null) {
                        runCatching { session.connection.sendText(text) }
                            .onFailure { FlashLog.w(TAG_WS, "PTT control send failed peer=$peerId", it) }
                            .getOrDefault(false)
                    } else {
                        FlashLog.w(TAG_WS, "PTT control dropped: no session for $peerId", null)
                        false
                    }
                },
                sendAudio = { peerId, bytes ->
                    val session = net.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
                    if (session == null) {
                        FlashLog.w(TAG_WS, "PTT audio dropped: no session for $peerId", null)
                    } else {
                        runCatching { session.connection.sendBinary(bytes) }
                            .onFailure { FlashLog.w(TAG_WS, "PTT audio send failed peer=$peerId", it) }
                    }
                },
                hasMicPermission = hasMicPermission,
                isCallActive = isCallActive,
                audioRateHz = audioRateHz,
                audio = pttAudio,
            )
            pttImpl = ptt
            scope.launch { ptt.notices.collect { FlashLog.i(TAG_WS, "PTT notice: $it") } }
            return ptt
        }
    }

    override fun attachPtt(engine: FlashPtt) {
        synchronized(this) {
            if (pttImpl == null) {
                pttImpl = engine
            }
        }
    }

    override fun detachPtt() {
        synchronized(this) {
            runCatching { pttImpl?.shutdown() }
            pttImpl = null
        }
    }

    /**
     * Registers a host-constructed [FlashCalling] as this engine's calling engine, **instead of** the
     * `CallCoordinator` the boot would build: [assemble] keeps an engine attached before it ran. The host owns what it
     * attached (its sender, trust gate and scope); the engine only routes inbound call text and signaling edges to it.
     *
     * Attaching the same instance again is a no-op.
     *
     * @throws IllegalStateException when a different calling engine is already attached (including the one the boot
     * built), or the engine is closed. Call [detachCalling] first.
     */
    override fun attachCalling(engine: FlashCalling) {
        synchronized(this) {
            check(!closed) { "DesktopEngine is closed; cannot attach a calling engine." }
            val current = callsImpl
            if (current === engine) return
            check(current == null) { "A calling engine is already attached; detachCalling() before attaching another." }
            callsImpl = engine
        }
    }

    /**
     * Detaches the calling engine and **ends its active call** (hang up, bounded to a few seconds), the same
     * teardown [stop] performs; it is then forgotten. Idempotent. A built-in `CallCoordinator` also keeps helper
     * jobs on [scope] that only [stop] / [close] cancel.
     */
    override fun detachCalling() {
        val detached = synchronized(this) {
            val held = callsImpl
            callsImpl = null
            held
        }
        endActiveCall(detached)
    }

    /** Hangs up a call in progress on [calls] so detaching or closing never leaves a live call or an open microphone. */
    private fun endActiveCall(calls: FlashCalling?) {
        if (calls == null) return
        runCatching {
            runBlocking {
                withTimeoutOrNull(CALL_END_TIMEOUT_MS) {
                    if (calls.activeCall.value?.let { it.state != FlashCallState.ENDED } == true) calls.hangUp()
                }
            }
        }.onFailure { FlashLog.w(TAG_WS, "Ending the active call on detach failed: ${it.message}") }
    }

    override suspend fun onInboundCallText(peerDeviceId: String, text: String): Boolean =
        callsImpl?.onInboundText(peerDeviceId, text) ?: false

    override fun onCallSignalingLost(peerDeviceId: String) {
        callsImpl?.onSignalingLost(peerDeviceId)
    }

    override fun onCallSignalingRestored(peerDeviceId: String) {
        callsImpl?.onSignalingRestored(peerDeviceId)
    }

    override fun busyCallPeerIds(): Set<String> =
        callsImpl?.activeCall?.value?.busyPeerIds.orEmpty()

    /**
     * Renames the device without blocking the caller (this is a non-suspend `FlashEngine` member, usually called on
     * the UI thread). The store write (a tiny properties file), the observable name, the transport hello and the
     * pairing name are applied before it returns; the chat repository's copy and the re-advertisement run on [scope]
     * afterwards. Use the suspend [renameLocalDevice] to await all of it.
     */
    override fun updateFriendlyName(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || closed || !applyLocalName(trimmed)) return false
        scope.launch { propagateLocalName(trimmed) }
        return true
    }

    /** Closes the engine: same as [stop], and idempotent. A closed engine cannot be started again. */
    override fun close() {
        shutdown()
    }

    public val localDeviceId: String get() = identity.deviceId.value
    public val localFriendlyName: String get() = _localFriendlyName.value

    /**
     * Forces an immediate rediscovery and reconnect sweep across discovered endpoints, matching
     * the Android host's `AppEngine.reconnectNow()`.
     *
     * @return false when the engine has not booted yet.
     */
    public fun reconnectNow(): Boolean {
        networkImpl ?: return false
        val disc = discoveryImpl ?: return false
        scope.launch {
            FlashLog.i(TAG_DISCOVERY, "Manual retry: restarting discovery and triggering redials")
            runCatching { disc.restartDiscovery() }
                .onFailure { FlashLog.w(TAG_DISCOVERY, "Discovery restart failed", it) }
            rememberedRoutes?.resetBackoff()
            autoConnector?.sweepNow()
            presenceExchange?.refresh()
            modeController?.refresh()
        }
        return true
    }

    /**
     * This device's advertisement, rebuilt from the CURRENT identity.
     *
     * Was built inline at boot from a captured `friendlyName`, so a rename could update the store and
     * still advertise the old name. Rebuilding on demand is what makes
     * [renameLocalDevice] reach peers at all.
     *
     * `protocolVersion` was also a literal `2` here while `FlashProtocol.VERSION` is the constant the
     * handshake actually enforces — the same drift the Settings About card had.
     */
    private fun buildAdvertisedIdentity(): FlashAdvertisedIdentity = FlashAdvertisedIdentity(
        deviceId = identity.deviceId,
        friendlyName = effectiveFriendlyName(),
        deviceModel = "Desktop",
        protocolVersion = FlashProtocol.VERSION,
        // Declares this endpoint's kind so the other side's Nearby row can say "PC" rather than
        // guessing from the model string. Rides the existing `caps` field the cross-radio TXT
        // contract already defines, so no wire key is added and an older peer simply shows no
        // badge. See FlashDeviceKind.
        capabilities = setOf(FlashDeviceKind.CAP_DESKTOP),
    )

    /**
     * Renames this device for every peer that discovers it.
     *
     * `DesktopIdentityStore.updateFriendlyName` has always existed, persisted correctly, and had no
     * caller — the engine's `identityStore` is private and the Settings Identity row was wired to
     * nothing, so a desktop install could never be called anything but "Flash Desktop".
     *
     * The caller must nudge discovery to re-advertise: the name rides the mDNS/multicast TXT record,
     * so peers keep the old one until this device announces again.
     */
    public suspend fun renameLocalDevice(name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        if (!applyLocalName(trimmed)) return false
        propagateLocalName(trimmed)
        return true
    }

    /**
     * The synchronous half of a rename: persists [trimmed], ends the config override, and updates every in-memory
     * copy that does not need a suspend call (the observable name, the WebSocket hello, pairing requests).
     */
    private fun applyLocalName(trimmed: String): Boolean {
        val renamed = runCatching { identityStore.updateFriendlyName(trimmed) }.getOrNull()
        if (renamed !is FlashResult.Success) return false

        // The owner's explicit choice wins over a config-supplied display name from now on.
        nameOverride = null

        // Every place that holds a copy of the name. Before, only the store changed and discovery was nudged,
        // so the UI, the WebSocket hello, pairing requests and group messages all kept the old name.
        _localFriendlyName.value = trimmed
        networkImpl?.localFriendlyName = trimmed
        pairing.updateLocalName(trimmed)
        return true
    }

    /** The asynchronous half of a rename: the chat repository's copy, then the discovery re-advertisement. */
    private suspend fun propagateLocalName(trimmed: String) {
        runCatching { chatImpl?.updateLocalDisplayName(trimmed) }
            .onFailure { FlashLog.w(TAG_DISCOVERY, "Chat display name update failed after rename", it) }

        // Re-advertise, or peers keep the old name: the name rides the discovery TXT record, which
        // was published at boot. `updateIdentity` swaps what `startAdvertising` will publish, and
        // `advertisedPort` is the port bound at boot, so no session is disturbed.
        //
        // KNOWN LIMIT: a peer that stays connected keeps the previous name in its live session until it
        // reconnects (the hello name is sent once per connection). The Nearby/chat rows read discovery, so
        // they update immediately.
        runCatching {
            discoveryImpl?.let { discovery ->
                discovery.updateIdentity(buildAdvertisedIdentity())
                if (advertisedPort > 0) discovery.startAdvertising(advertisedPort)
            }
        }
    }

    public val appVersionName: String = "1.0.0-desktop"

    /**
     * The wire protocol label shown in Settings → About.
     *
     * Derived from [FlashProtocol.VERSION] rather than written out, because the hand-written
     * alternative was wrong: `FlashSettingsModel`'s default reads "FLASH_XFER/1" while the engine
     * actually advertises version 2 (`FlashProtocol.VERSION`, used by the handshake both sides
     * enforce with an exact match). "FLASH_XFER/1" appears nowhere else in the repo and is not a wire
     * token — "FLASH_XFER" is the frame prefix and carries no number.
     *
     * (The same wrong default is what Android's About card shows. That is a shared-model issue and is
     * left alone here rather than changed under an Android build nobody has run.)
     */
    public val protocolVersionLabel: String get() = "FLASH_XFER/${FlashProtocol.VERSION}"

    // --- Receive-side state, mirroring Flash.kt's Wiring ---
    private var _canonicalRoot: File = (receivedRoot ?: File(settingsStore.loadSettings().saveLocation)).canonicalFile.apply { mkdirs() }
    public val canonicalRoot: File get() = _canonicalRoot

    /**
     * The canonical directory received files land in — the one the receive pipeline's containment
     * check writes against.
     *
     * Exposed because the Settings tab's storage card needs it, and because the alternative was
     * already wrong: `DesktopHelpers.clearReceivedFiles` re-derived `~/FlashReceived` from the user
     * home instead of asking the engine, so a `DesktopEngine(receivedRoot = …)` — which is exactly
     * what `DesktopEngineBootTest` and `DesktopEngineAutoDialTest` construct — reported and cleared
     * the WRONG directory. Canonical, so it matches what the pipeline compares against rather than
     * a symlink-resolved twin of it.
     */
    public val receivedDirectory: File get() = _canonicalRoot

    /**
     * Passphrase for the chat database. Random hex generated once and kept in
     * `<stateDir>/chat/db-key.bin`, inheriting whatever OS protections `stateDir` has —
     * the same trust model as the identity and trust stores beside it.
     *
     * Per-device by design (D5 = C: no SQLCipher parity): losing this file means the next
     * boot mints a fresh key and the old history fails loudly (wrong-key reads are an
     * error, never an empty chat list — proven by `JdbcCipherSQLiteDriverTest`), rather
     * than silently decrypting.
     */
    private fun chatDbKey(): String {
        val dir = File(stateDir, "chat").apply { mkdirs() }
        val keyFile = File(dir, "db-key.bin")
        val saved = runCatching { keyFile.takeIf { it.isFile }?.readText()?.trim() }
            .getOrNull().orEmpty()
        if (saved.isNotEmpty()) return saved
        val fresh = ByteArray(32)
            .also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        runCatching { keyFile.writeText(fresh) }
        return fresh
    }

    private val openHandles = ConcurrentHashMap<String, RandomAccessSinkHandle>()
    private val incomingMeta = ConcurrentHashMap<String, ChunkFrame.FileStart>()

    /** transferId to the peer that offered it (FlashInboundRouter binds transfer control and chunks to it). */
    private val incomingOwners = ConcurrentHashMap<String, String>()
    private val receivedPaths = ConcurrentHashMap<String, String>()
    private val rejectedLogCache = ConcurrentHashMap.newKeySet<String>()
    private val rejectedCancelSent = ConcurrentHashMap.newKeySet<String>()

    private var sessionJobs = ConcurrentHashMap<WsSession, Job>()

    /**
     * The shared auto-connector (PC2, ADR-045), non-null once [assemble] has armed it. It replaced
     * `dialIfNeeded` and its `dialing` set, and with them two desktop-only gaps: no suppression
     * window (an unreachable peer was redialed every 5 s) and a registry-presence session check
     * where the phones had used the freshness check since ERROR-031.
     */
    @Volatile
    private var autoConnector: AutoConnector? = null

    /** Presence sharing (PC4, ADR-046); the text router hands it every FLASH_PRES frame. */
    @Volatile
    private var presenceExchange: PresenceExchange? = null

    /** DR1 (ADR-047): the addresses paired peers were last authenticated at; null before boot. */
    private var rememberedRoutes: RememberedRoutes? = null

    /** Connection mode (PC5, ADR-048); the text router hands it every FLASH_LINK frame. */
    @Volatile
    private var modeController: ConnectionModeController? = null

    /** DR3 (ADR-047): the subnet sweep behind Nearby's "Scan network" and the automatic fallback. */
    @Volatile
    private var sweepController: SweepController? = null

    private val _sweepState = MutableStateFlow<SweepState>(SweepState.Idle)

    /** The sweep's progress and outcome, for the Nearby screen. Idle until the stack has booted. */
    public val sweepState: StateFlow<SweepState> = _sweepState.asStateFlow()

    /**
     * The user's "Scan network". False when nothing started: a scan is already running, the previous
     * one ended a moment ago, or the engine is not up. [sweepState] says which.
     */
    public fun scanNetwork(): Boolean = sweepController?.scanNow() ?: false

    private val _nearbyVisible = MutableStateFlow(false)

    /**
     * The Nearby screen is (not) on screen. ECO dials unpaired peers only while it is, so they can
     * be paired (PC5, plan §3.4). The shell calls this from the screen's composition.
     */
    public fun setNearbyVisible(visible: Boolean) {
        _nearbyVisible.value = visible
    }

    /** The connection policy for the current mode and performance tier; read per use. */
    private fun connectionPolicy(): ConnectionModePolicy =
        ConnectionModePolicy.of(_discoveryMode.value, _settings.value.performanceMode ?: FlashPerformanceMode.HIGH)

    private var started = false
    private val startMutex = Any()

    /** A no-arg view for the shell when nothing is booted. */
    private val fallbackTransfers = MutableStateFlow(emptyList<com.transfer.flash.core.transfer.model.FlashTransfer>())

    /** The boot coroutine, for tests that must wait for the boot's tail (including its teardown after a close). */
    @Volatile
    internal var bootJob: Job? = null
        private set

    /** Test seam: called with each bring-up stage's name on the boot thread. Production never sets it. */
    @Volatile
    internal var bootStageHook: ((stage: String) -> Unit)? = null

    /** Throws a CancellationException once the engine is closed, to abort [assemble]. */
    private fun ensureOpen() {
        if (closed) throw kotlin.coroutines.cancellation.CancellationException("DesktopEngine closed during bring-up")
    }

    /**
     * Assembles and boots the desktop stack. Idempotent while the engine is running; failures land in [startError].
     *
     * @throws IllegalStateException after [close] / [stop]: a closed engine cannot be restarted (its scope is
     * cancelled and its database closed), so starting it again is a programming error. [startError] carries the same
     * exception. Build a new engine (on the same state directory if you like) instead.
     */
    public fun start() {
        synchronized(startMutex) {
            if (closed) {
                val failure = IllegalStateException(
                    "DesktopEngine was closed and cannot be started again; create a new engine.",
                )
                _startError.value = failure
                FlashLog.e(TAG_WS, "start() called on a closed engine", failure)
                throw failure
            }
            if (started) return
            started = true
        }
        // PROBE session.header: what a log reader needs to know about this process (docs/testing/PROBES.md).
        com.transfer.flash.core.common.logging.FlashProbe.emit(
            "session.header",
            "app" to "desktop",
            "os" to System.getProperty("os.name"),
            "java" to System.getProperty("java.version"),
            "local" to com.transfer.flash.core.common.logging.FlashProbe.short(localDeviceId),
            "tzOffsetMin" to (java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000),
        )
        bootJob = scope.launch {
            val result = runCatching { assemble() }
            // `assemble()` is blocking code (`runBlocking { network.start(0) }` ...), so `scope.cancel()` in [shutdown]
            // cannot interrupt it. `closed` is therefore the authority: a boot that finishes on a closed engine never
            // flips [ready], and releases what it built after the shutdown's own release had already run.
            val live = synchronized(startMutex) {
                if (closed) {
                    false
                } else {
                    result.onSuccess {
                        _startError.value = null
                        _ready.value = true
                        networkWatcher.start()
                    }
                    true
                }
            }
            if (!live) {
                FlashLog.i(TAG_WS, "bring-up ended on a closed engine; releasing what it started")
                releaseSubsystems()
                return@launch
            }
            result.onFailure { failure ->
                // Logged, not just parked in a StateFlow. The shell renders `startError` in the
                // transfers list's error surface, but a bring-up failure that aborts `assemble()`
                // is exactly the case where the console is what a human reads — and the run that
                // motivated this line showed a working roster with no sessions and NO trace of
                // why, because the throwable only ever reached the UI.
                FlashLog.e(TAG_WS, "desktop stack bring-up FAILED", failure)
                _startError.value = failure
            }
        }
    }

    /**
     * Stops the engine for good: identical to [close]. The engine cannot be started again afterwards (its scope is
     * cancelled), but a new [DesktopEngine] can be built on the same state directory straight away.
     */
    public fun stop() {
        shutdown()
    }

    /**
     * The one teardown behind [stop] and [close]. Idempotent, safe to call before [start], during the boot and from
     * any thread.
     */
    private fun shutdown() {
        val first = synchronized(startMutex) {
            if (closed) {
                false
            } else {
                closed = true
                started = false
                true
            }
        }
        if (!first) return
        // Logged first, and unconditionally: `stop()` cancels the scope, so anything still in
        // flight — including a bring-up that has not finished — dies here silently. A premature
        // call is indistinguishable from a stalled engine in every other observable (the transports
        // keep running, the roster still fills), and that ambiguity cost a session: the desktop's
        // `application { }` body called this straight after composing the window. `ready` is the
        // tell — it is set true by a completed assembly and false here.
        val wasReady = _ready.value
        FlashLog.i(TAG_WS, "engine stop() — cancelling scope (ready=$wasReady)")
        _ready.value = false
        // An engine closed before it finished booting never becomes ready; say why for anyone awaiting it.
        if (!wasReady && _startError.value == null) {
            _startError.value = IllegalStateException("DesktopEngine was closed before it finished starting.")
        }
        releaseSubsystems()
        liveStateDirs.entries.removeIf { it.value === this }
    }

    /**
     * Stops and releases everything the engine owns, in dependency order, and puts the facade back to its empty
     * stand-ins. Idempotent and serialised: [shutdown] runs it, and a boot that was still in flight runs it again once
     * it notices the engine is closed, so whatever it installed after the first pass is not leaked (a bound port, the
     * discovery sockets, the open encrypted database).
     */
    private fun releaseSubsystems() {
        synchronized(teardownLock) {
            runCatching { networkWatcher.stop() }
            // A live PTT session holds the microphone; shutdown() releases it (and resets the flows).
            detachPtt()
            detachSwarm()
            // An active call is hung up (it holds the camera/microphone); the coordinator is then forgotten.
            detachCalling()
            val discovery = discoveryImpl ?: builtDiscovery
            val network = networkImpl ?: builtNetwork
            runCatching {
                runBlocking {
                    // Separate bounds: a discovery transport that hangs in close must not keep the port bound.
                    withTimeoutOrNull(SUBSYSTEM_STOP_TIMEOUT_MS) { discovery?.stopAll() }
                    withTimeoutOrNull(SUBSYSTEM_STOP_TIMEOUT_MS) { network?.stop() }
                }
            }.onFailure { FlashLog.w(TAG_WS, "Stopping discovery/network on shutdown failed: ${it.message}") }
            scope.cancel()
            // Receive sinks still open for a parked or half-received file.
            openHandles.values.forEach { handle -> runCatching { handle.close() } }
            openHandles.clear()
            incomingMeta.clear()
            receivedPaths.clear()
            // The database closes after the scope is cancelled, so no coroutine of this engine writes to it any more.
            val db = chatDb
            chatImpl = null
            chatDb = null
            runCatching { db?.close() }
                .onFailure { FlashLog.w(TAG_WS, "Closing the chat database failed: ${it.message}") }
            discoveryImpl = null
            networkImpl = null
            transferImpl = null
            receivePipeline = null
            autoConnector = null
            presenceExchange = null
            modeController = null
            sweepController = null
            rememberedRoutes = null
        }
    }

    // -------------------------------------------------------------------------
    // Composition — the Phase 16 harness wiring, verbatim in shape.
    // -------------------------------------------------------------------------
    private fun assemble() {
        val localId = identity.deviceId.value
        val friendlyName = effectiveFriendlyName()

        // Bring-up breadcrumbs, with elapsed time.
        //
        // There is a stretch of this method that can be doing anything — from the WS bind through
        // `discovery.startAll` to the collectors — during which the ONLY output is whatever the
        // transports print themselves. That gap is why a run can show `Found Flash V760` (a
        // transport's socket thread, alive) next to a roster that never prints, a dial that never
        // fires, and a phone that cannot see this desktop: those three together are a STALL, and
        // the log could not say where. Each stage below prints as it completes, so the last line
        // before the silence names the step that never returned.
        val bootStartedAt = System.currentTimeMillis()
        // Every stage is also a close checkpoint: a stop()/close() that lands mid-boot cannot interrupt this blocking
        // method, so it aborts here (the boot coroutine's tail then releases whatever was already built).
        fun boot(stage: String) {
            FlashLog.i(TAG_WS, "[bring-up] $stage (+${System.currentTimeMillis() - bootStartedAt}ms)")
            bootStageHook?.invoke(stage)
            ensureOpen()
        }
        boot("assemble entered")

        // Audit S3: TLS is mandatory. A failure lands in [startError] (the shell shows it with a
        // retry); there is no plaintext fallback.
        val tlsOptions = requireTransportSecurity(
            onAttemptFailed = { attempt, error -> FlashLog.w(TAG_WS, "TLS setup attempt $attempt failed: ${error.message}") },
        ) {
            val keyPair = (crypto as? PersistedFlashCrypto)?.javaKeyPair() ?: FlashCertMaker.newEcKeyPair()
            val km = FlashCertMaker.createKeyManagers(keyPair, cn = "CN=$localId")
            val pinVerifier = TofuPinVerifier(
                lookupPin = { peerId -> trustStore.getPin(FlashDeviceId(peerId)) },
                recordPin = { peerId, pin -> trustStore.savePin(FlashDeviceId(peerId), pin) },
                // ERROR-077: never accept or pin this PC's own key under a peer's id.
                ownFingerprintHex = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(keyPair.public.encoded).joinToString("") { "%02X".format(it) },
            )
            TlsOptions(
                pinVerifier = pinVerifier,
                keyManagers = km,
            )
        }

        val network = JvmWsFlashNetwork(
            localDeviceId = localId,
            localFriendlyName = friendlyName,
            tlsOptions = tlsOptions,
            // PC5 (ADR-048): the tier's pacing, adjusted by the connection mode (ECO / BOOST).
            transportProfile = { connectionPolicy().transport },
            localFeatures = { if (swarmBinding != null) setOf("sw1", "gs1", FEATURE_VIDEO_UPGRADE) else setOf("gs1", FEATURE_VIDEO_UPGRADE) },
        )
        networkImpl = network

        val discovery = CompositeDiscovery(
            transports = listOf(
                JmdnsTransport(
                    directory = StandardEndpointDirectory(),
                    sweep = { _ -> emptyList() },
                    // DR5: a Hyper-V, VPN or VM adapter would advertise an address peers cannot reach.
                    includeVirtual = { _settings.value.includeVirtualAdapters },
                ),
                // ADDITIVE second LAN transport (UDP multicast with self-announcement); JmDNS is
                // kept, not replaced. It is the transport that fixes the desktop's side of the
                // measured failure — the hollow-TXT resolve that reported the phone's address with
                // no device id, so the desktop could never build an endpoint for it and never
                // dialed — and it is the one that expires a peer which was killed without a goodbye
                // instead of leaving it visible for up to a resolver-cache TTL.
                MulticastTransport(
                    socketFactory = JvmMulticastSocketFactory(includeVirtual = { _settings.value.includeVirtualAdapters }),
                    directory = StandardEndpointDirectory(),
                ),
            ),
            // DR5: one line saying which source sees which peer, so a field report names the path that failed.
            sourceLog = { FlashLog.i(TAG_WS, it) },
        )
        discoveryImpl = discovery

        val transfer = RealFlashTransferRepository(
            streamChannelFactory = { channelId, peerDeviceId -> sessionChannel(channelId, peerDeviceId) },
            fileSourceOpener = FileSourceOpener { uriString ->
                val file = runCatching {
                    if (uriString.startsWith("file:", ignoreCase = true)) {
                        java.io.File(java.net.URI(uriString))
                    } else {
                        java.io.File(uriString)
                    }
                }.getOrElse {
                    java.io.File(uriString)
                }
                FileSystem.SYSTEM.source(file.absolutePath.toPath())
            },
            store = null, // D5 = C pending (09B-2) — matches the Phase 16 harness.
            repositoryScope = scope,
            requireReceiverAcceptance = true,
            isPeerEncrypted = { peerId -> trustStore.getSessionKey(FlashDeviceId(peerId)) != null },
            performanceMode = { _settings.value.performanceMode ?: FlashPerformanceMode.HIGH },
            freeSpaceBytes = { canonicalRoot.takeIf { it.exists() }?.usableSpace },
        )
        transferImpl = transfer

        // ---- presence sharing (PC4, ADR-046): the same wiring as both Android hosts ----
        // Built before the chat repository, whose Online set it feeds. Group rosters come from
        // that repository once it exists (none before, or if the database failed to open).
        val presence = PresenceExchange(
            scope = scope,
            localDeviceId = localId,
            snapshot = {
                PresenceLocalView.of(
                    ghost = discovery.discoveryMode.value == FlashDiscoveryMode.GHOST,
                    sessions = network.activeSessions.value.entries.associate { (id, s) -> id.value to s },
                    isLive = network::hasLiveSession,
                    sightings = discovery.discoveredEndpoints.value.map {
                        PresenceLocalView.Sighting(it.deviceId.value, it.hostAddress, it.port)
                    },
                    trusted = trustStore.getTrustedPeers().keys.mapTo(HashSet()) { it.value },
                    hasPin = { id -> trustStore.getPin(FlashDeviceId(id)) != null },
                    rosters = chatImpl?.activeGroupRosters().orEmpty(),
                )
            },
            send = { peerId, text ->
                val session = network.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
                if (session == null) {
                    false
                } else {
                    session.connection.sendTextAsync(text)
                    true
                }
            },
            sha256 = FlashFingerprint::fingerprint,
            randomSalt = SecureRandom().let { random -> { ByteArray(PresenceCodec.SALT_BYTES).also(random::nextBytes) } },
            config = { connectionPolicy().presence },
            log = { FlashLog.i(TAG_WS, it) },
        )
        presenceExchange = presence

        // ---- connection mode (PC5, ADR-048): the same wiring as both Android hosts ----
        val modes = ConnectionModeController(
            scope = scope,
            localDeviceId = localId,
            policy = ::connectionPolicy,
            snapshot = {
                val sessions = network.activeSessions.value.keys.mapTo(HashSet()) { it.value }
                LinkView(
                    available = discovery.discoveredEndpoints.value.mapTo(HashSet()) { it.deviceId.value } +
                        presence.reachablePeerIds.value + sessions,
                    contacts = trustStore.getTrustedPeers().keys.mapTo(HashSet()) { it.value } +
                        chatImpl?.activeGroupRosters().orEmpty().flatten(),
                    activity = network.linkActivity(),
                    busy = callsImpl?.activeCall?.value?.busyPeerIds.orEmpty(),
                    nearbyOpen = _nearbyVisible.value,
                )
            },
            send = { peerId, text ->
                val session = network.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
                session?.connection?.sendTextAsync(text)
                session != null
            },
            release = network::releaseSession,
            close = { peerId -> network.disconnect(FlashDeviceId(peerId)) },
            onPolicyChanged = {
                network.retimeConnections()
                presence.refresh()
            },
            log = { FlashLog.i(TAG_WS, it) },
        )
        modeController = modes

        // ---- durable chat (slice 4): the same repository the phone runs ----
        // Built after the transfer repository because the attachment-progress join reads
        // it, and before the WS server binds because inbound frames can arrive as soon as
        // sessions do. A database failure must not abort boot (chat degrades to the honest
        // empty repository; transfers/discovery/pairing are unaffected).
        runCatching {
            val db = openEncryptedFlashDatabase(File(File(stateDir, "chat"), FlashDatabase.DATABASE_NAME), chatDbKey())
            chatDb = db
            chatImpl = RealFlashChatRepository(
                localDeviceId = localId,
                localDisplayName = friendlyName,
                conversationDao = db.conversationDao(),
                messageDao = db.messageDao(),
                outboxDao = db.outboxDao(),
                receiptDao = db.receiptDao(),
                draftDao = db.draftDao(),
                recentSearchDao = db.recentSearchDao(),
                reactionDao = db.reactionDao(),
                groupMemberDao = db.groupMemberDao(),
                groupDeliveryDao = db.groupDeliveryDao(),
                messagePinDao = db.messagePinDao(),
                readCursorDao = db.readCursorDao(),
                runInTransaction = { block -> db.runInWriteTransaction(block) },
                // ADR-044 V1: signed groups. The port signs with the identity key the TLS certificate presents; the
                // pin and the session's level and key are what an owner checks before certifying an invitee.
                groupCrypto = com.transfer.flash.core.engine.group.FlashGroupCrypto(crypto),
                // ADR-044 V2: an owner-signed certificate becomes a pin here, so a member never paired with this
                // device still connects and is trusted inside that group (and only there).
                groupVouching = com.transfer.flash.core.engine.group.TrustStoreGroupVouching(trustStore),
                pinnedFingerprint = { peerId -> trustStore.getPin(FlashDeviceId(peerId)) },
                peerGroupProtocol = { peerId -> network.activeSessions.value[FlashDeviceId(peerId)]?.peer?.groupProtocol ?: 1 },
                peerIdentityKey = { peerId ->
                    network.activeSessions.value[FlashDeviceId(peerId)]?.peer?.identityKey
                        ?.let { com.transfer.flash.core.common.protocol.Base64.decode(it) }
                },
                isTrustedPeer = { peerId -> trustStore.isTrusted(FlashDeviceId(peerId)) },
                isChannelEncrypted = { peerId -> trustStore.getSessionKey(FlashDeviceId(peerId)) != null },
                onlinePeerIds = network.activeSessions.map { sessions ->
                    sessions.keys.mapTo(HashSet()) { it.value }
                },
                // PC3 (UI-030b): seen by discovery without a session → "Online" with a ring dot.
                // PC4: or reported by a mutual peer.
                reachablePeerIds = combine(discovery.discoveredEndpoints, presence.reachablePeerIds) { endpoints, reported ->
                    endpoints.mapTo(HashSet()) { it.deviceId.value } + reported
                },
                peerNameResolver = { id ->
                    trustStore.getTrustedPeers()[FlashDeviceId(id)]
                        ?: discovery.discoveredEndpoints.value.firstOrNull { it.deviceId.value == id }?.friendlyName
                },
                attachmentProgress = transfer.activeTransfers.map { transfers ->
                    transfers.associate { t ->
                        t.id.value to FlashAttachmentProgress(
                            progress = if (t.bytesTotal > 0L) {
                                (t.bytesDone.toFloat() / t.bytesTotal.toFloat()).coerceIn(0f, 1f)
                            } else {
                                0f
                            },
                            status = when (t.state) {
                                FlashTransferState.Completed,
                                FlashTransferState.Verifying ->
                                    FlashFileTransferStatus.Downloaded
                                FlashTransferState.Failed,
                                FlashTransferState.Cancelled ->
                                    FlashFileTransferStatus.Failed
                                FlashTransferState.Offered ->
                                    FlashFileTransferStatus.AwaitingAcceptance
                                FlashTransferState.Paused ->
                                    FlashFileTransferStatus.Paused
                                else ->
                                    FlashFileTransferStatus.Transferring
                            },
                            localPath = t.localPath ?: t.sourceUri,
                            speedMbps = t.speedBytesPerSec / 1_000_000f,
                            etaSeconds = t.etaSeconds.toInt(),
                            waitReason = t.waitReason?.name,
                            canGoOffline = t.canGoOffline,
                            holdersOnline = t.holdersOnline,
                            errorMessage = t.errorMessage,
                            bytesDone = t.bytesDone,
                            bytesTotal = t.bytesTotal,
                            pieceBlocks = t.pieceBlocks,
                            recipients = t.recipients.map {
                                com.transfer.flash.core.messaging.model.FlashRecipientProgress(
                                    peerId = it.peerId,
                                    progress = if (it.bytesTotal > 0L) (it.bytesHeld.toFloat() / it.bytesTotal.toFloat()).coerceIn(0f, 1f) else 0f,
                                    hasAll = it.hasAll,
                                    online = it.online,
                                    bytesPerSec = it.rateBytesPerSec,
                                )
                            },
                        )
                    }
                },
                transportSink = { targetDeviceId, wireFrame ->
                    // PC3 dial on demand: a message to a peer that is seen but not connected dials
                    // first (≤ 1 s). Receipts and typing never wait.
                    if (wireFrame is MessageWireFrame.TextMessage) {
                        autoConnector?.ensureSession(targetDeviceId)
                    }
                    sendChatFrame(network, targetDeviceId, wireFrame)
                },
                groupTransportSink = { targetDeviceId, wireFrame ->
                    val session = network.activeSessions.value[FlashDeviceId(targetDeviceId)] as? WsSession
                    if (session == null) {
                        FlashLog.w(TAG_WS, "Group frame dropped: no active session for $targetDeviceId")
                        false
                    } else {
                        session.connection.sendTextAsync(GroupFrameCodec.encode(wireFrame))
                        true
                    }
                },
                peerFeatures = { peerId -> network.activeSessions.value[FlashDeviceId(peerId)]?.peer?.features.orEmpty() },
                groupInviteDao = db.groupInviteDao(),
                groupJoinRequestDao = db.groupJoinRequestDao(),
                groupSecretStore = com.transfer.flash.core.engine.group.RoomGroupSecretStore(db.groupSecretDao()),
                groupRotationDao = db.groupRotationDao(),
                groupSettingsDao = db.groupSettingsDao(),
                groupHistoryDao = db.groupHistoryDao(),
                groupPreferencesDao = db.groupPreferencesDao(),
                localAddressHints = {
                    val port = network.serverPort
                    if (port > 0) {
                        com.transfer.flash.core.network.sweep.JvmLocalSubnets.lanSubnets()
                            .map { "${it.address}:$port" }
                    } else {
                        emptyList()
                    }
                },
                onConnectPeerWithHints = { peerId, hints ->
                    for (hint in hints) {
                        if (network.hasLiveSession(peerId)) break
                        val parts = hint.split(":")
                        val host = parts[0]
                        val port = parts.getOrNull(1)?.toIntOrNull() ?: 4433
                        val res = network.connectManual(host, port, peerId)
                        if (res is com.transfer.flash.core.common.result.FlashResult.Success) break
                    }
                },
                onInboundTextMessage = { conversationId, senderName, text ->
                    FlashLog.i(TAG_WS, "Inbound chat in $conversationId from ${senderName ?: "?"}: ${text.take(120)}")
                    onInboundMessageNotification?.invoke(conversationId, senderName, text, null)
                },
                onInboundTextMessageWithGroupTitle = { conversationId, senderName, text, groupTitle ->
                    FlashLog.i(TAG_WS, "Inbound chat in $conversationId from ${senderName ?: "?"} (group $groupTitle): ${text.take(120)}")
                    onInboundMessageNotification?.invoke(conversationId, senderName, text, groupTitle)
                },
                onInboundAttachment = { conversationId, senderName, fileName, mimeType ->
                    FlashLog.i(TAG_WS, "Inbound attachment in $conversationId from ${senderName ?: "?"}: $fileName ($mimeType)")
                    onInboundAttachmentNotification?.invoke(conversationId, senderName, fileName, mimeType, null)
                },
                onInboundAttachmentWithGroupTitle = { conversationId, senderName, fileName, mimeType, groupTitle ->
                    FlashLog.i(TAG_WS, "Inbound attachment in $conversationId from ${senderName ?: "?"} (group $groupTitle): $fileName ($mimeType)")
                    onInboundAttachmentNotification?.invoke(conversationId, senderName, fileName, mimeType, groupTitle)
                },
                scope = scope,
            )
            chatImpl?.onJoinRequestNotification = { groupId, groupTitle, requesterName ->
                FlashLog.i(TAG_WS, "Inbound join request in $groupId ($groupTitle) from $requesterName")
                onJoinRequestNotification?.invoke(groupId, groupTitle, requesterName)
            }
            chatImpl?.onGroupOfferRefused = { groupId, groupName, message ->
                FlashLog.i(TAG_WS, "Group offer refused: $groupId ($groupName)")
                onGroupOfferRefused?.invoke(groupId, groupName, message)
            }
            boot("chat repository opened (${File(File(stateDir, "chat"), FlashDatabase.DATABASE_NAME).absolutePath})")
        }.onFailure { e ->
            FlashLog.w(TAG_WS, "Chat database unavailable; chats will be empty this run: ${e.message}")
            chatImpl = null
        }

        // ---- voice/video calling, 33a: audio-only, outgoing only ----
        //
        // 33-2 verdict (recorded, not deferred): there is NO desktop counterpart to
        // `FlashWebRtcEngine`. That object exists for two Android-only reasons — installing a
        // low-latency ADM before libwebrtc's lazy factory init, and probing OEM-HAL capture
        // breakage. webrtc-java has no JavaAudioDeviceModule class (its own ADM instead) and
        // the HAL failure mode is an Android audio-stack bug. `DesktopMediaStackSmokeTest`
        // already constructs a working PeerConnection with zero configuration, so the honest
        // outcome is no engine object. If desktop capture misbehaves live, suspect the
        // `preferIPv4Stack` flag's effect on ICE first (phase doc Do-NOT), not a missing shim.
        // A calling engine the host attached before the boot is kept (never overwritten, never orphaned): the host owns it.
        if (callsImpl != null) {
            FlashLog.i(TAG_WS, "Keeping the host-attached calling engine; not building a CallCoordinator")
        } else callsImpl = CallCoordinator(
            localDeviceId = localId,
            localName = friendlyName,
            scope = scope,
            // Group Phase 0 trust closure, like the app host: only paired peers can place or
            // receive calls; an inbound invite from a stranger is auto-declined, never rung.
            isTrustedPeer = { peerId -> trustStore.isTrusted(FlashDeviceId(peerId)) },
            // ADR-044 V2: a vouched member of a v2 group may join that group's calls without being paired.
            isGroupTrustedPeer = { peerId, groupId ->
                chatImpl?.isGroupCallPeer(groupId, peerId) ?: trustStore.isTrusted(FlashDeviceId(peerId))
            },
            // ERROR-088: who is in the call is the roster (paired or vouched); the live-key check above is applied when
            // an announcement is sent or received. A member that is not connected yet is dialed on demand.
            isGroupMember = { peerId, groupId ->
                chatImpl?.isGroupCallMember(groupId, peerId) ?: trustStore.isTrusted(FlashDeviceId(peerId))
            },
            // A member this device is not paired with is labelled with the name its group roster stores.
            groupRosterNames = { groupId -> chatImpl?.groupRosterNames(groupId).orEmpty() },
            reachPeer = { peerId -> autoConnector?.ensureSession(peerId, AutoConnector.CALL_DIAL_BUDGET_MS) ?: false },
            // ADR-078: "Turn on camera" is offered only to a peer whose HELLO advertised cv1.
            peerFeatures = { peerId -> network.activeSessions.value[FlashDeviceId(peerId)]?.peer?.features.orEmpty() },
            // Honest desktop settings: voice priority and performance mode read per call via
            // lambdas so settings changes take immediate effect.
            prioritiseVoice = { _settings.value.prioritiseVoiceQuality },
            smallerVideoForMany = { _settings.value.smallerVideoForMany },
            performanceMode = { _settings.value.performanceMode ?: FlashPerformanceMode.HIGH },
            peerNameResolver = { peerId ->
                trustStore.getTrustedPeers()[FlashDeviceId(peerId)]
                    ?: discovery.discoveredEndpoints.value.firstOrNull { it.deviceId.value == peerId }?.friendlyName
            },
            // G2: Ethernet or the Wi-Fi band, cached (netsh is a process spawn) and never blocking.
            networkBand = DesktopNetworkBand(scope).also { it.start() }::current,
            sendFrame = { frame, peerId -> sendCallFrame(network, peerId, frame) },
            // Every terminated call becomes a row in the peer's thread (UI-050), mirroring the
            // app host. Each side writes its own row — no wire frame involved. Fires on
            // whichever thread ended the call, so this only logs + launches into the repo.
            onCallLog = { entry ->
                FlashLog.i(
                    TAG_WS,
                    "Call log peer=${entry.peerId} video=${entry.video} " +
                        "reason=${entry.endReason} durationMs=${entry.durationMs}",
                )
                chatImpl?.recordCallEvent(
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
        boot("call coordinator built")

        // PTT (ADR-032, ADR-058): built via attachPtt over this host's transport. Every
        // transport access is a lazy lambda, so it does not matter that sessions come and go after this point.
        val ptt = attachPtt(
            hasMicPermission = { true },
            isCallActive = { callsImpl?.activeCall?.value?.let { it.state != FlashCallState.ENDED } == true },
            audioRateHz = {
                if ((_settings.value.performanceMode ?: FlashPerformanceMode.HIGH) == FlashPerformanceMode.LOW) 8_000 else 16_000
            },
        )
        // Mic exclusivity (ADR-032): a call becoming active tears any PTT session down; the press path refuses
        // while a call is active, so the floor can never fight the call for the microphone.
        scope.launch {
            callsImpl?.activeCall
                ?.map { call -> call != null && call.state != FlashCallState.ENDED }
                ?.distinctUntilChanged()
                ?.collect { active -> if (active) ptt?.onCallStarted() }
        }
        boot("ptt engine built")

        val pipeline = ReceivePipeline(
            sink = { _, _ -> error("legacy shared sink must not be invoked with sinkFactory set") },
            sinkFactory = { start ->
                val safeRelativePath = sanitizeRelativePath(start.fileName.ifBlank { "received.bin" })
                val safeId = sanitize(start.transferId)
                val destDir = File(canonicalRoot, safeId).canonicalFile
                val dest = File(destDir, safeRelativePath).canonicalFile
                // Same containment discipline as the production composition (Sentinel).
                require(dest.path.startsWith(destDir.path + File.separator)) {
                    "path traversal escape: ${start.fileName}"
                }
                dest.parentFile?.mkdirs()
                receivedPaths[start.transferId] = dest.absolutePath
                val handle = OkioRandomAccessSinkHandle(dest.absolutePath.toPath(), start.totalBytes)
                openHandles[start.transferId] = handle
                RandomAccessChunkSink(handle, start.chunkSize)
            },
            emitSessionStarted = true,
            requireAcceptance = true,
        )

        // ---- swarm integration (SW-8) ----
        if (_settings.value.groupSwarmEnabled && swarm == null) {
            attachSwarm()
        }

        // ---- bring-up: bind WS server, start discovery, route endpoints, auto-dial ----
        // No checkpoint between this hook and the bind on purpose: it is the window a close can slip into, and the
        // next boot() stage aborts the assembly (the boot tail then releases the late bind).
        bootStageHook?.invoke("before ws bind")
        val netStart = runBlocking { network.start(0) }
        val serverPort = (netStart as? FlashResult.Success)?.value
            ?: error("network server failed to start: ${(netStart as FlashResult.Failure).error}")
        check(serverPort > 0) { "network server bound no port" }

        advertisedPort = serverPort
        val identityFrame = buildAdvertisedIdentity()
        DiscoveryRouteBinder.observe(scope, discovery.discoveredEndpoints, network)
        boot("ws server bound port=$serverPort; entering discovery startAll")
        val startedAll = runBlocking {
            discovery.setMode(_discoveryMode.value)
            discovery.startAll(serverPort, identityFrame)
        }
        boot("discovery startAll returned (${if (startedAll is FlashResult.Success) "ok" else "partial"})")
        // A PARTIAL discovery failure must NOT abort the boot.
        //
        // `startAll` returns Failure if ANY transport failed at advertising OR browsing — while
        // the transports that DID start keep running (CompositeDiscovery.startAll's documented
        // contract). So a `require` here coupled every peer-facing mechanism BELOW this line to
        // the health of one radio the user never sees: the auto-dial sweep, the roster collector
        // and the whole session machinery. The measured signature was exact and cost an evening:
        // a peer visible in the roster (because multicast, the transport that actually reaches a
        // phone, was up) with `active sessions=[]`, no "Auto-connect dialing" line, no
        // "Discovered endpoints:" line, and "Couldn't reach …" on tap — because `assemble()`
        // threw here and nothing after line 307 ever ran.
        //
        // The Phase 16 harness never had this bug: `DesktopInteropHarness.start` DISCARDS the
        // startAll result and proceeds. Pairing works there and not here for exactly that reason.
        // This is the product catching up to the harness — with the failure reported instead of
        // swallowed, since which transport failed is the diagnostic, not a reason to stop.
        (startedAll as? FlashResult.Failure)?.let { failure ->
            FlashLog.w(
                TAG_WS,
                "discovery startAll reported a partial failure; continuing with the transports " +
                    "that started — ${failure.error}",
            )
        }

        // Auto-dial every discovered peer so inbound offers and chat frames have a session to
        // ride. Two triggers, one AutoConnector (PC2, shared with both Android hosts):
        //
        //  1. **The discovery edge** — dial the moment a peer shows up. This is the fix for the
        //     measured user-visible failure, and it is worth stating exactly: Pair only works once a
        //     session exists, and with a POLL-only loop a peer that appears just after a tick had no
        //     session for up to one sweep interval (5 s) — while `beginPair` gives up waiting
        //     for the peer's hello after 3 s. So a user who taps Pair as soon as the row appears
        //     loses a race that starts before they can see it, and reads `Couldn't reach …`, which
        //     looks like a pairing bug. (2026-09-14: exactly this — the run showed the peer found,
        //     `active sessions=[]`, and no dial line at all, because the tap beat the first tick.)
        //  2. **The periodic sweep** — unchanged, and still needed: it is the retry path after a
        //     refused or timed-out dial, and the safety net if an edge is ever missed.
        //
        // Logged on both the attempt and the outcome, like the app host's sweep. It was silent
        // before, and that silence is why a live run could show "Couldn't reach …" with no way to
        // tell "we never dialed" from "we dialed and the peer refused": the dial's failure is
        // swallowed by `runCatching` and the endpoint is never reprinted.
        // DR1 (ADR-047): the addresses paired peers were last authenticated at, kept in the
        // encrypted DB so a network that filters multicast does not strand a peer after a restart.
        // A hint source for the planner only, like a presence tip; it never changes Online/Offline.
        // With no database this run they live in memory only.
        val remembered = RememberedRoutes(
            scope = scope,
            store = chatDb?.let { DesktopRememberedEndpointStore(it.rememberedEndpointDao()) },
            isPaired = { id -> trustStore.isTrusted(FlashDeviceId(id)) },
            wallClockMs = System::currentTimeMillis,
            hasLiveSession = network::hasLiveSession,
            log = { FlashLog.i(TAG_WS, it) },
        )
        rememberedRoutes = remembered
        network.routeObserver = remembered
        remembered.start()

        // DR3 (ADR-047): when discovery finds nothing on a network with paired peers, probe the local
        // /24 for the peer port and hand the answers to the planner as dial hints, exactly like a
        // gateway probe. Manual from Nearby, automatic only in STANDARD/BOOST outside a call.
        val sweep = SweepController(
            scope = scope,
            subnets = { JvmLocalSubnets.lanSubnets(includeVirtual = _settings.value.includeVirtualAdapters) },
            probe = TcpHostProbe(),
            port = WsTransferServer.PREFERRED_PORT,
            situation = {
                SweepSituation(
                    pairedPeers = trustStore.getTrustedPeers().size,
                    liveSessions = network.activeSessions.value.size,
                    discoveredPeers = discovery.discoveredEndpoints.value.size,
                    connectionAllowsAuto = connectionPolicy().strategy != ConnectionStrategy.ECO &&
                        callsImpl?.activeCall?.value?.let { it.state != FlashCallState.ENDED } != true,
                )
            },
            log = { FlashLog.i(TAG_WS, it) },
        )

        val connector = AutoConnector(
            scope = scope,
            planner = ConnectionPlanner(localDeviceId = identity.deviceId.value),
            links = object : ConnectionPlanner.Links {
                override fun hasLiveSession(deviceId: String) = network.hasLiveSession(deviceId)
                override fun isReconnectInFlight(deviceId: String) = network.isReconnectInFlight(deviceId)
                override fun hasSessionAtHost(host: String) = network.hasSessionAtHost(host)
            },
            sightings = {
                discovery.discoveredEndpoints.value.map { ep ->
                    ConnectionPlanner.Sighting(ep.deviceId.value, ep.hostAddress, ep.port, ep.friendlyName)
                } + presence.tipSightings() + remembered.sightings()
            },
            // A presence tip or a remembered route is dialed with its device named, so TLS checks
            // that device's pin during the handshake. A tip outranks a route: it is fresher.
            dial = { d ->
                val tip = presence.tipFor(d.peerDeviceId, d.host, d.port)
                val route = if (tip == null) remembered.routeFor(d.peerDeviceId, d.host, d.port) else null
                when {
                    tip != null -> network.connectManual(d.host, d.port, tip.deviceId)
                        .also { presence.onTipResult(tip, it is FlashResult.Success) }
                    route != null -> network.connectManual(d.host, d.port, route.deviceId)
                        .also { if (it !is FlashResult.Success) remembered.onDialFailed(route) }
                    else -> network.connectManual(d.host, d.port)
                }
            },
            // DR3: hosts that answered the subnet sweep; each is dialed once (no device named, like a
            // gateway probe, so the pin is bound after HELLO) and then forgotten.
            sweepHosts = sweep::hostsToDial,
            sweepHitDialed = sweep::hitDialed,
            // PC5: ECO dials only the peers it wants; null (STANDARD, BOOST) dials everyone.
            allowed = { modes.dialFilter.value },
            log = { FlashLog.i(TAG_WS, it) },
        )
        autoConnector = connector
        connector.start(
            edges = merge(discovery.discoveredEndpoints, presence.tips, modes.dialFilter, remembered.changes, sweep.hitsChanged),
        )
        sweepController = sweep
        sweep.start()
        scope.launch { sweep.state.collect { _sweepState.value = it } }
        presence.start(edges = merge(network.activeSessions, discovery.discoveredEndpoints, discovery.discoveryMode, _discoveryMode))
        // `_settings` wakes it on a performance-tier change too; the controller acts only when the
        // resulting policy actually differs.
        modes.start(edges = merge(_discoveryMode, _settings, network.activeSessions, discovery.discoveredEndpoints, _nearbyVisible))
        boot("dial triggers armed (discovery edge + ${AutoConnector.DEFAULT_SWEEP_INTERVAL_MS}ms sweep)")

        // The endpoint roster itself: which device id each discovered row carries, and at which
        // address. This is the other half of an id mismatch — a session registered under the peer's
        // WS-hello id can only be matched against what discovery advertised if both are visible.
        scope.launch {
            discovery.discoveredEndpoints.collect { endpoints ->
                FlashLog.i(
                    TAG_WS,
                    "Discovered endpoints: " + endpoints.joinToString { ep ->
                        "'${ep.friendlyName}' id=${ep.deviceId.value} at ${ep.hostAddress}:${ep.port}"
                    },
                )
            }
        }

        // ---- session collectors: binary → receive pipeline, text → transfer control ----
        receivePipeline = pipeline
        scope.launch {
            network.activeSessions.collect { sessions ->
                sessionJobs.keys.filterNot { it in sessions.values }.forEach { stale ->
                    // `?.cancel()` is LOAD-BEARING, not tidiness.
                    //
                    // Removing the map entry alone drops our reference to the Job but does not stop
                    // it: the two children below keep collecting `incomingBinary`/`incomingText` on a
                    // session that is already gone, and each one pins the whole session graph — the
                    // WsSession, its connection and its buffers — for the life of the process. The
                    // comment here has always claimed this was "symmetric with the app host's
                    // 'Cancelled collectors for stale session' line"; the app host cancels, and this
                    // did not. Measured 2026-09-14: the desktop app reached 2.93 GB of live heap and
                    // 5.19 GB committed while merely discovering peers, because every superseded
                    // session leaked two coroutines. Supersede events are ROUTINE — a connect-glare
                    // tiebreak, or a re-dial after a peer's address changes — so this accumulated
                    // steadily rather than only in a rare path.
                    sessionJobs.remove(stale)?.cancel()
                    FlashSessionCoordinator.onSessionDown(
                        peerDeviceId = stale.peerDeviceId.value,
                        onSignalingLost = { callsImpl?.onSignalingLost(it) },
                    )
                    FlashLog.i(
                        TAG_WS,
                        "Session gone peer='${stale.peer.friendlyName}' id=${stale.peerDeviceId.value}",
                    )
                }
                sessions.values.forEach { session ->
                    if (session is WsSession && !sessionJobs.containsKey(session)) {
                        FlashLog.i(
                            TAG_WS,
                            "Session up peer='${session.peer.friendlyName}' " +
                                "id=${session.peerDeviceId.value} outbound=${session.isOutbound} " +
                                "— sending pairing hello",
                        )
                        FlashSessionCoordinator.onSessionUp(
                            peerDeviceId = session.peerDeviceId.value,
                            chatRepository = chatImpl,
                            onSignalingRestored = { callsImpl?.onSignalingRestored(it) },
                            onPairingHello = { pairing.onSessionUp(it) },
                        )
                        sessionJobs[session] = scope.launch {
                            launch {
                                session.incomingBinary.collect { data ->
                                    handleInboundBinary(session.peerDeviceId.value, data) { bytes ->
                                        session.connection.sendBinaryConsuming(bytes)
                                    }
                                }
                            }
                            launch {
                                session.incomingText.collect { text ->
                                    handleInboundText(session.peerDeviceId.value, text)
                                }
                            }
                        }
                    }
                }
            }
        }
        // ---- transfer control frames, both directions (the desktop port of Flash.kt's pair) ----
        //
        // These two collectors are what the consent gate above depends on, and their ABSENCE is why
        // the gate could not exist: `RealFlashTransferRepository` communicates accept/decline by
        // emitting a control frame rather than calling back, so without `incomingControl` a tap on
        // Accept would never resolve the sink, and without `outgoingControl` the desktop would never
        // tell the sender anything at all — not an accept, not a decline, not a cancel.
        scope.launch {
            transfer.incomingControl.collect { control ->
                when (control.action) {
                    // `IncomingControl` carries no peer id, unlike `OutgoingControl`, so it is
                    // resolved from the offer row the repository already holds.
                    RealFlashTransferRepository.ACTION_ACCEPT ->
                        acceptOffer(control.transferId, peerIdFor(control.transferId))
                    RealFlashTransferRepository.ACTION_DECLINE -> declineOffer(control.transferId)
                    RealFlashTransferRepository.ACTION_CANCEL -> {
                        receivePipeline?.cancelSession(control.transferId)
                        incomingMeta.remove(control.transferId)
                        receivedPaths.remove(control.transferId)
                        openHandles.remove(control.transferId)?.let { runCatching { it.close() } }
                        rejectedLogCache.remove(control.transferId)
                        rejectedCancelSent.add(control.transferId)
                    }
                    RealFlashTransferRepository.ACTION_RESUME -> {
                        val tid = control.transferId
                        if (transfer.isResumableInboundRetry(tid)) {
                            val peerId = peerIdFor(tid)
                            if (peerId.isNotEmpty()) {
                                acceptOffer(tid, peerId)
                            }
                        }
                    }
                    // Pause is the sender asking us to stop reading; the
                    // pipeline already stops delivering when a session is gone, and the local row is
                    // driven by the repository's own state.
                    else -> Unit
                }
            }
        }
        scope.launch {
            transfer.outgoingControl.collect { control ->
                val peerId = control.peerDeviceId ?: return@collect
                sendXfer(peerId, control.action, control.transferId)
            }
        }

        boot("session collectors armed — assemble complete")
    }

    /** One stream channel per channel id, riding the live session with the peer. */
    private suspend fun sessionChannel(channelId: Int, peerDeviceId: String?): StreamChannel? {
        val network = networkImpl ?: return null
        val peerId = peerDeviceId?.let { FlashDeviceId(it) }
        val session = (peerId?.let { network.activeSessions.value[it] }
            ?: network.activeSessions.value.values.firstOrNull()) ?: return null
        val resolvedPeerId = peerDeviceId ?: session.peerDeviceId.value
        val sessionKey = trustStore.getSessionKey(FlashDeviceId(resolvedPeerId))

        return object : StreamChannel {
            override val id: Int = channelId
            override suspend fun sendFrame(frameBytes: ByteArray): Boolean {
                val toSend = if (sessionKey != null) {
                    SecureBinaryFrameCodec.encrypt(frameBytes, sessionKey)
                } else {
                    frameBytes
                }
                return runCatching { session.send(toSend) is FlashResult.Success }.getOrDefault(false)
            }
        }
    }

    private fun sendXfer(peerId: String, action: String, transferId: String) {
        val network = networkImpl ?: return
        val session = network.activeSessions.value[FlashDeviceId(peerId)] as? WsSession ?: return
        session.connection.sendText(
            FlashTextFraming.encodeFields(
                "FLASH_XFER",
                listOf("action" to action, "transferId" to transferId),
            ),
        )
    }

    /**
     * Inbound binary routing — the desktop port of `Flash.kt`'s `handleInboundBinary` and of the
     * Phase 16 harness's version: sender-side ACK/COMPLETE first, then the receive pipeline's
     * events, including the already-completed short-circuit and the resumable-retry auto-accept.
     */
    /**
     * Inbound binary routing — delegated to [FlashInboundRouter] for sender-side ACK/COMPLETE,
     * receive pipeline events, PTT audio, and E2E decryption.
     */
    private fun handleInboundBinary(peerDeviceId: String, data: ByteArray, reply: (ByteArray) -> Boolean) {
        FlashInboundRouter.routeInboundBinary(
            peerDeviceId = peerDeviceId,
            data = data,
            reply = reply,
            peerLabel = "peer",
            pttProvider = { pttImpl },
            sessionKeyLookup = { pid -> trustStoreImpl.getSessionKey(FlashDeviceId(pid)) },
            magicRouter = magicRouter,
            transferRepository = transferImpl,
            receivePipeline = receivePipeline,
            incomingMeta = incomingMeta,
            receivedPaths = receivedPaths,
            openHandles = openHandles,
            fileExistsAndSizeMatches = { path, expectedBytes ->
                File(path).let { it.isFile && it.length() == expectedBytes }
            },
            sendXferResume = { pid, tid ->
                sendXfer(pid, RealFlashTransferRepository.ACTION_RESUME, tid)
            },
            sendXferCancel = { pid, tid ->
                sendXfer(pid, RealFlashTransferRepository.ACTION_CANCEL, tid)
            },
            incomingOwners = incomingOwners,
            // A re-offer of an already-accepted transfer takes the full accept path (ADR-069 / FA-2
            // storage gate, sink, progress seed, then RESUME), as it did before the shared router.
            onResumableRetry = { frame, pid, _ ->
                pid?.let { acceptOffer(frame.transferId, it) }
            },
            onOfferReceived = { frame, pid, secureReply ->
                val sessionKey = pid?.let { trustStoreImpl.getSessionKey(FlashDeviceId(it)) }
                FlashLog.i(
                    TAG_WS,
                    "Inbound file offer tid=${frame.transferId} name='${frame.fileName}' " +
                        "bytes=${frame.totalBytes} from peer=$pid (encrypted=${sessionKey != null})",
                )
                transferImpl?.onIncomingOffered(frame.transferId, frame.fileId, frame.fileName, frame.totalBytes, "peer", pid)
                val offerMime = guessOfferMime(frame.fileName)
                pid?.let { pId ->
                    chatImpl?.onInboundAttachment(
                        peerDeviceId = pId,
                        transferId = frame.transferId,
                        fileName = frame.fileName,
                        mimeType = offerMime,
                        sizeBytes = frame.totalBytes,
                    )
                    val offerTrusted = trustStoreImpl.isTrusted(FlashDeviceId(pId))
                    val currentSettings = _settings.value
                    val offerAuto = when {
                        offerMime.startsWith("audio/") -> currentSettings.autoDownloadVoice
                        offerMime.startsWith("image/") -> currentSettings.autoDownloadImage
                        offerMime.startsWith("video/") -> currentSettings.autoDownloadVideo
                        else -> currentSettings.autoDownloadFile
                    }
                    if (shouldAutoAcceptOffer(offerTrusted, offerAuto, autoAcceptIncoming)) {
                        FlashLog.i(
                            TAG_WS,
                            "Auto-accepting '${frame.fileName}' (mime=$offerMime) tid=${frame.transferId}",
                        )
                        acceptOffer(frame.transferId, pId)
                    }
                }
            },
            onRejected = { event, pid ->
                if (event.reason != RejectReason.AWAITING_ACCEPTANCE) {
                    val tid = event.transferId
                    val key = "$tid:${event.reason}"
                    if (rejectedLogCache.size > 256) rejectedLogCache.clear()
                    if (rejectedLogCache.add(key)) {
                        println("[flash-desktop] receiver rejected: ${event.reason} tid=$tid")
                    }
                    if (event.reason == RejectReason.UNKNOWN_TRANSFER && tid != null && pid != null) {
                        if (rejectedCancelSent.size > 256) rejectedCancelSent.clear()
                        if (rejectedCancelSent.add(tid)) {
                            sendXfer(pid, RealFlashTransferRepository.ACTION_CANCEL, tid)
                        }
                    }
                }
            },
        )
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

    /** FLASH_XFER control frames — route into the repository (both directions). */
    private suspend fun handleInboundText(peerDeviceId: String, text: String) {
        FlashInboundRouter.routeInboundText(
            peerDeviceId = peerDeviceId,
            text = text,
            callHandler = { pid, t -> callsImpl?.onInboundText(pid, t) == true },
            presenceHandler = { pid, t -> presenceExchange?.onInboundText(pid, t) == true },
            modeHandler = { pid, t -> modeController?.onInboundText(pid, t) == true },
            pttProvider = { pttImpl },
            pairingHandler = { pid, t -> pairing.onInbound(pid, t) },
            sessionKeyLookup = { pid -> trustStoreImpl.getSessionKey(FlashDeviceId(pid)) },
            chatRepository = chatImpl,
            transferRepository = transferImpl,
            incomingOwners = incomingOwners,
        )
    }

    /**
     * Shared extension→MIME table (also the auto-download classifier). Unknown extensions fall
     * back to the generic octet-stream so the offer still renders as a file card.
     */
    private fun guessOfferMime(fileName: String): String =
        FlashMimeTypes.fromExtension(fileName.substringAfterLast('.', ""))
            ?: "application/octet-stream"

    /**
     * The accept path, faithful to production's ordering: resolve the deferred sink FIRST,
     * surface Transferring + the started transfer, THEN RESUME the parked sender (missing the
     * RESUME is the exact bug that would deadlock a compliant sender).
     */
    private fun acceptOffer(transferId: String, peerDeviceId: String) {
        val transfer = transferImpl ?: return
        // ADR-069 / FA-2: refuse before the sink is resolved and the sender is released.
        if (!transfer.admitIncoming(transferId)) return
        val meta = incomingMeta[transferId] ?: return
        if (receivePipeline?.acceptSession(transferId) == true) {
            transfer.onIncomingStarted(
                transferId, meta.fileId, meta.fileName, meta.totalBytes,
                "peer", peerDeviceId, receivedPaths[transferId],
            )
            receivePipeline?.let { updateIncomingProgress(transfer, it, incomingMeta, transferId) }
            sendXfer(peerDeviceId, RealFlashTransferRepository.ACTION_RESUME, transferId)
        }
    }

    /**
     * The decline path: drop the parked session and forget the offer.
     *
     * Nothing is on disk to clean up — the sink is only resolved by [acceptOffer], which is the whole
     * point of the gate — so this is bookkeeping. Mirrors `Flash.kt`'s `declineOffer` minus the
     * `pausedIntakeIds`/`incomingByPeer` maps, which this engine does not keep.
     */
    private fun declineOffer(transferId: String) {
        receivePipeline?.declineSession(transferId)
        incomingMeta.remove(transferId)
        receivedPaths.remove(transferId)
        openHandles.remove(transferId)?.let { runCatching { it.close() } }
    }

    /** The peer a parked offer came from, for routing the RESUME when the offer is accepted. */
    private fun peerIdFor(transferId: String): String =
        transferImpl?.activeTransfers?.value?.firstOrNull { it.id.value == transferId }?.peerDeviceId.orEmpty()

    /**
     * Outbound call signaling frame → text, mirroring the app host. An invite races session
     * establishment (the user taps Call on a discovered peer whose session is still coming
     * up), so invites wait up to 2 s for the session the way the holder does; anything else
     * sends only on a live session. Blocking `sendTextAsync`, like the host.
     */
    private suspend fun sendCallFrame(
        network: JvmWsFlashNetwork,
        peerId: String,
        frame: CallWireFrame,
    ): Boolean {
        val encoded = CallFrameCodec.encode(frame)
        var session = network.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
        if (session == null && (frame is CallWireFrame.Invite || frame is CallWireFrame.GroupInvite)) {
            // Audit B2: already suspend — suspend for the session, never block the caller's thread.
            withTimeoutOrNull(2000L) {
                network.activeSessions.first { sessions ->
                    sessions.containsKey(FlashDeviceId(peerId))
                }
            }
            session = network.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
        }
        if (session != null) {
            session.connection.sendTextAsync(encoded)
            FlashLog.i(TAG_WS, "Call sendFrame action=${frame.javaClass.simpleName} peer=$peerId (hasSession=true)")
            return true
        }
        FlashLog.i(TAG_WS, "Call sendFrame action=${frame.javaClass.simpleName} peer=$peerId no session")
        return false
    }

    /**
     * Outbound chat frame → text, mirroring `Flash.kt`'s `sendChatFrame`: the five
     * direct-chat families through the shared codec, delete through its own. Blocking
     * `sendText`, like the app host — the caller is the repository's IO-scoped send.
     */
    private fun sendChatFrame(
        network: JvmWsFlashNetwork,
        targetDeviceId: String,
        wireFrame: MessageWireFrame,
    ): Boolean {
        val session = network.activeSessions.value[FlashDeviceId(targetDeviceId)] as? WsSession
            ?: return false
        val frameText = when (wireFrame) {
            is MessageWireFrame.DeleteForEveryone -> DirectMessageActionCodec.encode(wireFrame)
            is MessageWireFrame.TextMessage,
            is MessageWireFrame.DeliveryReceipt,
            is MessageWireFrame.ReadReceipt,
            is MessageWireFrame.ReactionFrame,
            is MessageWireFrame.TypingFrame -> ChatTextFrameCodec.encode(wireFrame) ?: return false
        }
        val sessionKey = trustStore.getSessionKey(FlashDeviceId(targetDeviceId))
        val wirePayload = if (sessionKey != null) {
            E2eFrameCodec.encryptToWireFrame(frameText, sessionKey)
        } else {
            frameText
        }
        return session.connection.sendText(wirePayload)
    }

    internal companion object {
        init {
            registerFactory()
        }

        /** How long a shutdown waits for discovery, and separately for the network, to stop. */
        const val SUBSYSTEM_STOP_TIMEOUT_MS: Long = 10_000L

        /** How long detaching the calling engine waits for the hang-up of a call in progress. */
        const val CALL_END_TIMEOUT_MS: Long = 3_000L

        /** State directories owned by an engine built through [FlashDesktop.create], released by close(). */
        private val liveStateDirs = ConcurrentHashMap<String, DesktopEngine>()

        /**
         * Test-only overrides for the engine [FlashDesktop.create] builds, so a test can use a temporary state
         * directory, the pass-through identity vault and a fake microphone instead of the developer's real
         * `~/.flash`, DPAPI and sound card. Production never sets it.
         */
        internal class FactoryOptions(
            val stateDir: File? = null,
            val identityVault: com.transfer.flash.core.security.identity.IdentityKeyVault? = null,
            val pttAudio: PttAudioPlatform? = null,
        )

        @Volatile
        internal var factoryOptions: FactoryOptions? = null

        /**
         * Registers [FlashDesktop]'s engine factory. Runs when this class loads (that is how `FlashDesktop.create`
         * finds it by reflection) and can be called again after `FlashDesktop.resetForTesting()`, since a class
         * initialiser runs only once per class loader.
         */
        internal fun registerFactory() {
            FlashDesktop.registerFactory { config -> createFromConfig(config) }
        }

        /**
         * Builds and starts the engine for [FlashDesktop.create]. Refuses a second live engine on the same state
         * directory (shared identity, encrypted chat database and port) with an [IllegalStateException]; the
         * directory is released when that engine is closed.
         */
        private fun createFromConfig(config: FlashConfig): DesktopEngine {
            val options = factoryOptions
            val stateDir = options?.stateDir ?: DesktopPaths.stateDir()
            val key = runCatching { stateDir.canonicalPath }.getOrElse { stateDir.absolutePath }
            if (config.enableResume) {
                // The factory's `store = null` (D5 = C): there is no persistent transfer store on desktop yet.
                FlashLog.w(
                    TAG_WS,
                    "FlashConfig.enableResume=true is not supported on desktop yet (no persistent transfer store, " +
                        "D5 = C): transfers resume within a session only. Set enableResume = false to silence this.",
                )
            }
            val engine = synchronized(liveStateDirs) {
                val owner = liveStateDirs[key]
                check(owner == null || owner.closed) {
                    "Another DesktopEngine already owns the state directory $key; close it (FlashEngine.close()) " +
                        "before FlashDesktop.create() builds a second one on the same identity, database and port."
                }
                DesktopEngine(
                    receivedRoot = config.receivedFilesPath?.let { File(it) },
                    stateDir = stateDir,
                    identityVault = options?.identityVault ?: DesktopVaults.forCurrentOs(stateDir),
                    pttAudio = options?.pttAudio ?: platformPttAudio(),
                    displayName = config.displayName,
                    autoAcceptIncoming = config.autoAcceptIncoming,
                ).also { liveStateDirs[key] = it }
            }
            try {
                engine.start()
            } catch (failure: Throwable) {
                engine.close()
                throw failure
            }
            return engine
        }

        /**
         * Strips anything that could escape the intended directory (AGENTS.md §19) or cause NTFS/filesystem errors.
         * Preserves Unicode (ERROR-093 / TXT-09), spaces, file extensions on truncation (TXT-10), and guards Windows
         * reserved device names (TXT-11).
         */
        internal fun sanitize(component: String): String =
            FlashPathSanitizer.sanitize(component)

        /**
         * Sanitizes a relative file path (potentially with subdirectories from a folder transfer)
         * while strictly guarding against path traversal (AGENTS.md §19).
         */
        internal fun sanitizeRelativePath(raw: String): String =
            FlashPathSanitizer.sanitizeRelativePath(raw, File.separator)

        /** AGENTS.md §24 tag for the WS mesh; matches the app host's `TAG_WS`. */
        const val TAG_WS = "WS"

        /** AGENTS.md §24 tag for Discovery; matches the app host's `TAG_DISCOVERY`. */
        const val TAG_DISCOVERY = "DISCOVERY"
    }

    // The receive pipeline is assembled inside [assemble] but stored here so the private
    // routing helpers above can reach it without threading it through every call.
    @Volatile
    private var receivePipeline: ReceivePipeline? = null

    /** The bound WS port, kept so a rename can re-advertise without a restart. */
    @Volatile
    private var advertisedPort: Int = 0

    /** The WebSocket port the boot bound (0 until it has), for tests that check a close released it. */
    internal val boundPort: Int get() = advertisedPort
}
