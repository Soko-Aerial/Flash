package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.swarm.codec.SwarmFrame
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPT-O02-04: the per-peer outbound queue was `Channel.UNLIMITED`, so a stalled link could accumulate megabyte-sized
 * `Piece` frames without any limit applying (the engine releases a peer's in-flight bytes when the *read* finishes, not
 * when the socket accepts the bytes).
 *
 * The queue is bounded now. A piece frame may be dropped — the requester times out and asks again — but a control frame
 * never is: a lost CANCEL is a cancelled file that keeps downloading.
 */
class SwarmSendQueueTest {

    private val root = ContentRoot("a".repeat(64))

    private fun piece(index: Int) = SwarmFrame.Piece("g1", root, index, ByteArray(1024) { index.toByte() })
    private fun cancel(index: Int) = SwarmFrame.Cancel("g1", root, "origin", "m$index", SwarmTombstoneReason.USER, 1_000L, ByteArray(8))
    private fun request(index: Int) = SwarmFrame.Request("g1", root, listOf(index))

    @Test
    fun `a full queue drops piece frames but never a control frame`() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val delivered = CopyOnWriteArrayList<SwarmFrame>()
            // The first piece blocks in "send": that is what a stalled socket looks like to this queue.
            val gate = Channel<Unit>(Channel.UNLIMITED)
            var blocking = true
            val queue = SwarmSendQueue(
                peerId = "peerA",
                scope = scope,
                dispatcher = Dispatchers.Default,
                capacity = 2,
                send = { frame ->
                    delivered += frame
                    if (blocking && frame is SwarmFrame.Piece) gate.receive()
                },
            )
            try {
                queue.offer(piece(0)) // taken by the consumer, which then blocks
                withTimeout(2_000) { while (delivered.isEmpty()) delay(5) }
                queue.offer(piece(1)) // buffered
                queue.offer(piece(2)) // buffered
                queue.offer(piece(3)) // dropped: the buffer is full
                queue.offer(cancel(9)) // a control frame must survive
                queue.offer(request(4)) // and so must a REQUEST
                assertEquals(1, queue.droppedPieces)
                assertEquals(2, queue.deferredControls)

                blocking = false
                gate.trySend(Unit)
                withTimeout(5_000) {
                    while (delivered.none { it is SwarmFrame.Cancel } || delivered.none { it is SwarmFrame.Request }) delay(5)
                }

                assertTrue(delivered.any { it is SwarmFrame.Cancel }, "the CANCEL was delivered")
                assertTrue(delivered.any { it is SwarmFrame.Request }, "the REQUEST was delivered")
                assertFalse(
                    delivered.filterIsInstance<SwarmFrame.Piece>().any { it.index == 3 },
                    "the dropped piece is never delivered — the requester's timeout re-asks for it",
                )
            } finally {
                queue.close()
                scope.cancel()
            }
        }
    }

    @Test
    fun `frames leave in the order they were offered`() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val delivered = CopyOnWriteArrayList<Int>()
            val queue = SwarmSendQueue(
                peerId = "peerA",
                scope = scope,
                dispatcher = Dispatchers.Default,
                capacity = 16,
                send = { frame -> if (frame is SwarmFrame.Piece) delivered += frame.index },
            )
            try {
                for (i in 0 until 12) queue.offer(piece(i))
                withTimeout(5_000) { while (delivered.size < 12) delay(5) }
                assertEquals((0 until 12).toList(), delivered.toList())
            } finally {
                queue.close()
                scope.cancel()
            }
        }
    }
}
