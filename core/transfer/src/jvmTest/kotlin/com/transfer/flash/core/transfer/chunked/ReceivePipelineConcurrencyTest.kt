package com.transfer.flash.core.transfer.chunked

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceivePipelineConcurrencyTest {

    @Test
    fun `slow sinkFactory in acceptSession does not block concurrent transfers`() {
        val enteredFactory = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val data = ByteArray(100) { (it % 13).toByte() }
        val chunker = Chunker()

        val slowMeta = FileMeta("slow-tx", "slow-file", "slow.bin", data.size.toLong())
        val slowPlan = chunker.plan(slowMeta, 16_384)
        val slowStart = chunker.fileStart(slowMeta, slowPlan, Sha256.digestHex(data))

        val fastMeta = FileMeta("fast-tx", "fast-file", "fast.bin", data.size.toLong())
        val fastPlan = chunker.plan(fastMeta, 16_384)
        val fastStart = chunker.fileStart(fastMeta, fastPlan, Sha256.digestHex(data))
        val fastChunk = chunker.openChunkStream(
            ChunkSource { okio.Buffer().write(data) },
            fastMeta,
            fastPlan,
        ).use { it.next() }

        val sink = ChunkSink { _, _ -> }
        val pipeline = ReceivePipeline(
            sink = sink,
            requireAcceptance = true,
            sinkFactory = { start ->
                if (start.transferId == "slow-tx") {
                    enteredFactory.countDown()
                    releaseFactory.await(5, TimeUnit.SECONDS)
                }
                sink
            },
        )

        pipeline.onFrame(ChunkFrame.serialize(slowStart))
        pipeline.onFrame(ChunkFrame.serialize(fastStart))

        var slowAccepted = false
        val worker = thread {
            slowAccepted = pipeline.acceptSession("slow-tx")
        }

        assertTrue(enteredFactory.await(2, TimeUnit.SECONDS), "slow acceptSession never entered sinkFactory")

        // While slow-tx is blocked in sinkFactory, fast-tx must accept and complete without stalling.
        assertTrue(pipeline.acceptSession("fast-tx"))
        val events = pipeline.onFrame(ChunkFrame.serialize(fastChunk))
        assertTrue(events.any { it is ReceiveEvent.Completed }, "fast-tx completed while slow-tx was in sinkFactory")
        assertEquals(data.size.toLong(), pipeline.doneBytes("fast-tx"))

        releaseFactory.countDown()
        worker.join(2_000)
        assertTrue(slowAccepted)
    }
}
