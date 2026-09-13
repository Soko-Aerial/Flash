@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.interop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import com.transfer.flash.core.discovery.jmdns.JmdnsTransport
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.ws.JvmWsFlashNetwork
import com.transfer.flash.core.network.ws.WsSession
import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.RejectReason
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.policy.OkioRandomAccessSinkHandle
import com.transfer.flash.core.transfer.policy.RandomAccessChunkSink
import com.transfer.flash.core.transfer.policy.RandomAccessSinkHandle
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * # Phase 16 — headless desktop↔Android interop harness (DESKTOP HALF)
 *
 * The only code this phase adds, per its charter: a thin `main()` that drives the **public**
 * repository contracts on the desktop JVM with no UI, printing one line per event so a human
 * (or a script) can run this on one end and the Android app on the other. **Never shipped** —
 * this lives in `jvmTest`, which no publication packages.
 *
 * ## What it wires (the FULL Phase 16 wiring, receive half included)
 *
 * Everything Phase 13–15 delivered, composed exactly the way `androidMain`'s `Wiring` composes
 * its transfer half, minus the Android-only pieces (Room DB → `store = null`, so D5=C resume is
 * out of scope for this harness until 09B-2 lands; chat/PTT/calling/pairing are not transfer-gate
 * concerns):
 *
 * - Discovery: `JmdnsTransport` (Phase 14) behind `CompositeDiscovery`, speaking the same
 *   `_flash-transfer._tcp` service type and TxtCodec wire format as Android's NSD.
 * - Transport: `JvmWsFlashNetwork` (Phase 15-4) — same `FLASH_WS_HELLO`, protocol version 2.
 * - Transfer: `RealFlashTransferRepository` (commonMain since 13B-3e) with a desktop
 *   `FileSourceOpener` (Okio over `java.io.File`) and stream channels riding the WS session's
 *   binary lane.
 * - **Receive: `ReceivePipeline` with the #5 accept gate** (`requireAcceptance` + deferred
 *   sinkFactory + RESUME-to-start) and the path-containment Sentinel — the same wiring the
 *   self-test fixture (`DesktopEndpointFixture`) proves and the shipped `:desktop` engine uses.
 *   Inbound FILE_START/OFFER/ACK/COMPLETE frames from an Android peer are consumed, printed,
 *   and auto-accepted, with the completed file's SHA-256 printed for the gate's byte checks.
 *
 * ## Usage (argv) — run via the `interopHarness` Gradle task, or with program args from an IDE
 *
 * ```text
 * discover [seconds]                G1: browse only, print the roster after N seconds (default 30)
 * advertise [name]                  G1: advertise + browse + accept inbound; Ctrl-C to stop
 * send <host> <port> <filePath>     G3/G4: dial a peer and push a file (prints SHA-256 of source)
 * receive [outDir] [seconds]        G3/G4: accept inbound offers and complete, print SHA-256
 * cancel <host> <port> <filePath>   G5: push a file then cancel it mid-flight
 * ```
 *
 * Every terminal event prints the SHA-256 of the source/received file so the operator compares
 * the two lines. The gate's verdict is recorded in the migration log from the observed output.
 * Pairing (G2/G6) is NOT wired on desktop — see `research/desktop-pairing-gap-scoping.md` (P pick).
 */
public object DesktopInteropHarness {

    public fun run(args: Array<String>) {
        when (args.firstOrNull()) {
            null, "help" -> printUsage()
            "advertise" -> advertise(args.getOrNull(1) ?: "Flash Desktop")
            "discover" -> discover(args.getOrNull(1)?.toLongOrNull() ?: 30_000L)
            "send" -> send(
                host = args.getOrNull(1) ?: error("send needs <host> <port> <filePath>"),
                port = args.getOrNull(2)?.toIntOrNull() ?: error("send needs <host> <port> <filePath>"),
                filePath = args.getOrNull(3) ?: error("send needs <host> <port> <filePath>"),
                cancelAfterMs = null,
            )
            "cancel" -> cancel(
                host = args.getOrNull(1) ?: error("cancel needs <host> <port> <filePath> [afterMs]"),
                port = args.getOrNull(2)?.toIntOrNull() ?: error("cancel needs <host> <port> <filePath> [afterMs]"),
                filePath = args.getOrNull(3) ?: error("cancel needs <host> <port> <filePath> [afterMs]"),
                afterMs = args.getOrNull(4)?.toLongOrNull() ?: 3_000L,
            )
            "receive" -> receive(
                outDir = args.getOrNull(1) ?: "flash-received",
                seconds = args.getOrNull(2)?.toLongOrNull() ?: 120_000L,
            )
            else -> printUsage()
        }
    }

