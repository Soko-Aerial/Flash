package com.transfer.flash.core.transfer.chunked

import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceivePipelineTest {

    private val chunkSize = 16_384
    private val totalBytes = 70L * chunkSize // 70 chunks -> exercises two full batches + tail
    private val meta = FileMeta("t-recv", "f-recv", "video.bin", totalBytes)
    private val fileHash = Sha256.digestHex(ByteArray(totalBytes.toInt()) { (it % 7).toByte() })

    private class RecordingSink : ChunkSink {
        val parts = HashMap<Int, ByteArray>()
        var writes = 0
        val indexesWritten = ArrayList<Int>()

        override fun write(index: Int, data: ByteArray) {
            writes++
            if (parts.put(index, data.copyOf()) == null) indexesWritten.add(index)
        }
    }

    private fun source() = ChunkSource { ByteArray(totalBytes.toInt()) { (it % 7).toByte() }.inputStream() }

    private fun startFrame(): ChunkFrame.FileStart =
        Chunker().fileStart(meta, Chunker().plan(meta, chunkSize), fileHash)

    private fun chunkFrames(): List<ChunkFrame.Chunk> {
        val chunker = Chunker()
        val plan = chunker.plan(meta, chunkSize)
        chunker.openChunkStream(source(), meta, plan).use { s ->
            return generateSequence { if (s.hasNext()) s.next() else null }.toList()
        }
    }

    @Test
    fun `fileStart validation rejects broken chunk math and sizes`() {
        val sink = RecordingSink()
        val pipeline = ReceivePipeline(sink)

        val badMath = startFrame().copy(totalChunks = 5)
        assertEquals(
            listOf(RejectReason.INVALID_FILE_START),
            pipeline.onFrame(ChunkFrame.serialize(badMath)).filterIsInstance<com.transfer.flash.core.transfer.chunked.ReceiveEvent.Rejected>().map { it.reason },
        )

        val badChunkSize = startFrame().copy(chunkSize = 1000)
        assertEquals(
            listOf(RejectReason.INVALID_FILE_START),
            pipeline.onFrame(ChunkFrame.serialize(badChunkSize)).filterIsInstance<com.transfer.flash.core.transfer.chunked.ReceiveEvent.Rejected>().map { it.reason },
        )
        assertEquals(0, sink.writes)
    }

    @Test
    fun `chunk for unknown transfer is rejected gracefully`() {
        val pipeline = ReceivePipeline(RecordingSink())
        val stranger = chunkFrames().first().copy(transferId = "who-is-this")
        val events = pipeline.onFrame(ChunkFrame.serialize(stranger))
        assertEquals(listOf(RejectReason.UNKNOWN_TRANSFER), events.filterIsInstance<ReceiveEvent.Rejected>().map { it.reason })
    }

    @Test
    fun `corrupted chunk is rejected without write and NACKed via ack absence`() {
        val sink = RecordingSink()
        val pipeline = ReceivePipeline(sink)
        assertTrue(pipeline.onFrame(ChunkFrame.serialize(startFrame())).isEmpty())

        val good = chunkFrames()
        val corruptedOriginal = good[3]
        val flipped = corruptedOriginal.data.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val corrupted = corruptedOriginal.copy(data = flipped)

        // Chunks around the corrupt one flow normally first.
        pipeline.onFrame(ChunkFrame.serialize(good[2]))
        val events = pipeline.onFrame(ChunkFrame.serialize(corrupted))
        assertEquals(1, events.size)
        assertEquals(RejectReason.HASH_MISMATCH, (events.single() as ReceiveEvent.Rejected).reason)
        assertFalse(sink.parts.containsKey(3))

        // Implicit NACK: the flushed batch must NOT contain the corrupt index.
        pipeline.onFrame(ChunkFrame.serialize(good[4]))
        val ack = pipeline.flushPendingAck() as ReceiveEvent.AckBatchReady
        assertEquals(listOf(2, 4), ack.frame.indexes)
        assertFalse(ack.frame.indexes.contains(3))
    }

    @Test
    fun `duplicate chunk idempotent - still acked but never rewritten`() {
        val sink = RecordingSink()
        val pipeline = ReceivePipeline(sink)
        pipeline.onFrame(ChunkFrame.serialize(startFrame()))

        val frame = chunkFrames()[0]
        assertTrue(pipeline.onFrame(ChunkFrame.serialize(frame)).isEmpty())
        assertTrue(pipeline.onFrame(ChunkFrame.serialize(frame)).isEmpty())
        assertEquals(1, sink.writes)
        assertEquals(listOf(0), sink.indexesWritten)

        val ack = pipeline.flushPendingAck() as ReceiveEvent.AckBatchReady
        assertEquals(listOf(0), ack.frame.indexes)
        assertNull("pending cleared after flush", pipeline.flushPendingAck())
    }

    @Test
    fun `acks batch every 32 distinct chunks then complete flushes the tail`() {
        val sink = RecordingSink()
        val pipeline = ReceivePipeline(sink)
        pipeline.onFrame(ChunkFrame.serialize(startFrame()))

        val frames = chunkFrames()
        val batchSizes = ArrayList<Int>()
        var completed: ChunkFrame.Complete? = null
        outer@ for (frame in frames) {
            for (event in pipeline.onFrame(ChunkFrame.serialize(frame))) {
                when (event) {
                    is ReceiveEvent.SessionStarted -> Unit
                    is ReceiveEvent.AckBatchReady -> batchSizes.add(event.frame.indexes.size)
                    is ReceiveEvent.Completed -> completed = event.frame
                    is ReceiveEvent.Rejected -> throw AssertionError("unexpected rejection $event")
                }
            }
            if (completed != null) break@outer
        }

        // Two full 32-chunk batches, then the completion handler flushes the 6-chunk tail.
        assertEquals(listOf(32, 32, 6), batchSizes)
        assertNotNull(completed)
        assertTrue(completed!!.verified)
        assertEquals((64 until frames.size).toList(), sink.indexesWritten.takeLast(6))
        assertNull("nothing left pending after completion", pipeline.flushPendingAck())
        assertEquals((0 until frames.size).toList(), sink.indexesWritten)
    }

    @Test
    fun `out-of-range and size-mismatched chunks are rejected without write`() {
        val sink = RecordingSink()
        val pipeline = ReceivePipeline(sink)
        pipeline.onFrame(ChunkFrame.serialize(startFrame()))

        val tooBigIndex = chunkFrames()[0].copy(index = 999)
        assertEquals(
            RejectReason.INDEX_OUT_OF_RANGE,
            (pipeline.onFrame(ChunkFrame.serialize(tooBigIndex)).single() as ReceiveEvent.Rejected).reason,
        )

        val shortData = chunkFrames()[0].copy(data = byteArrayOf(1, 2, 3))
        assertEquals(
            RejectReason.CHUNK_SIZE_MISMATCH,
            (pipeline.onFrame(ChunkFrame.serialize(shortData)).single() as ReceiveEvent.Rejected).reason,
        )
        assertEquals(0, sink.writes)
    }

    @Test
    fun `wrong-direction and malformed frames are rejected`() {
        val pipeline = ReceivePipeline(RecordingSink())
        val ack = ChunkFrame.AckBatch("t", "f", listOf(1))
        assertEquals(
            RejectReason.UNEXPECTED_DIRECTION,
            (pipeline.onFrame(ChunkFrame.serialize(ack)).single() as ReceiveEvent.Rejected).reason,
        )
        assertEquals(
            RejectReason.MALFORMED_FRAME,
            (pipeline.onFrame(byteArrayOf(1, 2, 3)).single() as ReceiveEvent.Rejected).reason,
        )
    }

    @Test
    fun `whole-file digest recheck drives verified flag`() {
        val frames = chunkFrames()

        fun runWith(providerDigest: String?): Pair<ChunkFrame.Complete, RecordingSink> {
            val sink = RecordingSink()
            val pipeline = ReceivePipeline(
                sink = sink,
                recheckWholeFileDigest = true,
                wholeFileDigest = { providerDigest },
            )
            pipeline.onFrame(ChunkFrame.serialize(startFrame()))
            var completed: ChunkFrame.Complete? = null
            for (frame in frames) {
                for (event in pipeline.onFrame(ChunkFrame.serialize(frame))) {
                    if (event is ReceiveEvent.Completed) completed = event.frame
                }
                if (completed != null) break
            }
            return Pair(completed!!, sink)
        }

        val (okComplete, okSink) = runWith(fileHash)
        assertTrue(okComplete.verified)
        assertEquals(frames.size, okSink.writes)

        val (badComplete, _) = runWith(Sha256.digestHex("assembled-differently".toByteArray()))
        assertFalse(badComplete.verified)

        // Null digest from the provider must not claim verification either.
        val (deferredComplete, _) = runWith(null)
        assertFalse(deferredComplete.verified)
    }

    @Test
    fun `resume seed pre-marks persisted chunks so sender may skip them`() {
        val frames = chunkFrames()
        // Simulate a pre-restart receiver that already persisted the first 40 chunks.
        val seeded = (0 until 40).toList()
        val sink = RecordingSink()
        val pipeline = ReceivePipeline(
            sink = sink,
            emitSessionStarted = true,
            resumeIndexesProvider = { seeded },
        )

        val startEvents = pipeline.onFrame(ChunkFrame.serialize(startFrame()))
        // Fresh session still just announces itself — seeded indexes are not yet complete.
        assertEquals(1, startEvents.filterIsInstance<ReceiveEvent.SessionStarted>().size)
        assertTrue(startEvents.none { it is ReceiveEvent.Completed })

        // The resuming sender omits the 40 seeded chunks and sends only the remaining 30.
        var completed: ChunkFrame.Complete? = null
        for (frame in frames.drop(40)) {
            for (event in pipeline.onFrame(ChunkFrame.serialize(frame))) {
                if (event is ReceiveEvent.Completed) completed = event.frame
            }
        }

        assertNotNull("completion reached without re-sending seeded chunks", completed)
        // Only the non-seeded chunks were written; seeded chunks were never re-received.
        assertEquals((40 until frames.size).toList(), sink.indexesWritten)
    }

    @Test
    fun `fully seeded resume completes on fileStart with no further chunks`() {
        val frames = chunkFrames()
        val sink = RecordingSink()
        val pipeline = ReceivePipeline(
            sink = sink,
            emitSessionStarted = true,
            resumeIndexesProvider = { (0 until frames.size).toList() },
        )

        val events = pipeline.onFrame(ChunkFrame.serialize(startFrame()))
        val completed = events.filterIsInstance<ReceiveEvent.Completed>().singleOrNull()
        assertNotNull("all-chunks-persisted resume finalizes immediately", completed)
        assertEquals(0, sink.writes)
    }

    @Test
    fun `resume seed ignores out-of-range indexes`() {
        val frames = chunkFrames()
        val sink = RecordingSink()
        val pipeline = ReceivePipeline(
            sink = sink,
            emitSessionStarted = true,
            resumeIndexesProvider = { listOf(-1, 0, frames.size, frames.size + 100) },
        )
        // Only index 0 is in range; the session must not complete or throw on the bogus indexes.
        val events = pipeline.onFrame(ChunkFrame.serialize(startFrame()))
        assertTrue(events.none { it is ReceiveEvent.Completed })

        var completed: ChunkFrame.Complete? = null
        for (frame in frames.drop(1)) {
            for (event in pipeline.onFrame(ChunkFrame.serialize(frame))) {
                if (event is ReceiveEvent.Completed) completed = event.frame
            }
        }
        assertNotNull(completed)
        assertEquals((1 until frames.size).toList(), sink.indexesWritten)
    }

    // ---- #5: inbound offer / acceptance gate ------------------------------------------------

    @Test
    fun `requireAcceptance defers the sink and drops chunks until accepted`() {
        val sink = RecordingSink()
        var sinkFactoryCalls = 0
        val pipeline = ReceivePipeline(
            sink = sink,
            emitSessionStarted = true,
            requireAcceptance = true,
            sinkFactory = { sinkFactoryCalls++; sink },
        )

        val startEvents = pipeline.onFrame(ChunkFrame.serialize(startFrame()))
        // The offer is announced, but no destination is created before consent.
        assertEquals(1, startEvents.filterIsInstance<ReceiveEvent.SessionStarted>().size)
        assertEquals("no sink resolved before acceptance", 0, sinkFactoryCalls)

        // A chunk arriving before the user accepts is dropped, never written.
        val early = chunkFrames()[0]
        val rejected = pipeline.onFrame(ChunkFrame.serialize(early)).single() as ReceiveEvent.Rejected
        assertEquals(RejectReason.AWAITING_ACCEPTANCE, rejected.reason)
        assertEquals(0, sink.writes)
        assertNull("nothing acked while awaiting acceptance", pipeline.flushPendingAck())
    }

    @Test
    fun `acceptSession resolves the sink and lets chunks flow to completion`() {
        val sink = RecordingSink()
        var sinkFactoryCalls = 0
        val pipeline = ReceivePipeline(
            sink = sink,
            requireAcceptance = true,
            sinkFactory = { sinkFactoryCalls++; sink },
        )
        pipeline.onFrame(ChunkFrame.serialize(startFrame()))

        assertTrue("acceptSession opens an awaiting offer", pipeline.acceptSession(meta.transferId))
        assertEquals("sink resolved exactly once on accept", 1, sinkFactoryCalls)
        assertFalse("second accept is a no-op", pipeline.acceptSession(meta.transferId))

        val frames = chunkFrames()
        var completed: ChunkFrame.Complete? = null
        for (frame in frames) {
            for (event in pipeline.onFrame(ChunkFrame.serialize(frame))) {
                if (event is ReceiveEvent.Completed) completed = event.frame
            }
            if (completed != null) break
        }
        assertNotNull("accepted offer streams to completion", completed)
        assertEquals(frames.size, sink.writes)
    }

    @Test
    fun `declineSession drops the offer so later chunks are unknown`() {
        val sink = RecordingSink()
        val pipeline = ReceivePipeline(
            sink = sink,
            requireAcceptance = true,
            sinkFactory = { sink },
        )
        pipeline.onFrame(ChunkFrame.serialize(startFrame()))

        assertTrue(pipeline.declineSession(meta.transferId))
        assertFalse("declining a gone session returns false", pipeline.declineSession(meta.transferId))

        val chunk = chunkFrames()[0]
        val rejected = pipeline.onFrame(ChunkFrame.serialize(chunk)).single() as ReceiveEvent.Rejected
        assertEquals(RejectReason.UNKNOWN_TRANSFER, rejected.reason)
        assertEquals(0, sink.writes)
    }

    @Test
    fun `fully seeded resume bypasses the acceptance gate and finalizes`() {
        val frames = chunkFrames()
        val sink = RecordingSink()
        var sinkFactoryCalls = 0
        val pipeline = ReceivePipeline(
            sink = sink,
            requireAcceptance = true,
            resumeIndexesProvider = { (0 until frames.size).toList() },
            sinkFactory = { sinkFactoryCalls++; sink },
        )

        val events = pipeline.onFrame(ChunkFrame.serialize(startFrame()))
        val completed = events.filterIsInstance<ReceiveEvent.Completed>().singleOrNull()
        assertNotNull("a fully-persisted resume finalizes even under the offer gate", completed)
        // No new destination is created for an already-complete resume, and accept is a no-op.
        assertEquals(0, sink.writes)
        assertFalse(pipeline.acceptSession(meta.transferId))
    }
}
