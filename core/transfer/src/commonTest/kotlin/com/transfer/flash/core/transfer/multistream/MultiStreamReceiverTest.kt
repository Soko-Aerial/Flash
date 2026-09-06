package com.transfer.flash.core.transfer.multistream

import com.transfer.flash.core.transfer.chunked.ChunkFrame
import com.transfer.flash.core.transfer.chunked.ChunkSink
import com.transfer.flash.core.transfer.chunked.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Deterministic tests for the C5.7 receive side: N arrival channels feeding ONE pipeline,
 * ACK/COMPLETE replies routed back down their ARRIVING channel id.
 *
 * Moved to `commonTest` in Phase 13B-3e with `MultiStreamReceiver.kt`, whose four
 * `synchronized(Any())` blocks became
 * [com.transfer.flash.core.transfer.concurrent.PlatformLock] ones. The header used to say
 * "Deterministic JVM tests"; the word is dropped because these now run on Android and on the
 * desktop JVM, and nothing in the file was ever JVM-specific beyond the JUnit imports. Six
 * message-carrying assertions had their arguments swapped for `kotlin.test`'s message-last order.
 *
 * `assertEquals(listOf(0, 1, 2, 3), receiver.doneIndexes(transferId))` is unaffected by 13B-3e's
 * `sortedSetOf` → `HashSet` swap in `ReceivePipeline`: that list comes from `ResumeBitVector`, which
 * is a bit vector and therefore ascending by construction. The set that changed feeds only the ACK
 * batches, whose order the two `ack.frame.indexes` assertions here pin down.
 */
class MultiStreamReceiverTest {

    private class Assembler : ChunkSink {
        val parts = HashMap<Int, ByteArray>()
        var writes = 0

        override fun write(index: Int, data: ByteArray) {
            parts[index] = data.copyOf()
            writes++
        }
    }

    private val chunkSize = 16_384

    /** 4 chunks: three full + 10 000-byte tail. */
    private val chunks: List<ByteArray> = buildList {
        repeat(3) { add(ByteArray(chunkSize) { ((it * 7) + 1).toByte() }) }
        add(ByteArray(10_000) { (it.toByte()) })
    }
    private val totalBytes = chunks.sumOf { it.size }.toLong()
    private val transferId = "rx-t"
    private val fileId = "file-rx"

    private fun fileStartBytes(): ByteArray = ChunkFrame.serialize(
        ChunkFrame.FileStart(
            transferId = transferId,
            fileId = fileId,
            fileName = "rx.bin",
            totalBytes = totalBytes,
            totalChunks = chunks.size,
            chunkSize = chunkSize,
            fileSha256Hex = Sha256.digestHex(chunks.reduce { acc, b -> acc + b }),
        ),
    )

    private fun chunkBytes(index: Int): ByteArray = ChunkFrame.serialize(
        ChunkFrame.Chunk(
            transferId = transferId,
            fileId = fileId,
            index = index,
            data = chunks[index],
            chunkSha256 = Sha256.digest(chunks[index]),
        ),
    )

    @Test
    fun `frames from any channel land in one pipeline - out of order, exactly once`() {
        val sink = Assembler()
        val receiver = MultiStreamReceiver(sink)

        assertTrue(receiver.onFrame(0, fileStartBytes()).isEmpty())
        // Reverse order across THREE different arrival channels.
        assertTrue(receiver.onFrame(2, chunkBytes(3)).isEmpty())
        assertTrue(receiver.onFrame(0, chunkBytes(0)).isEmpty())
        assertTrue(receiver.onFrame(1, chunkBytes(1)).isEmpty())
        val completionEvents = receiver.onFrame(2, chunkBytes(2))

        assertEquals(2, completionEvents.size, "final partial ACK + COMPLETE")
        val ack = completionEvents[0] as RoutedReceiveEvent.AckBatchReady
        assertEquals(2, ack.channelId, "routed down ARRIVING channel")
        assertEquals(listOf(0, 1, 2, 3), ack.frame.indexes, "final batch covers every verified index")
        val complete = completionEvents[1] as RoutedReceiveEvent.Completed
        assertEquals(2, complete.channelId)
        assertTrue(complete.frame.verified)
        assertEquals(4, sink.writes)
        for (index in chunks.indices) {
            assertTrue(sink.parts[index]!!.contentEquals(chunks[index]), "chunk $index byte-identical")
        }

        // Duplicate delivery on yet another channel: idempotent, no rewrite.
        val dupEvents = receiver.onFrame(1, chunkBytes(2))
        assertEquals(4, sink.writes)
        assertTrue(dupEvents.isEmpty())
    }

    @Test
    fun `ack batches route back down the arriving channel`() {
        val sink = Assembler()
        val receiver = MultiStreamReceiver(sink, ackEvery = 2)

        assertTrue(receiver.onFrame(0, fileStartBytes()).isEmpty())
        assertNull(receiver.flushPendingAck(9)) // nothing pending before any chunk

        assertTrue(receiver.onFrame(0, chunkBytes(0)).isEmpty())
        val firstBatch = receiver.onFrame(1, chunkBytes(1))
        assertEquals(1, firstBatch.size)
        val routed = firstBatch[0] as RoutedReceiveEvent.AckBatchReady
        assertEquals(1, routed.channelId, "batch answers on the channel whose frame completed it")
        assertEquals(listOf(0, 1), routed.frame.indexes)
        assertTrue(routed.frameBytes.isNotEmpty())

        assertTrue(receiver.onFrame(2, chunkBytes(2)).isEmpty())
        val last = receiver.onFrame(3, chunkBytes(3))
        assertEquals(2, last.size)
        assertEquals(3, (last[0] as RoutedReceiveEvent.AckBatchReady).channelId)
        assertEquals(3, (last[1] as RoutedReceiveEvent.Completed).channelId)
        assertEquals(setOf(transferId), receiver.activeTransferIds())
        assertEquals(listOf(0, 1, 2, 3), receiver.doneIndexes(transferId))
    }

    @Test
    fun `partial pending ack flushes on an explicit channel`() {
        val sink = Assembler()
        val receiver = MultiStreamReceiver(sink, ackEvery = 32) // never auto-batches

        assertTrue(receiver.onFrame(5, fileStartBytes()).isEmpty())
        assertTrue(receiver.onFrame(5, chunkBytes(0)).isEmpty())

        val flushed = receiver.flushPendingAck(5)
        val ack = flushed as RoutedReceiveEvent.AckBatchReady
        assertEquals(5, ack.channelId, "flush routes down caller-specified live channel")
        assertEquals(listOf(0), ack.frame.indexes)
        assertNull(receiver.flushPendingAck(5)) // drained
    }
}
