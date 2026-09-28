@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.mode

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.runSuspendCatching
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Applies the connection mode on one host (PC5, plan §3.4, ADR-048). The app holder, `Flash.create`
 * and the desktop engine each run one; the rules themselves are in [ConnectionModePolicy] and
 * [EcoLinkSelector].
 *
 * 1. **Mode changes.** When [policy] returns something new, [onPolicyChanged] runs once; the hosts
 *    re-time their live connections there, so a switch applies without reconnecting anyone.
 * 2. **Who ECO dials.** [dialFilter] is the set the connection planner may dial, or null for
 *    everyone (STANDARD, BOOST). Dial on demand (a send) ignores it.
 * 3. **Parking (ECO only).** A session this device dialed, idle for 10 minutes, with a peer ECO does
 *    not want, is offered for closing with `FLASH_LINK t=park`. The peer answers `park-ok` only
 *    when it is in ECO and does not want the session either; it then [release]s the session (it
 *    will not redial when it closes), and this side [close]s it. Anything else, including silence
 *    from an old client, keeps the session. So the side that wants a connection keeps it, and a
 *    BOOST or STANDARD phone never sees an ECO phone churn its sessions (plan §3.4 "Mixed modes").
 * 4. **Never** closes a session the other side dialed, and never refuses an incoming one.
 *
 * One coroutine owns all state; [onInboundText] and [refresh] only enqueue.
 */
public class ConnectionModeController(
    private val scope: CoroutineScope,
    private val localDeviceId: String,
    private val policy: () -> ConnectionModePolicy,
    private val snapshot: suspend () -> LinkView,
    private val send: suspend (peerId: String, text: String) -> Boolean,
    /** Stop redialing [peerId] when its session closes, without closing it. */
    private val release: suspend (peerId: String) -> Unit,
    /** Close the session with [peerId] locally, without a redial. */
    private val close: suspend (peerId: String) -> Unit,
    private val onPolicyChanged: (ConnectionModePolicy) -> Unit = {},
    private val log: (String) -> Unit = {},
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    private val nowMs: () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } },
) {
    private sealed interface Event {
        data object Wake : Event
        data class Inbound(val peerId: String, val frame: LinkFrame) : Event
    }

    private val events = Channel<Event>(EVENT_CAPACITY, BufferOverflow.DROP_OLDEST)

    private val _dialFilter = MutableStateFlow<Set<String>?>(null)

    /** Peers the connection planner may dial; null means every discovered peer. */
    public val dialFilter: StateFlow<Set<String>?> = _dialFilter.asStateFlow()

    private var lastPolicy: ConnectionModePolicy? = null

    /** Park requests awaiting an answer: peer -> when asked. */
    private val asked = HashMap<String, Long>()

    /** Peers that answered `keep`: peer -> when this side may ask again. */
    private val keepUntil = HashMap<String, Long>()

    /** The policy last applied, for logs and tests. */
    public val currentPolicy: ConnectionModePolicy? get() = lastPolicy

    /**
     * Consumes [text] when it is a `FLASH_LINK` frame and returns true (malformed ones too); false for
     * anything else. Safe from any thread and never blocks.
     */
    public fun onInboundText(peerId: String, text: String): Boolean {
        if (!LinkCodec.isLinkFrame(text)) return false
        val frame = LinkCodec.decode(text) ?: return true
        events.trySend(Event.Inbound(peerId, frame))
        return true
    }

    /** Re-evaluates now: call when the mode, the tier or the Nearby screen changes. */
    public fun refresh() {
        events.trySend(Event.Wake)
    }

    /** Starts the loop; [edges] (mode, sessions, discovery, Nearby) wake it on every emission. */
    public fun start(edges: Flow<*>? = null): Job = scope.launch {
        if (edges != null) launch { edges.collect { events.trySend(Event.Wake) } }
        var first = true
        while (isActive) {
            val head = if (first) null else withTimeoutOrNull(checkIntervalMs) { events.receive() }
            first = false
            val batch = ArrayList<Event>()
            head?.let(batch::add)
            while (true) batch += events.tryReceive().getOrNull() ?: break
            try {
                runStep(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log("Link mode step failed: ${t.message}")
            }
        }
    }

    private suspend fun runStep(batch: List<Event>) {
        val p = policy()
        val previous = lastPolicy
        if (p != previous) {
            lastPolicy = p
            if (previous != null) {
                log("Connection mode ${previous.strategy} -> ${p.strategy} ping=${p.transport.pingIntervalMs}ms")
                runCatching { onPolicyChanged(p) }.onFailure { log("Connection mode change failed: ${it.message}") }
            }
        }
        val view = snapshot()
        val now = nowMs()
        val wanted = if (p.limitsSessions) EcoLinkSelector.wanted(localDeviceId, view) else null

        for (event in batch) {
            if (event is Event.Inbound) onFrame(event.peerId, event.frame, wanted, view, now)
        }

        _dialFilter.value = wanted
        if (wanted == null) {
            asked.clear()
            keepUntil.clear()
            return
        }
        asked.keys.retainAll(view.activity.keys)
        keepUntil.keys.retainAll(view.activity.keys)
        for (peer in EcoLinkSelector.parkCandidates(localDeviceId, view, wanted)) {
            if ((keepUntil[peer] ?: Long.MIN_VALUE) > now) continue
            val askedAt = asked[peer]
            if (askedAt != null && now - askedAt < PARK_ANSWER_TIMEOUT_MS) continue
            if (sendFrame(peer, LinkFrame.Park)) {
                asked[peer] = now
                log("Link park requested peer=$peer")
            }
        }
    }

    private suspend fun onFrame(peer: String, frame: LinkFrame, wanted: Set<String>?, view: LinkView, now: Long) {
        when (frame) {
            LinkFrame.Park -> {
                // Grant only when this side does not want the session either. Release before
                // answering, so the close that follows the answer can never start a redial.
                val grant = wanted != null && peer !in wanted && view.activity[peer]?.outbound == false
                if (grant) runSuspendCatching { release(peer) }
                sendFrame(peer, if (grant) LinkFrame.ParkOk else LinkFrame.Keep)
                log("Link park from peer=$peer granted=$grant")
            }
            LinkFrame.ParkOk -> {
                if (peer !in asked) return
                // Still idle and still unwanted? Something may have been sent while the answer was
                // on its way; then the session stays, and only its redial on the peer's side is gone.
                val stillParkable = wanted != null && peer in EcoLinkSelector.parkCandidates(localDeviceId, view, wanted)
                if (stillParkable) {
                    runSuspendCatching { close(peer) }
                    log("Link parked peer=$peer")
                }
                // Keep the request on record: until the session is gone from the registry (or the
                // answer timeout passes) it must not be asked again. The step prunes it after that.
                asked[peer] = now
            }
            LinkFrame.Keep -> {
                asked.remove(peer)
                keepUntil[peer] = now + ConnectionModePolicy.ECO_IDLE_PARK_MS
            }
        }
    }

    private suspend fun sendFrame(peer: String, frame: LinkFrame): Boolean =
        runSuspendCatching { send(peer, LinkCodec.encode(frame)) }.getOrNull() == true

    public companion object {
        /** ECO's idle check cadence; parking is a 10-minute rule, so a minute is precise enough. */
        public const val DEFAULT_CHECK_INTERVAL_MS: Long = 60_000L

        /** An unanswered park request (an old client) is asked again after this long. */
        public const val PARK_ANSWER_TIMEOUT_MS: Long = 10 * 60_000L

        private const val EVENT_CAPACITY = 64
    }
}
