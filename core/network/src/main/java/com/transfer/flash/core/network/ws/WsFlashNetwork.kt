@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import android.content.Context
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionHealth
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.FlashNetworkState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.network.bridge.EndpointMemory
import com.transfer.flash.core.network.resilience.AndroidNetworkWatcher
import com.transfer.flash.core.network.resilience.ConnectionHealthAggregator
import com.transfer.flash.core.network.resilience.DuplicateSessionDecision
import com.transfer.flash.core.network.resilience.ReconnectPolicy
import com.transfer.flash.core.network.resilience.SessionHardeningPolicy
import com.transfer.flash.core.network.tls.TlsOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * High-performance WebSocket mesh network implementation of [FlashNetwork].
 *
 * Provides:
 * - Full-duplex persistent RFC 6455 WebSockets between all connected peers.
 * - Works identically over Wi-Fi Routers (via mDNS discovery) and Mobile Hotspots (via gateway/probe).
 * - Instant disconnect detection via socket FIN/RST and WebSocket ping/pong keepalives.
 * - Unified text (chat/signaling) and binary (chunked file transfer) transport.
 */
class WsFlashNetwork(
    private val context: Context?,
    private val localDeviceId: String,
    private val localFriendlyName: String,
    private val tlsOptions: TlsOptions? = null,
    private val hardeningPolicy: SessionHardeningPolicy = SessionHardeningPolicy(),
    private val healthAggregator: ConnectionHealthAggregator = ConnectionHealthAggregator(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : FlashNetwork, EndpointMemory, WsConnection.Listener {

    private val running = AtomicBoolean(false)
    private var server: WsTransferServer? = null
    private val client = WsTransferClient(context, this, tlsOptions)

    private val knownEndpoints = ConcurrentHashMap<String, Endpoint>()
    private val sessionsById = ConcurrentHashMap<FlashDeviceId, WsSession>()
    private val sessionByConnection = ConcurrentHashMap<WsConnection, WsSession>()
    private val pendingHandshakes = ConcurrentHashMap<WsConnection, CompletableDeferred<FlashDevice>>()

    // ------------------------------------------------------------------
    // #18: outbound reconnect engine (wires the previously-dead ReconnectPolicy +
    // AndroidNetworkWatcher into the live WS path). Only sessions WE dialed
    // (connectManual) get a reconnect target — inbound peers dialed us and will
    // redial themselves. On an unexpected drop we redial the last-known route with
    // exponential-backoff-with-jitter; a fresh network (Wi-Fi rejoin) collapses the
    // pending backoff to an immediate attempt. Heartbeat liveness is already handled
    // at the connection layer (WsConnection PING + no-inbound watchdog, ADR-016), so
    // the HeartbeatTracker state machine stays the TCP/LanSession path's concern.
    // ------------------------------------------------------------------

    /** deviceId -> last route we dialed, kept so we can redial after an unexpected drop. */
    private val reconnectTargets = ConcurrentHashMap<String, Endpoint>()

    /** deviceId -> in-flight reconnect loop (one per peer). */
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

    /** deviceId -> its backoff counter; reset on a stable (re)connect. */
    private val reconnectPolicies = ConcurrentHashMap<String, ReconnectPolicy>()

    private var networkWatcher: AndroidNetworkWatcher? = null

    /**
     * Frames that arrive on a connection AFTER its HELLO completed but BEFORE the local
     * [WsSession] is registered (the peer may start streaming immediately after ITS
     * registration). They are buffered per connection and flushed into the session on
     * registration instead of being silently dropped (fix for early-frame race).
     */
    private val earlyFrames = ConcurrentHashMap<WsConnection, ConcurrentLinkedQueue<Any>>()

    private data class Endpoint(val host: String, val port: Int)

    private val _networkState = MutableStateFlow(FlashNetworkState())
    override val networkState: StateFlow<FlashNetworkState> = _networkState.asStateFlow()

    private val _activeSessions = MutableStateFlow<Map<FlashDeviceId, FlashSession>>(emptyMap())
    override val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>> = _activeSessions.asStateFlow()

    // Health is derived purely from live signals (discovered peers, in-flight connect attempts,
    // online/degraded session counts) via [healthAggregator]; never hardcoded. See
    // [refreshHealthFromSessions]. Mirrors DefaultFlashNetwork (C4.7).
    override val connectionHealth: StateFlow<FlashConnectionHealth> = healthAggregator.health

    /** In-flight outbound connect attempts, fed to the health aggregator as the "Connecting" signal. */
    private val connectingAttempts = AtomicInteger(0)

    /**
     * Guards the compound session-registry mutations (cap check + duplicate coalescing + map
     * writes) so two concurrent registrations for the same peer — the classic connect-glare race
     * between an inbound and an outbound dial — cannot both pass the [SessionHardeningPolicy]
     * admission gate. The maps stay [ConcurrentHashMap] for lock-free reads on the frame paths.
     */
    private val registryLock = Any()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override suspend fun start(listenPort: Int): FlashResult<Int> = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext FlashResult.Success(server?.listenPort ?: 0)
        running.set(true)

        val serverImpl = WsTransferServer(
            connectionListener = this@WsFlashNetwork,
            onConnection = { connection ->
                handleInboundConnection(connection)
            },
            tls = tlsOptions,
        )
        server = serverImpl
        val port = runCatching { serverImpl.start() }.getOrElse {
            running.set(false)
            return@withContext FlashResult.Failure(FlashError.NetworkUnavailable("WS server start failed: ${it.message}"))
        }

        // Health is not forced to Connected here — the server merely listening is not a peer
        // connection. It stays driven by real signals (remembered endpoints / sessions).
        refreshState()
        refreshHealthFromSessions()
        startNetworkWatcher()
        FlashResult.Success(port)
    }

    override suspend fun stop(): FlashResult<Unit> = withContext(Dispatchers.IO) {
        running.set(false)
        server?.stop()
        server = null

        // #18: tear down the reconnect engine before closing sessions. running=false already gates
        // onSessionDisconnected from scheduling new attempts; clearing targets/jobs makes it explicit
        // and stops the network watcher from kicking redials against a stopped network.
        stopNetworkWatcher()
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        reconnectTargets.clear()
        reconnectPolicies.clear()

        // Pending handshakes hold live sockets with active read loops — close, don't just forget.
        pendingHandshakes.keys.forEach { it.close("Network stopped") }
        pendingHandshakes.clear()
        earlyFrames.clear()

        sessionsById.values.forEach { it.disconnect("Network stopped") }
        sessionsById.clear()
        sessionByConnection.clear()

        _activeSessions.value = emptyMap()
        connectingAttempts.set(0)
        // #16: cancel any coroutines still launched on our scope (e.g. in-flight inbound handshake
        // waiters) so they do not linger as zombies after stop. cancelChildren (not cancel) keeps
        // the scope's Job alive so a subsequent start on the same instance still works.
        scope.coroutineContext.cancelChildren()
        refreshState()
        refreshHealthFromSessions()
        FlashResult.Success(Unit)
    }

    // ------------------------------------------------------------------
    // Endpoint Memory (Discovery binding)
    // ------------------------------------------------------------------

    override fun rememberEndpoint(deviceId: String, host: String, port: Int) {
        knownEndpoints[deviceId] = Endpoint(host, port)
    }

    /**
     * #17: discovery dropped this peer — remove its route so `peerCountDiscovered`
     * shrinks. A live session (tracked separately in [sessionsById]) is unaffected;
     * only the resolve-by-deviceId route is forgotten.
     */
    override fun forgetEndpoint(deviceId: String) {
        if (knownEndpoints.remove(deviceId) != null) refreshState()
    }

    /** Resolved endpoint for a discovered peer (host + WS port), or null when unknown. */
    fun endpointOf(deviceId: String?): Pair<String, Int>? =
        deviceId?.let { id -> knownEndpoints[id]?.let { it.host to it.port } }

    // ------------------------------------------------------------------
    // Connect / Disconnect
    // ------------------------------------------------------------------

    override suspend fun connect(device: FlashDevice): FlashResult<FlashSession> {
        val endpoint = knownEndpoints[device.id.value]
            ?: return FlashResult.Failure(FlashError.PeerUnavailable(device.id.value, "No remembered endpoint"))
        return connectManual(endpoint.host, endpoint.port)
    }

    override suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession> = withContext(Dispatchers.IO) {
        if (!running.get()) {
            return@withContext FlashResult.Failure(FlashError.NetworkUnavailable("Network not started"))
        }

        // Signal "Connecting" for the duration of the dial + handshake; the finally clause always
        // clears it so a failed/timed-out attempt can never wedge health in Connecting.
        connectingAttempts.incrementAndGet()
        refreshHealthFromSessions()
        try {
            val actualPort = if (port > 0) port else WsTransferServer.PREFERRED_PORT

        val connection = runCatching {
            client.connect(host, actualPort)
        }.getOrElse { error ->
            return@withContext FlashResult.Failure(FlashError.PeerUnavailable(host, error.message ?: "WS connect failed"))
        }

        val handshakeWaiter = CompletableDeferred<FlashDevice>()
        pendingHandshakes[connection] = handshakeWaiter
        connection.start()

        // Send local HELLO handshake
        val helloMsg = FlashTextFraming.encodeFields(
            HELLO_PREFIX,
            "version" to PROTOCOL_VERSION.toString(),
            "deviceId" to localDeviceId,
            "name" to localFriendlyName,
        )
        connection.sendText(helloMsg)

        // Wait for peer HELLO response
        val handshakeOutcome = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            runCatching { handshakeWaiter.await() }
        }

        pendingHandshakes.remove(connection)
        earlyFrames.remove(connection)

        val peerDevice = when {
            // Timed out waiting for the peer HELLO.
            handshakeOutcome == null -> {
                connection.close("Handshake timeout")
                return@withContext FlashResult.Failure(
                    FlashError.ConnectionTimeout(HANDSHAKE_TIMEOUT_MS, "WS handshake timed out"),
                )
            }
            // Peer rejected our HELLO (e.g. protocol version mismatch) and closed.
            handshakeOutcome.isFailure -> {
                val cause = handshakeOutcome.exceptionOrNull()
                connection.close("Handshake rejected")
                return@withContext FlashResult.Failure(
                    FlashError.PeerUnavailable(host, cause?.message ?: "Handshake rejected"),
                )
            }
            else -> handshakeOutcome.getOrThrow()
        }

            val session = WsSession(connection, peerDevice) { s, _ ->
                onSessionDisconnected(s)
            }

            if (!registerSession(session)) {
                // Policy rejected (concurrency cap hit, or an equal/richer incumbent already holds
                // this peer). Tear down our freshly-opened socket so it cannot keep pumping frames.
                session.disconnect("Session not admitted")
                return@withContext FlashResult.Failure(
                    FlashError.PeerUnavailable(peerDevice.id.value, "Session not admitted"),
                )
            }

            // #18: remember the route WE dialed so an unexpected drop can be auto-redialed, and reset
            // the peer's backoff counter now that we have a stable connect. Only outbound dials get a
            // reconnect target; inbound peers reconnect from their side.
            reconnectTargets[peerDevice.id.value] = Endpoint(host, actualPort)
            reconnectPolicies.remove(peerDevice.id.value)

            FlashResult.Success(session)
        } finally {
            connectingAttempts.decrementAndGet()
            refreshHealthFromSessions()
        }
    }

    override suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit> = withContext(Dispatchers.IO) {
        // #18: an explicit local disconnect is intentional — drop the reconnect target and cancel any
        // in-flight redial loop BEFORE closing the session, so onSessionDisconnected does not immediately
        // reschedule the peer we just asked to leave.
        reconnectTargets.remove(deviceId.value)
        reconnectPolicies.remove(deviceId.value)
        reconnectJobs.remove(deviceId.value)?.cancel()

        val session = synchronized(registryLock) {
            val s = sessionsById.remove(deviceId)
            if (s != null) sessionByConnection.remove(s.connection)
            s
        } ?: return@withContext FlashResult.Failure(FlashError.PeerUnavailable(deviceId.value, "Session not active"))
        session.disconnect("Local disconnect")
        refreshState()
        refreshHealthFromSessions()
        FlashResult.Success(Unit)
    }

    // ------------------------------------------------------------------
    // Inbound Handshake & Session Registration
    // ------------------------------------------------------------------

    private fun handleInboundConnection(connection: WsConnection) {
        val handshakeWaiter = CompletableDeferred<FlashDevice>()
        pendingHandshakes[connection] = handshakeWaiter
        connection.start()

        scope.launch {
            val outcome = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                runCatching { handshakeWaiter.await() }
            }
            pendingHandshakes.remove(connection)

            if (outcome?.isSuccess != true) {
                connection.close(
                    if (outcome == null) "Inbound handshake timeout"
                    else "Inbound handshake rejected: ${outcome.exceptionOrNull()?.message}",
                )
                return@launch
            }

            // Reply with local HELLO
            val helloReply = FlashTextFraming.encodeFields(
                HELLO_PREFIX,
                "version" to PROTOCOL_VERSION.toString(),
                "deviceId" to localDeviceId,
                "name" to localFriendlyName,
            )
            connection.sendText(helloReply)

            val session = WsSession(connection, outcome.getOrThrow()) { s, _ ->
                onSessionDisconnected(s)
            }
            if (!registerSession(session)) {
                session.disconnect("Session not admitted")
            }
        }
    }

    /**
     * Admits [session] into the registry under [SessionHardeningPolicy], returning false (registry
     * unchanged) when rejected so the caller can tear the socket down.
     *
     * Rules (mirrors DefaultFlashNetwork.registerSession, C4.5):
     * - **Concurrency cap:** reject once [SessionHardeningPolicy.maxConcurrentSessions] live
     *   sessions exist — bounds socket/fd/thread pressure on a phone.
     * - **Duplicate coalescing:** when a session for this peer already exists, defer to
     *   [SessionHardeningPolicy.resolveDuplicate] on transport rank. Equal/worse rank keeps the
     *   incumbent (stability wins — no reconnect churn, no frame loss across a swap); a strictly
     *   richer new path supersedes it. This replaces the old unconditional "newer session wins",
     *   which tore down a healthy incumbent on every connect-glare event.
     *
     * The compound check-then-mutate runs under [registryLock] so a simultaneous inbound+outbound
     * glare for the same peer cannot both be admitted.
     */
    private fun registerSession(session: WsSession): Boolean {
        synchronized(registryLock) {
            if (!hardeningPolicy.canAcceptSession(sessionsById.size)) {
                return false
            }
            val existing = sessionsById[session.peerDeviceId]
            if (existing != null && existing !== session) {
                val decision = hardeningPolicy.resolveDuplicate(
                    SessionHardeningPolicy.transportRank(existing.transportType),
                    SessionHardeningPolicy.transportRank(session.transportType),
                )
                if (decision == DuplicateSessionDecision.KeepExisting) {
                    return false
                }
                // PreferNew: migrate to the richer path, closing the incumbent's socket.
                sessionsById.remove(session.peerDeviceId)
                sessionByConnection.remove(existing.connection)
                existing.disconnect("Superseded by richer path")
            }

            sessionsById[session.peerDeviceId] = session
            sessionByConnection[session.connection] = session

            // Flush any frames that arrived between handshake completion and registration.
            earlyFrames.remove(session.connection)?.let { queued ->
                queued.forEach { frame ->
                    when (frame) {
                        is String -> session.onTextReceived(frame)
                        is ByteArray -> session.onBinaryReceived(frame)
                    }
                }
            }

            _activeSessions.value = sessionsById.toMap()
        }
        refreshState()
        refreshHealthFromSessions()
        return true
    }

    private fun onSessionDisconnected(session: WsSession) {
        synchronized(registryLock) {
            // Identity-safe removal: a glare replacement already re-pointed sessionsById at the
            // new session — a late callback from the OLD session must not evict it.
            sessionsById.remove(session.peerDeviceId, session)
            sessionByConnection.remove(session.connection, session)
            earlyFrames.remove(session.connection)
            _activeSessions.value = sessionsById.toMap()
        }
        refreshState()
        refreshHealthFromSessions()

        // #18: an unexpected drop of a session WE dialed → schedule a backoff reconnect. Guards: the
        // network is still running, we still hold a reconnect target for this peer (a local disconnect
        // clears it), and no live session has since been re-established for the peer.
        val peerId = session.peerDeviceId.value
        if (running.get() &&
            reconnectTargets.containsKey(peerId) &&
            sessionsById[session.peerDeviceId] == null
        ) {
            scheduleReconnect(peerId, immediate = false)
        }
    }

    // ------------------------------------------------------------------
    // #18: reconnect engine
    // ------------------------------------------------------------------

    /**
     * (Re)launches the backoff redial loop for [deviceId]. At most one loop runs per peer; an existing
     * loop is cancelled and replaced (so a network-available "collapse to immediate" can pre-empt a
     * loop that is currently sleeping out its backoff). The loop exits as soon as the peer reconnects,
     * the target is cleared (local disconnect / stop), or the network stops.
     *
     * @param immediate skip the first backoff delay (used by the network watcher on Wi-Fi rejoin).
     */
    private fun scheduleReconnect(deviceId: String, immediate: Boolean) {
        reconnectJobs.remove(deviceId)?.cancel()
        val job = scope.launch {
            var first = true
            while (isActive &&
                running.get() &&
                reconnectTargets.containsKey(deviceId) &&
                sessionsById[FlashDeviceId(deviceId)] == null
            ) {
                val policy = reconnectPolicies.getOrPut(deviceId) {
                    ReconnectPolicy(random01 = { ThreadLocalRandom.current().nextDouble() })
                }
                if (!(first && immediate)) {
                    delay(policy.nextDelay())
                }
                first = false

                // Re-check after the sleep: state may have changed while we backed off.
                if (!running.get() || sessionsById[FlashDeviceId(deviceId)] != null) break
                val target = reconnectTargets[deviceId] ?: break

                val result = runCatching { connectManual(target.host, target.port) }.getOrNull()
                if (result is FlashResult.Success) {
                    // connectManual already reset the policy on success; nothing more to do.
                    break
                }
            }
            reconnectJobs.remove(deviceId)
        }
        reconnectJobs[deviceId] = job
    }

    /**
     * Starts the [AndroidNetworkWatcher] (no-op when [context] is null, e.g. JVM tests). On a fresh
     * usable network (Wi-Fi/Ethernet available) it collapses every pending peer backoff to an
     * immediate attempt instead of waiting the loop out.
     */
    private fun startNetworkWatcher() {
        val ctx = context ?: return
        if (networkWatcher != null) return
        networkWatcher = AndroidNetworkWatcher(
            context = ctx,
            onAvailable = onAvailable@{
                if (!running.get()) return@onAvailable
                reconnectTargets.keys.forEach { deviceId ->
                    if (sessionsById[FlashDeviceId(deviceId)] == null) {
                        reconnectPolicies.remove(deviceId) // fresh network → restart backoff from base
                        scheduleReconnect(deviceId, immediate = true)
                    }
                }
            },
        ).also { it.start() }
    }

    private fun stopNetworkWatcher() {
        networkWatcher?.stop()
        networkWatcher = null
    }

    /**
     * Feeds a fresh snapshot of all health signals to [healthAggregator]. Snapshot-based (not
     * delta-based) so a dropped event can never wedge health in a stale state.
     *
     * Degraded is always 0 for now: WS sessions carry no impaired-path signal in v1 (relay/mesh
     * lands with D5, post-v1) — same stance as DefaultFlashNetwork.refreshHealthFromSessions.
     */
    private fun refreshHealthFromSessions() {
        val sessions = sessionsById.values
        healthAggregator.apply(
            peerCountDiscovered = knownEndpoints.size,
            connectingAttempts = connectingAttempts.get(),
            onlineSessions = sessions.count { it.connectionState.value == FlashConnectionState.Connected },
            degradedSessions = 0,
        )
    }

    private fun refreshState() {
        val activeCount = sessionsById.size
        _networkState.update {
            it.copy(
                isRunning = server?.isRunning == true,
                localPort = server?.listenPort ?: 0,
                activePeerCount = activeCount,
            )
        }
    }

    // ------------------------------------------------------------------
    // WsConnection.Listener implementation
    // ------------------------------------------------------------------

    override fun onTextMessage(connection: WsConnection, text: String) {
        val session = sessionByConnection[connection]
        if (session != null) {
            session.onTextReceived(text)
            return
        }

        // Check if this is an incoming HELLO handshake
        val parsedFields = FlashTextFraming.parseFields(text, HELLO_PREFIX)
        if (parsedFields != null) {
            val peerDeviceId = parsedFields["deviceId"] ?: "unknown"
            val peerName = parsedFields["name"] ?: "Peer"
            val peerVersion = parsedFields["version"]?.toIntOrNull() ?: 1

            if (peerVersion != PROTOCOL_VERSION) {
                pendingHandshakes[connection]?.completeExceptionally(
                    IllegalStateException("protocol version mismatch: local=$PROTOCOL_VERSION peer=$peerVersion"),
                )
                connection.close("Unsupported protocol version $peerVersion")
                return
            }

            val peerDevice = FlashDevice(
                id = FlashDeviceId(peerDeviceId),
                friendlyName = peerName,
                transportType = FlashTransportType.LAN,
                presence = FlashPeerPresence.Online,
                protocolVersion = peerVersion,
            )

            pendingHandshakes[connection]?.complete(peerDevice)
        } else {
            // Not yet registered: buffer instead of dropping (early-frame race).
            earlyFrameQueue(connection).add(text)
        }
    }

    override fun onBinaryMessage(connection: WsConnection, data: ByteArray) {
        val session = sessionByConnection[connection]
        if (session != null) {
            session.onBinaryReceived(data)
        } else {
            earlyFrameQueue(connection).add(data)
        }
    }

    private fun earlyFrameQueue(connection: WsConnection): ConcurrentLinkedQueue<Any> =
        earlyFrames.computeIfAbsent(connection) { ConcurrentLinkedQueue() }

    override fun onConnectionClosed(connection: WsConnection, reason: String) {
        pendingHandshakes.remove(connection)?.completeExceptionally(Exception("Closed: $reason"))
        earlyFrames.remove(connection)
        val session = sessionByConnection.remove(connection)
        if (session != null) {
            session.onClosed(reason)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 2
        private const val HELLO_PREFIX = "FLASH_WS_HELLO"
        private const val HANDSHAKE_TIMEOUT_MS = 6_000L
    }
}
