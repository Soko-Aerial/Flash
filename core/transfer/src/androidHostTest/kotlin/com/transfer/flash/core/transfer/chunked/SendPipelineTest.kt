package com.transfer.flash.core.transfer.chunked

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendPipelineTest {

    private val chunkSize = 16_384
    private val totalBytes = 10L * chunkSize
    private val meta = FileMeta("t-send", "f-send", "archive.zip", totalBytes)
    private val payload = ByteArray(totalBytes.toInt()) { (it % 13).toByte() }
    private val fileHash = Sha256.digestHex(payload)

    private fun source() = ChunkSource { Buffer().write(payload) }

    @Test
    fun `abort before FILE_START surfaces zero progress`() = runBlocking {
        val pipeline = SendPipeline(Chunker()) { false }
        val result = pipeline.send(meta, source(), requestedChunkSize = chunkSize, fileSha256Hex = fileHash)
        val aborted = result as SendResult.Aborted
        assertEquals(-1, aborted.failedIndex)
        assertEquals(0, aborted.chunksSentBeforeFailure)
    }

    @Test
    fun `abort mid-file records failed index and sent count`() = runBlocking {
        var chunksSent = 0
        val failingSend: suspend (ByteArray) -> Boolean = { bytes ->
            if (ChunkFrame.parse(bytes) is ChunkFrame.FileStart) {
                true
            } else if (chunksSent >= 4) {
                false
            } else {
                chunksSent++
                true
            }
        }
        val pipeline = SendPipeline(Chunker(), failingSend)
        val result = pipeline.send(meta, source(), requestedChunkSize = chunkSize, fileSha256Hex = fileHash)
        val aborted = result as SendResult.Aborted
        assertEquals(4, chunksSent)
        assertEquals(4, aborted.chunksSentBeforeFailure)
        assertEquals(4, aborted.failedIndex)
        assertEquals(listOf(0, 1, 2, 3), pipeline.sentSnapshot)
    }

    @Test
    fun `resumeFrom skips done chunks - read and hashed but never sent`() = runBlocking {
        val outbound = ArrayDeque<ByteArray>()
        val pipeline = SendPipeline(Chunker()) { bytes ->
            outbound.addLast(bytes)
            true
        }
        val done = listOf(0, 1, 2, 7) // arbitrary holes pattern, including a high index
        val result = pipeline.send(
            meta,
            source(),
            requestedChunkSize = chunkSize,
            fileSha256Hex = fileHash,
            doneIndexes = done,
        )
        val completed = result as SendResult.Completed
        assertEquals(6, completed.chunksSent)
        assertEquals(4, completed.chunksSkippedResume)
        assertEquals(totalBytes - 4L * chunkSize, completed.bytesSent)

        val chunkSends = outbound.mapNotNull { ChunkFrame.parse(it) as? ChunkFrame.Chunk }
        assertEquals(6, chunkSends.size)
        val sentIndexes = chunkSends.map { it.index }.toSet()
        assertTrue(sentIndexes.intersect(done.toSet()).isEmpty())

        // Skipped chunks still contributed to the identity guard via the stream's own digest.
        assertEquals(fileHash, completed.fileSha256Hex)
        assertTrue(completed.fullyConfirmedByReceiver.not())
    }

    @Test
    fun `ack batches merge into confirmed mirror driving pendingConfirmation`() = runBlocking {
        val pipeline = SendPipeline(Chunker()) { true }
        val result = pipeline.send(meta, source(), requestedChunkSize = chunkSize, fileSha256Hex = fileHash)
        assertTrue(result is SendResult.Completed)
        assertTrue(pipeline.sentSnapshot!!.isNotEmpty())
        assertTrue(pipeline.pendingConfirmation!!.isNotEmpty())

        val ack = ChunkFrame.AckBatch(meta.transferId, meta.fileId, listOf(0, 1, 2))
        assertTrue(pipeline.onFrame(ChunkFrame.serialize(ack)))
        assertEquals((3 until 10).toList(), pipeline.pendingConfirmation)

        assertTrue(
            pipeline.onFrame(
                ChunkFrame.serialize(ChunkFrame.Complete(meta.transferId, meta.fileId, verified = true)),
            ),
        )
        assertEquals(true, pipeline.receiverVerified)
    }

    @Test
    fun `stale or foreign frames are ignored without crashing`() = runBlocking {
        val pipeline = SendPipeline(Chunker()) { true }
        pipeline.send(meta, source(), requestedChunkSize = chunkSize, fileSha256Hex = fileHash)

        assertFalse(pipeline.onFrame(byteArrayOf(9, 9)))
        assertFalse(pipeline.onFrame(ChunkFrame.serialize(ChunkFrame.AckBatch("other", "other", listOf(0)))))
        assertNull(pipeline.receiverVerified)
        pipeline.reset()
        assertNull(pipeline.sentSnapshot)
    }

    @Test
    fun `hash-only prepass produces correct FILE_START digest`() = runBlocking {
        var opens = 0
        val countingSource = ChunkSource {
            opens++
            Buffer().write(payload)
        }
        val outbound = ArrayDeque<ByteArray>()
        val pipeline = SendPipeline(Chunker()) { outbound.addLast(it); true }
        val result = pipeline.send(meta, countingSource, requestedChunkSize = chunkSize)
        assertTrue(result is SendResult.Completed)
        // One open for hashOnly pre-pass + one for the chunking pass.
        assertEquals(2, opens)
        val start = outbound.firstOrNull { ChunkFrame.parse(it) is ChunkFrame.FileStart }
        val parsedStart = ChunkFrame.parse(start!!) as ChunkFrame.FileStart
        assertEquals(fileHash, parsedStart.fileSha256Hex)
    }
}
