@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.persistence.db.runInWriteTransaction
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.calling.CallCoordinator
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
import com.transfer.flash.core.messaging.util.FlashMimeTypes
import com.transfer.flash.core.persistence.db.FlashDatabase
import com.transfer.flash.core.persistence.db.openEncryptedFlashDatabase
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.ws.JvmWsFlashNetwork
import com.transfer.flash.core.network.ws.WsSession
import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.RejectReason
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.policy.OkioRandomAccessSinkHandle
import com.transfer.flash.core.transfer.policy.RandomAccessChunkSink
import com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.network.tls.FlashCertMaker
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.TofuPinVerifier
import com.transfer.flash.core.network.tls.requireTransportSecurity
import com.transfer.flash.core.security.crypto.PersistedFlashCrypto
import com.transfer.flash.core.security.crypto.SecureBinaryFrameCodec
import com.transfer.flash.ui.settings.FlashThemeMode
import java.io.File
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
import kotlinx.coroutines.flow.map
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
    /** Root for identity/trust/settings-free state. Default: `~/.flash`. */
    private val stateDir: File = File(System.getProperty("user.home", "."), ".flash"),
) {
    public val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _ready = MutableStateFlow(false)
    public val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _startError = MutableStateFlow<Throwable?>(null)
    public val startError: StateFlow<Throwable?> = _startError.asStateFlow()

    // --- Identity (file-backed; see DesktopIdentityStores.kt) ---
    private val identityStore = DesktopIdentityStore(stateDir)
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

    private val trustStore = DesktopTrustStore(stateDir)
    public val identity: com.transfer.flash.core.security.identity.FlashIdentity
        get() = identityStore.getIdentity()

    /**
     * The desktop identity crypto — Phase 26 (P2, ADR-035): a P-256 identity keypair generated
     * once, DPAPI-protected at rest under `<stateDir>/identity/id-key.bin`, surviving restarts.
     * This is what will make TOFU trust durable and pairing (G2/G6) possible on desktop; the
     * pairing-session coordinator + numeric-comparison dialog that CONSUME it are the remaining
     * 26-3 work. Falls back LOUDLY to an in-memory identity if the vault is unreadable — see
     * [PersistedFlashCrypto]'s degradation contract.
     */
    public val crypto: com.transfer.flash.core.security.crypto.FlashCrypto =
        com.transfer.flash.core.security.crypto.PersistedFlashCrypto(stateDir)

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
        localName = identity.friendlyName,
        localModel = "desktop",
        // One ephemeral ECDH key for the engine's lifetime, used to derive AES-256 session keys.
        ephemeralPublicKey = ephemeralKeyPair.publicKeyEncoded,
        trustStore = trustStore,
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
    private var networkImpl: JvmWsFlashNetwork? = null
    private var discoveryImpl: CompositeDiscovery? = null
    private var transferImpl: RealFlashTransferRepository? = null
    private var chatImpl: RealFlashChatRepository? = null
    private var chatDb: FlashDatabase? = null
    private var callsImpl: CallCoordinator? = null

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

    /**
     * Chat history. The real repository once [assemble] has built it; the honest empty
     * repository before that (and if the database ever fails to open) — the shell renders
     * both, per ERROR-034.
     */
    public val chats: FlashChatRepository get() = chatImpl ?: EmptyFlashChatRepository

    /**
     * Voice/video calling. The shared coordinator once [assemble] has built it; null before
     * that (and the shell renders no call UI until it exists). Nullable like [transfers]
     * rather than an empty stand-in: there is no honest "empty call", only absence.
     */
    public val calls: FlashCalling? get() = callsImpl
    public val transfers: FlashTransferRepository? get() = transferImpl
    public val network: FlashNetwork? get() = networkImpl
    public val discovery: FlashDiscovery? get() = discoveryImpl
    public val trust: com.transfer.flash.core.security.trust.FlashTrustStore get() = trustStore

    public val localDeviceId: String get() = identity.deviceId.value
    public val localFriendlyName: String get() = identity.friendlyName

    /**
     * Forces an immediate rediscovery and reconnect sweep across discovered endpoints, matching
     * the Android host's `AppEngine.reconnectNow()`.
     *
     * @return false when the engine has not booted yet.
     */
    public fun reconnectNow(): Boolean {
        val net = networkImpl ?: return false
        val disc = discoveryImpl ?: return false
        scope.launch {
            FlashLog.i(TAG_DISCOVERY, "Manual retry: restarting discovery and triggering redials")
            runCatching { disc.restartDiscovery() }
                .onFailure { FlashLog.w(TAG_DISCOVERY, "Discovery restart failed", it) }
            disc.discoveredEndpoints.value.forEach { ep ->
                dialIfNeeded(net, ep)
            }
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
        friendlyName = identity.friendlyName,
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
        val renamed = runCatching { identityStore.updateFriendlyName(trimmed) }.getOrNull()
        if (renamed !is FlashResult.Success) return false

        // Re-advertise, or peers keep the old name: the name rides the discovery TXT record, which
        // was published at boot. `updateIdentity` swaps what `startAdvertising` will publish, and
        // `advertisedPort` is the port bound at boot, so no session is disturbed.
        //
        // KNOWN LIMIT: the WS handshake name is captured when `JvmWsFlashNetwork` is constructed and
        // has no setter, so a peer that stays connected keeps the previous name in its session until
        // it reconnects. The Nearby/chat rows read discovery, so they update immediately.
        runCatching {
            discoveryImpl?.let { discovery ->
                discovery.updateIdentity(buildAdvertisedIdentity())
                if (advertisedPort > 0) discovery.startAdvertising(advertisedPort)
            }
        }
        return true
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
    private val receivedPaths = ConcurrentHashMap<String, String>()

    private var sessionJobs = ConcurrentHashMap<WsSession, Job>()

    /**
     * Peer ids with a `connectManual` in flight — see [dialIfNeeded], which is reached from two
     * triggers and must not dial the same peer twice.
     */
    private val dialing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private var started = false
    private val startMutex = Any()

    /** A no-arg view for the shell when nothing is booted. */
    private val fallbackTransfers = MutableStateFlow(emptyList<com.transfer.flash.core.transfer.model.FlashTransfer>())

    /** Assembles and boots the desktop stack. Idempotent; failures land in [startError]. */
    public fun start() {
        synchronized(startMutex) {
            if (started) return
            started = true
        }
        scope.launch {
            val result = runCatching { assemble() }
            result
                .onSuccess {
                    _startError.value = null
                    _ready.value = true
                    networkWatcher.start()
                }
                .onFailure { failure ->
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

    public fun stop() {
        synchronized(startMutex) {
            if (!started) return
            started = false
        }
        // Logged first, and unconditionally: `stop()` cancels the scope, so anything still in
        // flight — including a bring-up that has not finished — dies here silently. A premature
        // call is indistinguishable from a stalled engine in every other observable (the transports
        // keep running, the roster still fills), and that ambiguity cost a session: the desktop's
        // `application { }` body called this straight after composing the window. `ready` is the
        // tell — it is set true by a completed assembly and false here.
        FlashLog.i(TAG_WS, "engine stop() — cancelling scope (ready=${_ready.value})")
        networkWatcher.stop()
        runCatching {
            runBlocking {
                discoveryImpl?.stopAll()
                networkImpl?.stop()
            }
        }
        scope.cancel()
        _ready.value = false
    }

    // -------------------------------------------------------------------------
    // Composition — the Phase 16 harness wiring, verbatim in shape.
    // -------------------------------------------------------------------------
    private fun assemble() {
        val localId = identity.deviceId.value
        val friendlyName = identity.friendlyName

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
        fun boot(stage: String) {
            FlashLog.i(TAG_WS, "[bring-up] $stage (+${System.currentTimeMillis() - bootStartedAt}ms)")
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
            transportProfile = { (_settings.value.performanceMode ?: FlashPerformanceMode.HIGH).transport },
        )
        networkImpl = network

        val discovery = CompositeDiscovery(
            transports = listOf(
                JmdnsTransport(
                    directory = StandardEndpointDirectory(),
                    sweep = { _ -> emptyList() },
                ),
                // ADDITIVE second LAN transport (UDP multicast with self-announcement); JmDNS is
                // kept, not replaced. It is the transport that fixes the desktop's side of the
                // measured failure — the hollow-TXT resolve that reported the phone's address with
                // no device id, so the desktop could never build an endpoint for it and never
                // dialed — and it is the one that expires a peer which was killed without a goodbye
                // instead of leaving it visible for up to a resolver-cache TTL.
                MulticastTransport(
                    socketFactory = JvmMulticastSocketFactory(),
                    directory = StandardEndpointDirectory(),
                ),
            ),
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
        )
        transferImpl = transfer

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
                runInTransaction = { block -> db.runInWriteTransaction(block) },
                isTrustedPeer = { peerId -> trustStore.isTrusted(FlashDeviceId(peerId)) },
                isChannelEncrypted = { peerId -> trustStore.getSessionKey(FlashDeviceId(peerId)) != null },
                onlinePeerIds = network.activeSessions.map { sessions ->
                    sessions.keys.mapTo(HashSet()) { it.value }
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
                        )
                    }
                },
                transportSink = { targetDeviceId, wireFrame -> sendChatFrame(network, targetDeviceId, wireFrame) },
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
        callsImpl = CallCoordinator(
            localDeviceId = localId,
            localName = friendlyName,
            scope = scope,
            // Group Phase 0 trust closure, like the app host: only paired peers can place or
            // receive calls; an inbound invite from a stranger is auto-declined, never rung.
            isTrustedPeer = { peerId -> trustStore.isTrusted(FlashDeviceId(peerId)) },
            // Honest desktop settings: voice priority and performance mode read per call via
            // lambdas so settings changes take immediate effect.
            prioritiseVoice = { _settings.value.prioritiseVoiceQuality },
            performanceMode = { _settings.value.performanceMode ?: FlashPerformanceMode.HIGH },
            peerNameResolver = { peerId ->
                trustStore.getTrustedPeers()[FlashDeviceId(peerId)]
                    ?: discovery.discoveredEndpoints.value.firstOrNull { it.deviceId.value == peerId }?.friendlyName
            },
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

        // ---- bring-up: bind WS server, start discovery, route endpoints, auto-dial ----
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
        // ride. Two triggers, one function:
        //
        //  1. **The discovery edge** — dial the moment a peer shows up. This is the fix for the
        //     measured user-visible failure, and it is worth stating exactly: Pair only works once a
        //     session exists, and with a POLL-only loop a peer that appears just after a tick had no
        //     session for up to `AUTO_CONNECT_SWEEP_MS` (5 s) — while `beginPair` gives up waiting
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
        scope.launch {
            discovery.discoveredEndpoints.collect { endpoints ->
                endpoints.forEach { dialIfNeeded(network, it) }
            }
        }
        scope.launch {
            while (isActive) {
                discovery.discoveredEndpoints.value.forEach { dialIfNeeded(network, it) }
                delay(AUTO_CONNECT_SWEEP_MS)
            }
        }
        boot("dial triggers armed (discovery edge + ${AUTO_CONNECT_SWEEP_MS}ms sweep)")

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
                    // Signaling death opens the call recovery window (ERROR-033) rather than
                    // dropping the call: a Wi-Fi roam takes the session down and redials it
                    // within seconds. Without this every roam would read as a hang-up.
                    callsImpl?.onSignalingLost(stale.peerDeviceId.value)
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
                        // Pairing hello on every session-up, exactly like the app host: it is what
                        // lets the peer derive the shared 6-digit code the moment either side taps
                        // Pair (FLASH_PAIR hello carries the identity fingerprint).
                        pairing.onSessionUp(session.peerDeviceId.value)
                        // A live session again: close any recovery window so a renegotiation that
                        // needs this channel (ICE restart after a roam) can travel on it.
                        callsImpl?.onSignalingRestored(session.peerDeviceId.value)
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
                    }
                    // Pause/resume are the sender asking us to stop or continue reading; the
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

    /**
     * Dials [endpoint] unless a session already exists or one is being established.
     *
     * Called from BOTH the discovery edge and the periodic sweep, so it must be cheap and
     * idempotent — hence the [dialing] set. It is not a nicety: `isReconnectInFlight` covers the
     * resilience layer's own redials, not `connectManual`, so without a local guard the reactive
     * edge and a sweep tick one millisecond later would both dial the same peer, and two crossings
     * between one pair of devices is exactly the connect-glare case (`registerSession`,
     * ERROR-023) — where the loser hangs for the full 6 s handshake timeout.
     *
     * The dial itself is launched rather than awaited: the sweep used to await it inline, so one
     * unreachable peer stalled the whole sweep for 6 s and every peer after it in the list waited
     * its turn.
     */
    private fun dialIfNeeded(network: JvmWsFlashNetwork, endpoint: FlashDiscoveredEndpoint) {
        val id = endpoint.device.id.value
        if (network.activeSessions.value[endpoint.device.id] != null) return
        if (network.isReconnectInFlight(id)) return
        if (!dialing.add(id)) return
        FlashLog.i(
            TAG_WS,
            "Auto-connect dialing peer='${endpoint.friendlyName}' id=$id " +
                "at ${endpoint.hostAddress}:${endpoint.port}",
        )
        scope.launch {
            try {
                val result = runCatching {
                    network.connectManual(endpoint.hostAddress, endpoint.port)
                }.getOrNull()
                FlashLog.i(
                    TAG_WS,
                    "Auto-connect result peer=$id success=${result is FlashResult.Success} " +
                        "detail=${result ?: "threw"}",
                )
            } finally {
                dialing.remove(id)
            }
        }
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
    private fun handleInboundBinary(peerDeviceId: String, data: ByteArray, reply: (ByteArray) -> Boolean) {
        val transfer = transferImpl ?: return
        val sessionKey = trustStore.getSessionKey(FlashDeviceId(peerDeviceId))
        val frameData = if (SecureBinaryFrameCodec.isSecureFrame(data)) {
            if (sessionKey == null) {
                FlashLog.w(TAG_WS, "Received encrypted binary frame from $peerDeviceId but no session key exists")
                return
            }
            val decrypted = SecureBinaryFrameCodec.decryptOrNull(data, sessionKey)
            if (decrypted == null) {
                FlashLog.w(TAG_WS, "Failed to decrypt binary frame from $peerDeviceId (tampered or wrong key)")
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

        val consumedBySender = try {
            transfer.onInboundFrame(frameData)
        } catch (t: Throwable) {
            FlashLog.w(TAG_WS, "Failed to route inbound frame to sender: ${t.message}")
            false
        }
        if (consumedBySender) return
        val receivePipeline = this.receivePipeline ?: return
        val events = try {
            receivePipeline.onFrame(frameData)
        } catch (e: Throwable) {
            FlashLog.w(TAG_WS, "Failed to process inbound binary frame: ${e.message}")
            return
        }
        for (event in events) {
            when (event) {
                is ReceiveEvent.SessionStarted -> {
                    val frame = event.frame
                    // Offer arrival is otherwise invisible: no log line fires between the WS frame
                    // and the Transfers-tab row, which makes "nothing in desktop logs" indistinguishable
                    // from "nothing arrived". Log the offer; acceptance still needs the user (consent gate).
                    FlashLog.i(
                        TAG_WS,
                        "Inbound file offer tid=${frame.transferId} name='${frame.fileName}' " +
                            "bytes=${frame.totalBytes} from peer=$peerDeviceId (encrypted=${sessionKey != null})",
                    )
                    incomingMeta[frame.transferId] = frame
                    val existing = transfer.activeTransfers.value.firstOrNull { it.id.value == frame.transferId }
                    val existingPath = receivedPaths[frame.transferId] ?: existing?.localPath
                    val alreadyCompleted =
                        (existing != null && existing.state == FlashTransferState.Completed) ||
                            (existingPath != null && File(existingPath).let { it.isFile && it.length() == frame.totalBytes })
                    if (alreadyCompleted) {
                        secureReply(ChunkFrame.serialize(ChunkFrame.Complete(frame.transferId, frame.fileId, verified = true)))
                        sendXfer(peerDeviceId, RealFlashTransferRepository.ACTION_RESUME, frame.transferId)
                        continue
                    }
                    if (transfer.isResumableInboundRetry(frame.transferId)) {
                        acceptOffer(frame.transferId, peerDeviceId)
                        continue
                    }
                    transfer.onIncomingOffered(frame.transferId, frame.fileId, frame.fileName, frame.totalBytes, "peer", peerDeviceId)
                    // Offer chat bubble, parity with the app host (`onAttachmentStarted`): the
                    // offer must be visible (and acceptable) in the conversation, not only in the
                    // Transfers tab. ERROR-062: the owner expected accept-in-chat and it was absent.
                    val offerMime = guessOfferMime(frame.fileName)
                    chatImpl?.onInboundAttachment(
                        peerDeviceId = peerDeviceId,
                        transferId = frame.transferId,
                        fileName = frame.fileName,
                        mimeType = offerMime,
                        sizeBytes = frame.totalBytes,
                    )
                    // Auto-download parity with the app host (Bug 3): voice/image/video/file auto-accept
                    // driven by desktop settings, and ONLY from trusted peers.
                    val offerTrusted = trustStore.isTrusted(FlashDeviceId(peerDeviceId))
                    val currentSettings = _settings.value
                    val offerAuto = when {
                        offerMime.startsWith("audio/") -> currentSettings.autoDownloadVoice
                        offerMime.startsWith("image/") -> currentSettings.autoDownloadImage
                        offerMime.startsWith("video/") -> currentSettings.autoDownloadVideo
                        else -> currentSettings.autoDownloadFile
                    }
                    if (offerTrusted && offerAuto) {
                        FlashLog.i(
                            TAG_WS,
                            "Auto-accepting '${frame.fileName}' (mime=$offerMime) tid=${frame.transferId}",
                        )
                        acceptOffer(frame.transferId, peerDeviceId)
                    }
                    // CONSENT GATE — deliberately NOT auto-accepted.
                    //
                    // This used to call `acceptOffer` here, with the comment "no consent UI on
                    // desktop yet". That was wrong twice over: the consent UI *does* exist (the
                    // Transfers tab renders Accept/Decline, and `FlashTransferRepository` has had
                    // `acceptIncoming`/`declineIncoming` all along), and auto-accepting meant any
                    // paired device on the LAN could write files into `~/FlashReceived` with the
                    // user never asked — while the Accept button beside it was decorative.
                    //
                    // The offer now parks. `ReceivePipeline.requireAcceptance = true` holds the
                    // session with no destination sink resolved, so nothing is created on disk until
                    // the user accepts; accepting emits `ACTION_ACCEPT`, which the collector below
                    // turns into `acceptOffer` (sink, then RESUME — the load-bearing order).
                }
                is ReceiveEvent.AckBatchReady -> {
                    transfer.onIncomingChunkConfirmed(event.frame.transferId, event.frame.indexes)
                    updateIncomingProgress(transfer, receivePipeline, incomingMeta, event.frame.transferId)
                    secureReply(ChunkFrame.serialize(event.frame))
                }
                is ReceiveEvent.Completed -> {
                    val transferId = event.frame.transferId
                    openHandles.remove(transferId)?.let { it.flush(); it.close() }
                    val path = receivedPaths.remove(transferId)
                    incomingMeta.remove(transferId)
                    transfer.onIncomingCompleted(transferId, event.frame.verified, path)
                    secureReply(ChunkFrame.serialize(event.frame))
                }
                is ReceiveEvent.Rejected -> {
                    if (event.reason != RejectReason.AWAITING_ACCEPTANCE) {
                        // Console-grade logging only: desktop has no logcat; the shell surfaces
                        // failures through the Transfers tab state.
                        println("[flash-desktop] receiver rejected: ${event.reason} tid=${event.transferId}")
                    }
                }
            }
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

    /** FLASH_XFER control frames — route into the repository (both directions). */
    private suspend fun handleInboundText(peerDeviceId: String, text: String) {
        // Calling signaling first — the most latency-sensitive frame class, mirroring the app
        // host. `onInboundText` answers true for every FLASH_CALL frame it consumed and false
        // when the text is not a call frame at all, so chat/pairing/transfer never see call
        // traffic either way.
        if (callsImpl?.onInboundText(peerDeviceId, text) == true) return
        // Phase 26-3: pairing traffic shares the FLASH_XFER routing point but its own prefix —
        // check it FIRST so a pairing line is never handed to the transfer repository.
        if (FlashTextFraming.parseFields(text, "FLASH_PAIR") != null) {
            pairing.onInbound(peerDeviceId, text)
            return
        }
        val plainText = if (E2eFrameCodec.isSecuredFrame(text)) {
            val sessionKey = peerDeviceId?.let { trustStore.getSessionKey(FlashDeviceId(it)) }
            if (sessionKey != null) {
                E2eFrameCodec.decryptWireFrame(text, sessionKey) ?: run {
                    FlashLog.w(TAG_WS, "Failed to decrypt FLASH_SEC frame from $peerDeviceId", null)
                    return
                }
            } else {
                FlashLog.w(TAG_WS, "Received FLASH_SEC from $peerDeviceId with no stored session key; dropping", null)
                return
            }
        } else {
            // Audit S1b: the direct-chat family is ALWAYS encrypted by the sender once a session key
            // exists, so a plaintext one from a keyed peer is a downgrade — drop it.
            if (DirectChatFamily.matches(text) && peerDeviceId?.let { trustStore.getSessionKey(FlashDeviceId(it)) } != null) {
                FlashLog.w(TAG_WS, "Dropped plaintext direct-chat frame from keyed peer $peerDeviceId (downgrade)", null)
                return
            }
            text
        }

        DirectMessageActionCodec.decode(plainText)?.let { frame ->
            chatImpl?.onInboundWireFrame(frame, transportPeerId = peerDeviceId)
            return
        }
        GroupFrameCodec.decode(plainText)?.let { frame ->
            chatImpl?.onInboundGroupWireFrame(peerDeviceId, frame)
            return
        }
        when (val decoded = ChatTextFrameCodec.decode(plainText, System.currentTimeMillis(), peerDeviceId)) {
            is ChatTextFrameCodec.DecodeResult.Frame -> {
                val frame = decoded.frame
                // transportPeerId for ALL direct families, not just typing: the codec decodes the
                // direct-chat family only (group frames travel a separate path), so the frame
                // author IS the transport peer — and the repository's fail-closed spoof guards
                // (PR #11) only fire when it is non-null. Same fix as both Android call sites.
                chatImpl?.onInboundWireFrame(
                    frame,
                    transportPeerId = peerDeviceId,
                )
                return
            }
            ChatTextFrameCodec.DecodeResult.RecognizedButInvalid -> return
            null -> Unit
        }
        val transfer = transferImpl ?: return
        val fields = FlashTextFraming.parseFields(text, "FLASH_XFER")
        if (fields != null) {
            val action = fields["action"]
            val tid = fields["transferId"]
            if (action != null && tid != null) {
                transfer.onRemoteTransferControl(tid, action)
            }
        }
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
            runBlocking {
                withTimeoutOrNull(2000L) {
                    network.activeSessions.first { sessions ->
                        sessions.containsKey(FlashDeviceId(peerId))
                    }
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
        /** Strips anything that could escape the intended directory (AGENTS.md §19). */
        internal fun sanitize(component: String): String =
            component.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

        /**
         * Sanitizes a relative file path (potentially with subdirectories from a folder transfer)
         * while strictly guarding against path traversal (AGENTS.md §19).
         */
        internal fun sanitizeRelativePath(raw: String): String {
            val normalized = raw.replace('\\', '/').trim().trimStart('/')
            val segments = normalized.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return "unnamed"
            val safeSegments = mutableListOf<String>()
            for (seg in segments) {
                if (seg == "." || seg == "..") continue
                val sanitized = sanitize(seg)
                if (sanitized.isNotBlank() && sanitized != "." && sanitized != "..") {
                    safeSegments.add(sanitized)
                }
            }
            return if (safeSegments.isEmpty()) "unnamed" else safeSegments.joinToString(File.separator)
        }

        /** Same cadence as Flash.kt's auto-connect sweep. */
        const val AUTO_CONNECT_SWEEP_MS = 5_000L

        /** AGENTS.md §24 tag for the WS mesh; matches the app host's `TAG_WS`. */
        const val TAG_WS = "WS"

        /** AGENTS.md §24 tag for Discovery; matches the app host's `TAG_DISCOVERY`. */
        const val TAG_DISCOVERY = "DISCOVERY"
    }

    // The receive pipeline is assembled inside [assemble] but stored here so the private
    // routing helpers above can reach it without threading it through every call.
    private var receivePipeline: ReceivePipeline? = null

    /** The bound WS port, kept so a rename can re-advertise without a restart. */
    private var advertisedPort: Int = 0
}
