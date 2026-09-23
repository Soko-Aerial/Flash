@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.model.FlashPeerPresence
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.perf.FlashTransportProfile
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionHealth
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.FlashNetworkState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.network.bridge.EndpointMemory
import com.transfer.flash.core.network.resilience.ConnectionHealthAggregator
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
 * DESKTOP `FlashNetwork` implementation — the `jvmMain` twin of `androidMain`'s `WsFlashNetwork`
 * (Phase 15-4), speaking the **same** WS wire protocol so desktop and Android peers interoperate
 * on one LAN: same `FLASH_WS_HELLO` handshake, same `PROTOCOL_VERSION = 2`, same preferred port
 * 45822, same TOFU-pinned TLS over the duplicated [SecureSocketUpgrader].
 *
 * D1 = Option B forbids a shared JVM tier, so the orchestrator is duplicated rather than
 * inherited. The Android-specific 10% is simply absent here: no `Context`, no
 * `AndroidNetworkWatcher` (desktop has no `ConnectivityManager`; a desktop JVM's kernel routing
 * table and the reconnect engine's backoff are sufficient), and the client dials with plain
 * default routing. Everything else — session registry, HELLO handshake, connect-glare
 * tiebreaker, reconnect engine with backup loops, early-frame rescue, health aggregation — is
 * the same logic, ported verbatim from the Android original; where a KDoc cites an Android
 * behaviour (Doze, screen-off), the mechanism it documents is the watchdog-and-backoff pair
 * both platforms share.
 *
 * ## What is deliberately NOT here
 *
 * No `probeSessionsAfterLinkChange`/`sweepDisconnectedPeers` watcher hooks: those are driven on
 * Android by `ConnectivityManager` callbacks, which a desktop does not have. The reconnect
 * engine's own backoff (including the accepting-side backup loop, ERROR-026) still recovers
 * sessions after an interruption; only the *instant* rejoin a Wi-Fi-rejoin callback buys is
 * desktop-absent. Phase 16's interop gate exercises exactly this difference.
 */
public class JvmWsFlashNetwork(
    private val localDeviceId: String,
    localFriendlyName: String,
    private val tlsOptions: TlsOptions? = null,
    private val hardeningPolicy: SessionHardeningPolicy = SessionHardeningPolicy(),
    private val healthAggregator: ConnectionHealthAggregator = ConnectionHealthAggregator(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Backoff floor for the accepting side's backup redial loop (ERROR-026); injectable for tests. */
    private val backupRedialBaseMs: Long = BACKUP_REDIAL_BASE_MS,
    /** Clock for session-freshness checks; injectable so tests need not wait. */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** The device's transport tier (ERROR-033), read per use; defaults to the shipped numbers. */
    private val transportProfile: () -> FlashTransportProfile = { FlashPerformanceMode.HIGH.transport },
) : FlashNetwork, EndpointMemory, WsConnection.Listener {

    @Volatile
    public var localFriendlyName: String = localFriendlyName

    private val running = AtomicBoolean(false)
    private var server: WsTransferServer? = null
    public val serverPort: Int get() = server?.listenPort ?: 0
    private val client = WsTransferClient(this, tlsOptions, keepalive = ::keepaliveTiming)

    /** The tier's keepalive pair, as [WsConnection] wants it. Read once per new connection. */
    private fun keepaliveTiming(): WsKeepaliveTiming {
        val profile = transportProfile()
        return WsKeepaliveTiming(
            pingIntervalMs = profile.pingIntervalMs,
            livenessTimeoutMs = profile.livenessTimeoutMs,
        )
    }

    private val knownEndpoints = ConcurrentHashMap<String, Endpoint>()
    private val sessionsById = ConcurrentHashMap<FlashDeviceId, WsSession>()
    private val sessionByConnection = ConcurrentHashMap<WsConnection, WsSession>()
    private val pendingHandshakes = ConcurrentHashMap<WsConnection, CompletableDeferred<FlashDevice>>()

    // Reconnect engine (the #18/ERROR-026/ERROR-031 logic of the Android original, verbatim):
    // sessions WE dialed get a primary backoff loop off the route we dialed; inbound-only peers
    // get the slower backup loop off knownEndpoints. Only dialers redial on Android — here every
    // peer can be either.
    private val reconnectTargets = ConcurrentHashMap<String, Endpoint>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val reconnectPolicies = ConcurrentHashMap<String, ReconnectPolicy>()
    private val localDisconnects = ConcurrentHashMap.newKeySet<String>()

    /** Pre-registration frames, buffered per connection and flushed on registration. */
    private val earlyFrames = ConcurrentHashMap<WsConnection, ConcurrentLinkedQueue<Any>>()

    /** Frames rescued from a connection that died before any session owned its peer. Bounded. */
    private val pendingPeerFrames = ConcurrentHashMap<FlashDeviceId, ConcurrentLinkedQueue<Any>>()

    private data class Endpoint(val host: String, val port: Int)

    private val _networkState = MutableStateFlow(FlashNetworkState())
    override val networkState: StateFlow<FlashNetworkState> = _networkState.asStateFlow()

    private val _activeSessions = MutableStateFlow<Map<FlashDeviceId, FlashSession>>(emptyMap())
    override val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>> = _activeSessions.asStateFlow()

    override val connectionHealth: StateFlow<FlashConnectionHealth> = healthAggregator.health

    private val connectingAttempts = AtomicInteger(0)

    /** Guards the compound session-registry mutations so connect glare cannot both be admitted. */
    private val registryLock = Any()

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override suspend fun start(listenPort: Int): FlashResult<Int> = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext FlashResult.Success(server?.listenPort ?: 0)
        running.set(true)

        val serverImpl = WsTransferServer(
            connectionListener = this@JvmWsFlashNetwork,
            onConnection = { connection -> handleInboundConnection(connection) },
            tls = tlsOptions,
            keepalive = ::keepaliveTiming,
        )
        server = serverImpl
        val port = runCatching { serverImpl.start() }.getOrElse {
            running.set(false)
            return@withContext FlashResult.Failure(FlashError.NetworkUnavailable("WS server start failed: ${it.message}"))
        }

        refreshState()
        refreshHealthFromSessions()
        FlashResult.Success(port)
    }

    override suspend fun stop(): FlashResult<Unit> = withContext(Dispatchers.IO) {
        running.set(false)
        server?.stop()
        server = null

        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        reconnectTargets.clear()
        reconnectPolicies.clear()
        localDisconnects.clear()

        pendingHandshakes.keys.forEach { it.close("Network stopped") }
        pendingHandshakes.clear()
        earlyFrames.clear()
        pendingPeerFrames.clear()

        sessionsById.values.forEach { it.disconnect("Network stopped") }
        sessionsById.clear()
        sessionByConnection.clear()

        _activeSessions.value = emptyMap()
        connectingAttempts.set(0)
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

    override fun forgetEndpoint(deviceId: String) {
        if (knownEndpoints.remove(deviceId) != null) refreshState()
    }

    public fun endpointOf(deviceId: String?): Pair<String, Int>? =
        deviceId?.let { id -> knownEndpoints[id]?.let { it.host to it.port } }

    // ------------------------------------------------------------------
    // Connect / Disconnect
    // ------------------------------------------------------------------

    override suspend fun connect(device: FlashDevice): FlashResult<FlashSession> {
        val endpoint = knownEndpoints[device.id.value]
            ?: return FlashResult.Failure(FlashError.PeerUnavailable(device.id.value, "No remembered endpoint"))
        return connectManual(endpoint.host, endpoint.port, device.id.value)
    }

    override suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession> =
        connectManual(host, port, null)

    public suspend fun connectManual(host: String, port: Int, peerDeviceId: String?): FlashResult<FlashSession> = withContext(Dispatchers.IO) {
        if (!running.get()) {
            return@withContext FlashResult.Failure(FlashError.NetworkUnavailable("Network not started"))
        }

        connectingAttempts.incrementAndGet()
        refreshHealthFromSessions()
        try {
            val actualPort = if (port > 0) port else WsTransferServer.PREFERRED_PORT
            val resolvedPeerDeviceId = peerDeviceId ?: knownEndpoints.entries.firstOrNull { it.value.host == host && it.value.port == actualPort }?.key

            val connection = runCatching {
                client.connect(host, actualPort, resolvedPeerDeviceId)
            }.getOrElse { error ->
                return@withContext FlashResult.Failure(FlashError.PeerUnavailable(host, error.message ?: "WS connect failed"))
            }

            val handshakeWaiter = CompletableDeferred<FlashDevice>()
            pendingHandshakes[connection] = handshakeWaiter
            connection.start()

            val helloMsg = FlashTextFraming.encodeFields(
                HELLO_PREFIX,
                "version" to PROTOCOL_VERSION.toString(),
                "deviceId" to localDeviceId,
                "name" to localFriendlyName,
            )
            connection.sendText(helloMsg)

            val handshakeOutcome = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                runCatching { handshakeWaiter.await() }
            }

            pendingHandshakes.remove(connection)

            val peerDevice = when {
                handshakeOutcome == null -> {
                    connection.close("Handshake timeout")
                    return@withContext FlashResult.Failure(
                        FlashError.ConnectionTimeout(HANDSHAKE_TIMEOUT_MS, "WS handshake timed out"),
                    )
                }
                handshakeOutcome.isFailure -> {
                    val cause = handshakeOutcome.exceptionOrNull()
                    connection.close("Handshake rejected")
                    return@withContext FlashResult.Failure(
                        FlashError.PeerUnavailable(host, cause?.message ?: "Handshake rejected"),
                    )
                }
                else -> handshakeOutcome.getOrThrow()
            }


            // ADR-040: the dial could not name the peer, so the TLS handshake accepted its leaf
            // without evaluating a pin. HELLO has now named it — run the SAME check here, before
            // the session is registered or carries a single frame. TofuPinVerifier records the pin
            // on genuine first contact and fails closed when a different key answers for a peer we
            // already pinned.
            val deferredLeaf = connection.deferredPeerLeafFingerprintHex
            if (deferredLeaf != null) {
                val verifier = tlsOptions?.pinVerifier
                val bound = verifier != null && verifier.isPinned(peerDevice.id.value, deferredLeaf)
                if (!bound) {
                    FlashLog.w(
                        TAG,
                        "[tls] manual dial rejected: leaf does not match the pin for " +
                            "peer=${shortId(peerDevice.id.value)}",
                    )
                    connection.close("TLS pin mismatch after HELLO")
                    return@withContext FlashResult.Failure(
                        FlashError.PeerUnavailable(
                            host,
                            "This device's security key does not match the one Flash trusted before.",
                        ),
                    )
                }
            }

            val session = WsSession(connection, peerDevice, isOutbound = true) { s, _ ->
                onSessionDisconnected(s)
            }

            if (!registerSession(session)) {
                handOffEarlyFrames(connection, session.peerDeviceId)
                session.disconnect("Session not admitted")
                return@withContext FlashResult.Failure(
                    FlashError.PeerUnavailable(peerDevice.id.value, "Session not admitted"),
                )
            }

            reconnectTargets[peerDevice.id.value] = Endpoint(host, actualPort)
            reconnectPolicies.remove(peerDevice.id.value)

            FlashResult.Success(session)
        } finally {
            connectingAttempts.decrementAndGet()
            refreshHealthFromSessions()
        }
    }

    override suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit> = withContext(Dispatchers.IO) {
        localDisconnects.add(deviceId.value)
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
                handOffEarlyFrames(connection, session.peerDeviceId)
                session.disconnect("Session not admitted")
            }
        }
    }

    /**
     * Admits [session] under [SessionHardeningPolicy] — concurrency cap, duplicate coalescing,
     * the deterministic connect-glare tiebreaker (ERROR-023) and the same-direction supersede
     * rule (ERROR-031), all identical to the Android original; see its KDoc for the reasoning.
     */
    private fun registerSession(session: WsSession): Boolean {
        var superseded: WsSession? = null
        // The REASON a candidate was refused, computed inside the lock and logged outside it: every
        // line below is I/O (stderr + a file sink on desktop), and the registry lock is shared with
        // every other registration. A `null` here means the session was admitted.
        val rejected = synchronized(registryLock) {
            val existing = sessionsById[session.peerDeviceId]
            val isReplacement = existing != null
            if (!isReplacement && !hardeningPolicy.canAcceptSession(sessionsById.size)) {
                return@synchronized "session cap reached (${sessionsById.size} live)"
            }
            if (existing != null && existing !== session) {
                val keepExisting = if (existing.transportType == session.transportType) {
                    existing.isOutbound != session.isOutbound && resolveGlareTie(existing, session)
                } else {
                    hardeningPolicy.resolveDuplicate(
                        SessionHardeningPolicy.transportRank(existing.transportType),
                        SessionHardeningPolicy.transportRank(session.transportType),
                    ) == com.transfer.flash.core.network.resilience.DuplicateSessionDecision.KeepExisting
                }
                if (keepExisting) {
                    return@synchronized "connect glare: the tiebreaker kept the incumbent " +
                        "incumbent(${describe(existing)}) candidate(${describe(session)})"
                }
                superseded = existing
                sessionByConnection.remove(existing.connection)
            }

            sessionsById[session.peerDeviceId] = session
            sessionByConnection[session.connection] = session
            localDisconnects.remove(session.peerDeviceId.value)
            drainEarlyFrames(session.connection, session)
            drainPendingPeerFrames(session)
            _activeSessions.value = sessionsById.toMap()
            null
        }
        if (rejected != null) {
            // The loser of a connect glare is rejected SILENTLY by contract — its dialer then waits
            // out the full 6 s `ConnectionTimeout` with nothing to distinguish "lost a race" from
            // "the peer is busy". Two engines in one JVM emit the same `I/WS:` lines into one stream,
            // so without the local id this line cannot be attributed to a side, and a run that failed
            // to converge could not be read at all. That is the whole reason it exists.
            FlashLog.i(
                TAG,
                "[session] local=${shortId(localDeviceId)} REJECTED " +
                    "peer=${shortId(session.peerDeviceId.value)} candidate(${describe(session)}) — $rejected",
            )
            return false
        }
        superseded?.let { old ->
            FlashLog.i(
                TAG,
                "[session] local=${shortId(localDeviceId)} ADOPTED " +
                    "peer=${shortId(session.peerDeviceId.value)} candidate(${describe(session)}) " +
                    "over incumbent(${describe(old)})",
            )
            old.disconnect(
                if (old.isOutbound == session.isOutbound) "Superseded by a newer connection"
                else "Superseded by richer path",
            )
        }
        refreshState()
        refreshHealthFromSessions()
        return true
    }

    /**
     * A session's two identifying facts, as the glare tiebreak sees them.
     *
     * The ORIGINATOR is the value [resolveGlareTie] actually compares (mirrored across the pair:
     * A's outbound is B's inbound, and both call its originator A). Printing it beside the direction
     * is what makes a glare decision checkable by eye — "kept outbound/origin-aaa over
     * inbound/origin-aaa" is a same-origin tie, and a tie is a bug, not a preference.
     */
    private fun describe(s: WsSession): String =
        "dir=${if (s.isOutbound) "outbound" else "inbound"} origin=${shortId(originOf(s))}"

    /**
     * The session's ORIGINATOR — the id the tiebreak compares. Our own for a dial we made, the
     * peer's for one we accepted. [resolveGlareTie] uses this same function rather than restating the
     * rule, so a logged decision and the decision itself cannot drift apart.
     */
    private fun originOf(s: WsSession): String =
        if (s.isOutbound) localDeviceId else s.peerDeviceId.value

    private fun shortId(id: String): String = id.take(8)

    private fun resolveGlareTie(existing: WsSession, candidate: WsSession): Boolean {
        val existingOrigin = originOf(existing)
        val candidateOrigin = originOf(candidate)
        return existingOrigin <= candidateOrigin
    }

    private fun drainEarlyFrames(connection: WsConnection, into: WsSession) {
        val queued = earlyFrames.remove(connection) ?: return
        queued.forEach { frame ->
            when (frame) {
                is String -> into.onTextReceived(frame)
                is ByteArray -> into.onBinaryReceived(frame)
            }
        }
    }

    private fun handOffEarlyFrames(connection: WsConnection, peerDeviceId: FlashDeviceId) {
        val survivor = sessionsById[peerDeviceId]
        if (survivor != null && survivor.connection !== connection) {
            drainEarlyFrames(connection, survivor)
            return
        }
        if (survivor != null) {
            earlyFrames.remove(connection)
            return
        }
        // Nobody owns the peer yet — the loser of a glare can finish BEFORE the winner registers.
        // Park the frames per-peer so registerSession can still deliver them (ERROR-031).
        val queued = earlyFrames.remove(connection) ?: return
        if (queued.isEmpty()) return
        val mailbox = pendingPeerFrames.computeIfAbsent(peerDeviceId) { ConcurrentLinkedQueue() }
        queued.forEach { frame ->
            if (mailbox.size >= MAX_PENDING_PEER_FRAMES) {
                mailbox.poll()
            }
            mailbox.add(frame)
        }
    }

    /** Delivers frames parked by [handOffEarlyFrames], once a session finally owns the peer. */
    private fun drainPendingPeerFrames(into: WsSession) {
        val queued = pendingPeerFrames.remove(into.peerDeviceId) ?: return
        while (true) {
            val frame = queued.poll() ?: break
            when (frame) {
                is String -> into.onTextReceived(frame)
                is ByteArray -> into.onBinaryReceived(frame)
            }
        }
    }

    public fun hasLiveSession(deviceId: String): Boolean {
        val session = sessionsById[FlashDeviceId(deviceId)] ?: return false
        if (!session.connection.isOpen) return false
        if (session.connectionState.value != FlashConnectionState.Connected) return false
        return nowMs() - session.connection.lastInboundAtMs <= STALE_SESSION_AFTER_MS
    }

    private fun onSessionDisconnected(session: WsSession) {
        synchronized(registryLock) {
            sessionsById.remove(session.peerDeviceId, session)
            sessionByConnection.remove(session.connection, session)
            earlyFrames.remove(session.connection)
            _activeSessions.value = sessionsById.toMap()
        }
        refreshState()
        refreshHealthFromSessions()

        val peerId = session.peerDeviceId.value
        if (!running.get() || hasLiveSession(peerId)) return
        when {
            reconnectTargets.containsKey(peerId) -> scheduleReconnect(peerId, immediate = false)
            peerId !in localDisconnects -> scheduleReconnect(peerId, immediate = false, backup = true)
        }
    }

    // ------------------------------------------------------------------
    // Reconnect engine
    // ------------------------------------------------------------------

    private fun scheduleReconnect(deviceId: String, immediate: Boolean, backup: Boolean = false) {
        reconnectJobs.remove(deviceId)?.cancel()
        val job = scope.launch {
            var first = true
            while (isActive &&
                running.get() &&
                redialTargetOf(deviceId, backup) != null &&
                !hasLiveSession(deviceId)
            ) {
                val policy = reconnectPolicies.getOrPut(deviceId) {
                    ReconnectPolicy(
                        baseMs = if (backup) backupRedialBaseMs else ReconnectPolicy.DEFAULT_BASE_MS,
                        capMs = transportProfile().reconnectCapMs,
                        random01 = { ThreadLocalRandom.current().nextDouble() },
                    )
                }
                if (!(first && immediate)) {
                    delay(policy.nextDelay())
                }
                first = false

                if (!running.get() || hasLiveSession(deviceId)) break
                val target = redialTargetOf(deviceId, backup) ?: break

                val result = runCatching { connectManual(target.host, target.port, deviceId) }.getOrNull()
                if (result is FlashResult.Success) {
                    break
                }
            }
            reconnectJobs.remove(deviceId)
        }
        reconnectJobs[deviceId] = job
    }

    private fun redialTargetOf(deviceId: String, backup: Boolean): Endpoint? = when {
        !backup -> reconnectTargets[deviceId]
        deviceId in localDisconnects -> null
        else -> reconnectTargets[deviceId] ?: knownEndpoints[deviceId]
    }

    public fun isReconnectInFlight(deviceId: String): Boolean = reconnectJobs.containsKey(deviceId)

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
        session?.onClosed(reason)
    }

    public companion object {
        /** Wire-identical to the Android original — the interop contract. */
        public const val PROTOCOL_VERSION: Int = 2
        private const val TAG = "WS"
        private const val HELLO_PREFIX = "FLASH_WS_HELLO"
        private const val HANDSHAKE_TIMEOUT_MS = 6_000L
        private const val BACKUP_REDIAL_BASE_MS = 4_000L
        private const val STALE_SESSION_AFTER_MS = 45_000L

        /** Per-peer cap on frames parked for a session that has not registered yet. */
        private const val MAX_PENDING_PEER_FRAMES = 64
    }
}