    private fun printUsage() {
        println(
            """
            Flash Phase 16 desktop interop harness
            usage:
              discover [seconds]               G1: browse only, print the roster after N seconds
              advertise [name]                 G1: advertise + browse + accept inbound; Ctrl-C to stop
              send <host> <port> <filePath>    G3/G4: dial a peer and push one file (prints SHA-256)
              cancel <host> <port> <filePath> [afterMs]  G5: push then cancel mid-flight
              receive [outDir] [seconds]       G3/G4: accept inbound offers, complete, print SHA-256
            """.trimIndent(),
        )
    }

    /**
     * The full endpoint: transfer send + receive (pipeline + accept gate), discovery, auto-dial.
     * Same composition as `DesktopEndpointFixture`, parameterised for the interactive verbs.
     */
    private class DesktopEndpoint(name: String, receivedRoot: File) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stateDir = File(System.getProperty("java.io.tmpdir"), "flash-interop-$name").apply { mkdirs() }
        val identity = DesktopIdentityStore(stateDir).getIdentity()
        val network = JvmWsFlashNetwork(
            localDeviceId = identity.deviceId.value,
            localFriendlyName = identity.friendlyName,
        )
        val discovery = CompositeDiscovery(
            transports = listOf(
                JmdnsTransport(
                    directory = StandardEndpointDirectory(),
                    sweep = { _ -> emptyList() },
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

        private val canonicalRoot = receivedRoot.canonicalFile.apply { mkdirs() }
        private val openHandles = ConcurrentHashMap<String, RandomAccessSinkHandle>()
        private val incomingMeta = ConcurrentHashMap<String, ChunkFrame.FileStart>()
        private val receivedPaths = ConcurrentHashMap<String, String>()
        private var sessionJobs = ConcurrentHashMap<WsSession, Job>()

        /** The #5 accept gate, same shape as production's wiring and the self-test fixture. */
        val receivePipeline = ReceivePipeline(
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

        fun start(): Int {
            val port = runBlocking { (network.start(0) as FlashResult.Success).value }
            val identityFrame = FlashAdvertisedIdentity(
                deviceId = identity.deviceId,
                friendlyName = identity.friendlyName,
                deviceModel = "desktop",
                protocolVersion = 2,
            )
            runBlocking {
                discovery.startAll(port, identityFrame)
            }
            DiscoveryRouteBinder.observe(scope, discovery.discoveredEndpoints, network)
            // Auto-dial every discovered peer so inbound offers have a session to ride.
            scope.launch {
                while (isActive) {
                    discovery.discoveredEndpoints.value.forEach { endpoint ->
                        val id = endpoint.device.id
                        if (network.activeSessions.value[id] == null && !network.isReconnectInFlight(id.value)) {
                            runCatching { network.connectManual(endpoint.hostAddress, endpoint.port) }
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
                                        val fields = FlashTextFraming.parseFields(text, "FLASH_XFER")
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

        /**
         * Inbound binary routing — the desktop port of `Flash.kt`'s `handleInboundBinary`:
         * sender-side ACK/COMPLETE first, then the receive pipeline's events, incl. the
         * already-completed short-circuit and the resumable-retry auto-accept. Harness policy:
         * auto-accept every inbound offer (the gate's G3 does not test consent UI).
         */
        private fun handleInboundBinary(peerDeviceId: String, data: ByteArray, reply: (ByteArray) -> Boolean) {
            if (transfer.onInboundFrame(data)) return
            for (event in receivePipeline.onFrame(data)) {
                when (event) {
                    is ReceiveEvent.SessionStarted -> {
                        val frame = event.frame
                        incomingMeta[frame.transferId] = frame
                        println("[offer] inbound ${frame.fileName} (${frame.totalBytes} bytes) from $peerDeviceId")
                        val existing = transfer.activeTransfers.value.firstOrNull { it.id.value == frame.transferId }
                        val existingPath = receivedPaths[frame.transferId] ?: existing?.localPath
                        val alreadyCompleted =
                            (existing != null && existing.state == FlashTransferState.Completed) ||
                                (existingPath != null && File(existingPath).let { it.isFile && it.length() == frame.totalBytes })
                        if (alreadyCompleted) {
                            println("[offer] already complete — replying COMPLETE")
                            reply(ChunkFrame.serialize(ChunkFrame.Complete(frame.transferId, frame.fileId, verified = true)))
                            sendXfer(peerDeviceId, RealFlashTransferRepository.ACTION_RESUME, frame.transferId)
                            continue
                        }
                        if (transfer.isResumableInboundRetry(frame.transferId)) {
                            acceptOffer(frame.transferId, peerDeviceId)
                            continue
                        }
                        transfer.onIncomingOffered(frame.transferId, frame.fileId, frame.fileName, frame.totalBytes, "peer", peerDeviceId)
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
                        println("[progress] 100% ($transferId ${if (event.frame.verified) "verified" else "UNVERIFIED"})")
                        if (path != null) {
                            println("[sha256 received] ${sha256(File(path))}  ${File(path).name}")
                        }
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

        /** Accept path with production's ordering: sink first, started, THEN RESUME. */
        private fun acceptOffer(transferId: String, peerDeviceId: String) {
            val meta = incomingMeta[transferId] ?: return
            if (receivePipeline.acceptSession(transferId)) {
                transfer.onIncomingStarted(
                    transferId, meta.fileId, meta.fileName, meta.totalBytes,
                    "peer", peerDeviceId, receivedPaths[transferId],
                )
                sendXfer(peerDeviceId, RealFlashTransferRepository.ACTION_RESUME, transferId)
            }
        }

        private fun sendXfer(peerId: String, action: String, transferId: String) {
            val session = network.activeSessions.value[FlashDeviceId(peerId)] as? WsSession ?: return
            session.connection.sendText(
                FlashTextFraming.encodeFields(
                    "FLASH_XFER",
                    listOf("action" to action, "transferId" to transferId),
                ),
            )
        }

        /** One stream channel per channel id: rides an existing session with the peer. */
        private suspend fun sessionChannel(channelId: Int, peerDeviceId: String?): StreamChannel? {
            val session = peerDeviceId
                ?.let { network.activeSessions.value[FlashDeviceId(it)] }
                ?: network.activeSessions.value.values.firstOrNull()
            if (session == null) {
                println("[harness] channel $channelId: no live session (${peerDeviceId ?: "any"}) - cannot open stream")
                return null
            }
            return object : StreamChannel {
                override val id: Int = channelId
                override suspend fun sendFrame(frameBytes: ByteArray): Boolean =
                    runCatching { session.send(frameBytes) is FlashResult.Success }.getOrDefault(false)
            }
        }

        fun stop() {
            runBlocking {
                discovery.stopAll()
                network.stop()
            }
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------
    // Verbs
    // ------------------------------------------------------------------

    private fun advertise(name: String) {
        val outDir = File("flash-received").apply { mkdirs() }
        val endpoint = DesktopEndpoint(name, outDir)
        val port = endpoint.start()
        println("[advertise] deviceId=${endpoint.identity.deviceId.value} name=${endpoint.identity.friendlyName} wsPort=$port")
        println("[advertise] waiting for peers — Ctrl-C to stop")
        runBlocking {
            var seen = 0
            while (true) {
                val peers = endpoint.discovery.discoveredEndpoints.value
                if (peers.size > seen) {
                    peers.drop(seen).forEach { p ->
                        println("[peer] id=${p.device.id.value} name=${p.device.friendlyName} addr=${p.hostAddress}:${p.port}")
                    }
                    seen = peers.size
                }
                delay(1_000)
            }
        }
    }

    private fun discover(seconds: Long) {
        val outDir = File("flash-received").apply { mkdirs() }
        val endpoint = DesktopEndpoint("discover", outDir)
        endpoint.start()
        runBlocking {
            runCatching {
                withTimeout(seconds) {
                    endpoint.discovery.discoveredEndpoints.first { it.isNotEmpty() }
                }
            }
            endpoint.discovery.discoveredEndpoints.value.forEach { p ->
                println("[peer] id=${p.device.id.value} name=${p.device.friendlyName} addr=${p.hostAddress}:${p.port}")
            }
        }
        endpoint.stop()
    }

    private fun send(host: String, port: Int, filePath: String, cancelAfterMs: Long?) {
        val file = File(filePath)
        require(file.isFile) { "not a file: $filePath" }
        val outDir = File("flash-received").apply { mkdirs() }
        val endpoint = DesktopEndpoint("send", outDir)
        endpoint.start()
        runBlocking {
            println("[send] dialing $host:$port ...")
            val connect = endpoint.network.connectManual(host, port)
            check(connect is FlashResult.Success) { "connect failed: $connect" }
            val session = (connect as FlashResult.Success).value
            println("[send] session up; peer=${session.peer.friendlyName} id=${session.peer.id.value}")

            val peerDevice = session.peer
            val result = endpoint.transfer.sendFile(
                targetDevice = peerDevice,
                fileUri = file.absolutePath,
                displayName = file.name,
                fileSize = file.length(),
            )
            println("[send] sendFile result=$result")
            val id = (result as? FlashResult.Success)?.value
            if (id != null) {
                runCatching {
                    withTimeout(600_000) {
                        while (true) {
                            val t = endpoint.transfer.activeTransfers.value.firstOrNull { it.id == id }
                            if (t != null) {
                                println("[progress] ${t.bytesDone}/${t.bytesTotal} (${t.state})")
                                if (cancelAfterMs != null && t.bytesDone > 0L && t.bytesDone < t.bytesTotal) {
                                    // G5: cancel mid-flight once chunks are moving.
                                    println("[cancel] cancelling ${t.id.value} at ${t.bytesDone}/${t.bytesTotal}")
                                    endpoint.transfer.cancelTransfer(t.id)
                                }
                                if (t.state == FlashTransferState.Completed ||
                                    t.state == FlashTransferState.Failed ||
                                    t.state == FlashTransferState.Cancelled
                                ) break
                            }
                            delay(1_000)
                        }
                    }
                }
            }
            println("[sha256 source] ${sha256(file)}  ${file.name}")
            // Keep the endpoint alive briefly so a peer's terminal frames land before exit.
            delay(2_000)
        }
        endpoint.stop()
    }

    private fun cancel(host: String, port: Int, filePath: String, afterMs: Long) {
        send(host, port, filePath, cancelAfterMs = afterMs)
    }

    private fun receive(outDir: String, seconds: Long) {
        val root = File(outDir).apply { mkdirs() }
        val endpoint = DesktopEndpoint("receive", root)
        val port = endpoint.start()
        println("[receive] deviceId=${endpoint.identity.deviceId.value} wsPort=$port outDir=$outDir — waiting ${seconds / 1000}s")

        runBlocking {
            runCatching {
                withTimeout(seconds) {
                    while (endpoint.transfer.activeTransfers.value.any { it.state == FlashTransferState.Transferring }) {
                        delay(500)
                    }
                }
            }
            // Grace period for in-flight transfers to reach a terminal state.
            runCatching {
                withTimeout(60_000) {
                    while (endpoint.transfer.activeTransfers.value.any {
                            it.state != FlashTransferState.Completed &&
                                it.state != FlashTransferState.Failed &&
                                it.state != FlashTransferState.Cancelled
                        }) {
                        delay(500)
                    }
                }
            }
            delay(2_000)
        }
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            println("[sha256 received] ${sha256(f)}  ${f.name}")
        }
        endpoint.stop()
    }

    private fun sanitize(component: String): String =
        component.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * The JVM entry point. Top-level (NOT inside the object) so the JVM finds a public
 * zero-argument constructor in the generated `DesktopInteropHarnessKt` facade class — a
 * Kotlin `object` has only a private constructor, which `JavaExec` cannot invoke.
 */
public fun main(args: Array<String>) {
    DesktopInteropHarness.run(args)
}
