@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import com.transfer.flash.core.discovery.jmdns.JmdnsTransport
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

    // --- Subsystems; non-null once [ready] flips true ---
    private var networkImpl: JvmWsFlashNetwork? = null
    private var discoveryImpl: CompositeDiscovery? = null
    private var transferImpl: RealFlashTransferRepository? = null

    public val chats: FlashChatRepository = EmptyFlashChatRepository
    public val transfers: FlashTransferRepository? get() = transferImpl
    public val network: FlashNetwork? get() = networkImpl
    public val discovery: FlashDiscovery? get() = discoveryImpl
    public val trust: com.transfer.flash.core.security.trust.FlashTrustStore get() = trustStore

    public val localDeviceId: String get() = identity.deviceId.value
    public val localFriendlyName: String get() = identity.friendlyName
    public val appVersionName: String = "1.0.0-desktop"

    // --- Receive-side state, mirroring Flash.kt's Wiring ---
    private val canonicalRoot: File = receivedRoot.canonicalFile.apply { mkdirs() }
    private val openHandles = ConcurrentHashMap<String, RandomAccessSinkHandle>()
    private val incomingMeta = ConcurrentHashMap<String, ChunkFrame.FileStart>()
    private val receivedPaths = ConcurrentHashMap<String, String>()

    private var sessionJobs = ConcurrentHashMap<WsSession, Job>()
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
                    _startError.value = failure
                }
        }
    }

    public fun stop() {
        synchronized(startMutex) {
            if (!started) return
            started = false
        }
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

        val identityFrame = FlashAdvertisedIdentity(
            deviceId = identity.deviceId,
            friendlyName = friendlyName,
            deviceModel = "Desktop",
            protocolVersion = 2,
        )
        DiscoveryRouteBinder.observe(scope, discovery.discoveredEndpoints, network)
        val startedAll = runBlocking {
            discovery.setMode(FlashDiscoveryMode.STANDARD)
            discovery.startAll(serverPort, identityFrame)
        }
        require(startedAll.isSuccess) {
            "discovery startAll failed: ${(startedAll as FlashResult.Failure).error}"
        }

        // Auto-dial every discovered peer so inbound offers and chat frames have a session to
        // ride — same loop the Phase 16 harness runs.
        scope.launch {
            while (isActive) {
                discovery.discoveredEndpoints.value.forEach { endpoint ->
                    val id = endpoint.device.id
                    if (network.activeSessions.value[id] == null && !network.isReconnectInFlight(id.value)) {
                        runCatching { network.connectManual(endpoint.hostAddress, endpoint.port) }
                    }
                }
                delay(AUTO_CONNECT_SWEEP_MS)
            }
        }

        // ---- session collectors: binary → receive pipeline, text → transfer control ----
        receivePipeline = pipeline
        scope.launch {
            network.activeSessions.collect { sessions ->
                sessionJobs.keys.filterNot { it in sessions.values }.forEach { stale ->
                    sessionJobs.remove(stale)
                }
                sessions.values.forEach { session ->
                    if (session is WsSession && !sessionJobs.containsKey(session)) {
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
                    // v1 desktop shell policy: auto-accept (no consent UI on desktop yet; the
                    // Transfers tab still shows the offer row as it resolves).
                    acceptOffer(frame.transferId, peerDeviceId)
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

    private fun sanitize(component: String): String =
        component.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private companion object {
        /** Same cadence as Flash.kt's auto-connect sweep. */
        const val AUTO_CONNECT_SWEEP_MS = 5_000L
    }

    // The receive pipeline is assembled inside [assemble] but stored here so the private
    // routing helpers above can reach it without threading it through every call.
    private var receivePipeline: ReceivePipeline? = null
}
