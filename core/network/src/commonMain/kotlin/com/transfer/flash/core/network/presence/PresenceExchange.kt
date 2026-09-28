@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.presence

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.runSuspendCatching
import com.transfer.flash.core.network.planner.ConnectionPlanner
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
 * Presence sharing on one host (PC4, plan §3.2, ADR-046): the same class on the app holder,
 * `core:engine`'s `Flash.create` and the desktop engine.
 *
 * The host does four things:
 * 1. Hands every inbound text frame to [onInboundText] before its other routers. It returns true
 *    for any `FLASH_PRES` frame, valid or not, so presence never reaches chat or transfer code.
 * 2. Supplies [snapshot], its current [PresenceLocalView], and [send] for one text frame to a peer.
 * 3. Adds [reachablePeerIds] to discovery's ids for the chat repository's Online (ring) state.
 * 4. Adds [tipSightings] to the planner's sightings, dials a tip with the subject's id named (so
 *    the TLS pin is checked during the handshake), and reports the outcome to [onTipResult].
 *
 * All protocol state lives in [PresenceState] and is touched only by the loop [start] launches.
 * Inputs arrive through one bounded channel, so a flood of frames drops the oldest instead of
 * growing memory; the per-sender rate limit then applies to what gets through.
 */
public class PresenceExchange(
    private val scope: CoroutineScope,
    localDeviceId: String,
    private val snapshot: suspend () -> PresenceLocalView,
    private val send: suspend (peerId: String, text: String) -> Boolean,
    sha256: (ByteArray) -> ByteArray,
    randomSalt: () -> ByteArray,
    /** Read at every step, so a connection mode change applies without a restart (PC5). */
    private val config: () -> PresenceConfig = { PresenceConfig.STANDARD },
    private val log: (String) -> Unit = {},
    /** Monotonic milliseconds; injectable so tests can run on virtual time. */
    private val nowMs: () -> Long = TimeSource.Monotonic.markNow().let { origin -> { origin.elapsedNow().inWholeMilliseconds } },
) {
    private sealed interface Event {
        data object Wake : Event
        class Inbound(val peerId: String, val frame: PresenceFrame) : Event
        class TipResult(val tip: PresenceTip, val success: Boolean) : Event
    }

    private val state = PresenceState(localDeviceId, config(), sha256, randomSalt)
    private val events = Channel<Event>(capacity = EVENT_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val _reachable = MutableStateFlow<Set<String>>(emptySet())
    private val _tips = MutableStateFlow<Map<String, PresenceTip>>(emptyMap())

    /** Contacts a mutual peer reports as present. Shown as Online (ring), never as Connected. */
    public val reachablePeerIds: StateFlow<Set<String>> = _reachable.asStateFlow()

    /** Current endpoint tips, keyed by subject device id. */
    public val tips: StateFlow<Map<String, PresenceTip>> = _tips.asStateFlow()

    /**
     * Consumes [text] when it is a `FLASH_PRES` frame and returns true; returns false for anything
     * else. Safe from any thread and never blocks.
     */
    public fun onInboundText(peerId: String, text: String): Boolean {
        if (!PresenceCodec.isPresenceFrame(text)) return false
        val frame = PresenceCodec.decode(text) ?: return true
        events.trySend(Event.Inbound(peerId, frame))
        return true
    }

    /** Tips as planner sightings. The name is the id: a tip has no display name. */
    public fun tipSightings(): List<ConnectionPlanner.Sighting> =
        _tips.value.values.map { ConnectionPlanner.Sighting(it.deviceId, it.endpoint.host, it.endpoint.port, it.deviceId) }

    /** The tip a planner dial for [deviceId] came from, or null when it is an ordinary sighting. */
    public fun tipFor(deviceId: String?, host: String, port: Int): PresenceTip? =
        deviceId?.let { _tips.value[it] }?.takeIf { it.endpoint.host == host && it.endpoint.port == port }

    public fun onTipResult(tip: PresenceTip, success: Boolean) {
        events.trySend(Event.TipResult(tip, success))
    }

    /** Re-evaluates now: call on pairing, group changes, or a Ghost toggle the edges miss. */
    public fun refresh() {
        events.trySend(Event.Wake)
    }

    /** Starts the loop; [edges] (sessions, discovery, mode) wake it on every emission. */
    public fun start(edges: Flow<*>? = null): Job = scope.launch {
        if (edges != null) launch { edges.collect { events.trySend(Event.Wake) } }
        var waitMs = 0L
        while (isActive) {
            val first = if (waitMs <= 0L) null else withTimeoutOrNull(waitMs) { events.receive() }
            val batch = ArrayList<Event>()
            first?.let(batch::add)
            while (true) batch += events.tryReceive().getOrNull() ?: break
            waitMs = try {
                runStep(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log("Presence step failed: ${t.message}")
                RETRY_AFTER_FAILURE_MS
            }
        }
    }

    private suspend fun runStep(batch: List<Event>): Long {
        val view = snapshot()
        val now = nowMs()
        state.config = config()
        for (event in batch) {
            when (event) {
                is Event.Inbound -> if (!state.onFrame(now, event.peerId, event.frame, view)) {
                    log("Presence frame dropped from ${event.peerId}")
                }
                is Event.TipResult -> state.onTipResult(event.tip, event.success)
                Event.Wake -> Unit
            }
        }
        val step = state.step(now, view)
        for (out in step.out) {
            val ok = runSuspendCatching { send(out.peerId, PresenceCodec.encode(out.frame)) }.getOrNull() == true
            if (!ok) log("Presence send failed to ${out.peerId}")
        }
        _reachable.value = state.reachable(now, view)
        _tips.value = state.tips(now, view)
        return step.nextInMs
    }

    public companion object {
        private const val EVENT_CAPACITY = 256
        private const val RETRY_AFTER_FAILURE_MS = 5_000L
    }
}
