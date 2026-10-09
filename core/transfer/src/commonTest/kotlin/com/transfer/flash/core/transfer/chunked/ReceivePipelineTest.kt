package com.transfer.flash.core.transfer.chunked

import com.transfer.flash.core.transfer.chunked.ReceiveEvent
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moved to `commonTest` in Phase 13B-3e with `ReceivePipeline.kt`, whose eight `@Synchronized`
 * methods became [com.transfer.flash.core.transfer.concurrent.PlatformLock] blocks and whose
 * `sortedSetOf` done-set became a `HashSet`. Neither change is visible from here, which is the
 * point: the ACK-ordering assertions below (`assertEquals(listOf(2, 4), ack.frame.indexes)`,
 * `assertEquals(listOf(32, 32, 6), batchSizes)`) are exactly what proves the explicit `.sorted()`
 * that replaced the TreeSet still puts ascending indexes on the wire. They now run on the desktop
 * JVM as well as on Android.
 *
 * Twelve message-carrying assertions had their arguments swapped, since `kotlin.test` takes the
 * message LAST and `org.junit.Assert` takes it first. That flip is silent — `assertNull(message,
 * value)` compiles fine and asserts the wrong argument — so it was done mechanically and then
 * re-checked by grepping for any remaining string literal in first position.
 *
 * `"assembled-differently".toByteArray()` became `encodeToByteArray()`: the former resolves to the
 * JVM-only overload that takes a `java.nio.charset.Charset`. Both are UTF-8 here, so the digest the
 * negative-verification case feeds in is unchanged.
 */
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

    private fun source() = ChunkSource { Buffer().write(ByteArray(totalBytes.toInt()) { (it % 7).toByte() }) }

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
        assertNull(pipeline.flushPendingAck(), "pending cleared after flush")
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
        assertNull(pipeline.flushPendingAck(), "nothing left pending after completion")
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

        val (badComplete, _) = runWith(Sha256.digestHex("assembled-differently".encodeToByteArray()))
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

        assertNotNull(completed, "completion reached without re-sending seeded chunks")
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
        assertNotNull(completed, "all-chunks-persisted resume finalizes immediately")
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
        assertEquals(0, sinkFactoryCalls, "no sink resolved before acceptance")

        // A chunk arriving before the user accepts is dropped, never written.
        val early = chunkFrames()[0]
        val rejected = pipeline.onFrame(ChunkFrame.serialize(early)).single() as ReceiveEvent.Rejected
        assertEquals(RejectReason.AWAITING_ACCEPTANCE, rejected.reason)
        assertEquals(0, sink.writes)
        assertNull(pipeline.flushPendingAck(), "nothing acked while awaiting acceptance")
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

        assertTrue(pipeline.acceptSession(meta.transferId), "acceptSession opens an awaiting offer")
        assertEquals(1, sinkFactoryCalls, "sink resolved exactly once on accept")
        assertFalse(pipeline.acceptSession(meta.transferId), "second accept is a no-op")

        val frames = chunkFrames()
        var completed: ChunkFrame.Complete? = null
        for (frame in frames) {
            for (event in pipeline.onFrame(ChunkFrame.serialize(frame))) {
                if (event is ReceiveEvent.Completed) completed = event.frame
            }
            if (completed != null) break
        }
        assertNotNull(completed, "accepted offer streams to completion")
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
        assertFalse(pipeline.declineSession(meta.transferId), "declining a gone session returns false")

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
        assertNotNull(completed, "a fully-persisted resume finalizes even under the offer gate")
        // No new destination is created for an already-complete resume, and accept is a no-op.
        assertEquals(0, sink.writes)
        assertFalse(pipeline.acceptSession(meta.transferId))
    }

    // ---- finished sessions must not use up the session cap (audit 2026-10-08) ----

    private class OneChunkTransfer(val id: String) {
        private val data = ByteArray(100) { (it % 11).toByte() }
        private val meta = FileMeta(id, "f-$id", "small.bin", data.size.toLong())
        private val chunker = Chunker()
        private val plan = chunker.plan(meta, 16_384)
        val start: ChunkFrame.FileStart = chunker.fileStart(meta, plan, Sha256.digestHex(data))
        val chunk: ChunkFrame.Chunk = chunker.openChunkStream(ChunkSource { Buffer().write(data) }, meta, plan).use { it.next() }
    }

    @Test
    fun `forty sequential completed receives are all accepted, finished sessions do not fill the cap`() {
        val pipeline = ReceivePipeline(RecordingSink(), maxConcurrentSessions = 32)
        repeat(40) { n ->
            val t = OneChunkTransfer("seq-$n")
            val started = pipeline.onFrame(ChunkFrame.serialize(t.start))
            assertTrue(started.none { it is ReceiveEvent.Rejected }, "offer $n must be accepted: $started")
            val events = pipeline.onFrame(ChunkFrame.serialize(t.chunk))
            assertTrue(events.any { it is ReceiveEvent.Completed }, "transfer $n must complete: $events")
        }
    }

    @Test
    fun `a duplicate chunk after completion is still silently ignored while the session is retained`() {
        val pipeline = ReceivePipeline(RecordingSink(), maxConcurrentSessions = 2)
        val t = OneChunkTransfer("dup")
        pipeline.onFrame(ChunkFrame.serialize(t.start))
        assertTrue(pipeline.onFrame(ChunkFrame.serialize(t.chunk)).any { it is ReceiveEvent.Completed })
        // Churn well past the live cap, but within the finished-retention bound.
        repeat(10) { n ->
            val other = OneChunkTransfer("churn-$n")
            pipeline.onFrame(ChunkFrame.serialize(other.start))
            pipeline.onFrame(ChunkFrame.serialize(other.chunk))
        }
        assertEquals(emptyList(), pipeline.onFrame(ChunkFrame.serialize(t.chunk)))
    }

    @Test
    fun `live sessions still hit the cap and finished sessions are bounded`() {
        val pipeline = ReceivePipeline(RecordingSink(), maxConcurrentSessions = 2, maxFinishedSessionsRetained = 3)
        // Two sessions that never complete (a FILE_START only).
        pipeline.onFrame(ChunkFrame.serialize(OneChunkTransfer("live-1").start))
        pipeline.onFrame(ChunkFrame.serialize(OneChunkTransfer("live-2").start))
        val full = pipeline.onFrame(ChunkFrame.serialize(OneChunkTransfer("live-3").start))
        assertEquals(listOf(RejectReason.SESSION_FULL), full.filterIsInstance<ReceiveEvent.Rejected>().map { it.reason })

        val bounded = ReceivePipeline(RecordingSink(), maxConcurrentSessions = 2, maxFinishedSessionsRetained = 3)
        repeat(20) { n ->
            val t = OneChunkTransfer("done-$n")
            bounded.onFrame(ChunkFrame.serialize(t.start))
            bounded.onFrame(ChunkFrame.serialize(t.chunk))
        }
        assertTrue(bounded.activeTransferIds().size <= 4, "finished sessions are evicted oldest first: ${bounded.activeTransferIds()}")
        assertTrue("done-19" in bounded.activeTransferIds())
    }

    @Test
    fun `doneBytes is maintained incrementally and counts resume-seeded chunks`() {
        val pipeline = ReceivePipeline(RecordingSink(), ackEvery = 1000, resumeIndexesProvider = { listOf(0, 1) })
        val start = startFrame()
        pipeline.onFrame(ChunkFrame.serialize(start))
        assertEquals(2L * chunkSize, pipeline.doneBytes(start.transferId))
        val frames = chunkFrames()
        pipeline.onFrame(ChunkFrame.serialize(frames[5]))
        assertEquals(3L * chunkSize, pipeline.doneBytes(start.transferId))
        // A duplicate adds nothing.
        pipeline.onFrame(ChunkFrame.serialize(frames[5]))
        assertEquals(3L * chunkSize, pipeline.doneBytes(start.transferId))
        assertNull(pipeline.doneBytes("unknown"))
        assertEquals(pipeline.doneIndexes(start.transferId)!!.size * chunkSize.toLong(), pipeline.doneBytes(start.transferId))
    }

    // ---- sweep 2026-10-09: R-06 (a failed write is surfaced) and R-07 (bounded offers) ----

    private class FailingSink(private val failure: Throwable) : ChunkSink {
        var attempts = 0
        override fun write(index: Int, data: ByteArray) {
            attempts++
            throw failure
        }
    }

    @Test
    fun `R-06 a sink that throws produces a typed WRITE_FAILED rejection and drops the session`() {
        val sink = FailingSink(RuntimeException("No space left on device"))
        val pipeline = ReceivePipeline(sink, ackEvery = 1)
        pipeline.onFrame(ChunkFrame.serialize(startFrame()))
        val frames = chunkFrames()

        val events = pipeline.onFrame(ChunkFrame.serialize(frames[0]))
        val rejected = events.filterIsInstance<ReceiveEvent.Rejected>().single()
        assertEquals(RejectReason.WRITE_FAILED, rejected.reason)
        assertEquals(meta.transferId, rejected.transferId)
        assertEquals(0, rejected.index)
        assertTrue(events.none { it is ReceiveEvent.AckBatchReady }, "a chunk that was not written is never ACKed")
        assertNull(pipeline.doneIndexes(meta.transferId), "the session is gone")

        // The sender's next chunk answers UNKNOWN_TRANSFER (which stops it) and nothing is written again.
        val later = pipeline.onFrame(ChunkFrame.serialize(frames[1]))
        assertEquals(listOf(RejectReason.UNKNOWN_TRANSFER), later.filterIsInstance<ReceiveEvent.Rejected>().map { it.reason })
        assertEquals(1, sink.attempts)
    }

    @Test
    fun `R-06 an identical re-offer after a write failure opens a fresh session`() {
        val pipeline = ReceivePipeline(FailingSink(okio.IOException("disk")), emitSessionStarted = true)
        pipeline.onFrame(ChunkFrame.serialize(startFrame()))
        pipeline.onFrame(ChunkFrame.serialize(chunkFrames()[0]))
        val again = pipeline.onFrame(ChunkFrame.serialize(startFrame()))
        assertTrue(again.any { it is ReceiveEvent.SessionStarted }, "not swallowed as a resume restart: $again")
    }

    @Test
    fun `R-06 cancellation and errors are not swallowed as write failures`() {
        val cancelled = ReceivePipeline(FailingSink(kotlin.coroutines.cancellation.CancellationException("stop")))
        cancelled.onFrame(ChunkFrame.serialize(startFrame()))
        assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
            cancelled.onFrame(ChunkFrame.serialize(chunkFrames()[0]))
        }
        val fatal = ReceivePipeline(FailingSink(Error("out of memory stand-in")))
        fatal.onFrame(ChunkFrame.serialize(startFrame()))
        assertFailsWith<Error> { fatal.onFrame(ChunkFrame.serialize(chunkFrames()[0])) }
    }

    @Test
    fun `R-07 an offer above the chunk cap is refused before anything is allocated`() {
        val pipeline = ReceivePipeline(RecordingSink())
        val chunks = ReceivePipeline.MAX_TOTAL_CHUNKS + 1
        val huge = startFrame().copy(totalChunks = chunks, totalBytes = chunks.toLong() * chunkSize)
        val events = pipeline.onFrame(ChunkFrame.serialize(huge))
        assertEquals(listOf(RejectReason.INVALID_FILE_START), events.filterIsInstance<ReceiveEvent.Rejected>().map { it.reason })
        assertNull(pipeline.doneIndexes(huge.transferId))
        // Int.MAX_VALUE chunks (the 268 MB case) is refused the same way.
        val max = startFrame().copy(totalChunks = Int.MAX_VALUE, totalBytes = Int.MAX_VALUE.toLong() * chunkSize)
        assertEquals(
            listOf(RejectReason.INVALID_FILE_START),
            pipeline.onFrame(ChunkFrame.serialize(max)).filterIsInstance<ReceiveEvent.Rejected>().map { it.reason },
        )
    }

    @Test
    fun `R-07 an offer exactly at the cap is still accepted`() {
        val pipeline = ReceivePipeline(RecordingSink())
        val chunks = ReceivePipeline.MAX_TOTAL_CHUNKS
        val edge = startFrame().copy(totalChunks = chunks, totalBytes = chunks.toLong() * chunkSize)
        val events = pipeline.onFrame(ChunkFrame.serialize(edge))
        assertTrue(events.none { it is ReceiveEvent.Rejected }, "$events")
        assertNotNull(pipeline.doneIndexes(edge.transferId))
    }
}
