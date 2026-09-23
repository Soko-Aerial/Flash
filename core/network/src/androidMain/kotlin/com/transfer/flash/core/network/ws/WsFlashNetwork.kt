@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.ws

import android.content.Context
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
public class WsFlashNetwork(
    private val context: Context?,
    private val localDeviceId: String,
    localFriendlyName: String,
    private val tlsOptions: TlsOptions? = null,
    private val hardeningPolicy: SessionHardeningPolicy = SessionHardeningPolicy(),
    private val healthAggregator: ConnectionHealthAggregator = ConnectionHealthAggregator(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * Backoff floor for the **backup** redial loop the accepting side of a session runs
     * (ERROR-026). Deliberately larger than the primary loop's base (ReconnectPolicy.DEFAULT_BASE_MS,
     * 1 s) so the peer that originally dialed us gets first refusal and connect glare stays rare.
     * Injectable so tests need not sleep seconds.
     */
    private val backupRedialBaseMs: Long = BACKUP_REDIAL_BASE_MS,
    /** Clock for session-freshness checks ([hasLiveSession]); injectable so tests need not wait. */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Called when the platform reports a usable network (Wi-Fi/Ethernet available), on the
     * ConnectivityManager callback thread, just before the redial sweep below.
     *
     * The app layer uses it to retry a foreground-service promotion that was refused while the
     * process was backgrounded (ERROR-031 / D7): a rejoin is one of the few moments the retry can
     * succeed, and it is already being observed here. `core:network` knows nothing about foreground
     * services, and its watcher is `internal`, so the moment is surfaced rather than duplicated by a
     * second ConnectivityManager callback in `:app`. Must not throw; exceptions are swallowed so a
     * host-side failure cannot cost the mesh its rejoin sweep.
     */
    private val onUsableNetwork: () -> Unit = {},
    /**
     * The device's transport tier (ERROR-033), read per use.
     *
     * A lambda rather than a value so pinning a tier from Settings reaches the next connection and
     * the next redial without restarting the engine, and so `core:network` needs no knowledge of
     * where the tier came from (ADR-024). Defaults to [FlashPerformanceMode.HIGH]'s profile — the
     * stack's shipped numbers — which makes the untiered call sites below a provable no-op.
     */
    private val transportProfile: () -> FlashTransportProfile = { FlashPerformanceMode.HIGH.transport },
) : FlashNetwork, EndpointMemory, WsConnection.Listener {

    @Volatile
    public var localFriendlyName: String = localFriendlyName

    private val running = AtomicBoolean(false)
    private var server: WsTransferServer? = null
    public val serverPort: Int get() = server?.listenPort ?: 0
    private val client = WsTransferClient(context, this, tlsOptions, keepalive = ::keepaliveTiming)

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

    // ------------------------------------------------------------------
    // #18: outbound reconnect engine (wires the previously-dead ReconnectPolicy +
    // AndroidNetworkWatcher into the live WS path). Sessions WE dialed (connectManual)
    // get a reconnect target and redial it with exponential-backoff-with-jitter; a fresh
    // network (Wi-Fi rejoin) collapses the pending backoff to an immediate attempt.
    // Inbound-only peers have no target, so ERROR-026 added a slower BACKUP loop for them
    // off `knownEndpoints` — "the dialer will redial itself" is only true while the dialer's
    // process is actually scheduled, which is exactly what screen-off/Doze breaks.
    // Heartbeat liveness is already handled at the connection layer (WsConnection PING +
    // no-inbound watchdog, ADR-016), so the HeartbeatTracker state machine stays the
    // TCP/LanSession path's concern.
    // ------------------------------------------------------------------

    /** deviceId -> last route we dialed, kept so we can redial after an unexpected drop. */
    private val reconnectTargets = ConcurrentHashMap<String, Endpoint>()

    /** deviceId -> in-flight reconnect loop (one per peer). */
    private val reconnectJobs = ConcurrentHashMap<String, Job>()

    /** deviceId -> its backoff counter; reset on a stable (re)connect. */
    private val reconnectPolicies = ConcurrentHashMap<String, ReconnectPolicy>()

    /**
     * Peers we tore down locally via [disconnect]. ERROR-026 gave the ACCEPTING side of a session a
     * backup redial path, and that path must still honour an explicit local disconnect.
     * [reconnectTargets] used to carry that intent implicitly — removing the target meant "do not
     * redial" — but an inbound-only peer has no target to remove, so the intent is recorded here
     * instead. Cleared whenever a session for the peer is admitted again (any successful connect, in
     * either direction, means the peer is wanted).
     */
    private val localDisconnects = ConcurrentHashMap.newKeySet<String>()

    private var networkWatcher: AndroidNetworkWatcher? = null

    /**
     * Frames that arrive on a connection AFTER its HELLO completed but BEFORE the local
     * [WsSession] is registered (the peer may start streaming immediately after ITS
     * registration). They are buffered per connection and flushed into the session on
     * registration instead of being silently dropped (fix for early-frame race).
     */
    private val earlyFrames = ConcurrentHashMap<WsConnection, ConcurrentLinkedQueue<Any>>()

    /**
     * Frames rescued from a connection that died before ANY session owned its peer (glare loser
     * finishing first). Keyed by peer so the winner drains them at registration. Bounded: a peer
     * that never registers must not grow this without limit.
     */
    private val pendingPeerFrames = ConcurrentHashMap<FlashDeviceId, ConcurrentLinkedQueue<Any>>()

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
            keepalive = ::keepaliveTiming,
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
        localDisconnects.clear()

        // Pending handshakes hold live sockets with active read loops — close, don't just forget.
        pendingHandshakes.keys.forEach { it.close("Network stopped") }
        pendingHandshakes.clear()
        earlyFrames.clear()
        pendingPeerFrames.clear()

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

        // Signal "Connecting" for the duration of the dial + handshake; the finally clause always
        // clears it so a failed/timed-out attempt can never wedge health in Connecting.
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
                // Policy rejected (concurrency cap hit, or an equal/richer incumbent already holds
                // this peer). Tear down our freshly-opened socket so it cannot keep pumping frames —
                // but first move anything the peer already streamed on it to the surviving session.
                handOffEarlyFrames(connection, session.peerDeviceId)
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
        // reschedule the peer we just asked to leave. ERROR-026: record the intent explicitly too, because
        // an inbound-only peer has no reconnect target whose absence could encode it.
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

    /**
     * Why an inbound connection's certificate does not prove the id its HELLO claims, or null when it
     * does (audit S1). Runs the same [com.transfer.flash.core.network.tls.FlashPinVerifier] check an
     * outbound dial runs inside its handshake: trust-on-first-use records a first contact's key, and
     * a different key answering for an already-pinned id is refused.
     */
    private fun inboundIdentityFailure(connection: WsConnection, claimedDeviceId: String): String? {
        // Plaintext exists only in tests: production engines cannot start without TLS (audit S3).
        val tls = tlsOptions ?: return null
        val leaf = connection.deferredPeerLeafFingerprintHex
        if (leaf == null) {
            // Pin already evaluated during the handshake against a configured expected id; the
            // claim must then name that same device.
            val expected = tls.expectedDeviceId
            return if (expected != null && expected == claimedDeviceId) null
            else "no client certificate bound to the claimed id"
        }
        return if (tls.pinVerifier.isPinned(claimedDeviceId, leaf)) null
        else "client certificate does not match the pin for the claimed id"
    }

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

            // Audit S1: bind the client's TLS certificate to the id its HELLO claims BEFORE we reply
            // or register. Without this, any LAN device could claim a paired contact's id (they are
            // broadcast in mDNS) and replace that contact's session.
            val claimedPeer = outcome.getOrThrow()
            inboundIdentityFailure(connection, claimedPeer.id.value)?.let { reason ->
                FlashLog.w(
                    TAG,
                    "[tls] inbound peer rejected: $reason peer=${shortId(claimedPeer.id.value)} " +
                        "remote=${connection.remoteLabel}",
                )
                connection.close("Identity binding failed")
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

            val session = WsSession(connection, claimedPeer) { s, _ ->
                onSessionDisconnected(s)
            }
            if (!registerSession(session)) {
                handOffEarlyFrames(connection, session.peerDeviceId)
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
     * - **Connect-glare tiebreaker (ERROR-023):** when a second LAN session for the SAME peer
     *   arrives via the opposite direction (our inbound vs our outbound dial), rank is a tie
     *   (LAN=LAN), and `KeepExisting` alone would leave each side keeping whichever registered
     *   first — a coin flip that ~50% of the time cross-wires the two ends onto different sockets
     *   of the same TCP pair, both of which then die and re-glare forever. Break the tie
     *   DETERMINISTICALLY from the only data both devices share: keep the session whose originator
     *   id (`isOutbound` ? local : peer) is lexicographically smaller. Because device A's outbound
     *   IS device B's inbound (the same TCP pair), both ends compute the same winner and converge
     *   on one live socket. The loser's socket is closed by the caller's "Session not admitted"
     *   path on the accepting side, and on the dialing side by the local `registerSession`
     *   returning false.
     * - **Sequential reconnect, newest wins (ERROR-031):** two sessions for one peer in the SAME
     *   direction are not glare at all — they are two different TCP pairs, and the originator has
     *   already moved on to the newer one. The tiebreaker above cannot judge them (both sessions
     *   have the same originator, so it always ties and always kept the incumbent), which let a
     *   stale inbound session veto the peer's fully-handshaked reconnect indefinitely: the dot said
     *   Online, writes into the dead socket "succeeded", and only force-stopping the app cleared it.
     *   Same direction therefore always supersedes.
     *
     * The compound check-then-mutate runs under [registryLock] so a simultaneous inbound+outbound
     * glare for the same peer cannot both be admitted.
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
                    // Same rank: a deterministic tiebreak is meaningful only for real glare, i.e.
                    // the two directions of ONE TCP pair. Same-direction duplicates are sequential
                    // reconnects and the newcomer always wins.
                    existing.isOutbound != session.isOutbound && resolveGlareTie(existing, session)
                } else {
                    hardeningPolicy.resolveDuplicate(
                        SessionHardeningPolicy.transportRank(existing.transportType),
                        SessionHardeningPolicy.transportRank(session.transportType),
                    ) == DuplicateSessionDecision.KeepExisting
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
            // ERROR-026: a live session means the peer is wanted again, so lift any stale
            // local-disconnect veto — a later unexpected drop is then eligible for the backup redial.
            localDisconnects.remove(session.peerDeviceId.value)

            // Flush any frames that arrived between handshake completion and registration, then
            // any parked by a connection that lost the glare before this one registered.
            drainEarlyFrames(session.connection, session)
            drainPendingPeerFrames(session)

            _activeSessions.value = sessionsById.toMap()
            null
        }
        if (rejected != null) {
            // The loser of a connect glare is rejected SILENTLY by contract — its dialer then waits
            // out the full 6 s `ConnectionTimeout` with nothing to distinguish "lost a race" from
            // "the peer is busy". Two endpoints emit the same `I/WS:` lines into one stream (two
            // engines in one JVM during the auto-dial test), so without the local id this line cannot
            // be attributed to a side, and a run that failed to converge could not be read at all.
            // That is the whole reason it exists.
            FlashLog.i(
                TAG,
                "[session] local=${shortId(localDeviceId)} REJECTED " +
                    "peer=${shortId(session.peerDeviceId.value)} candidate(${describe(session)}) — $rejected",
            )
            return false
        }
        // Close the incumbent only AFTER the newcomer is in the registry. WsSession.disconnect fires
        // its onDisconnected callback synchronously, so onSessionDisconnected re-enters here; with
        // the slot already refilled it correctly declines to schedule a redial against the session
        // we just admitted.
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

    /**
     * Deterministic connect-glare tiebreaker (ERROR-023) for the two directions of ONE TCP pair.
     * Returns true to keep [existing] and reject [candidate].
     *
     * Converges both devices on ONE socket by comparing the session ORIGINATOR ids, which are
     * mirrored across the pair: A's outbound session (originator = A) is B's inbound session
     * (originator = A too, because B sees the peer's id A in its inbound HELLO). Keeping the
     * session whose originator id is lexicographically smaller therefore makes A and B both keep
     * the SAME underlying TCP pair — no coin-flip cross-wiring, no reconnect storm.
     *
     * Only meaningful when the two sessions face opposite ways. Two same-direction sessions share an
     * originator, so this always ties — and a tie kept the incumbent, which is how a stale session
     * came to veto every reconnect the peer made (ERROR-031). [registerSession] therefore calls this
     * only for `existing.isOutbound != candidate.isOutbound`.
     */
    private fun resolveGlareTie(existing: WsSession, candidate: WsSession): Boolean {
        val existingOrigin = originOf(existing)
        val candidateOrigin = originOf(candidate)
        // Prefer the session whose ORIGINATOR is the smaller id.
        return existingOrigin <= candidateOrigin
    }

    /**
     * Moves frames buffered on [connection] into [into], which has just taken ownership of them.
     * Removes the queue: whoever calls this owns the frames from then on.
     */
    private fun drainEarlyFrames(connection: WsConnection, into: WsSession) {
        val queued = earlyFrames.remove(connection) ?: return
        queued.forEach { frame ->
            when (frame) {
                is String -> into.onTextReceived(frame)
                is ByteArray -> into.onBinaryReceived(frame)
            }
        }
    }

    /**
     * Rescues frames buffered on a connection that is about to be torn down, handing them to
     * whichever session now owns [peerDeviceId].
     *
     * A candidate connection can already have carried real chat or signaling frames before the
     * admission decision — the peer starts streaming as soon as ITS side registers — and dropping an
     * inbound chat frame means the sender never receives a `DeliveryReceipt` and sits on a single
     * tick forever (ERROR-031). Frames are per-peer, not per-socket, so the surviving session is the
     * right consumer.
     */
    private fun handOffEarlyFrames(connection: WsConnection, peerDeviceId: FlashDeviceId) {
        val survivor = sessionsById[peerDeviceId]
        if (survivor != null && survivor.connection !== connection) {
            drainEarlyFrames(connection, survivor)
            return
        }
        if (survivor != null) {
            // This connection IS the live session's own: its frames are delivered normally.
            earlyFrames.remove(connection)
            return
        }
        // Nobody owns the peer yet — the loser of a glare can finish BEFORE the winner registers.
        // Park the frames per-peer (not per-socket) so registerSession can still deliver them;
        // dropping them here costs the sender its DeliveryReceipt forever (ERROR-031).
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

    /**
     * Delivers frames parked by [handOffEarlyFrames] for this peer, once a session finally owns it.
     */
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

    /**
     * True while [deviceId] has a session that is not merely *present* but demonstrably carrying
     * traffic — open, Connected, and with an inbound frame (any frame, including a keepalive PONG)
     * inside [STALE_SESSION_AFTER_MS].
     *
     * Every recovery path used to gate on map presence alone, so a session whose socket had died
     * without the watchdog noticing blocked its own replacement: the auto-connect gate refused to
     * begin, the sweep skipped the peer, and the Wi-Fi-rejoin callback skipped it too (ERROR-031).
     * Freshness is the honest question, and it is what [registerSession]'s same-direction supersede
     * rule needs in order to ever be reached.
     */
    public fun hasLiveSession(deviceId: String): Boolean {
        val session = sessionsById[FlashDeviceId(deviceId)] ?: return false
        if (!session.connection.isOpen) return false
        if (session.connectionState.value != FlashConnectionState.Connected) return false
        return nowMs() - session.connection.lastInboundAtMs <= STALE_SESSION_AFTER_MS
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
        // network is still running, and no live session has since been re-established for the peer.
        //
        // ERROR-026: the ACCEPTING side used to get no recovery here at all. reconnectTargets is only
        // ever written by connectManual, so over a Wi-Fi hotspot the host's sessions are all inbound
        // and containsKey was false for every one of them — the host just waited for the dialer to
        // notice and come back, which never happens if the dialer's process was frozen through the
        // drop. It now runs a slower BACKUP loop off knownEndpoints (the discovery-fed route table),
        // unless the teardown was a deliberate local disconnect.
        //
        // ERROR-031: the "already reconnected" guard asks whether the replacement is LIVE, not merely
        // present. A stale session in the map used to suppress the very redial that would have
        // replaced it.
        val peerId = session.peerDeviceId.value
        if (!running.get() || hasLiveSession(peerId)) return
        when {
            reconnectTargets.containsKey(peerId) -> scheduleReconnect(peerId, immediate = false)
            peerId !in localDisconnects -> scheduleReconnect(peerId, immediate = false, backup = true)
        }
    }

    // ------------------------------------------------------------------
    // #18: reconnect engine
    // ------------------------------------------------------------------

    /**
     * (Re)launches the backoff redial loop for [deviceId]. At most one loop runs per peer; an existing
     * loop is cancelled and replaced (so a network-available "collapse to immediate" can pre-empt a
     * loop that is currently sleeping out its backoff). The loop exits as soon as the peer reconnects,
     * its redial route disappears (local disconnect / stop / discovery forgot the route), or the
     * network stops.
     *
     * @param immediate skip the first backoff delay (used by the network watcher on Wi-Fi rejoin).
     * @param backup run as the accepting side's safety net (ERROR-026): dial the discovery-known
     *   route instead of a remembered dial target, and start from the larger [backupRedialBaseMs]
     *   floor so the original dialer's faster loop usually wins the race.
     */
    private fun scheduleReconnect(deviceId: String, immediate: Boolean, backup: Boolean = false) {
        reconnectJobs.remove(deviceId)?.cancel()
        val job = scope.launch {
            var first = true
            // ERROR-031: the exit condition is a LIVE session, not a session-shaped map entry. A
            // socket that died without the watchdog noticing used to end this loop on its first
            // iteration, and [registerSession]'s same-direction supersede — the thing that would
            // have replaced it — was never reached.
            while (isActive &&
                running.get() &&
                redialTargetOf(deviceId, backup) != null &&
                !hasLiveSession(deviceId)
            ) {
                val policy = reconnectPolicies.getOrPut(deviceId) {
                    ReconnectPolicy(
                        baseMs = if (backup) backupRedialBaseMs else ReconnectPolicy.DEFAULT_BASE_MS,
                        // ERROR-033: the single most load-bearing number for "does this device come
                        // back". A device that needs seconds to reassociate on a mesh roam is
                        // exactly the device that must not then wait out a 30 s ceiling before its
                        // next attempt — two seconds of radio outage used to become half a minute
                        // of "offline". Read at loop start, so a tier pinned mid-outage applies to
                        // the loop that is still running.
                        capMs = transportProfile().reconnectCapMs,
                        random01 = { ThreadLocalRandom.current().nextDouble() },
                    )
                }
                if (!(first && immediate)) {
                    delay(policy.nextDelay())
                }
                first = false

                // Re-check after the sleep: state may have changed while we backed off.
                if (!running.get() || hasLiveSession(deviceId)) break
                val target = redialTargetOf(deviceId, backup) ?: break

                val result = runCatching { connectManual(target.host, target.port, deviceId) }.getOrNull()
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
     * Route a redial loop should dial for [deviceId], or null to stop the loop.
     *
     * Primary loops consult only [reconnectTargets] — the route we actually dialed — so clearing the
     * target still terminates them exactly as before. Backup loops (ERROR-026) fall back to
     * [knownEndpoints], the discovery-maintained route table, and refuse to run once the peer has
     * been locally disconnected.
     */
    private fun redialTargetOf(deviceId: String, backup: Boolean): Endpoint? = when {
        !backup -> reconnectTargets[deviceId]
        deviceId in localDisconnects -> null
        else -> reconnectTargets[deviceId] ?: knownEndpoints[deviceId]
    }

    /**
     * True while a backoff reconnect loop is currently (re)dialing [deviceId] — i.e. a reconnect
     * is already in flight from the #18 engine. The auto-connect sweep uses this to skip peers it
     * would otherwise redundantly dial at the same moment, cutting connect-glare re-entry
     * (ERROR-023).
     */
    public fun isReconnectInFlight(deviceId: String): Boolean = reconnectJobs.containsKey(deviceId)

    /**
     * Starts the [AndroidNetworkWatcher] (no-op when [context] is null, e.g. JVM tests). On a fresh
     * usable network (Wi-Fi/Ethernet available) it collapses every pending peer backoff to an
     * immediate attempt instead of waiting the loop out; on a *link change* within the same network
     * (a mesh roam) it probes the sessions it already has — see [probeSessionsAfterLinkChange].
     */
    private fun startNetworkWatcher() {
        val ctx = context ?: return
        if (networkWatcher != null) return
        networkWatcher = AndroidNetworkWatcher(
            context = ctx,
            onAvailable = onAvailable@{
                // Host hook first and unconditionally (D7): if the process lost its foreground
                // service on the previous network, it should try to get it back now, whether or not
                // the mesh itself is still running.
                runCatching { onUsableNetwork() }
                if (!running.get()) return@onAvailable
                sweepDisconnectedPeers()
            },
            onLinkChanged = onLinkChanged@{
                if (!running.get()) return@onLinkChanged
                scope.launch { probeSessionsAfterLinkChange() }
            },
        ).also { it.start() }
    }

    /**
     * Gives every peer without a live session an immediate fresh redial attempt.
     *
     * ERROR-026: sweeps discovery-known peers too, not just ones we dialed, so the accepting side
     * also redials on a Wi-Fi rejoin. Peers we deliberately disconnected stay excluded
     * ([redialTargetOf] enforces the same rule inside the loop).
     *
     * ERROR-031: a rejoin is exactly when a session held over from the OLD network is most likely to
     * be a zombie, so this skips only peers whose session is demonstrably carrying traffic.
     *
     * Dropping the peer's [ReconnectPolicy] is the point of running this at all: a link event means
     * the *reason* for the previous failures is gone, so the accumulated backoff is now
     * misinformation.
     */
    private fun sweepDisconnectedPeers() {
        (reconnectTargets.keys + knownEndpoints.keys).forEach { deviceId ->
            if (hasLiveSession(deviceId)) return@forEach
            if (deviceId in localDisconnects) return@forEach
            val backup = !reconnectTargets.containsKey(deviceId)
            reconnectPolicies.remove(deviceId) // fresh link → restart backoff from base
            scheduleReconnect(deviceId, immediate = !backup, backup = backup)
        }
    }

    /**
     * Answers a suspected Wi-Fi roam (ERROR-033) by asking every session to prove itself, and
     * reaping only the ones that cannot.
     *
     * ## Why probe instead of reconnect
     *
     * The signal from [AndroidNetworkWatcher] is a hint — there is no permission-free way to *know*
     * a roam happened (see `LinkChangeTracker`). Tearing sessions down on a hint would turn a false
     * positive into a real outage, so the response is one PING per peer: a session still carried by
     * the new association answers within a round trip and is left completely alone, and a session
     * stranded on the old one answers with nothing and is closed *now* rather than in the 25-40 s
     * the liveness watchdog would take to reach the same conclusion. Closing it is what starts the
     * redial loop, which is the actual recovery.
     *
     * That difference is the whole Belfone bug: a mesh hand-off keeps the same `Network` object, so
     * `onAvailable`/`onLost` never fire, and nothing at all used to happen here. The Pixel and the
     * Infinix survived on radio behaviour alone; the Belfone's session sat dead until the watchdog
     * noticed, and then waited out a 30 s reconnect ceiling on top.
     *
     * ## Why the test is movement, not freshness
     *
     * A session that received a frame moments before the roam would pass a naive "is it fresh?"
     * check while having proven nothing about the *new* association. So the stamp is snapshotted
     * before the PING goes out and must strictly advance past that value. A session opened after the
     * snapshot is simply not in it, and one opened just before still has to answer like everyone
     * else — [WsConnection.start] stamps its birth, so a silent newborn is correctly suspicious.
     */
    private suspend fun probeSessionsAfterLinkChange() {
        val probeWindowMs = transportProfile().linkChangeProbeMs
        val probed = sessionsById.values
            .filter { it.connection.isOpen }
            .associateWith { it.connection.lastInboundAtMs }
        if (probed.isNotEmpty()) {
            FlashLog.i(TAG, "Link change: probing ${probed.size} session(s), window=${probeWindowMs}ms")
            probed.keys.forEach { session -> runCatching { session.connection.sendPing() } }
            delay(probeWindowMs)
            probed.forEach { (session, stampBefore) ->
                if (!running.get()) return
                if (!session.connection.isOpen) return@forEach
                if (session.connection.lastInboundAtMs > stampBefore) return@forEach
                FlashLog.w(TAG, "Link change: peer=${session.peerDeviceId.value} did not answer probe; reaping")
                // Closing drives onConnectionClosed -> WsSession.onClosed -> onSessionDisconnected,
                // which is what schedules the redial. Nothing else to do here.
                session.connection.close("Link change: unanswered probe")
            }
        }

        // Whether or not anything was reaped, a roam is a fresh chance for every peer that has no
        // live session — including the ones just closed above.
        if (!running.get()) return
        sweepDisconnectedPeers()
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

            // Raises the read cap from 64 KiB to the post-handshake limit (audit S5).
            connection.markPeerHelloAccepted()
            pendingHandshakes[connection]?.complete(peerDevice)
        } else {
            // Not yet registered: buffer instead of dropping (early-frame race).
            bufferEarlyFrame(connection, text)
        }
    }

    override fun onBinaryMessage(connection: WsConnection, data: ByteArray) {
        val session = sessionByConnection[connection]
        if (session != null) {
            session.onBinaryReceived(data)
        } else {
            bufferEarlyFrame(connection, data)
        }
    }

    /**
     * Buffers a frame that arrived after the peer's HELLO but before its session registered (the
     * early-frame race), refusing everything else (audit S5).
     *
     * A legitimate peer always sends `FLASH_WS_HELLO` first, so a frame before it is a protocol
     * violation, and buffering it would let an unauthenticated socket grow this queue without limit.
     * After HELLO the window lasts milliseconds, so it is bounded by count and by bytes.
     */
    private fun bufferEarlyFrame(connection: WsConnection, frame: Any) {
        if (!connection.peerHelloAccepted) {
            FlashLog.w(TAG, "[ws] frame before HELLO from ${connection.remoteLabel}; closing")
            connection.close("Protocol violation: frame before HELLO")
            return
        }
        val queue = earlyFrameQueue(connection)
        val queuedBytes = queue.sumOf { earlyFrameBytes(it) }
        if (queue.size >= MAX_EARLY_FRAMES || queuedBytes + earlyFrameBytes(frame) > MAX_EARLY_FRAME_BYTES) {
            FlashLog.w(TAG, "[ws] early-frame budget exceeded from ${connection.remoteLabel}; closing")
            earlyFrames.remove(connection)
            connection.close("Too many frames before registration")
            return
        }
        queue.add(frame)
    }

    private fun earlyFrameBytes(frame: Any): Long = when (frame) {
        is String -> frame.length.toLong() * 2
        is ByteArray -> frame.size.toLong()
        else -> 0L
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

    public companion object {
        public const val PROTOCOL_VERSION: Int = 2
        private const val TAG = "WS"
        private const val HELLO_PREFIX = "FLASH_WS_HELLO"
        private const val HANDSHAKE_TIMEOUT_MS = 6_000L

        /**
         * Backoff floor for the accepting side's backup redial (ERROR-026). Above the primary loop's
         * 1 s base so the original dialer's first attempts land first, and below the app-level 5 s
         * auto-connect sweep so recovery does not have to wait for the sweep.
         */
        private const val BACKUP_REDIAL_BASE_MS = 4_000L

        /**
         * How long a session may go without a single inbound frame before [hasLiveSession] stops
         * vouching for it. Deliberately above the connection's own watchdog budget
         * ([WsConnection.DEFAULT_LIVENESS_TIMEOUT_MS] plus its stall-confirm delay), so in the normal
         * case the watchdog reaps a dead session before any recovery path has to second-guess it.
         * This only catches the case the watchdog cannot: a keepalive coroutine Android never
         * schedules again, whose session would otherwise sit in the registry forever (ERROR-031).
         */
        private const val STALE_SESSION_AFTER_MS = 45_000L

        /** Per-peer cap on frames parked for a session that has not registered yet. */
        private const val MAX_PENDING_PEER_FRAMES = 64

        /** Early-frame budget per connection (see bufferEarlyFrame). */
        private const val MAX_EARLY_FRAMES = 64
        private const val MAX_EARLY_FRAME_BYTES = 8L * 1024L * 1024L
    }
}
