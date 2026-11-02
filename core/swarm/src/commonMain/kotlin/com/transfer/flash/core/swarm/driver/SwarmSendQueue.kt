package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.swarm.codec.SwarmFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * One peer's ordered outbound queue, bounded in frames (OPT-O02-04).
 *
 * It used to be `Channel.UNLIMITED`. A `Piece` frame carries up to a megabyte, and the engine's serve accounting releases
 * a peer's in-flight bytes when the *read* finishes, not when the socket accepts the bytes, so a stalled link could hold
 * tens of megabyte-sized frames per peer without any of the transfer's own limits applying.
 *
 * The drop rule is not "full = drop the newest":
 * - a **piece** frame may be dropped: the requester times out (5 s minimum), strikes, and asks again — possibly from
 *   another member. That is the protocol's own recovery path.
 * - a **control** frame (HAVE, HAVE_ALL, SUMMARY, REQUEST, UNREQUEST, REJECT, CANCEL, CANCEL_ACK, SOURCE_STATUS,
 *   MANIFEST_*) is never dropped; it waits for room in a background coroutine. A lost CANCEL is a cancelled file that
 *   keeps downloading, which no speed-up may buy.
 */
internal class SwarmSendQueue(
    private val peerId: String,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val capacity: Int = SEND_QUEUE_CAPACITY,
    private val send: suspend (SwarmFrame) -> Unit,
    private val onPieceDropped: (dropped: Long, deferred: Long) -> Unit = { _, _ -> },
) {
    private val frames = Channel<SwarmFrame>(capacity)

    /** Piece frames dropped because the peer's queue was full (measurement only). */
    var droppedPieces: Long = 0L
        private set

    /** Frames that had to wait for room (control frames only). */
    var deferredControls: Long = 0L
        private set

    init {
        scope.launch(dispatcher) {
            try {
                for (frame in frames) {
                    try {
                        send(frame)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // A failed send is the transport's business; the protocol re-asks.
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Closing the channel ends this loop.
            }
        }
    }

    /** Queues [frame]; a piece frame is dropped when the queue is full, a control frame waits for room. */
    fun offer(frame: SwarmFrame) {
        if (frames.trySend(frame).isSuccess) return
        if (frame is SwarmFrame.Piece) {
            droppedPieces++
            onPieceDropped(droppedPieces, deferredControls)
            return
        }
        deferredControls++
        scope.launch(dispatcher) {
            try {
                frames.send(frame)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Peer queue closed: nothing to deliver to.
            }
        }
    }

    fun close() {
        frames.close()
    }

    internal companion object {
        const val SEND_QUEUE_CAPACITY: Int = 16
    }
}
