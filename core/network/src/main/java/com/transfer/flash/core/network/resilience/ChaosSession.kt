package com.transfer.flash.core.network.resilience

import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashConnectionState
import com.transfer.flash.core.network.FlashSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile

/**
 * First-observation gate used by consumers to tolerate at-least-once
 * redelivery (plan C4.9 invariant i). Chaos faults deliberately duplicate
 * frames; receivers wrap arrival processing in [observe] and skip repeats.
 *
 * Unbounded by design: one gate per session lifetime with short string IDs;
 * sessions are torn down long before this becomes a memory concern. Scope it
 * per-session, never process-global. Thread-safe.
 */
internal class DedupGate {

    private val seen: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * @return true when [frameId] was observed for the FIRST time;
     *         false when it is a duplicate (redelivery).
     */
    fun observe(frameId: String): Boolean = seen.add(frameId)

    val size: Int get() = seen.size
}

/**
 * Seeded fault-injection configuration for [ChaosSession]. All probabilities
 * are in `[0, 1]`; randomness comes from one seeded [kotlin.random.Random]
 * so every scenario run is reproducible.
 */
internal data class ChaosConfig(
    /** Probability an inbound frame vanishes entirely (silent loss). */
    val inboundDropProbability: Double = 0.0,

    /** Probability an inbound frame is delivered twice (at-least-once). */
    val inboundDuplicateProbability: Double = 0.0,

    /**
     * Inbound frames are held until this many accumulate, then released in a
     * seed-shuffled order (reordering attack on ordering assumptions).
     * `<= 1` disables reordering.
     */
    val reorderWindow: Int = 1,

    /** Probability each inbound frame incurs [inboundDelayMs] before delivery. */
    val inboundDelayProbability: Double = 0.0,

    /** Delay applied when [inboundDelayProbability] hits, in ms. */
    val inboundDelayMs: Long = 0L,

    /** Probability each outbound send triggers a hard disconnect instead. */
    val outboundDisconnectProbability: Double = 0.0,
) {
    init {
        require(inboundDropProbability in 0.0..1.0) { "drop probability out of range" }
        require(inboundDuplicateProbability in 0.0..1.0) { "duplicate probability out of range" }
        require(reorderWindow >= 1) { "reorderWindow must be >= 1" }
        require(inboundDelayProbability in 0.0..1.0) { "delay probability out of range" }
        require(outboundDisconnectProbability in 0.0..1.0) { "disconnect probability out of range" }
    }
}

/** A frame delivered through the chaos pipeline after faults were applied. */
internal data class DeliveredFrame(
    val frameId: String,
    val payload: ByteArray,
    /** Explicit caller-supplied timestamp, optionally shifted by delay faults. */
    val atMs: Long,
)

/**
 * Fault-injection wrapper implementing [FlashSession] over a real delegate
 * (plan C4.9). Corrupts traffic exactly where resilience bugs live: silent
 * loss, duplication, reordering within a window, injected latency, and hard
 * disconnects — all reproducible via a seeded RNG.
 *
 * Inbound simulation: [FlashSession] exposes no inbound hook (reads happen in
 * transport-specific loops like LanSession.readLoop), so chaos inbound frames
 * enter via [deliverInbound] and leave through [onFrameListener].
 *
 * Time: [deliverInbound] takes explicit `nowMs`; latency faults surface as
 * data ([DeliveredFrame.atMs] shifted forward) rather than actually sleeping,
 * keeping JVM tests deterministic with no coroutine-test dependency.
 *
 * Delegation: interface methods default-delegate to [delegate]; only [send]
 * and disconnect behavior are overridden.
 */
