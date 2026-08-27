package com.transfer.flash.core.discovery.group

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-peer link state machine for a multi-peer group session.
 *
 * Transitions (per endpoint):
 * ```
 * Connecting(attempt n) --> Online            connector.connect succeeded
 * Connecting(attempt n) --> Connecting(n+1)   connect failed, retries remain
 * Connecting(last)      --> Failed            connect failed, attempts exhausted (TERMINAL)
 * Online                --> Sending           sendToAll started writing to this peer
 * Sending               --> Done              connector.send returned true (TERMINAL)
 * Sending               --> Failed            connector.send returned false / threw (TERMINAL)
 * any                   --> (map cleared)     disconnectAll() resets the session
 * ```
 */
internal enum class PeerLinkStateKind { Connecting, Online, Failed, Sending, Done }

/**
 * Immutable snapshot of one peer's link state. [attempt] is the 1-based
 * connect-attempt counter (history of how many tries it took / were burned);
 * [bytesSent]/[bytesTotal] are meaningful only while [kind] is [PeerLinkStateKind.Sending].
 */
internal data class PeerLinkState(
    val endpointId: String,
    val kind: PeerLinkStateKind,
    val bytesSent: Long = 0,
    val bytesTotal: Long = 0,
    val attempt: Int = 0,
    val error: String? = null,
)

/**
 * Transport seam owned ENTIRELY by this file (plan C4 sockets will provide the
 * real implementation; workstream-C code must not import nsd/, core/TxtCodec,
 * or CompositeDiscovery — concurrent-ownership rule).
 *
 * Contract notes:
 * - [connect] and [disconnect] are suspending; implementations are expected to
 *   be cooperative (check cancellation) so [FlashPeerGroupSession.disconnectAll]
 *   can tear links down promptly.
 * - [send] is intentionally NOT suspending: it drives its own progress via
 *   [onProgress] callbacks (synchronously on the caller thread or from the
 *   connector's own mechanism). The session only RELAYS those callbacks into
 *   [FlashPeerGroupSession.peerStates]; it never fabricates progress.
 * - Returning false from either function (rather than throwing) is the normal
 *   failure signal; thrown non-cancellation exceptions are treated as failure
 *   of THAT peer only (isolation invariant).
 */
internal interface GroupPeerConnector {
    /** Attempt to establish a link to [endpointId]. True = online. */
    suspend fun connect(endpointId: String): Boolean

    /**
     * Send a payload of [payloadSizeBytes] to [endpointId], invoking
     * [onProgress] with cumulative bytes written as it goes. True = delivered.
     */
    fun send(endpointId: String, payloadSizeBytes: Long, onProgress: (Long) -> Unit): Boolean

    /** Tear down the link to [endpointId]. Must be idempotent. */
    suspend fun disconnect(endpointId: String)
}

/**
 * Multi-peer group session orchestrator (core-upgrade-plan P3.5 workstream C,
 * C1+C2+C3): parallel per-peer connect with independent retry/backoff, fan-out
 * sends with per-peer progress relay, and clean teardown.
 *
 * Topology stance (R1 research, 2026-08-23):
 * https://developers.google.com/nearby/connections/strategies — Nearby
 * Connections offers CLUSTER (M-to-N mesh, lower per-link bandwidth), STAR
 * (1-to-N hub/spoke, higher bandwidth) and POINT_TO_POINT (1-to-1, highest).
 * This session is deliberately topology-AGNOSTIC: it models N independent
 * point-to-point links behind [GroupPeerConnector], which covers both the hub
 * side of a STAR topology (sender connects out to each spoke) and a CLUSTER
 * mesh (direct links to every member). Because bytes flow per-link, progress
 * is tracked PER PEER ([peerStates]) — a hub relaying to spokes reports each
 * spoke's own byte count, exactly what the Transfers page per-peer rows need
 * (C5.10 groundwork); aggregate throughput can be derived by summing peers.
 *
 * Isolation invariant: a failure of one peer (connect exhaustion, send error,
 * unexpected exception) NEVER affects another peer's state machine or job.
 * Retries apply only to the failing peer, up to [maxConnectAttempts] total
 * attempts, sleeping [retryDelayMs](attempt)
 * between them (kotlinx delay on the injected scope's dispatcher; pass
 * `{ 0 }` for virtual-time-safe tests; the default lambda is uncapped
 * exponential — production callers must supply a capped one).
 *
 * Concurrency contract:
 * - [scope] owns every coroutine this class launches (connect loops, the
 *   internal progress-relay collector, send jobs). Cancelling the scope tears
 *   everything down; callers should keep it alive for the session's lifetime.
 * - [connectAll] while a previous [connectAll] is still running is an
 *   IDEMPOTENT NO-OP returning Unit immediately (documented seam: callers who
 *   want a fresh topology call [disconnectAll] first).
 * - [sendToAll] snapshots the Online set at entry; peers that come online
 *   mid-send are not included until the next [sendToAll]. With zero online
 *   peers it returns true vacuously (nothing could fail).
 * - [disconnectAll] cancels in-flight connects AND sends cleanly, disconnects
 *   every known endpoint through the connector (exceptions swallowed — best
 *   effort), then clears [peerStates] back to an empty map.
 *
 * Mode seam (do NOT couple to FlashDiscoveryMode): the app layer maps user-
 * facing discovery modes (STANDARD/GHOST/BOOST/ECO/RECEIVE_KIOSK — persisted
 * separately as strings in :core:persistence settings) onto this session's
 * knobs (endpoint list passed to [connectAll], [maxConnectAttempts],
 * [retryDelayMs]) at construction/call time. Core stays enum-free here to
 * avoid cross-module coupling while that mapping settles.
 *
 * Pure Kotlin: zero Android dependencies; all collaborators constructor-
 * injected (ground rule R2).
 */
