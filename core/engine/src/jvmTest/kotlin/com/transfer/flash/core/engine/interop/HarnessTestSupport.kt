@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.interop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import com.transfer.flash.core.discovery.jmdns.JmdnsTransport
import com.transfer.flash.core.discovery.multicast.JvmMulticastSocketFactory
import com.transfer.flash.core.discovery.multicast.MulticastTransport
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.ws.JvmWsFlashNetwork
import com.transfer.flash.core.network.ws.WsSession
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashFingerprint
import com.transfer.flash.core.security.crypto.PersistedFlashCrypto
import com.transfer.flash.core.network.tls.TofuPinVerifier
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.FlashCertMaker
import com.transfer.flash.core.security.pairing.FlashPairingCoordinator
import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkSink
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.RejectReason
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.policy.RandomAccessChunkSink
import com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Phase 16 harness support: the desktop twin of `androidMain` `Wiring`'s **transfer half** —
 * the composition `DesktopInteropHarness.main` drives interactively and this test support
 * instantiates programmatically.
 *
 * Faithfully ports what a transfer gate needs from `Flash.kt`'s wiring:
 * - per-transfer random-access receive sinks under a canonical root (with the same
 *   path-containment discipline the production composition applies),
 * - the `#5` accept gate (`requireAcceptance` + deferred sink + RESUME-to-start),
 * - inbound binary routing: sender-side ACK/COMPLETE first, then the receive pipeline's events,
 *   including the already-completed short-circuit and the retry auto-accept,
 * - outbound control frames (`FLASH_XFER`) on the session's text lane.
 *
 * Deliberately NOT ported: chat/PTT/calling (not a transfer gate) and the Room-backed
 * `TransferStore` (09B-2 pending; `store = null` disables resume-across-restart only).
 */