internal class ChaosSession(
    private val delegate: FlashSession,
    val config: ChaosConfig,
    private val random: kotlin.random.Random,
) : FlashSession by delegate {

    private val _chaosState = MutableStateFlow(FlashConnectionState.Connected)

    /** Chaos view of the link; flips to Disconnected on any hard disconnect. */
    val chaosConnectionState: StateFlow<FlashConnectionState> = _chaosState.asStateFlow()

    @Volatile
    var isLinkBroken: Boolean = false
        private set

    /** Consumer hook receiving post-fault frames. */
    @Volatile
    var onFrameListener: ((DeliveredFrame) -> Unit)? = null

    /** Observed hard-disconnect reasons, in order. */
    val disconnectEvents: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val reorderBuffer = ArrayList<Pair<String, ByteArray>>(config.reorderWindow.coerceAtLeast(1))

    override suspend fun send(message: ByteArray): FlashResult<Unit> {
        if (isLinkBroken) {
            return FlashResult.Failure(FlashError.PeerUnavailable(peerDeviceId.value, "Chaos link broken"))
        }
        if (config.outboundDisconnectProbability > 0 &&
            random.nextDouble() < config.outboundDisconnectProbability
        ) {
            hardDisconnect("outbound-fault")
            return FlashResult.Failure(FlashError.PeerUnavailable(peerDeviceId.value, "Hard disconnect injected"))
        }
        return delegate.send(message)
    }

    override fun disconnect(reason: String) {
        hardDisconnect(reason)
        delegate.disconnect(reason)
    }

    /**
     * Feeds one raw inbound frame into the chaos pipeline. Fault order:
     * drop → duplicate → reorder-hold → delayed delivery. Held reorder
     * frames flush automatically when the window fills; use
     * [flushReorderBuffer] to force the remainder out.
     */
    fun deliverInbound(frameId: String, payload: ByteArray, nowMs: Long) {
        if (isLinkBroken) return

        if (config.inboundDropProbability > 0 && random.nextDouble() < config.inboundDropProbability) {
            return // silently lost
        }

        val copies = if (config.inboundDuplicateProbability > 0 &&
            random.nextDouble() < config.inboundDuplicateProbability
        ) 2 else 1

        repeat(copies) {
            if (config.reorderWindow > 1) {
                synchronized(reorderBuffer) {
                    reorderBuffer.add(frameId to payload)
                    if (reorderBuffer.size >= config.reorderWindow) {
                        flushReorderBufferLocked(nowMs)
                    }
                }
            } else {
                emit(frameId, payload, nowMs)
            }
        }
    }

    /** Releases any reorder-buffered frames (shuffled per seed), oldest timestamps preserved. */
    fun flushReorderBuffer(nowMs: Long) {
        synchronized(reorderBuffer) { flushReorderBufferLocked(nowMs) }
    }

    private fun flushReorderBufferLocked(baseNowMs: Long) {
        if (reorderBuffer.isEmpty()) return
        val shuffled = reorderBuffer.toMutableList().also { list ->
            if (list.size > 1) {
                // Fisher-Yates driven by the seeded rng for reproducibility.
                for (i in list.size - 1 downTo 1) {
                    val j = random.nextInt(i + 1)
                    val tmp = list[i]; list[i] = list[j]; list[j] = tmp
                }
            }
        }
        reorderBuffer.clear()
        shuffled.forEachIndexed { index, (id, payload) -> emit(id, payload, baseNowMs + index) }
    }

    private fun emit(frameId: String, payload: ByteArray, atMs: Long) {
        val effectiveAt = if (config.inboundDelayProbability > 0 &&
            random.nextDouble() < config.inboundDelayProbability
        ) atMs + config.inboundDelayMs else atMs
        onFrameListener?.invoke(DeliveredFrame(frameId, payload, effectiveAt))
    }

    /**
     * Hard-disconnect event: breaks the link immediately — sends fail with a
     * typed error, inbound delivery stops, state flips to Disconnected.
     */
    fun hardDisconnect(reason: String) {
        if (isLinkBroken) return
        isLinkBroken = true
        disconnectEvents.add(reason)
        _chaosState.value = FlashConnectionState.Disconnected
        synchronized(reorderBuffer) { reorderBuffer.clear() }
    }

    /**
     * Restores the link after [hardDisconnect] (simulates peer recovery /
     * reconnect success). Does NOT reset heartbeat or backoff — owners do
     * that explicitly via their policies on stable connect.
     */
    fun restoreLink() {
        if (!isLinkBroken) return
        isLinkBroken = false
        _chaosState.value = delegate.connectionState.value
    }
}
