@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.protocol.FlashTextFraming
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * **Chats bind [EmptyFlashChatRepository]** until 09B-2 lands: `RealFlashChatRepository` is
 * Room-backed androidMain, so a desktop chat history is impossible today. The shell follows the
 * app's own ERROR-034 discipline — an honest empty repository, never fabricated content.
 *
 * **Resume across restart is off** (`store = null`, D5 = C pending): the Phase 16 harness runs
 * the same way, and G7 is already logged BLOCKED ON 09B-2.
 */
public class DesktopEngine(
    /** Root for received files. Default: `~/FlashReceived` (Android uses app external storage). */
    receivedRoot: File = File(System.getProperty("user.home", "."), "FlashReceived"),
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
        // One ephemeral ECDH key for the engine's lifetime, as the app host does (the key rides
        // the handshake; no session encryption is wired to it yet).
        ephemeralPublicKey = crypto.generateEphemeralEcdhKeyPair().publicKeyEncoded,
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
    )

    // --- Subsystems; non-null once [ready] flips true ---
    private var networkImpl: JvmWsFlashNetwork? = null
    private var discoveryImpl: CompositeDiscovery? = null
    private var transferImpl: RealFlashTransferRepository? = null

    // --- Persisted desktop preferences (today: the Appearance selection) ---
    //
    // Narrow accessors rather than exposing the store, matching how identity/trust are handled:
    // `DesktopSettingsStore` stays internal so its file format is not part of the engine's surface.
    // Built from the engine's OWN `stateDir`, which is the same reason `receivedDirectory` exists —
    // re-deriving `~/.flash` at the call site is how `clearReceivedFiles` came to write to a folder
    // the engine was not using.

    /** The persisted Appearance selection; [FlashThemeMode.System] when unset. */
    public fun storedThemeMode(): FlashThemeMode = settingsStore.themeMode()

    /** Records the Appearance selection so it survives a restart. */
    public fun storeThemeMode(mode: FlashThemeMode) {
        settingsStore.setThemeMode(mode)
    }

    public val chats: FlashChatRepository = EmptyFlashChatRepository
    public val transfers: FlashTransferRepository? get() = transferImpl
    public val network: FlashNetwork? get() = networkImpl
    public val discovery: FlashDiscovery? get() = discoveryImpl
    public val trust: com.transfer.flash.core.security.trust.FlashTrustStore get() = trustStore

    public val localDeviceId: String get() = identity.deviceId.value
    public val localFriendlyName: String get() = identity.friendlyName

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
    private val canonicalRoot: File = receivedRoot.canonicalFile.apply { mkdirs() }

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
    public val receivedDirectory: File get() = canonicalRoot
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

        val network = JvmWsFlashNetwork(
            localDeviceId = localId,
            localFriendlyName = friendlyName,
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
            fileSourceOpener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) },
            store = null, // D5 = C pending (09B-2) — matches the Phase 16 harness.
            repositoryScope = scope,
            requireReceiverAcceptance = true,
        )
        transferImpl = transfer

        val pipeline = ReceivePipeline(
            sink = { _, _ -> error("legacy shared sink must not be invoked with sinkFactory set") },
            sinkFactory = { start ->
                val safeName = sanitize(start.fileName.ifBlank { "received.bin" })
                val safeId = sanitize(start.transferId)
                val dest = File(File(canonicalRoot, safeId), safeName).canonicalFile
                // Same containment discipline as the production composition (Sentinel).
                require(dest.path.startsWith(canonicalRoot.path + File.separator)) {
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
            discovery.setMode(FlashDiscoveryMode.STANDARD)
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
                    // Pause/resume/cancel are the sender asking us to stop or continue reading; the
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
        val session = peerDeviceId
            ?.let { network.activeSessions.value[FlashDeviceId(it)] }
            ?: network.activeSessions.value.values.firstOrNull()
            ?: return null
        return object : StreamChannel {
            override val id: Int = channelId
            override suspend fun sendFrame(frameBytes: ByteArray): Boolean =
                runCatching { session.send(frameBytes) is FlashResult.Success }.getOrDefault(false)
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
        if (transfer.onInboundFrame(data)) return
        val receivePipeline = this.receivePipeline ?: return
        for (event in receivePipeline.onFrame(data)) {
            when (event) {
                is ReceiveEvent.SessionStarted -> {
                    val frame = event.frame
                    incomingMeta[frame.transferId] = frame
                    val existing = transfer.activeTransfers.value.firstOrNull { it.id.value == frame.transferId }
                    val existingPath = receivedPaths[frame.transferId] ?: existing?.localPath
                    val alreadyCompleted =
                        (existing != null && existing.state == FlashTransferState.Completed) ||
                            (existingPath != null && File(existingPath).let { it.isFile && it.length() == frame.totalBytes })
                    if (alreadyCompleted) {
                        reply(ChunkFrame.serialize(ChunkFrame.Complete(frame.transferId, frame.fileId, verified = true)))
                        sendXfer(peerDeviceId, RealFlashTransferRepository.ACTION_RESUME, frame.transferId)
                        continue
                    }
                    if (transfer.isResumableInboundRetry(frame.transferId)) {
                        acceptOffer(frame.transferId, peerDeviceId)
                        continue
                    }
                    transfer.onIncomingOffered(frame.transferId, frame.fileId, frame.fileName, frame.totalBytes, "peer", peerDeviceId)
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
                    reply(ChunkFrame.serialize(event.frame))
                }
                is ReceiveEvent.Completed -> {
                    val transferId = event.frame.transferId
                    openHandles.remove(transferId)?.let { it.flush(); it.close() }
                    val path = receivedPaths.remove(transferId)
                    incomingMeta.remove(transferId)
                    transfer.onIncomingCompleted(transferId, event.frame.verified, path)
                    reply(ChunkFrame.serialize(event.frame))
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

    /** FLASH_XFER control frames — route into the repository (both directions). */
    private fun handleInboundText(peerDeviceId: String, text: String) {
        // Phase 26-3: pairing traffic shares the FLASH_XFER routing point but its own prefix —
        // check it FIRST so a pairing line is never handed to the transfer repository.
        if (FlashTextFraming.parseFields(text, "FLASH_PAIR") != null) {
            pairing.onInbound(peerDeviceId, text)
            return
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

    private fun sanitize(component: String): String =
        component.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private companion object {
        /** Same cadence as Flash.kt's auto-connect sweep. */
        const val AUTO_CONNECT_SWEEP_MS = 5_000L

        /** AGENTS.md §24 tag for the WS mesh; matches the app host's `TAG_WS`. */
        const val TAG_WS = "WS"
    }

    // The receive pipeline is assembled inside [assemble] but stored here so the private
    // routing helpers above can reach it without threading it through every call.
    private var receivePipeline: ReceivePipeline? = null

    /** The bound WS port, kept so a rename can re-advertise without a restart. */
    private var advertisedPort: Int = 0
}
