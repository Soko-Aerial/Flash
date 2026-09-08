package com.transfer.flash.core.network.resilience

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.FlashSession
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chaos resilience harness (plan C4.9): composes the four pure resilience
 * primitives around a chaos-wrapped [FlashSession] so invariant tests assert
 * system behavior, not just unit behavior:
 *
 * - [BoundedSendQueue] — outbound backpressure (reject-newest).
 * - [HeartbeatTracker] — dead-peer detection on explicit ticks.
 * - [ReconnectPolicy] — jittered reconnect delays with stable-connect reset.
 * - [ChaosSession] — seeded drop/duplicate/reorder/delay/disconnect faults.
 *
 * The harness owns the attempt counter and "unstable since" bookkeeping that
 * production `ReconnectEngine` wiring will own in C4.2 integration; keeping
 * it here makes the invariants executable before that wiring exists.
 *
 * All time flows through explicit `nowMs` parameters supplied by tests —
 * fully deterministic, no coroutine-test dependency.
 */
internal class ChaosNetworkHarness(
    delegate: FlashSession,
    val chaos: ChaosSession,
    queueCapacity: Int = BoundedSendQueue.DEFAULT_CAPACITY,
    val heartbeat: HeartbeatTracker = HeartbeatTracker(HeartbeatPolicy()),
    val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(random01 = { 0.5 }),
) {

    constructor(
        delegate: FlashSession,
        chaosConfig: ChaosConfig = ChaosConfig(),
        seed: Long = 42L,
        queueCapacity: Int = BoundedSendQueue.DEFAULT_CAPACITY,
        heartbeatPolicy: HeartbeatPolicy = HeartbeatPolicy(),
        reconnectBaseMs: Long = ReconnectPolicy.DEFAULT_BASE_MS,
        reconnectCapMs: Long = ReconnectPolicy.DEFAULT_CAP_MS,
    ) : this(
        delegate = delegate,
        chaos = ChaosSession(delegate, chaosConfig, kotlin.random.Random(seed)),
        queueCapacity = queueCapacity,
        heartbeat = HeartbeatTracker(heartbeatPolicy),
        reconnectPolicy = ReconnectPolicy(
            baseMs = reconnectBaseMs,
            capMs = reconnectCapMs,
            random01 = kotlin.random.Random(seed + 1)::nextDouble,
        ),
    )

    /** Outbound per-peer bounded queue feeding the session. */
    val sendQueue = BouncedSendQueueShim(queueCapacity)

    /** Frames received inbound post-faults (bounded by test scenario size). */
    val receivedFrames = CopyOnWriteArrayList<DeliveredFrame>()

    /** Dedup gate a consumer would use to tolerate redelivery. */
    val dedupGate = DedupGate()

    init {
        chaos.onFrameListener = { frame -> receivedFrames.add(frame) }
    }

    /**
     * Enqueues one frame for outbound send. Returns the typed enqueue result;
     * rejection means THE CALLER retains the write (outbox contract, C6).
     */
    fun enqueueOutbound(frameId: String, payload: ByteArray): EnqueueResult<OutboundFrame> =
        sendQueue.enqueue(OutboundFrame(frameId, payload))

    /**
     * Drains up to [maxItems] queued frames through the chaos session.
     * Returns pair of (sentCount, rejectedSends) — rejections occur when
     * chaos hard-disconnects mid-drain; those frames are re-queued so no
     * accepted write is ever lost inside the harness itself.
     */
    suspend fun drainOutbound(maxItems: Int = Int.MAX_VALUE): Pair<Int, Int> {
        var sent = 0
        var rejected = 0
        repeat(maxItems) {
            if (chaos.isLinkBroken) return@repeat
            val frame = sendQueue.poll() ?: return@repeat
            when (chaos.send(frame.payload)) {
                is FlashResult.Success -> sent++
                is FlashResult.Failure -> {
                    // Link died mid-send: put the frame back at the head so
                    // the outbox-retention guarantee holds even under chaos.
                    sendQueue.requeueAtHead(frame)
                    rejected++
                }
            }
        }
        return sent to rejected
    }

    /** Heartbeat tick driven by test-controlled time. */
    fun tickHeartbeat(nowMs: Long): HeartbeatAction = heartbeat.onTick(nowMs)

    fun pingSent(nowMs: Long) = heartbeat.onPingSent(nowMs)

    fun pongReceived(nowMs: Long) = heartbeat.onPongReceived(nowMs)

    /** Simulates the peer answering our last ping. */
    fun peerRepliesPong(nowMs: Long) = heartbeat.onPongReceived(nowMs)

    /**
     * Next jittered reconnect delay for the CURRENT unstable episode and
     * advances the policy's attempt counter.
     */
    fun nextReconnectDelay(): Long = reconnectPolicy.nextDelay()

    val currentReconnectAttempt: Int get() = reconnectPolicy.currentAttemptValue

    /**
     * Stable connect observed (session handshake completed): resets both the
     * reconnect attempt counter (invariant iv) and heartbeat liveness state.
     */
    fun simulateStableConnect(nowMs: Long = 0L) {
        reconnectPolicy.reset()
        heartbeat.reset(nowMs)
        chaos.restoreLink()
    }

    /** Injected hard disconnect from the chaos layer or external events. */
    fun forceDisconnect(reason: String = "chaos-event") {
        chaos.hardDisconnect(reason)
    }

    /** Convenience: unique-frame view of everything received. */
    fun uniqueReceivedFrameIds(): List<String> =
        receivedFrames.map { it.frameId }.distinct()

    /** Per-peer outbound frame value. */
    data class OutboundFrame(val frameId: String, val payload: ByteArray)

    /**
     * Thin wrapper exposing exactly the queue operations the harness needs.
     * Exists so tests exercise the real [BoundedSendQueue] semantics while
     * the harness can re-queue on failed sends without leaking internals.
     */
    class BouncedSendQueueShim(capacity: Int) {
        private val delegate = BoundedSendQueue<OutboundFrame>(capacity)

        val size: Int get() = delegate.size
        val isClosed: Boolean get() = delegate.isClosed

        fun enqueue(item: OutboundFrame): EnqueueResult<OutboundFrame> = delegate.enqueue(item)

        fun poll(): OutboundFrame? = delegate.poll()

        fun drainInto(sink: (OutboundFrame) -> Unit): Int = delegate.drainInto(sink)

        fun requeueAtHead(item: OutboundFrame): Boolean {
            // Re-inserting at head with FIFO preserved: drain into a temp
            // list, prepend, reload — bounded, so cost is O(capacity).
            val rest = ArrayList<OutboundFrame>(delegate.capacity)
            delegate.drainInto { rest.add(it) }
            if (delegate.enqueue(item) !is EnqueueResult.Enqueued) return false
            rest.forEach { candidate ->
                if (delegate.enqueue(candidate) !is EnqueueResult.Enqueued) return false
            }
            return true
        }
    }
}
