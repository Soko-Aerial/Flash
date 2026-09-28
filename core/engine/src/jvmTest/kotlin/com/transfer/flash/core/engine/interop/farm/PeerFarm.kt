@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.interop.farm

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashDeviceKind
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import com.transfer.flash.core.discovery.jmdns.JmdnsTransport
import com.transfer.flash.core.discovery.multicast.JvmMulticastSocketFactory
import com.transfer.flash.core.discovery.multicast.MulticastTransport
import com.transfer.flash.core.engine.interop.DesktopIdentityStore
import com.transfer.flash.core.engine.interop.DesktopTrustStore
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.tls.FlashCertMaker
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.TofuPinVerifier
import com.transfer.flash.core.network.ws.JvmWsFlashNetwork
import com.transfer.flash.core.network.ws.WsSession
import com.transfer.flash.core.security.crypto.PersistedFlashCrypto
import java.io.File
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * # PC0 peer farm: N headless Flash peers in one JVM
 *
 * `docs/network/PRESENCE-CONNECTIONS-PLAN.md` PC0 needs a phone to hold 1…19 idle sessions without 19
 * phones. Each farm peer is a real Flash endpoint: its own persisted identity and TLS key (under
 * `--state`, so a phone's TOFU pin for "Farm 03" stays valid across runs), the same TLS, WebSocket
 * keepalive and discovery transports (JmDNS + the `224.0.0.168` beacon) as `DesktopInteropHarness`.
 * Chat, transfers and pairing are not wired: PC0 measures idle sessions, and a farm peer that
 * answered pairing would pollute the phone's trust store. Inbound frames are drained and counted
 * (the session channels are bounded and `trySendBlocking`, so an undrained session stalls its reader).
 *
 * **Farm peers never dial each other.** Every host caps live sessions at
 * `SessionHardeningPolicy.DEFAULT_MAX_CONCURRENT_SESSIONS` (8), so a farm that meshed with itself would
 * fill its own slots and refuse the phone. With `--dial=external` (default) each peer dials every
 * discovered non-farm device every 5 s, as the product's auto-connect sweep does; with `--dial=none`
 * it only accepts.
 *
 * Output: a status line every `--report` seconds, a `[recover]` line when an external device gets all
 * its farm sessions back after losing some (the Wi-Fi-toggle measurement), and a CSV of every session
 * up/down event (written as it happens, so killing the JVM loses nothing).
 *
 * Never shipped (jvmTest). Run: `./gradlew :core:engine:peerFarm --args="--count=4 --minutes=60"`.
 */
public object PeerFarm {

    public enum class DialPolicy { EXTERNAL, NONE }

    public data class Options(
        val count: Int = 4,
        /** 0 = run until the process is killed. */
        val minutes: Long = 0,
        val tier: FlashPerformanceMode = FlashPerformanceMode.HIGH,
        val dial: DialPolicy = DialPolicy.EXTERNAL,
        val reportSeconds: Long = 60,
        val stateRoot: File = File(System.getProperty("user.home", "."), ".flash-peer-farm"),
        val csv: File? = null,
        /** false only in tests: skips mDNS/multicast so a loopback test cannot see the LAN. */
        val discovery: Boolean = true,
    ) {
        init {
            require(count in 1..MAX_PEERS) { "--count must be 1..$MAX_PEERS" }
            require(minutes >= 0) { "--minutes must be >= 0" }
            require(reportSeconds >= 1) { "--report must be >= 1" }
        }
    }

    public const val MAX_PEERS: Int = 32
    private const val DIAL_SWEEP_MS = 5_000L

    public fun parse(args: Array<String>): Options {
        var o = Options()
        for (arg in args) {
            val (key, value) = arg.removePrefix("--").split("=", limit = 2).let { it[0] to it.getOrNull(1) }
            fun v(): String = value ?: error("--$key needs a value (--$key=…)")
            o = when (key) {
                "count" -> o.copy(count = v().toInt())
                "minutes" -> o.copy(minutes = v().toLong())
                "tier" -> o.copy(tier = FlashPerformanceMode.valueOf(v().uppercase()))
                "dial" -> o.copy(dial = DialPolicy.valueOf(v().uppercase()))
                "report" -> o.copy(reportSeconds = v().toLong())
                "state" -> o.copy(stateRoot = File(v()))
                "csv" -> o.copy(csv = File(v()))
                "help" -> { printUsage(); kotlin.system.exitProcess(0) }
                else -> error("unknown option --$key (try --help)")
            }
        }
        return o
    }

    private fun printUsage() {
        println(
            """
            Flash PC0 peer farm: N headless peers that a phone connects to.
              --count=N        peers to run (1..$MAX_PEERS, default 4)
              --minutes=M      stop after M minutes (default 0 = until killed)
              --tier=T         keepalive tier LOW|MEDIUM|HIGH (default HIGH, the desktop's)
              --dial=D         EXTERNAL (dial every non-farm device, like the product) | NONE (accept only)
              --report=S       status line every S seconds (default 60)
              --state=DIR      identity root, one sub-dir per peer (default ~/.flash-peer-farm)
              --csv=FILE       session event log (default <state>/runs/<timestamp>.csv)
            """.trimIndent(),
        )
    }

    /** Starts the farm and blocks until `--minutes` elapse (or forever). */
    public fun run(options: Options) {
        val farm = Farm(options)
        Runtime.getRuntime().addShutdownHook(Thread { farm.stop() })
        farm.start()
        runBlocking {
            val endAt = if (options.minutes == 0L) Long.MAX_VALUE else System.currentTimeMillis() + options.minutes * 60_000
            while (System.currentTimeMillis() < endAt) delay(1_000)
        }
        farm.stop()
    }

    /** Session-event bookkeeping shared by all peers. Thread-safe; every method is short. */
    internal class Recorder(private val csvFile: File?, private val farmIds: () -> Set<String>) {
        private val t0 = System.currentTimeMillis()
        private val csv: FileWriter? = csvFile?.let { f ->
            f.parentFile?.mkdirs()
            FileWriter(f, true).apply {
                if (f.length() == 0L) write("epochMs,elapsedS,farmPeer,peerId,peerName,event,direction\n")
                flush()
            }
        }
        val ups = AtomicLong()
        val downs = AtomicLong()
        val dialAttempts = AtomicLong()
        val dialSuccesses = AtomicLong()
        val rxText = AtomicLong()
        val rxBinary = AtomicLong()

        /** externalId -> farm peer names currently holding a session with it. */
        private val holders = ConcurrentHashMap<String, MutableSet<String>>()
        private val names = ConcurrentHashMap<String, String>()
        private val maxHeld = ConcurrentHashMap<String, Int>()
        private val degradedSince = ConcurrentHashMap<String, Long>()
        private val upsAtDegrade = ConcurrentHashMap<String, Long>()
        private val dialsAtDegrade = ConcurrentHashMap<String, Long>()

        @Synchronized
        fun onUp(farmPeer: String, peerId: String, peerName: String, outbound: Boolean) {
            ups.incrementAndGet()
            write(farmPeer, peerId, peerName, "up", if (outbound) "out" else "in")
            if (peerId in farmIds()) return
            names[peerId] = peerName
            val held = holders.getOrPut(peerId) { mutableSetOf() }.apply { add(farmPeer) }.size
            val max = maxOf(maxHeld[peerId] ?: 0, held).also { maxHeld[peerId] = it }
            val since = degradedSince[peerId]
            if (since != null && held >= max) {
                degradedSince.remove(peerId)
                val secs = (System.currentTimeMillis() - since) / 1000.0
                val upCount = ups.get() - (upsAtDegrade.remove(peerId) ?: ups.get())
                val dialCount = dialAttempts.get() - (dialsAtDegrade.remove(peerId) ?: dialAttempts.get())
                log("[recover] $peerName: all $held farm sessions back after ${"%.1f".format(secs)} s " +
                    "($upCount session-ups, $dialCount farm dials meanwhile)")
            }
        }

        @Synchronized
        fun onDown(farmPeer: String, peerId: String, peerName: String, outbound: Boolean) {
            downs.incrementAndGet()
            write(farmPeer, peerId, peerName, "down", if (outbound) "out" else "in")
            if (peerId in farmIds()) return
            val held = holders[peerId]?.apply { remove(farmPeer) }?.size ?: 0
            if (degradedSince[peerId] == null && held < (maxHeld[peerId] ?: 0)) {
                degradedSince[peerId] = System.currentTimeMillis()
                upsAtDegrade[peerId] = ups.get()
                dialsAtDegrade[peerId] = dialAttempts.get()
                log("[degrade] $peerName: $held/${maxHeld[peerId]} farm sessions left")
            }
        }

        /** Live sessions per external device, for the status line and tests. */
        @Synchronized
        fun heldByExternal(): Map<String, Int> = holders.mapKeys { names[it.key] ?: it.key }.mapValues { it.value.size }

        fun statusLine(): String {
            val elapsed = (System.currentTimeMillis() - t0) / 1000
            val ext = heldByExternal().entries.joinToString(" ") { "${it.key}=${it.value}" }.ifEmpty { "none" }
            return "[t+%02d:%02d:%02d] sessions per external device: %s | ups=%d downs=%d dials=%d ok=%d | rx text=%d bin=%d"
                .format(elapsed / 3600, elapsed / 60 % 60, elapsed % 60, ext, ups.get(), downs.get(),
                    dialAttempts.get(), dialSuccesses.get(), rxText.get(), rxBinary.get())
        }

        private fun write(farmPeer: String, peerId: String, peerName: String, event: String, direction: String) {
            val now = System.currentTimeMillis()
            val safeName = peerName.replace(",", " ").replace("\"", "'")
            csv?.apply {
                write("$now,${"%.1f".format((now - t0) / 1000.0)},$farmPeer,$peerId,$safeName,$event,$direction\n")
                flush()
            }
        }

        fun close() {
            runCatching { csv?.close() }
        }

        private fun log(line: String) = println(line)
    }

    internal class FarmPeer(
        val name: String,
        stateDir: File,
        private val options: Options,
        private val recorder: Recorder,
        private val farmIds: () -> Set<String>,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val identity = DesktopIdentityStore(stateDir.apply { mkdirs() }).getIdentity()
        private val trustStore = DesktopTrustStore(stateDir)
        private val crypto = PersistedFlashCrypto(stateDir)

        /** Same TLS construction as the harness and `DesktopEngine` (audit S3/S1). */
        private val tlsOptions = TlsOptions(
            pinVerifier = TofuPinVerifier(
                lookupPin = { peerId -> trustStore.getPin(FlashDeviceId(peerId)) },
                recordPin = { peerId, pin -> trustStore.savePin(FlashDeviceId(peerId), pin) },
            ),
            keyManagers = FlashCertMaker.createKeyManagers(crypto.javaKeyPair(), cn = "CN=${identity.deviceId.value}"),
        )

        val network = JvmWsFlashNetwork(
            localDeviceId = identity.deviceId.value,
            localFriendlyName = name,
            tlsOptions = tlsOptions,
            transportProfile = { options.tier.transport },
        )

        private val discovery: CompositeDiscovery? = if (!options.discovery) null else CompositeDiscovery(
            transports = listOf(
                JmdnsTransport(directory = StandardEndpointDirectory(), sweep = { _ -> emptyList() }),
                MulticastTransport(socketFactory = JvmMulticastSocketFactory(), directory = StandardEndpointDirectory()),
            ),
        )

        private val sessionJobs = ConcurrentHashMap<WsSession, Job>()
        var port: Int = 0
            private set

        fun start() {
            port = runBlocking { (network.start(0) as FlashResult.Success).value }
            discovery?.let { d ->
                val frame = FlashAdvertisedIdentity(
                    deviceId = identity.deviceId,
                    friendlyName = name,
                    deviceModel = "peer-farm",
                    protocolVersion = 2,
                    capabilities = setOf(FlashDeviceKind.CAP_DESKTOP),
                )
                (runBlocking { d.startAll(port, frame) } as? FlashResult.Failure)?.let {
                    println("[$name] discovery startAll PARTIAL FAILURE: ${it.error}")
                }
                DiscoveryRouteBinder.observe(scope, d.discoveredEndpoints, network)
                if (options.dial == DialPolicy.EXTERNAL) scope.launch { dialLoop(d) }
            }
            scope.launch { trackSessions() }
        }

        private suspend fun dialLoop(d: CompositeDiscovery) {
            while (scope.isActive) {
                val farm = farmIds()
                d.discoveredEndpoints.value.forEach { ep ->
                    val id = ep.device.id.value
                    if (id in farm || network.hasLiveSession(id) || network.isReconnectInFlight(id)) return@forEach
                    recorder.dialAttempts.incrementAndGet()
                    scope.launch {
                        val result = runCatching { network.connectManual(ep.hostAddress, ep.port, id) }
                        if (result.getOrNull() is FlashResult.Success) {
                            recorder.dialSuccesses.incrementAndGet()
                        } else {
                            // A failed dial is the interesting case in a PC0 run (the phone's session cap
                            // refusing us, or a stale route), so say which target and why.
                            val why = result.exceptionOrNull()?.toString()
                                ?: (result.getOrNull() as? FlashResult.Failure)?.error?.toString()
                            println("[$name] dial ${ep.friendlyName} ($id) ${ep.hostAddress}:${ep.port} failed: $why")
                        }
                    }
                }
                delay(DIAL_SWEEP_MS)
            }
        }

        private suspend fun trackSessions() {
            network.activeSessions.collect { sessions ->
                val live = sessions.values.filterIsInstance<WsSession>().toSet()
                sessionJobs.keys.filter { it !in live }.forEach { gone ->
                    sessionJobs.remove(gone)?.cancel()
                    recorder.onDown(name, gone.peerDeviceId.value, gone.peer.friendlyName, gone.isOutbound)
                }
                live.filter { it !in sessionJobs.keys }.forEach { s ->
                    recorder.onUp(name, s.peerDeviceId.value, s.peer.friendlyName, s.isOutbound)
                    sessionJobs[s] = scope.launch {
                        launch { s.incomingText.collect { recorder.rxText.incrementAndGet() } }
                        launch { s.incomingBinary.collect { recorder.rxBinary.incrementAndGet() } }
                    }
                }
            }
        }

        fun stop() {
            runBlocking {
                runCatching { discovery?.stopAll() }
                runCatching { network.stop() }
            }
            scope.cancel()
        }
    }

    internal class Farm(private val options: Options) {
        private val runStamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val csvFile: File = options.csv ?: File(options.stateRoot, "runs/peer-farm-$runStamp.csv")
        private var ids: Set<String> = emptySet()
        val recorder = Recorder(csvFile) { ids }
        val peers: List<FarmPeer> = (1..options.count).map { i ->
            val label = "Farm %02d".format(i)
            FarmPeer(label, File(options.stateRoot, "peer-%02d".format(i)), options, recorder) { ids }
        }
        private val reporter = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        @Volatile private var stopped = false

        fun start() {
            ids = peers.map { it.identity.deviceId.value }.toSet()
            println("[farm] ${options.count} peers, tier=${options.tier} (ping ${options.tier.transport.pingIntervalMs} ms, " +
                "liveness ${options.tier.transport.livenessTimeoutMs} ms), dial=${options.dial}, " +
                "minutes=${if (options.minutes == 0L) "until killed" else options.minutes}")
            println("[farm] state=${options.stateRoot.absolutePath}")
            println("[farm] csv=${csvFile.absolutePath}")
            peers.forEach { p ->
                p.start()
                println("[farm] ${p.name} id=${p.identity.deviceId.value} port=${p.port}")
            }
            reporter.launch {
                while (isActive) {
                    delay(options.reportSeconds * 1_000)
                    println(recorder.statusLine())
                }
            }
        }

        @Synchronized
        fun stop() {
            if (stopped) return
            stopped = true
            reporter.cancel()
            println(recorder.statusLine())
            peers.forEach { it.stop() }
            recorder.close()
            println("[farm] stopped; events in ${csvFile.absolutePath}")
        }
    }
}

/** Entry point for the `:core:engine:peerFarm` task (see `build.gradle.kts`). */
public fun main(args: Array<String>) {
    PeerFarm.run(PeerFarm.parse(args))
}
