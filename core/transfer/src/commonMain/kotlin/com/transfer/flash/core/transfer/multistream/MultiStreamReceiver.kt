package com.transfer.flash.core.transfer.multistream

import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkSink
import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import com.transfer.flash.core.transfer.chunked.ReceivePipeline
import com.transfer.flash.core.transfer.chunked.RejectReason
import com.transfer.flash.core.transfer.concurrent.PlatformLock

/**
 * Receive-side counterpart of [MultiStreamDispatcher] (C5.7): accepts frames arriving over ANY of
 * the N parallel channels and feeds them into ONE [ReceivePipeline], then routes emitted feedback
 * frames back down the channel whose bytes triggered them.
 *
 * ## Why ONE pipeline for N channels
 *
 * Chunk arrival order across sockets is arbitrary; ordering is delegated to the pipeline's
 * duplicate-tolerant, out-of-order-tolerant semantics (`ResumeBitVector` marks by index,
 * `ChunkSink.write(index, ...)` scatters to slot positions — no sequencing needed). A per-channel
 * pipeline would fragment ACK state and break exactly-once bookkeeping.
 *
 * ## Why ACK_BATCH replies travel down the ARRIVING channel
 *
 * 1. **Liveness symmetry** — the socket that just delivered a chunk is provably open in both
 *    directions right now (TCP-level reachability was just demonstrated); replying on it never
 *    needs a reverse connection.
 * 2. **Per-path congestion honesty** — pairing each ACK with its own path lets the sender's
 *    failure isolation attribute loss/latency to the exact stream instead of smearing it across
 *    all N (same reason MPSCP assigns results to their serving stream:
 *    https://www.osti.gov/servlets/purl/1143126).
 * 3. **No routing table needed** — the sender's dispatcher accepts ACK batches on ANY channel
 *    (`MultiStreamDispatcher.onInboundFrame`), so arrival-channel reply is always correct even if
 *    another channel died a moment earlier.
 * COMPLETE is likewise routed down the arriving channel: it is emitted atomically with the final
 * partial ACK under this class's single lock, so both land on one provably-live path.
 *
 * ## Exactly-once COMPLETE across concurrent channels
 *
 * All pipeline access is serialized through one monitor, so even when the last chunks race in on
 * different channels, `ReceivePipeline`'s session-finished guard produces the COMPLETE frame
 * exactly once, attributed to whichever single delivery won the lock ("last-finishing coordinator
 * path").
 */
internal class MultiStreamReceiver(
    sink: ChunkSink,
    ackEvery: Int = ReceivePipeline.DEFAULT_ACK_EVERY,
) {

    private val pipeline = ReceivePipeline(sink, ackEvery)

    /**
     * Phase 13B-3e replaced `synchronized(Any())` with [PlatformLock] at all four call sites below;
     * `kotlin.synchronized` is JVM-only. The nesting is unchanged and still one-directional —
     * receiver lock, then the pipeline's own lock, never the reverse, and `ReceivePipeline` never
     * calls back into this class — so there is no new deadlock edge. (It would be reentrant even if
     * there were: both `PlatformLock` actuals are a plain `synchronized(monitor)`.)
     */
    private val lock = PlatformLock()

    /**
     * Processes one inbound frame from [channelId].
     * Returned events are already routed: feed each `frameBytes` back down `channelId`.
     */
    fun onFrame(channelId: Int, bytes: ByteArray): List<RoutedReceiveEvent> {
        val events = lock.withLock { pipeline.onFrame(bytes) }
        return events.map { it.route(channelId) }
    }

    /** Forces any pending partial ACK batch; routed down [channelId]. Null when nothing pending. */
    fun flushPendingAck(channelId: Int): RoutedReceiveEvent? =
        lock.withLock { pipeline.flushPendingAck() }?.route(channelId)

    fun doneIndexes(transferId: String): List<Int>? =
        lock.withLock { pipeline.doneIndexes(transferId) }

    fun activeTransferIds(): Set<String> =
        lock.withLock { pipeline.activeTransferIds() }

    private fun ReceiveEvent.route(fromChannelId: Int): RoutedReceiveEvent = when (this) {
        is ReceiveEvent.SessionStarted -> RoutedReceiveEvent.SessionStarted(frame, fromChannelId)
        is ReceiveEvent.AckBatchReady -> RoutedReceiveEvent.AckBatchReady(frame, fromChannelId)
        is ReceiveEvent.Completed -> RoutedReceiveEvent.Completed(frame, fromChannelId)
        is ReceiveEvent.Rejected ->
            RoutedReceiveEvent.Rejected(reason, transferId, index, fromChannelId)
    }
}

/** A [ReceiveEvent] tagged with the channel id it must be answered on. */
internal sealed interface RoutedReceiveEvent {

    /** Informational: a receive session opened (no wire reply required). */
    data class SessionStarted(
        val frame: ChunkFrame.FileStart,
        val channelId: Int,
    ) : RoutedReceiveEvent

    /** Serialize [frameBytes-serialized frame][frame] and send down [channelId]. */
    data class AckBatchReady(
        val frame: ChunkFrame.AckBatch,
        val channelId: Int,
    ) : RoutedReceiveEvent {
        val frameBytes: ByteArray get() = ChunkFrame.serialize(frame)
    }

    data class Completed(
        val frame: ChunkFrame.Complete,
        val channelId: Int,
    ) : RoutedReceiveEvent {
        val frameBytes: ByteArray get() = ChunkFrame.serialize(frame)
    }

    data class Rejected(
        val reason: RejectReason,
        val transferId: String?,
        val index: Int,
        val channelId: Int,
    ) : RoutedReceiveEvent
}
