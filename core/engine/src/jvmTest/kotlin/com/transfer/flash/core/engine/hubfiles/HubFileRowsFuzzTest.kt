@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.engine.hubfiles

import com.transfer.flash.core.persistence.db.entity.HubFileEntity
import com.transfer.flash.core.persistence.db.entity.HubFileStates
import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferState
import okio.Buffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Fuzz (HUB-QUALITY-CONTRACT): the streaming source against random reads, and the row mapping over every row shape. */
class HubFileRowsFuzzTest {

    private val states = listOf(
        HubFileStates.UPLOADING, HubFileStates.READY, HubFileStates.QUEUED, HubFileStates.OFFERED, HubFileStates.DOWNLOADING,
        HubFileStates.DONE, HubFileStates.FAILED, HubFileStates.CANCELLED, HubFileStates.DECLINED, "SOMETHING-NEWER",
    )

    @Test
    fun `U39 random reads of a streamed source return exactly the bytes at that offset`() {
        val rnd = Random(2026)
        val data = rnd.nextBytes(64 * 1024 + 13)
        val opens = AtomicInteger()
        val opener = FileSourceOpener { _ -> opens.incrementAndGet(); Buffer().write(data) }
        val source = StreamingHubFileSource(opener, "mem://x", data.size.toLong())
        repeat(2000) {
            val offset = rnd.nextLong(0, data.size.toLong())
            val length = rnd.nextInt(0, (data.size - offset).coerceAtMost(9000L).toInt() + 1)
            assertContentEquals(data.copyOfRange(offset.toInt(), offset.toInt() + length), source.read(offset, length))
        }
        // Reading front to back opens the stream once.
        source.close()
        opens.set(0)
        var at = 0L
        while (at < data.size) {
            val n = minOf(4096L, data.size - at).toInt()
            source.read(at, n)
            at += n
        }
        assertEquals(1, opens.get())
        assertFailsWith<IllegalArgumentException> { source.read(data.size.toLong() - 1, 2) }
        assertFailsWith<IllegalArgumentException> { source.read(-1, 1) }
        assertEquals(data.size.toLong(), StreamingHubFileSource.measure(opener, "mem://x"))
    }

    @Test
    fun `U40 every row shape maps to a consistent transfer`() {
        val rnd = Random(77)
        repeat(5000) {
            val total = rnd.nextLong(1, 60_000_000)
            val row = HubFileEntity(
                transferId = (if (rnd.nextBoolean()) HubFileStates.TRANSFER_PREFIX_IN else HubFileStates.TRANSFER_PREFIX_OUT) + it,
                direction = if (rnd.nextBoolean()) HubFileStates.IN else HubFileStates.OUT,
                fileId = "f$it", peerId = if (rnd.nextBoolean()) "peer-abcdefghij" else "", hubUrl = "https://h",
                fileName = "n$it", mime = "x/y", plainBytes = total, sourceUri = null, fileKey = null, cipherSha256 = null,
                cipherChunkBytes = 0, chunkCount = 0, expiresAtMs = 0L, messageId = null,
                state = states[rnd.nextInt(states.size)], paused = rnd.nextBoolean(),
                errorText = if (rnd.nextBoolean()) "why" else null, bytesDone = rnd.nextLong(0, total + 1),
                localPath = null, createdAtMs = 0L, updatedAtMs = 0L,
            )
            val live = if (rnd.nextBoolean()) HubFileLive(rnd.nextLong(0, total + 1), rnd.nextLong(0, 1_000_000), rnd.nextLong(0, 10_000)) else null
            val running = rnd.nextBoolean()
            val t = HubFileRows.toTransfer(row, live, running, if (rnd.nextBoolean()) "Bea" else null)

            assertTrue(t.viaHub && t.isEncrypted)
            assertEquals(row.transferId, t.id.value)
            assertEquals(if (row.direction == HubFileStates.OUT) FlashTransferDirection.Sending else FlashTransferDirection.Receiving, t.direction)
            assertTrue(t.bytesDone in 0..t.bytesTotal, "progress stays within the file")
            assertTrue(t.peerName.isNotEmpty() || row.peerId.isEmpty())
            if (t.state != FlashTransferState.Transferring) assertTrue(t.speedBytesPerSec == 0L && t.etaSeconds == 0L, "no speed when idle")
            if (t.state == FlashTransferState.Completed) assertEquals(t.bytesTotal, t.bytesDone)
            val inFlight = row.state == HubFileStates.UPLOADING || row.state == HubFileStates.DOWNLOADING
            if (inFlight && row.paused) assertEquals(FlashTransferState.Paused, t.state, "paused wins over running")
            if (!inFlight) assertTrue(t.state != FlashTransferState.Transferring && t.state != FlashTransferState.Paused)
            if (row.state !in states.take(9)) assertEquals(FlashTransferState.Cancelled, t.state, "an unknown state never looks active")
        }
    }
}