internal class FlashPeerGroupSession(
    private val connector: GroupPeerConnector,
    private val scope: CoroutineScope,
    private val maxConnectAttempts: Int = DEFAULT_MAX_CONNECT_ATTEMPTS,
    retryDelayMs: (attempt: Int) -> Long = { attempt -> (1L shl (attempt - 1)) * DELAY_UNIT_MS },
) {
    init {
        require(maxConnectAttempts >= 1) { "maxConnectAttempts must be >= 1" }
    }

    private val retryDelay: (Int) -> Long = retryDelayMs

    private val _peerStates = MutableStateFlow<Map<String, PeerLinkState>>(emptyMap())

    /** Live per-peer state map keyed by endpointId. Never contains null kinds. */
    val peerStates: StateFlow<Map<String, PeerLinkState>> = _peerStates.asStateFlow()

    /** Guards lifecycle bookkeeping ONLY — never held across joins/delays. */
    private val lifecycleMutex = Mutex()

    /** Parent of all per-peer connect jobs; null/idle when not connecting. */
    private var connectJob: Job? = null

    /** Parent of the current fan-out send's per-peer jobs, if any. */
    private var sendJob: Job? = null

    /** Progress events relayed off connector callback threads into peerStates. */
    private val progressEvents = Channel<PeerProgress>(capacity = Channel.UNLIMITED)

    private data class PeerProgress(val endpointId: String, val bytesSent: Long)

    init {
        // Single collector for the session lifetime; runs on the injected scope
        // so tests with an inline dispatcher stay deterministic.
        scope.launch {
            for (event in progressEvents) {
                _peerStates.update { current ->
                    val existing = current[event.endpointId] ?: return@update current
                    // Only mutate while actively Sending; never resurrect Done/Failed rows.
                    if (existing.kind != PeerLinkStateKind.Sending) return@update current
                    current + (
                        event.endpointId to existing.copy(bytesSent = event.bytesSent.coerceIn(0, existing.bytesTotal))
                        )
                }
            }
        }
    }

    /**
     * Connects to every distinct endpoint IN PARALLEL, each with its own
     * retry/backoff loop. Idempotent no-op when already running (see KDoc).
     * Any endpoints previously in state are reset: their entries are replaced
     * with fresh Connecting states (endpoints absent from [endpoints] are left
     * untouched until [disconnectAll]).
     */
    suspend fun connectAll(endpoints: List<String>) {
        val targets = endpoints.distinct()
        // Built LAZY and started OUTSIDE the lifecycle mutex: an inline
        // dispatcher would otherwise execute child bodies (which may block in
        // a connector call) while the mutex is held, stalling disconnectAll.
        val parent = scope.launch(start = CoroutineStart.LAZY) {
            targets.forEach { endpointId ->
                launch { runConnectLoop(endpointId) }
            }
        }
        var duplicate = false
        lifecycleMutex.withLock {
            if (connectJob?.isActive == true) {
                duplicate = true // idempotent no-op (documented)
            } else {
                _peerStates.update { current ->
                    current + targets.associateWith {
                        PeerLinkState(it, PeerLinkStateKind.Connecting, attempt = 1)
                    }
                }
                connectJob = parent
            }
        }
        if (duplicate) {
            parent.cancel()
            return
        }
        parent.start()
        // Children catch ALL non-cancellation throwables so one peer can never
        // cancel siblings (isolation invariant); the parent awaits them all.
        parent.join()
    }

    /**
     * Sends [payloadSizeBytes] to every currently-[PeerLinkStateKind.Online]
     * peer in parallel and returns true iff ALL of them succeeded (vacuously
     * true when none are online). Send failures are TERMINAL for that peer
     * (Failed) — no automatic send retries; callers re-invoke [sendToAll].
     */
    suspend fun sendToAll(payloadSizeBytes: Long): Boolean {
        val targets = _peerStates.value
            .filterValues { it.kind == PeerLinkStateKind.Online }
            .keys
            .toList()
        if (targets.isEmpty()) return true
        val results = ConcurrentHashMap<String, Boolean>(targets.size)
        val parent = scope.launch(start = CoroutineStart.LAZY) {
            targets.forEach { endpointId ->
                launch { results[endpointId] = runSend(endpointId, payloadSizeBytes) }
            }
        }
        lifecycleMutex.withLock { sendJob = parent }
        parent.start()
        parent.join()
        lifecycleMutex.withLock { if (sendJob === parent) sendJob = null }
        return targets.all { results[it] == true }
    }

    /**
     * Cancels in-flight connects and sends, best-effort disconnects every
     * known endpoint, and resets [peerStates] to empty. Safe to call at any
     * time, including concurrently with other operations and repeatedly.
     */
    suspend fun disconnectAll() {
        val connectToCancel: Job?
        val sendToCancel: Job?
        lifecycleMutex.withLock {
            connectToCancel = connectJob
            sendToCancel = sendJob
            connectJob = null
            sendJob = null
        }
        connectToCancel?.cancel()
        sendToCancel?.cancel()
        val knownEndpoints = _peerStates.value.keys.toList()
        knownEndpoints.forEach { endpointId ->
            try {
                connector.disconnect(endpointId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Best-effort teardown; a flaky disconnect must not wedge the session.
            }
        }
        _peerStates.value = emptyMap()
    }

    // ----- internals -----

    private suspend fun runConnectLoop(endpointId: String) {
        var attempt = 1
        while (true) {
            setState(endpointId) { it.copy(kind = PeerLinkStateKind.Connecting, attempt = attempt, error = null) }
            val connected = try {
                connector.connect(endpointId)
            } catch (cancelled: CancellationException) {
                throw cancelled // let disconnectAll/scope-cancel unwind cleanly
            } catch (t: Throwable) {
                null // treat a throwing connector as a failed attempt (isolation)
            }
            if (connected == true) {
                // A cancelled session must not resurrect state after teardown.
                currentCoroutineContext().ensureActive()
                setState(endpointId) { it.copy(kind = PeerLinkStateKind.Online, error = null) }
                return
            }
            if (attempt >= maxConnectAttempts) {
                setState(endpointId) {
                    it.copy(kind = PeerLinkStateKind.Failed, error = "connect failed after $attempt attempts")
                }
                return
            }
            delay(retryDelay(attempt)) // CancellationException propagates here on teardown
            attempt++
        }
    }

    private suspend fun runSend(endpointId: String, payloadSizeBytes: Long): Boolean {
        setState(endpointId) {
            it.copy(kind = PeerLinkStateKind.Sending, bytesSent = 0, bytesTotal = payloadSizeBytes, error = null)
        }
        val delivered = try {
            connector.send(endpointId, payloadSizeBytes) { sent ->
                // Connector may call from any thread; UNLIMITED channel makes
                // this non-blocking and the collector applies ordering.
                progressEvents.trySend(PeerProgress(endpointId, sent))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            false
        }
        // If disconnectAll() cancelled us while the connector was blocked, do
        // NOT write a terminal state over the cleared map: bail out instead.
        currentCoroutineContext().ensureActive()
        setState(endpointId) {
            if (delivered) {
                it.copy(kind = PeerLinkStateKind.Done, error = null)
            } else {
                it.copy(kind = PeerLinkStateKind.Failed, error = "send failed")
            }
        }
        return delivered
    }

    private fun setState(endpointId: String, transform: (PeerLinkState) -> PeerLinkState) {
        _peerStates.update { current ->
            val existing = current[endpointId] ?: PeerLinkState(endpointId, PeerLinkStateKind.Connecting)
            current + (endpointId to transform(existing))
        }
    }

    companion object {
        const val DEFAULT_MAX_CONNECT_ATTEMPTS: Int = 3

        /** Default backoff is uncapped exponential (1 s, 2 s, 4 s…); CAP IT by passing your own [retryDelayMs] lambda. */
        private const val DELAY_UNIT_MS: Long = 1_000L
    }
}
