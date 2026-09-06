package com.transfer.flash.core.transfer.chunked

import kotlinx.coroutines.test.runTest
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Deterministic round-trip: SendPipeline -> in-memory wire -> ReceivePipeline -> ACK/COMPLETE
 * back-channel. The send lambdas never truly suspend, so `runTest`'s virtual clock is never
 * advanced here — it is used only because a common test cannot call `runBlocking`.
 *
 * Moved to `commonTest` in Phase 13B-3e; before that the header said "JVM round-trip", which is now
 * the point of the move: the same assertions run on Android and on the desktop JVM. An unused
 * `org.junit.Ignore` import was dropped rather than translated to `kotlin.test.Ignore` — nothing in
 * this file was ever annotated with it.
 */
class PipelineEndToEndTest {

    private val chunkSize = 16_384

    /** 37 chunks: 36 full + 10_176-byte tail; exercises one full 32-ACK batch + completion tail. */
    private val totalBytes = 600_000L

    private val payload = ByteArray(totalBytes.toInt()) { ((it * 31) + (it ushr 5)).toByte() }

    private fun source() = ChunkSource { Buffer().write(payload) }

    private fun meta(transferId: String = "e2e") =
        FileMeta(transferId, "file-1", "holiday photos.zip", totalBytes)

    /** Assembles received chunk parts back into a byte array. */
    private class Assembler(totalChunks: Int) : ChunkSink {
        val parts = arrayOfNulls<ByteArray>(totalChunks)
        var writes = 0

        override fun write(index: Int, data: ByteArray) {
            parts[index] = data.copyOf()
            writes++
        }

        fun assembled(): ByteArray {
            val flat = ByteArray(parts.sumOf { it!!.size })
            var offset = 0
            for (part in parts) {
                part!!.copyInto(flat, offset)
                offset += part.size
            }
            return flat
        }

        fun matches(expected: ByteArray): Boolean =
            assembled().let { it.size == expected.size && Sha256.hexEqualsConstantTime(Sha256.digestHex(it), Sha256.digestHex(expected)) }
    }

    @Test
    fun `happy path - sender to receiver over in-memory wire verifies complete`() = runTest {
        val chunker = Chunker()
        val plan = chunker.plan(meta(), chunkSize)

        val wire = ArrayDeque<ByteArray>()
        val sender = SendPipeline(chunker) { frameBytes ->
            wire.addLast(frameBytes)
            true
        }

        val assembler = Assembler(plan.totalChunks)
        val receiver = ReceivePipeline(assembler)

        val sendResult = sender.send(meta(), source(), requestedChunkSize = chunkSize)
        assertTrue(sendResult is SendResult.Completed)

        // Drain the wire through the receiver; route its feedback frames back to the sender.
        var ackBatches = 0
        var completedFrame: ChunkFrame.Complete? = null
        while (wire.isNotEmpty()) {
            val events = receiver.onFrame(wire.removeFirst())
            for (event in events) {
                when (event) {
                    is ReceiveEvent.SessionStarted -> Unit
                    is ReceiveEvent.AckBatchReady -> {
                        ackBatches++
                        assertTrue(sender.onFrame(ChunkFrame.serialize(event.frame)))
                    }

                    is ReceiveEvent.Completed -> {
                        completedFrame = event.frame
                        assertTrue(sender.onFrame(ChunkFrame.serialize(event.frame)))
                    }

                    is ReceiveEvent.Rejected -> throw AssertionError("unexpected $event")
                }
            }
        }

        assertTrue(assembler.matches(payload))
        assertEquals(plan.totalChunks, assembler.writes)
        assertNotNull(completedFrame)
        assertTrue(completedFrame!!.verified)
        assertEquals(true, sender.receiverVerified)
        assertTrue((sendResult as SendResult.Completed).fileSha256Hex.isNotEmpty())
        assertTrue(sender.pendingConfirmation!!.isEmpty())
        assertTrue(ackBatches >= 1, "expected at least one batched ACK")
    }

    @Test
    fun `resume mid-file - kill after k chunks then finish from receiver done-set`() = runTest {
        val chunker = Chunker()
        val meta = meta("resume-case")
        val plan = chunker.plan(meta, chunkSize)
        val killAfterChunks = 12

        val assembler = Assembler(plan.totalChunks)
        var chunkSends = 0
        val firstReceiver = ReceivePipeline(assembler)
        val killingSend: suspend (ByteArray) -> Boolean = { frameBytes ->
            val parsed = ChunkFrame.parse(frameBytes)
            if (chunkSends >= killAfterChunks && parsed !is ChunkFrame.FileStart) {
                false
            } else {
                if (parsed !is ChunkFrame.FileStart) chunkSends++
                firstReceiver.onFrame(frameBytes)
                true
            }
        }
        val killedSender = SendPipeline(chunker, killingSend)

        val aborted = killedSender.send(meta, source(), requestedChunkSize = chunkSize)
        assertTrue(aborted is SendResult.Aborted)
        assertEquals(killAfterChunks, (aborted as SendResult.Aborted).chunksSentBeforeFailure)
        assertEquals(killAfterChunks, assembler.writes)

        val doneIndexes = firstReceiver.doneIndexes(meta.transferId)!!
        assertEquals(killAfterChunks, doneIndexes.size)

        // Fresh pipeline reconciles against the receiver's persisted done-set and finishes.
        val wire2 = ArrayDeque<ByteArray>()
        val resumedSender = SendPipeline(chunker) { frameBytes ->
            wire2.addLast(frameBytes)
            true
        }
        val resumed = resumedSender.send(
            meta,
            source(),
            requestedChunkSize = chunkSize,
            fileSha256Hex = Sha256.digestHex(payload),
            doneIndexes = doneIndexes,
        )
        val resumedCompleted = resumed as SendResult.Completed
        assertEquals(killAfterChunks, resumedCompleted.chunksSkippedResume)
        assertEquals(plan.totalChunks - killAfterChunks, resumedCompleted.chunksSent)

        var completedFrame: ChunkFrame.Complete? = null
        while (wire2.isNotEmpty()) {
            for (event in firstReceiver.onFrame(wire2.removeFirst())) {
                when (event) {
                    is ReceiveEvent.SessionStarted -> Unit
                    is ReceiveEvent.AckBatchReady ->
                        assertTrue(resumedSender.onFrame(ChunkFrame.serialize(event.frame)))

                    is ReceiveEvent.Completed -> {
                        completedFrame = event.frame
                        assertTrue(resumedSender.onFrame(ChunkFrame.serialize(event.frame)))
                    }

                    is ReceiveEvent.Rejected -> throw AssertionError("unexpected $event")
                }
            }
        }

        // Chunks are disjoint across attempts, so the shared sink sees each index exactly once.
        assertEquals(plan.totalChunks, assembler.writes)
        assertTrue(assembler.matches(payload))
        assertNotNull(completedFrame)
        assertTrue(completedFrame!!.verified)
        assertEquals(true, resumedSender.receiverVerified)
        // ACKs drain after send() exits, so full confirmation is not yet visible in the result.
        assertFalse(resumedCompleted.fullyConfirmedByReceiver)
    }
}
