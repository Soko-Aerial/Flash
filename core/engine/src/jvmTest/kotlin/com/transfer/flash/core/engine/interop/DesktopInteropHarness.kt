@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.interop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import com.transfer.flash.core.discovery.jmdns.JmdnsTransport
import com.transfer.flash.core.discovery.multicast.JvmMulticastSocketFactory
import com.transfer.flash.core.discovery.multicast.MulticastTransport
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.tls.FlashCertMaker
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.TofuPinVerifier
import com.transfer.flash.core.network.ws.JvmWsFlashNetwork
import com.transfer.flash.core.network.ws.WsSession
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashFingerprint
import com.transfer.flash.core.security.crypto.PersistedFlashCrypto
import com.transfer.flash.core.security.pairing.FlashPairingCoordinator
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
import kotlin.system.exitProcess
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
 *
 * **Pairing is wired as of 2026-09-14** (`pair`), so ladder steps L2/L3 are testable from this CLI.
 * It drives the same `FlashPairingCoordinator` `:desktop:run` uses — see [HarnessPairingConsole] for
 * why it must, and note that the trust decision stays with the operator: the console prints the
 * numeric-comparison code and waits for an explicit `y`.
 */
public object DesktopInteropHarness {

    public fun run(args: Array<String>) {
        when (args.firstOrNull()) {
            null, "help" -> printUsage()
            "advertise" -> advertise(args.getOrNull(1) ?: "Flash Desktop")
            "discover" -> discover(secondsArg(args.getOrNull(1), 30L))
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
                seconds = secondsArg(args.getOrNull(2), 120L),
            )
            // `pair [--accept] [<host> <port>] [seconds]`. The `--accept` flag is dropped first so
            // the positional parsing below cannot mistake it for a host.
            //
            // Two shape rules: a first argument that is not a number is a host, and the window goes
            // through `secondsArg` like every other verb — it is SECONDS on the command line and
            // MILLISECONDS everywhere below, and skipping that conversion gave this verb a 0 ms
            // window (caught in its first live run, 2026-09-14).
            "pair" -> {
                val pairArgs = args.drop(1).filterNot { it == "--accept" }
                pair(
                    host = pairArgs.getOrNull(0)?.takeIf { it.toLongOrNull() == null },
                    port = pairArgs.getOrNull(1)?.toIntOrNull(),
                    seconds = secondsArg(
                        pairArgs.lastOrNull()?.takeIf { it.toLongOrNull() != null },
                        120L,
                    ),
                    autoAccept = args.any { it == "--accept" },
                )
            }
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
              pair [seconds]                   L2: answer a peer's Pair tap (prints the 6-digit code)
              pair <host> <port> [seconds]     L3: dial a peer and start pairing from this side
              pair [--accept] [...]            ...with no prompt. ONLY if typing is impossible (a
                                               Gradle-daemon stdin that is not forwarded): it still
                                               prints the code, and comparing it with the peer's
                                               screen is still yours to do.

            Pairing always prints the numeric-comparison code and waits for an explicit 'y' at the
            prompt — read it aloud against the peer's screen before accepting. Every verb answers
            pairing frames at the protocol level, but only `pair` asks you anything.
            """.trimIndent(),
        )
    }

    /**
     * The full endpoint: transfer send + receive (pipeline + accept gate), discovery, auto-dial.
     * Same composition as `DesktopEndpointFixture`, parameterised for the interactive verbs.
     */
    private class DesktopEndpoint(
        private val name: String,
        receivedRoot: File,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val stateDir = File(System.getProperty("java.io.tmpdir"), "flash-interop-$name").apply { mkdirs() }
        val identity = DesktopIdentityStore(stateDir).getIdentity()
        private val trustStore = DesktopTrustStore(stateDir)

        /** Declared before [network]: its TLS identity IS this key (property init runs top to bottom). */
        private val crypto: FlashCrypto = PersistedFlashCrypto(stateDir)

        /**
         * The same TLS construction `DesktopEngine` uses (audit S3/S1, ADR-042): the harness used to run
         * plaintext, which production can no longer do, and a gate that exercises a different transport
         * from the product proves nothing about it. Pairing v2 binds to the pins recorded here.
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
            tlsOptions = tlsOptions,
            // "Harness $name", NOT identity.friendlyName — the same rule the discovery frame below
            // follows, for the same reason, and it was being broken here. The persisted desktop
            // identity's name is "Flash Desktop", so the WS hello announced the harness to the peer
            // as the PRODUCT while its mDNS advertisement said "Harness discover". Observed
            // 2026-09-14: the phone's roster row read "Harness discover" (id 3bd5ed8f…) while the
            // session for the same id reported name="Flash Desktop", which made a pairing log read
            // like two devices.
            localFriendlyName = "Harness $name",
        )
        val discovery = CompositeDiscovery(
            transports = listOf(
                JmdnsTransport(
                    directory = StandardEndpointDirectory(),
                    sweep = { _ -> emptyList() },
                ),
                // The same additive second transport the product hosts now run (see
                // `DiscoveryEngineHolder` / `DesktopEngine`). Without it here, `discover` measures
                // only the DNS-SD path — and the DNS-SD path is precisely the one that cannot
                // describe this phone (hollow TXT: a correct address and no device id), so the verb
                // reported "0 distinct peers" against a device that was up and reachable the whole
                // time. A harness that cannot see what the product can see is not a gate.
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
         * Pairing (G2/G6), wired 2026-09-14 so the ladder's L2/L3 are testable from this CLI.
         *
         * The SAME [FlashPairingCoordinator] `:desktop:run` uses — not a harness-only responder —
         * because the harness is the gate for the hardware ladder, and a gate that exercises
         * different code from the product proves nothing about the product. Same durable crypto
         * ([PersistedFlashCrypto], keyed off this verb's state dir) so the fingerprint the phone
         * displays survives a restart, which is what L6 checks. (`crypto` is declared above, next to
         * the TLS options that use the same key.)
         */
        val pairing: FlashPairingCoordinator = FlashPairingCoordinator(
            localFingerprintHex = FlashFingerprint.formatHexGroups(
                FlashFingerprint.fingerprint(crypto.identityPublicKeyEncoded),
            ),
            localDeviceId = identity.deviceId.value,
            // "Harness $name" — must match the advertisement below, or the peer's roster row and its
            // session disagree about who it is talking to (observed 2026-09-14).
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

        /** Console front-end; [interactive] is true only for the `pair` verb (see the class KDoc). */
        fun startPairingConsole(interactive: Boolean, autoAccept: Boolean = false) {
            HarnessPairingConsole(pairing, scope, interactive, autoAccept).start()
        }

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
                // NOT identity.friendlyName — see the sibling fixture in HarnessTestSupport.kt.
                // This class is the one `advertise`/`discover`/`send`/`receive` actually use, so a
                // harness endpoint that advertises the app's name makes the harness
                // indistinguishable from the product on the wire.
                friendlyName = "Harness $name",
                deviceModel = "desktop",
                protocolVersion = 2,
                // Advertise the kind like the real desktop does, so a hardware run's Nearby row
                // shows "PC" for the harness too. Test artifacts stay distinguishable by name; the
                // KIND is not a distinguishing property and should match the product.
                capabilities = setOf(FlashDeviceKind.CAP_DESKTOP),
            )
            // This result was silently discarded, which made the harness a POOR oracle for the
            // product in the one way that mattered: `DesktopEngine.assemble()` used to `require` it,
            // so a single failing transport aborted the product's whole session machinery while the
            // harness sailed on — "pairing works in the harness but not in the app". Now that both
            // proceed, the harness must still SAY what startAll reported, or a half-started
            // discovery looks identical to a healthy one on the console.
            val startedAll = runBlocking {
                discovery.startAll(port, identityFrame)
            }
            (startedAll as? FlashResult.Failure)?.let {
                println("[discovery] startAll PARTIAL FAILURE — ${it.error}")
            }
            DiscoveryRouteBinder.observe(scope, discovery.discoveredEndpoints, network)
            // Auto-dial every discovered peer so inbound offers have a session to ride.
            //
            // Logged on the attempt AND on the outcome, like the product's sweep. It used to be a
            // bare `runCatching`: the dial's failure was swallowed, so a run could show a peer in the
            // roster with no way to tell "we never dialed it" from "we dialed and it refused" — the
            // same silence that made a live pairing failure take an evening to localise.
            scope.launch {
                while (isActive) {
                    discovery.discoveredEndpoints.value.forEach { endpoint ->
                        val id = endpoint.device.id
                        if (network.activeSessions.value[id] == null && !network.isReconnectInFlight(id.value)) {
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
                        if (session is WsSession && !sessionJobs.containsKey(session)) {
                            // Pairing hello on every session-up, exactly like both product hosts: the
                            // hello carries our identity fingerprint, and without it the peer cannot
                            // derive the numeric-comparison code when its user taps Pair.
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
                                        // Pairing FIRST, like the product hosts: its prefix is its
                                        // own, and a pairing line must never reach the transfer repo.
                                        if (FlashTextFraming.parseFields(text, "FLASH_PAIR") != null) {
                                            pairing.onInbound(session.peerDeviceId.value, text)
                                            return@collect
                                        }
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
        // Plain ASCII: an em dash renders as mojibake in a cp1252 Windows console.
        println("[discover] watching ${seconds / 1_000}s - peers print as they appear; Ctrl-C to stop early")
        runBlocking {
            // Stream for the WHOLE window, and print each peer the first time it is seen.
            //
            // The previous shape was `discoveredEndpoints.first { it.isNotEmpty() }`, which
            // RETURNED at the first non-empty roster — so a verb whose whole purpose is "who is on
            // this network, and do they stay there" printed one line and quit the instant it saw
            // anything. That also hid the failure mode this gate exists to catch: a peer that
            // appears and then vanishes, or one whose records are dropped. It now observes for the
            // full window like `advertise` does.
            // `seconds` is already MILLISECONDS — `run()` passes it through `secondsArg`, which
            // multiplies by 1_000; the old code handed it straight to `withTimeout`. Kept the
            // parameter name to avoid churn, but do not scale it again.
            val deadline = System.currentTimeMillis() + seconds
            val seen = LinkedHashMap<String, String>()
            while (System.currentTimeMillis() < deadline) {
                endpoint.discovery.discoveredEndpoints.value.forEach { p ->
                    val id = p.device.id.value
                    if (seen.put(id, p.device.friendlyName) == null) {
                        println("[peer] id=$id name=${p.device.friendlyName} addr=${p.hostAddress}:${p.port}")
                    }
                }
                delay(500)
            }
            println("[discover] window closed; ${seen.size} distinct peer(s): ${seen.values.toList()}")
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

    /**
     * Ladder L2 (responder: the phone taps Pair) and L3 (initiator: this side taps Pair).
     *
     * Which one it runs depends on whether a host was given: `pair [seconds]` listens, `pair <host>
     * <port> [seconds]` dials first. Both then wait for the window, printing the code and prompting.
     *
     * The prompt is the point of the whole design, so it is never skipped: accept requires a literal
     * `y` from the operator, and end-of-input counts as a decline. An unattended run cannot trust a
     * peer — which is also why this is the ONLY verb that touches stdin (see [HarnessPairingConsole]).
     */
    private fun pair(host: String?, port: Int?, seconds: Long, autoAccept: Boolean = false) {
        // Distinct state dirs (hence distinct identities) per role: two harness processes must never
        // share a device id — it is the multicast self-filter AND the session key, so one identity
        // wearing both roles would filter itself out of discovery and confuse glare resolution. It
        // also keeps their mDNS instance names distinct.
        val endpoint = DesktopEndpoint(if (host == null) "pair" else "pair-init", File("flash-received").apply { mkdirs() })
        val wsPort = endpoint.start()
        endpoint.startPairingConsole(interactive = true, autoAccept = autoAccept)
        println(
            "[pair] deviceId=${endpoint.identity.deviceId.value} wsPort=$wsPort window=${seconds / 1000}s " +
                "mode=${if (host == null) "responder — tap Pair on the peer" else "initiator"}" +
                if (autoAccept) "  [--accept: no prompt, comparison is on you]" else "  [will prompt for 'y']"
        )

        runBlocking {
            // The harness's trust store is FILE-BACKED (that is deliberate — it is how L6's
            // "pairing survives a restart" is checkable), so a previous run leaves peers trusted.
            // Waiting for "any trusted peer" would therefore print PAIRED instantly on the second
            // run without doing anything; wait for a peer that is trusted *now and was not before*.
            val alreadyTrusted = endpoint.pairing.trustedPeers.value.associateBy { it.id }
            if (alreadyTrusted.isNotEmpty()) {
                println(
                    "[pair] already trusted from an earlier run: " +
                        alreadyTrusted.values.joinToString { it.name } +
                        "  (this is durable trust — delete ${endpoint.stateDir} to start clean)",
                )
            }

            if (host != null) {
                val targetPort = port ?: error("pair <host> <port> [seconds] — port is required")
                println("[pair] dialing $host:$targetPort ...")
                val connect = endpoint.network.connectManual(host, targetPort)
                check(connect is FlashResult.Success) { "connect failed: $connect" }
                val session = (connect as FlashResult.Success).value
                println("[pair] session up; peer=${session.peer.friendlyName} id=${session.peer.id.value}")
                // The dial itself does NOT send our hello — `onSessionUp` does, from the session
                // collector, exactly as both product hosts do it. beginPair then races that hello,
                // which is the case the coordinator's pending-fingerprint path exists for.
                endpoint.pairing.beginPair(session.peer.id.value, session.peer.friendlyName) { message ->
                    println("[pair] $message")
                }
            }
            // Wait out the window, but stop early once NEW trust is established: a paired run should
            // not sit for two minutes pretending to still be working.
            val deadline = System.currentTimeMillis() + seconds
            while (System.currentTimeMillis() < deadline) {
                val newly = endpoint.pairing.trustedPeers.value.filterNot { it.id in alreadyTrusted }
                if (newly.isNotEmpty()) {
                    println("[pair] PAIRED — ${newly.joinToString { "${it.name} (${it.id})" }}")
                    break
                }
                delay(500)
            }
            delay(1_000)
        }
        endpoint.stop()
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

    /**
     * Duration args are SECONDS (the usage text's unit), converted to ms here. The first live
     * run passed "30" and got a 30 ms window — read as milliseconds, the verb exited before
     * mDNS could resolve anything.
     */
    private fun secondsArg(raw: String?, defaultSeconds: Long): Long =
        (raw?.toLongOrNull() ?: defaultSeconds) * 1_000L

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
    // Finite verbs (discover/send/cancel/receive) return here. `advertise` deliberately never does.
    //
    // Forcing the exit is deliberate, not laziness. `DesktopEndpoint.stop()` closes discovery and
    // the network and cancels its scope, but JmDNS spawns its OWN non-daemon threads, so the JVM
    // can outlive the verb and keep ADVERTISING. Observed 2026-09-13: a leftover `discover` process
    // left the phone listing a `Harness discover` peer that no running app owned — which reads
    // exactly like a product bug, and which cost a long detour when the same symptom appeared
    // earlier from an equally stale advertiser.
    //
    // A headless gate tool must not keep a service on the network after it has printed its result:
    // the peer roster it reports is supposed to describe OTHER devices, never itself.
    exitProcess(0)
}