internal class DesktopEndpointFixture(
    /** Also the mDNS instance-name tag; see [start] on why endpoints must not advertise as the app. */
    private val name: String,
    private val receivedRoot: File,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val stateDir = File(System.getProperty("java.io.tmpdir"), "flash-interop-$name").apply { mkdirs() }
    val identity = DesktopIdentityStore(stateDir).getIdentity()
    val trustStore = DesktopTrustStore(stateDir)
    private val crypto: FlashCrypto = PersistedFlashCrypto(stateDir)

    /**
     * Real TLS, built exactly as `DesktopEngine` builds it (audit S3/S1, ADR-042). The fixture used to run
     * plaintext, which production can no longer do; pairing v2 binds to the pins recorded here.
     */
    private val tlsOptions = TlsOptions(
        pinVerifier = TofuPinVerifier(
            lookupPin = { peerId -> trustStore.getPin(FlashDeviceId(peerId)) },
            recordPin = { peerId, pin -> trustStore.savePin(FlashDeviceId(peerId), pin) },
        ),
        keyManagers = FlashCertMaker.createKeyManagers(
            (crypto as PersistedFlashCrypto).javaKeyPair(),
            cn = "CN=${identity.deviceId.value}",
        ),
    )

    val network = JvmWsFlashNetwork(
        localDeviceId = identity.deviceId.value,
        // "Harness $name" — see the sibling in DesktopInteropHarness.kt: the WS hello must not name
        // the harness as the product.
        localFriendlyName = "Harness $name",
        tlsOptions = tlsOptions,
    )
    val discovery = CompositeDiscovery(
        transports = listOf(
            JmdnsTransport(
                directory = StandardEndpointDirectory(),
                sweep = { _ -> emptyList() },
            ),
            // Mirrors the product's additive second transport; see the sibling in
            // DesktopInteropHarness.kt for why the harness must not measure DNS-SD alone.
            MulticastTransport(
                socketFactory = JvmMulticastSocketFactory(),
                directory = StandardEndpointDirectory(),
            ),
        ),
    )
    val transfer = RealFlashTransferRepository(
        streamChannelFactory = { channelId, peerDeviceId -> sessionChannel(channelId, peerDeviceId) },
        fileSourceOpener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) },
        store = null,
        repositoryScope = scope,
        requireReceiverAcceptance = true,
    )

    /**
     * Pairing, so the fixture covers everything the product composition covers — added 2026-09-14
     * with the harness's `pair` verb, and the only reason `DesktopPairingLoopbackTest` can exist.
     *
     * No console front-end here: the trust decision belongs to a human, and a test must not be able
     * to make it. `acceptLocal()` is called by the test, which is what makes the assertion "the two
     * sides agreed on a code" meaningful rather than "something auto-accepted".
     */
    val pairing: FlashPairingCoordinator = FlashPairingCoordinator(
        localFingerprintHex = FlashFingerprint.formatHexGroups(
            FlashFingerprint.fingerprint(crypto.identityPublicKeyEncoded),
        ),
        localDeviceId = identity.deviceId.value,
        localName = "Harness $name",
        localModel = "desktop",
        ephemeralPublicKey = crypto.generateEphemeralEcdhKeyPair().publicKeyEncoded,
        trustStore = trustStore,
        scope = scope,
        sendToPeer = { peerId, text ->
            val session = network.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
            if (session != null) {
                session.connection.sendTextAsync(text)
                true
            } else {
                false
            }
        },
    )

    private val openHandles = ConcurrentHashMap<String, RandomAccessSinkHandle>()
    private val incomingMeta = ConcurrentHashMap<String, ChunkFrame.FileStart>()
    private val receivedPaths = ConcurrentHashMap<String, String>()

    /** The inbound pipeline with the same #5 gate + per-transfer sinks as production wiring. */
    val receivePipeline = ReceivePipeline(
        sink = { _, _ -> error("legacy shared sink must not be invoked with sinkFactory set") },
        sinkFactory = { start ->
            val safeName = sanitize(start.fileName.ifBlank { "received.bin" })
            val safeId = sanitize(start.transferId)
            val canonicalRoot = receivedRoot.canonicalFile
            val dest = File(File(canonicalRoot, safeId), safeName).canonicalFile
            // Same containment discipline as the production composition (Sentinel).
            require(dest.path.startsWith(canonicalRoot.path + File.separator)) {
                "path traversal escape: ${start.fileName}"
            }
            dest.parentFile?.mkdirs()
            receivedPaths[start.transferId] = dest.absolutePath
            val handle = com.transfer.flash.core.transfer.policy.OkioRandomAccessSinkHandle(
                dest.absolutePath.toPath(),
                start.totalBytes,
            )
            openHandles[start.transferId] = handle
            RandomAccessChunkSink(handle, start.chunkSize)
        },
        emitSessionStarted = true,
        requireAcceptance = true,
    )

    private fun sanitize(component: String): String =
        component.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    /** One stream channel per channel id, riding the session with the peer. */
    private suspend fun sessionChannel(channelId: Int, peerDeviceId: String?): StreamChannel? {
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
        val session = network.activeSessions.value[FlashDeviceId(peerId)] as? WsSession ?: return
        session.connection.sendText(
            com.transfer.flash.core.common.protocol.FlashTextFraming.encodeFields(
                "FLASH_XFER",
                listOf("action" to action, "transferId" to transferId),
            ),
        )
    }

    /** Inbound binary routing — the desktop port of `Flash.kt`'s `handleInboundBinary`. */
    private fun handleInboundBinary(peerDeviceId: String, data: ByteArray, reply: (ByteArray) -> Boolean) {
        if (transfer.onInboundFrame(data)) return // sender-side ACK/COMPLETE consumed it
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
                        peerDeviceId?.let { pid ->
                            sendXfer(pid, RealFlashTransferRepository.ACTION_RESUME, frame.transferId)
                        }
                        continue
                    }
                    if (transfer.isResumableInboundRetry(frame.transferId)) {
                        receivePipeline.acceptSession(frame.transferId)
                        transfer.onIncomingStarted(
                            frame.transferId, frame.fileId, frame.fileName, frame.totalBytes,
                            "peer", peerDeviceId, receivedPaths[frame.transferId],
                        )
                        peerDeviceId?.let { pid ->
                            sendXfer(pid, RealFlashTransferRepository.ACTION_RESUME, frame.transferId)
                        }
                        continue
                    }
                    transfer.onIncomingOffered(frame.transferId, frame.fileId, frame.fileName, frame.totalBytes, "peer", peerDeviceId)
                    // Harness policy: auto-accept (the gate's G3 does not test consent UI).
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
                        println("[harness] receiver rejected: ${event.reason} tid=${event.transferId}")
                    }
                }
            }
        }
    }

    /**
     * The accept path, faithful to production's ordering: resolve the deferred sink FIRST, surface
     * Transferring + the started transfer, THEN send the sender a RESUME so its parked dispatcher
     * starts streaming. (Missing the RESUME is the exact bug that would deadlock a compliant
     * sender — `requireReceiverAcceptance` makes it park until one arrives.)
     */
    private fun acceptOffer(transferId: String, peerDeviceId: String?) {
        val meta = incomingMeta[transferId] ?: return
        if (receivePipeline.acceptSession(transferId)) {
            transfer.onIncomingStarted(
                transferId, meta.fileId, meta.fileName, meta.totalBytes,
                "peer", peerDeviceId, receivedPaths[transferId],
            )
            peerDeviceId?.let { pid ->
                sendXfer(pid, RealFlashTransferRepository.ACTION_RESUME, transferId)
            }
        }
    }

    private var sessionJobs = ConcurrentHashMap<WsSession, Job>()

    fun start(): Int {
        val port = runBlocking { (network.start(0) as FlashResult.Success).value }
        val identityFrame = FlashAdvertisedIdentity(
            deviceId = identity.deviceId,
            // NOT identity.friendlyName. `DesktopIdentityStore` defaults every fresh state
            // directory to the hardcoded name "Flash Desktop" (DesktopIdentityStores.kt:55), and
            // JmdnsTransport advertises as "Flash " + friendlyName. So a harness endpoint and the
            // `:desktop` app both claim the mDNS instance name "Flash Flash Desktop" — two
            // advertisers, one name, on one host.
            //
            // That is not cosmetic. JmDNS resolves the conflict by renaming and re-registering,
            // and the collision corrupts the cache: the name then resolves with an SRV from one
            // registration and a TXT from another, and the losing registration carries JmDNS's
            // EMPTY_TXT (`new byte[]{0}`, ByteWrangler.java:43). The reader sees one byte, an
            // empty property map, no device_id — and drops the peer. Observed exactly that:
            // `name=Flash Flash Desktop ... txtKeys=[] txtBytes=1`, for the app's OWN name.
            //
            // It also outlives the app: `advertise` runs `while (true)` until Ctrl-C, so a
            // forgotten harness keeps the name on the network and phones keep listing a desktop
            // that is not running.
            //
            // A harness endpoint is a test artifact and must never be mistakable for the product,
            // so it gets a name the app cannot produce.
            friendlyName = "Harness $name",
            deviceModel = "desktop",
            protocolVersion = 2,
            // Same kind the real desktop advertises — see DesktopInteropHarness.
            capabilities = setOf(FlashDeviceKind.CAP_DESKTOP),
        )
        runBlocking { discovery.startAll(port, identityFrame) }
        DiscoveryRouteBinder.observe(scope, discovery.discoveredEndpoints, network)
        scope.launch {
            while (isActive) {
                discovery.discoveredEndpoints.value.forEach { endpoint ->
                    val id = endpoint.device.id
                    if (network.activeSessions.value[id] == null && !network.isReconnectInFlight(id.value)) {
                        // Logged on attempt and outcome — see the sibling in DesktopInteropHarness.kt
                        // for why a swallowed dial result is a diagnostic dead end.
                        println("[dial] ${endpoint.friendlyName} ${endpoint.hostAddress}:${endpoint.port} ...")
                        val result = runCatching {
                            network.connectManual(endpoint.hostAddress, endpoint.port)
                        }.getOrNull()
                        println("[dial] ${endpoint.friendlyName} success=${result is FlashResult.Success}")
                    }
                }
                delay(5_000)
            }
        }
        scope.launch {
            network.activeSessions.collect { sessions ->
                sessionJobs.keys.filterNot { it in sessions.values }.forEach { stale ->
                    sessionJobs.remove(stale)
                }
                sessions.values.forEach { session ->
                    if (session is WsSession && sessionJobs.containsKey(session).not()) {
                        // Pairing hello on every session-up, as both product hosts do: without it the
                        // peer has no fingerprint and cannot derive the numeric-comparison code.
                        pairing.onSessionUp(session.peerDeviceId.value)
                        sessionJobs[session] = scope.launch {
                            launch {
                                session.incomingBinary.collect { data ->
                                    handleInboundBinary(
                                        session.peerDeviceId.value, data,
                                    ) { bytes -> session.connection.sendBinaryConsuming(bytes) }
                                }
                            }
                            launch {
                                session.incomingText.collect { text ->
                                    // Pairing FIRST, like the product hosts: its own prefix, and a
                                    // pairing line must never reach the transfer repository.
                                    if (com.transfer.flash.core.common.protocol.FlashTextFraming
                                            .parseFields(text, "FLASH_PAIR") != null
                                    ) {
                                        pairing.onInbound(session.peerDeviceId.value, text)
                                        return@collect
                                    }
                                    // XFER control frames — route into the repository.
                                    val fields = com.transfer.flash.core.common.protocol.FlashTextFraming
                                        .parseFields(text, "FLASH_XFER")
                                    if (fields != null) {
                                        val action = fields["action"]
                                        val tid = fields["transferId"]
                                        if (action != null && tid != null) {
                                            transfer.onRemoteTransferControl(tid, action)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return port
    }

    fun stop() {
        runBlocking {
            discovery.stopAll()
            network.stop()
        }
        scope.cancel()
    }
}
