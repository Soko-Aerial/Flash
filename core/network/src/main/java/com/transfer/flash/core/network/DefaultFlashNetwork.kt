package com.transfer.flash.core.network

import android.content.Context
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.resilience.AndroidNetworkWatcher
import com.transfer.flash.core.network.resilience.ConnectionHealthAggregator
import com.transfer.flash.core.network.resilience.DuplicateSessionDecision
import com.transfer.flash.core.network.resilience.ReconnectPolicy
import com.transfer.flash.core.network.resilience.SessionHardeningPolicy
import com.transfer.flash.core.network.tcp.LanConnectionProbe
import com.transfer.flash.core.network.tcp.LanProbeHello
import com.transfer.flash.core.network.tcp.LanProbeServer
import com.transfer.flash.core.network.tcp.LanSession
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete [FlashNetwork] over the LAN TCP stack (plan C4.5/C4.7 composition).
 *
 * Composes:
 * - [LanProbeServer] (inbound) + [LanConnectionProbe] (outbound) transports,
 * - [SessionHardeningPolicy] — concurrent-session cap + duplicate-device coalescing,
 * - [ConnectionHealthAggregator] — feeds [connectionHealth] for UI-030/UI-044,
 * - [ReconnectPolicy] + [AndroidNetworkWatcher] — auto-reconnect with instant
 *   network-available trigger ([retryConnection] is the manual entry point).
 *
 * Endpoint memory: [connect] needs host/port which [FlashDevice] does not carry;
 * the discovery layer calls [rememberEndpoint] per discovered peer (C3→C4 seam,
 * wired in :core:engine). TLS upgrade lands via SecureSocketUpgrader at the WS
 * layer today and LAN sessions next (tracked debt item, AGENTS.md §19).
 */
