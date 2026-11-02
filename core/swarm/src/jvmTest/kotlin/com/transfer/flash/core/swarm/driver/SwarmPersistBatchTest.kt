package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.swarm.engine.SwarmCommand
import com.transfer.flash.core.swarm.model.ContentRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPT-O08-04: one `fsync` and one bitfield row write per received piece (4,096 of each for a 4 GB file at 1 MiB pieces,
 * 16,384 for 1 GB at 64 KiB) becomes one of each per content per burst, because only the newest snapshot of the
 * bitfield carries information.
 *
 * The batch must never lose the newest state and must never reorder itself around a record write.
 */
class SwarmPersistBatchTest {

    private fun bits(groupId: String, rootHex: String, pieces: Int, bytesDone: Long): SwarmCommand.SyncAndPersistBits =
        SwarmCommand.SyncAndPersistBits(
            groupId = groupId,
            root = ContentRoot(rootHex),
            bits = ByteArray((pieces + 7) / 8) { if (it < pieces / 8) -1 else 0 },
            bytesDone = bytesDone,
            totalSize = 4L * 1024 * 1024,
        )

    private val rootA = "a".repeat(64)
    private val rootB = "b".repeat(64)

    @Test
    fun `a burst of pieces collapses to one write per content and keeps the newest snapshot`() {
        val batch = SwarmPersistBatch()
        for (i in 1..100) assertTrue(batch.add(bits("g1", rootA, pieces = i, bytesDone = i.toLong())))
        for (i in 1..10) assertTrue(batch.add(bits("g1", rootB, pieces = i, bytesDone = i.toLong())))
        assertEquals(108, batch.coalesced)

        val drained = batch.drain()
        assertEquals(2, drained.size, "one write per content, not one per piece")
        assertEquals(100L, drained[0].bytesDone, "the newest snapshot wins")
        assertEquals(10L, drained[1].bytesDone)
        assertTrue(batch.isEmpty)
    }

    @Test
    fun `a record stops the batch so the queue order is kept`() {
        val batch = SwarmPersistBatch()
        assertTrue(batch.add(bits("g1", rootA, pieces = 1, bytesDone = 1)))
        assertFalse(
            batch.add(SwarmCommand.PersistRecord(fakeRecord())),
            "a record must be handled by the caller, after this batch is written",
        )
        assertEquals(1, batch.drain().size)
        assertEquals(0, batch.coalesced)
    }

    @Test
    fun `an empty batch writes nothing`() {
        val batch = SwarmPersistBatch()
        assertTrue(batch.drain().isEmpty())
        assertEquals(0, batch.coalesced)
    }

    private fun fakeRecord() = com.transfer.flash.core.swarm.model.SwarmContentRecord(
        root = ContentRoot(rootA),
        groupId = "g1",
        messageId = "m1",
        role = com.transfer.flash.core.swarm.model.SwarmRole.RECEIVER,
        originId = "origin",
        originKey = "key",
        fileName = "f.bin",
        mime = "application/octet-stream",
        totalSize = 1024,
        pieceSize = 65_536,
        manifestBytes = null,
        bits = ByteArray(1),
        bytesDone = 0,
        state = com.transfer.flash.core.swarm.model.SwarmLifecycleState.ACTIVE,
        waitReason = null,
        failReason = null,
        localTransferId = "t1",
        sourceUri = null,
        sourcePersistent = false,
        partialKey = "k",
        finalPath = null,
        identitySize = 1024,
        identityModifiedMs = 0,
        deliveredTo = emptySet(),
        createdAtMs = 0,
        lastProgressAtMs = 0,
        expiresAtMs = 1_000,
    )
}
