package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.concurrent.SyncMap
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.engine.concurrent.PlatformLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Hands inbound frames to [handler] one peer at a time, in arrival order, with a bounded buffer per peer.
 *
 * The magic router calls the host synchronously. Starting one coroutine per frame (the previous code) lets two frames
 * from one peer overtake each other on a multi-threaded dispatcher, and lets a peer queue unlimited work. Here each
 * peer has one worker and one bounded channel: order is preserved, and a flood is dropped (newest first) with a
 * rate-limited warning instead of growing memory without bound.
 */
@OptIn(FlashInternalApi::class)
internal class PeerOrderedInbox(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val handler: suspend (peerId: String, frame: ByteArray) -> Unit,
) {
    private val queues = SyncMap<String, Channel<ByteArray>>()
    private val counterLock = PlatformLock()
    private var dropped: Long = 0L

    /** Number of frames dropped because a peer's buffer was full. */
    val droppedCount: Long get() = counterLock.withLock { dropped }

    /** Queues [frame] for [peerId]. Returns false when it was dropped (buffer full); never blocks, never throws. */
    fun offer(peerId: String, frame: ByteArray): Boolean {
        repeat(2) {
            val channel = queues.getOrPut(peerId) { newQueue(peerId) }
            val result = channel.trySend(frame)
            if (result.isSuccess) return true
            if (!result.isClosed) {
                // Full: drop the newest frame. The protocol re-asks (request timeouts, summaries), so a drop is a delay.
                val n = counterLock.withLock { ++dropped }
                if (n == 1L || n % 100L == 0L) {
                    FlashLog.w("SWARM", "inbound swarm buffer full, frame dropped peer=$peerId dropped=$n capacity=$capacity")
                }
                return false
            }
            // Closed concurrently with a peer-down: forget it and open a fresh queue once.
            queues.remove(peerId, channel)
        }
        return false
    }

    /** Stops [peerId]'s worker after it has handled what is already queued. */
    fun closePeer(peerId: String) {
        queues.remove(peerId)?.close()
    }

    /** Peers that currently have a queue. */
    fun peers(): List<String> = queues.keysSnapshot()

    fun closeAll() {
        for (peer in queues.keysSnapshot()) closePeer(peer)
    }

    private fun newQueue(peerId: String): Channel<ByteArray> {
        val channel = Channel<ByteArray>(capacity)
        scope.launch(dispatcher) {
            try {
                for (frame in channel) {
                    try {
                        handler(peerId, frame)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        // One bad frame must not stop this peer's worker, let alone the binding.
                        FlashLog.w("SWARM", "inbound swarm frame failed peer=$peerId: ${t::class.simpleName}: ${t.message}")
                    }
                }
            } finally {
                queues.remove(peerId, channel)
            }
        }
        return channel
    }

    internal companion object {
        const val DEFAULT_CAPACITY: Int = 64
    }
}