internal class DefaultFlashNetwork(
    context: Context?,
    private val localDeviceId: String,
    private val localFriendlyName: String,
    private val hardeningPolicy: SessionHardeningPolicy = SessionHardeningPolicy(),
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(
        random01 = { kotlin.random.Random.nextDouble() },
    ),
    private val healthAggregator: ConnectionHealthAggregator = ConnectionHealthAggregator(),
    private val logger: com.transfer.flash.core.network.tcp.LanSessionLogger = com.transfer.flash.core.network.tcp.LanSessionLogger.ANDROID,
    /** Test seam: null watcher skips platform network callbacks (JVM tests). */
    private val watcherFactory: ((onAvailable: () -> Unit, onLost: () -> Unit) -> AndroidNetworkWatcher?)? =
        if (context != null) {
            { onAvailable, onLost -> AndroidNetworkWatcher(context, onAvailable, onLost) }
        } else {
            null
        },
    /** Test seam: outbound transport when no Android context is available. */
    private val probeOverride: LanConnectionProbe? = null,
) : FlashNetwork, com.transfer.flash.core.network.bridge.EndpointMemory {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)

    private var probe: LanConnectionProbe? =
        probeOverride ?: context?.let { LanConnectionProbe(it, localDeviceId, localFriendlyName) }
    private var server: LanProbeServer? = null
    private var watcher: AndroidNetworkWatcher? = null
    private var reconnectJob: Job? = null

    private val lock = Any()
    private val sessionsById = HashMap<FlashDeviceId, FlashSession>()
    private val knownEndpoints = HashMap<String, Endpoint>() // deviceId -> last-seen route
    private val connectingAttempts = java.util.concurrent.atomic.AtomicInteger(0)

    private data class Endpoint(val host: String, val port: Int, val failedAtMs: Long)

    @Volatile
    private var lastRetryTarget: Endpoint? = null

    private val _networkState = MutableStateFlow(FlashNetworkState())
    override val networkState: StateFlow<FlashNetworkState> = _networkState.asStateFlow()

    private val _activeSessions =
        MutableStateFlow<Map<FlashDeviceId, FlashSession>>(emptyMap())
    override val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>> =
        _activeSessions.asStateFlow()

    override val connectionHealth: StateFlow<FlashConnectionHealth> =
        healthAggregator.health

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    override suspend fun start(listenPort: Int): FlashResult<Int> {
        if (running.get()) return FlashResult.Success(server?.port ?: 0)
        running.set(true)
        watcher = watcherFactory?.invoke(
            { scope.launch { attemptImmediateReconnect() } },
            { /* network lost: sessions will die naturally; backoff handles rest */ },
        )?.also { it.start() }

        val serverImpl = LanProbeServer(
            deviceId = localDeviceId,
            friendlyName = localFriendlyName,
            onPeerProbed = ::onInboundSession,
            onPeerDisconnected = { hello -> onSessionClosed(hello, "") },
            logger = logger,
        )
        server = serverImpl
        val startedPort = runCatching { serverImpl.start() }.getOrElse {
            running.set(false)
            return FlashResult.Failure(
                FlashError.NetworkUnavailable("LAN server start failed: ${it.message}"),
            )
        }
        refreshState()
        return FlashResult.Success(startedPort)
    }

    override suspend fun stop(): FlashResult<Unit> {
        running.set(false)
        stopReconnectLoop()
        watcher?.stop()
        watcher = null
        synchronized(lock) {
            sessionsById.values.forEach { session ->
                runCatching { session.disconnect("Network stopped") }
            }
            sessionsById.clear()
        }
        _activeSessions.value = emptyMap()
        server?.stop()
        server = null
        connectingAttempts.set(0)
        refreshHealthFromSessions()
        refreshState()
        return FlashResult.Success(Unit)
    }

    // ------------------------------------------------------------------
    // Endpoints (C3→C4 seam)
    // ------------------------------------------------------------------

    /** Records a connectable route for a peer (called by discovery consumers). */
    override fun rememberEndpoint(deviceId: String, host: String, port: Int) {
        synchronized(lock) { knownEndpoints[deviceId] = Endpoint(host, port, 0L) }
    }

    /** #17: discovery dropped this peer — forget its route so `peerCountDiscovered` shrinks. */
    override fun forgetEndpoint(deviceId: String) {
        val removed = synchronized(lock) { knownEndpoints.remove(deviceId) != null }
        if (removed) refreshState()
    }

    // ------------------------------------------------------------------
    // Connect / disconnect
    // ------------------------------------------------------------------

    override suspend fun connect(device: FlashDevice): FlashResult<FlashSession> {
        val endpoint = synchronized(lock) { knownEndpoints[device.id.value] }
            ?: return FlashResult.Failure(
                FlashError.PeerUnavailable(device.id.value, "No remembered endpoint; call rememberEndpoint first"),
            )
        return connectTo(endpoint.host, endpoint.port, device)
    }

    override suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession> =
        connectTo(host, port, null)

    private suspend fun connectTo(
        host: String,
        port: Int,
        device: FlashDevice?,
    ): FlashResult<FlashSession> {
        val impl = probe ?: return FlashResult.Failure(
            FlashError.NetworkUnavailable("No transport available"),
        )
        connectingAttempts.incrementAndGet()
        refreshHealthFromSessions()
        try {
            val result = impl.connectSession(host, port, device?.id?.value) { hello, reason ->
                onSessionClosed(hello, reason)
            }
            return result.fold(
                onSuccess = { session ->
                    reconnectPolicy.reset()
                    lastRetryTarget = null
                    if (!registerSession(session.peerInfo, session, inbound = false)) {
                        session.close("Duplicate session")
                        return FlashResult.Failure(
                            FlashError.PeerUnavailable(session.peerDeviceId.value, "Session already active"),
                        )
                    }
                    FlashResult.Success(session)
                },
                onFailure = { error ->
                    lastRetryTarget = Endpoint(host, port, System.currentTimeMillis())
                    scheduleAutoReconnect()
                    FlashResult.Failure(
                        FlashError.PeerUnavailable(
                            device?.id?.value ?: host,
                            error.message ?: "Connect failed",
                        ),
                    )
                },
            )
        } finally {
            connectingAttempts.decrementAndGet()
            refreshHealthFromSessions()
        }
    }

    override suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit> {
        val session = synchronized(lock) { sessionsById.remove(deviceId) }
            ?: return FlashResult.Failure(FlashError.PeerUnavailable(deviceId.value, "No active session"))
        _activeSessions.value = sessionsByIdSnapshot() - deviceId
        session.disconnect("Local disconnect")
        refreshHealthFromSessions()
        return FlashResult.Success(Unit)
    }

    // ------------------------------------------------------------------
    // Manual + automatic reconnection (C4.7 / C4.2 upgrades 1-2)
    // ------------------------------------------------------------------

    override fun retryConnection(): Boolean {
        val target = lastRetryTarget ?: return false
        stopReconnectLoop()
        reconnectJob = scope.launch { reconnectLoop(target.host, target.port) }
        return true
    }

    private fun scheduleAutoReconnect() {
        if (!running.get()) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch { lastRetryTarget?.let { reconnectLoop(it.host, it.port) } }
    }

    private suspend fun attemptImmediateReconnect() {
        // Network came back: bypass backoff entirely (plan C4.2 upgrade 2).
        lastRetryTarget?.let { target ->
            stopReconnectLoop()
            reconnectJob = scope.launch {
                val impl = probe ?: return@launch
                val result = impl.connectSession(target.host, target.port, null) { hello, reason ->
                    onSessionClosed(hello, reason)
                }
                result.fold(
                    onSuccess = { session ->
                        reconnectPolicy.reset()
                        registerSession(session.peerInfo, session, inbound = false)
                    },
                    onFailure = { scheduleBackoffLoop(target.host, target.port) },
                )
            }
        }
    }

    private fun scheduleBackoffLoop(host: String, port: Int) {
        if (!running.get()) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch { reconnectLoop(host, port) }
    }

    private suspend fun reconnectLoop(host: String, port: Int) {
        while (running.get() && scope.isActive) {
            // Full-jitter delay per attempt; give-up only when a bound was
            // explicitly configured on the policy (P2P default: never).
            val delayMs = reconnectPolicy.nextDelay()
            logger.log(com.transfer.flash.core.network.tcp.LanSessionLogger.INFO, TAG, "Reconnecting $host:$port in $delayMs ms", null)
            delay(delayMs)
            if (!running.get()) return
            val impl = probe ?: return
            val result = impl.connectSession(host, port, null) { hello, reason ->
                onSessionClosed(hello, reason)
            }
            result.fold(
                onSuccess = { session ->
                    reconnectPolicy.reset()
                    lastRetryTarget = null
                    registerSession(session.peerInfo, session, inbound = false)
                    return
                },
                onFailure = { /* loop continues with next jittered delay */ },
            )
        }
    }

    private fun stopReconnectLoop() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    // ------------------------------------------------------------------
    // Session registry (hardened, plan C4.5)
    // ------------------------------------------------------------------

    private fun onInboundSession(hello: LanProbeHello, host: String, session: LanSession) {
        rememberEndpoint(hello.deviceId, host, server?.port ?: 0)
        if (!registerSession(hello, session, inbound = true)) {
            session.close("Duplicate session")
            return
        }
    }

    /** Returns false (and leaves registry unchanged) when policy rejects. */
    private fun registerSession(hello: LanProbeHello, session: FlashSession, inbound: Boolean): Boolean {
        val id = FlashDeviceId(hello.deviceId)
        synchronized(lock) {
            if (!hardeningPolicy.canAcceptSession(sessionsById.size)) {
                logger.log(com.transfer.flash.core.network.tcp.LanSessionLogger.WARN, TAG, "Session cap reached; rejecting peer=${hello.deviceId}", null)
                return false
            }
            val existing = sessionsById[id]
            if (existing != null) {
                val decision = hardeningPolicy.resolveDuplicate(
                    SessionHardeningPolicy.transportRank(existing.transportType),
                    SessionHardeningPolicy.transportRank(session.transportType),
                )
                if (decision == DuplicateSessionDecision.KeepExisting) {
                    logger.log(com.transfer.flash.core.network.tcp.LanSessionLogger.INFO, TAG, "Coalesced duplicate peer=${hello.deviceId}", null)
                    return false
                }
                sessionsById.remove(id)
                runCatching { existing.disconnect("Superseded by richer path") }
            }
            sessionsById[id] = session
        }
        _activeSessions.value = sessionsById.toMap()
        refreshHealthFromSessions()
        logger.log(com.transfer.flash.core.network.tcp.LanSessionLogger.INFO, TAG, "Session registered peer=${hello.deviceId} inbound=$inbound", null)
        return true
    }

    private fun onSessionClosed(hello: LanProbeHello, reason: String) {
        val id = FlashDeviceId(hello.deviceId)
        synchronized(lock) {
            val registered = sessionsById[id]
            // A rejected duplicate/superseded session also reports closed; only
            // drop the registry entry when the REGISTERED session itself ended.
            if (registered != null && registered.connectionState.value != FlashConnectionState.Disconnected) {
                logger.log(
                    com.transfer.flash.core.network.tcp.LanSessionLogger.DEBUG,
                    TAG,
                    "Ignored close for coalesced duplicate peer=${hello.deviceId}",
                    null,
                )
                return
            }
            sessionsById.remove(id)
        }
        _activeSessions.value = sessionsByIdSnapshot()
        refreshHealthFromSessions()
        refreshState()
    }

    private fun sessionsByIdSnapshot(): Map<FlashDeviceId, FlashSession> =
        synchronized(lock) { sessionsById.toMap() }

    // ------------------------------------------------------------------
    // Health + state
    // ------------------------------------------------------------------

    private fun refreshHealthFromSessions() {
        val sessions = sessionsByIdSnapshot().values
        healthAggregator.apply(
            peerCountDiscovered = synchronized(lock) { knownEndpoints.size },
            connectingAttempts = connectingAttempts.get(),
            onlineSessions = sessions.count { it.connectionState.value == FlashConnectionState.Connected },
            // LAN sessions have no impaired-path state today (relay/mesh is
            // post-v1, D5); the Degraded signal arrives with C4.6 transports.
            degradedSessions = 0,
        )
    }

    private fun refreshState() {
        _networkState.value = FlashNetworkState(
            isRunning = running.get(),
            localPort = server?.port ?: 0,
            activePeerCount = sessionsByIdSnapshot().size,
        )
    }

    private companion object {
        const val TAG = "CONNECTION"
    }
}
