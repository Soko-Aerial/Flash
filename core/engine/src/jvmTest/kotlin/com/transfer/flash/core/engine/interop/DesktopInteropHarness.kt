package com.transfer.flash.core.engine.interop

import com.transfer.flash.core.common.model.FlashDeviceId
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
import com.transfer.flash.core.transfer.multistream.StreamChannel
import com.transfer.flash.core.transfer.model.FlashTransferState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * # Phase 16 — headless desktop↔Android interop harness (DESKTOP HALF)
 *
 * The only code this phase adds, per its charter: a thin `main()` that drives the **public**
 * repository contracts on the desktop JVM with no UI, printing one line per event so a human
 * (or a script) can run this on one end and the Android app / instrumented counterpart on the
 * other. **Never shipped** — this lives in `jvmTest`, which no publication packages.
 *
 * ## What it wires
 *
 * Everything Phase 13–15 delivered, composed exactly the way `androidMain`'s `Wiring` composes
 * it, minus the Android-only pieces (Room DB → `store = null`, so D5=C resume is out of scope
 * for this harness until 09B-2 lands; chat/PTT/calling are not a transfer gate):
 *
 * - Discovery: `JmdnsTransport` (Phase 14) behind `CompositeDiscovery`, speaking the same
 *   `_flash-transfer._tcp` service type and TxtCodec wire format as Android's NSD.
 * - Transport: `JvmWsFlashNetwork` (Phase 15-4) — same `FLASH_WS_HELLO`, protocol version 2,
 *   port 45822.
 * - Transfer: `RealFlashTransferRepository` (commonMain since 13B-3e) with a desktop
 *   `FileSourceOpener` (Okio over `java.io.File`) and stream channels riding the WS session's
 *   binary lane.
 *
 * ## Usage (argv)
 *
 * ```text
 * advertise <friendlyName>        G1: advertise + browse; print peers as they appear
 * discover <seconds>             G1: browse only, print the roster after N seconds
 * send <host> <port> <filePath>  G3: dial a peer directly and push a file (prints SHA-256)
 * receive <outDir> <seconds>     G3: accept inbound offers and complete, print SHA-256
 * ```
 *
 * Every terminal event prints the SHA-256 of the source/received file so the operator compares
 * the two lines. The gate's verdict is recorded in the migration log from the observed output.
 */
public object DesktopInteropHarness {

    public fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            null, "help" -> printUsage()
            "advertise" -> advertise(args.getOrNull(1) ?: "Flash Desktop")
            "discover" -> discover(args.getOrNull(1)?.toLongOrNull() ?: 30_000L)
            "send" -> send(
                host = args.getOrNull(1) ?: error("send needs <host> <port> <filePath>"),
                port = args.getOrNull(2)?.toIntOrNull() ?: error("send needs <host> <port> <filePath>"),
                filePath = args.getOrNull(3) ?: error("send needs <host> <port> <filePath>"),
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
              advertise <friendlyName>       G1: advertise + browse, print discovered peers
              discover <seconds>             G1: browse only, print the roster after N seconds
              send <host> <port> <filePath>  G3: dial a peer and push one file (prints SHA-256)
              receive <outDir> <seconds>    G3: accept inbound offers, complete, print SHA-256
            """.trimIndent(),
        )
    }

    /**
     * Shared composition: every scenario needs discovery + transport + the transfer repository.
     * Teardown is scope cancellation (the repository has no shutdown API by design — its scope
     * is the engine's, and the engine's close cancels that scope).
     */
    private class DesktopEndpoint(name: String) {
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
            streamChannelFactory = { channelId, peerDeviceId ->
                sessionChannel(channelId, peerDeviceId)
            },
            fileSourceOpener = FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) },
            store = null,
            repositoryScope = scope,
            requireReceiverAcceptance = false,
        )

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
            // Auto-dial every discovered peer so inbound G3 offers have a session to ride.
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

    private fun advertise(name: String) {
        val endpoint = DesktopEndpoint(name)
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
        val endpoint = DesktopEndpoint("discover")
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

    private fun send(host: String, port: Int, filePath: String) {
        val file = File(filePath)
        require(file.isFile) { "not a file: $filePath" }
        val endpoint = DesktopEndpoint("send")
        endpoint.start()
        runBlocking {
            println("[send] dialing $host:$port ...")
            val connect = endpoint.network.connectManual(host, port)
            check(connect is FlashResult.Success) { "connect failed: $connect" }
            val session = (connect as FlashResult.Success).value
            println("[send] session up; peer=${session.peer.friendlyName} id=${session.peer.id.value}")

            // A peer may push back at us too — auto-accept any inbound offer.
            val acceptJob = endpoint.scope.launch {
                while (true) {
                    val offered = endpoint.transfer.activeTransfers.value
                        .firstOrNull { it.state == FlashTransferState.Offered }
                    if (offered != null) {
                        endpoint.transfer.acceptIncoming(offered.id)
                        println("[send] accepted inbound offer ${offered.id.value}")
                    }
                    delay(250)
                }
            }

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
            acceptJob.cancel()
        }
        endpoint.stop()
    }

    private fun receive(outDir: String, seconds: Long) {
        val endpoint = DesktopEndpoint("receive")
        val port = endpoint.start()
        println("[receive] deviceId=${endpoint.identity.deviceId.value} wsPort=$port outDir=$outDir — waiting ${seconds / 1000}s")

        runBlocking {
            runCatching {
                withTimeout(seconds) {
                    while (true) {
                        val offered = endpoint.transfer.activeTransfers.value
                            .firstOrNull { it.state == FlashTransferState.Offered }
                        if (offered != null) {
                            endpoint.transfer.acceptIncoming(offered.id)
                            println("[receive] accepted offer ${offered.id.value}")
                        }
                        delay(250)
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
        }
        val receivedRoot = File(outDir)
        receivedRoot.walkTopDown().filter { it.isFile }.forEach { f ->
            println("[sha256 received] ${sha256(f)}  ${f.name}")
        }
        endpoint.stop()
    }

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
