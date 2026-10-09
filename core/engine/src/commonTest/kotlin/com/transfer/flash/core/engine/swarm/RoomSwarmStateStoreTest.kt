package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.engine.swarm.RoomSwarmStateStore.Companion.toEntity
import com.transfer.flash.core.engine.swarm.RoomSwarmStateStore.Companion.toModel
import com.transfer.flash.core.engine.swarm.RoomSwarmStateStore.Companion.toRecord
import com.transfer.flash.core.persistence.db.dao.SwarmDao
import com.transfer.flash.core.persistence.db.entity.SwarmContentEntity
import com.transfer.flash.core.persistence.db.entity.SwarmTombstoneEntity
import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmLifecycleState
import com.transfer.flash.core.swarm.model.SwarmRole
import com.transfer.flash.core.swarm.model.SwarmTombstone
import com.transfer.flash.core.swarm.model.SwarmTombstoneReason
import com.transfer.flash.core.swarm.model.SwarmWaitReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomSwarmStateStoreTest {

    private val fakeDao = FakeSwarmDao()
    private val store = RoomSwarmStateStore(fakeDao)

    @Test
    fun `content record entity round trip preserves all fields`() {
        val record = SwarmContentRecord(
            root = ContentRoot("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
            groupId = "g2-crew",
            messageId = "m-101",
            role = SwarmRole.RECEIVER,
            originId = "alice",
            originKey = "key-alice",
            fileName = "archive.tar",
            mime = "application/x-tar",
            totalSize = 5242880L,
            pieceSize = 131072,
            manifestBytes = byteArrayOf(10, 20, 30),
            bits = byteArrayOf(0b01010101),
            bytesDone = 131072L,
            state = SwarmLifecycleState.ACTIVE,
            waitReason = SwarmWaitReason.WAITING_FOR_HOLDERS,
            failReason = null,
            localTransferId = "tx-local-recv",
            sourceUri = null,
            sourcePersistent = false,
            partialKey = "part-key-101",
            finalPath = null,
            identitySize = 5242880L,
            identityModifiedMs = 1700000000000L,
            deliveredTo = setOf("bob", "charlie"),
            createdAtMs = 1700000000000L,
            lastProgressAtMs = 1700000005000L,
            expiresAtMs = 1700003600000L,
        )

        val entity = record.toEntity()
        assertEquals(record.root.hex, entity.root)
        assertEquals(record.groupId, entity.groupId)
        assertEquals("bob,charlie", entity.deliveredTo)
        assertEquals("RECEIVER", entity.role)
        assertEquals("ACTIVE", entity.state)
        assertEquals("WAITING_FOR_HOLDERS", entity.waitReason)

        val restored = entity.toRecord()
        assertEquals(record, restored)
    }

    @Test
    fun `tombstone entity round trip preserves core fields`() {
        val tombstone = SwarmTombstone(
            groupId = "g2-crew",
            root = ContentRoot("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
            originId = "alice",
            messageId = "m-101",
            reason = SwarmTombstoneReason.USER,
            cancelledAtMs = 1700000000000L,
            signature = byteArrayOf(1, 2, 3, 4),
        )

        val entity = tombstone.toEntity()
        assertEquals(tombstone.groupId, entity.groupId)
        assertEquals(tombstone.root.hex, entity.root)
        assertEquals("USER", entity.reason)
        assertTrue(entity.expiresAtMs > entity.cancelledAtMs)

        val restored = entity.toModel()
        assertEquals(tombstone, restored)
    }

    @Test
    fun `deliveredTo parser handles empty, comma-separated and json array representations`() {
        val baseEntity = SwarmContentEntity(
            root = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            groupId = "g1",
            messageId = "m1",
            role = "ORIGIN",
            originId = "a",
            originKey = "k",
            fileName = "f",
            mime = "m",
            totalSize = 10,
            pieceSize = 10,
            manifest = null,
            bits = byteArrayOf(1),
            bytesDone = 10,
            state = "COMPLETE",
            waitReason = null,
            failReason = null,
            localTransferId = "tx1",
            sourceUri = null,
            sourcePersistent = false,
            partialKey = "pk",
            finalPath = null,
            identitySize = 10,
            identityModifiedMs = 1,
            deliveredTo = "",
            createdAtMs = 1,
            lastProgressAtMs = 1,
            expiresAtMs = 100,
        )

        assertEquals(emptySet(), baseEntity.copy(deliveredTo = "").toRecord().deliveredTo)
        assertEquals(emptySet(), baseEntity.copy(deliveredTo = "[]").toRecord().deliveredTo)
        assertEquals(setOf("p1", "p2"), baseEntity.copy(deliveredTo = "p1,p2").toRecord().deliveredTo)
        assertEquals(setOf("p1", "p2"), baseEntity.copy(deliveredTo = "[\"p1\", \"p2\"]").toRecord().deliveredTo)
    }

    @Test
    fun `store operations delegate correctly to dao`() = runTest {
        val root = ContentRoot("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
        val record = SwarmContentRecord(
            root = root,
            groupId = "g2-crew",
            messageId = "m-101",
            role = SwarmRole.ORIGIN,
            originId = "alice",
            originKey = "key-alice",
            fileName = "file.bin",
            mime = "application/octet-stream",
            totalSize = 1000L,
            pieceSize = 100,
            manifestBytes = null,
            bits = byteArrayOf(0),
            bytesDone = 0L,
            state = SwarmLifecycleState.ACTIVE,
            waitReason = null,
            failReason = null,
            localTransferId = "tx-1",
            sourceUri = null,
            sourcePersistent = true,
            partialKey = "pk",
            finalPath = null,
            identitySize = 1000L,
            identityModifiedMs = 100L,
            deliveredTo = emptySet(),
            createdAtMs = 1000L,
            lastProgressAtMs = 1000L,
            expiresAtMs = 2000L,
        )

        // Upsert
        store.upsert(record)
        assertEquals(1, store.loadAll().size)
        assertEquals(record, store.loadAll().first())

        // Set bits
        val newBits = byteArrayOf(1)
        store.setBits(root, "g2-crew", newBits, 100L)
        val afterBits = store.loadAll().first()
        assertTrue(newBits.contentEquals(afterBits.bits))
        assertEquals(100L, afterBits.bytesDone)

        // Tombstone
        val tombstone = SwarmTombstone(
            groupId = "g2-crew",
            root = root,
            originId = "alice",
            messageId = "m-101",
            reason = SwarmTombstoneReason.USER,
            cancelledAtMs = 1500L,
            signature = byteArrayOf(5, 5, 5),
        )
        store.putTombstone(tombstone)
        assertEquals(1, store.tombstones().size)
        assertEquals(tombstone, store.tombstones().first())

        // Purge
        store.purgeExpired(nowMs = 5000L)
        // Record had expiresAtMs = 2000L, but state was ACTIVE so not purged by state filter
        assertEquals(1, store.loadAll().size)

        // Delete
        store.delete(root, "g2-crew")
        assertTrue(store.loadAll().isEmpty())
    }

    private fun entityWith(root: String, messageId: String, role: String = "RECEIVER", state: String = "ACTIVE") = SwarmContentEntity(
        root = root, groupId = "g1", messageId = messageId, role = role, originId = "a", originKey = "k",
        fileName = "f", mime = "m", totalSize = 10, pieceSize = 10, manifest = null, bits = byteArrayOf(1),
        bytesDone = 0, state = state, waitReason = null, failReason = null, localTransferId = messageId,
        sourceUri = null, sourcePersistent = false, partialKey = "pk", finalPath = null, identitySize = 0,
        identityModifiedMs = 0, deliveredTo = "", createdAtMs = 1, lastProgressAtMs = 1, expiresAtMs = 100,
    )

    @Test
    fun `one row with an unknown enum value or a corrupt root does not abort the restore`() = runTest {
        val good1 = "1".repeat(64)
        val good2 = "2".repeat(64)
        fakeDao.upsertContent(entityWith(good1, "ok1"))
        fakeDao.upsertContent(entityWith("3".repeat(64), "bad-state", state = "FROM_A_NEWER_BUILD"))
        fakeDao.upsertContent(entityWith("4".repeat(64), "bad-role", role = "SPECTATOR"))
        fakeDao.upsertContent(entityWith("not-a-root", "bad-root"))
        fakeDao.upsertContent(entityWith(good2, "ok2"))

        val loaded = store.loadAll()

        assertEquals(setOf("ok1", "ok2"), loaded.map { it.messageId }.toSet())
    }

    @Test
    fun `a tombstone with a corrupt root is skipped, the others are restored`() = runTest {
        fakeDao.upsertTombstone(
            SwarmTombstoneEntity(
                groupId = "g1", messageId = "t-bad", root = "zz", originId = "o", reason = "USER",
                cancelledAtMs = 1, signature = byteArrayOf(1), receivedAtMs = 1, expiresAtMs = 100,
            )
        )
        fakeDao.upsertTombstone(
            SwarmTombstoneEntity(
                groupId = "g1", messageId = "t-ok", root = "5".repeat(64), originId = "o", reason = "USER",
                cancelledAtMs = 1, signature = byteArrayOf(1), receivedAtMs = 1, expiresAtMs = 100,
            )
        )
        assertEquals(listOf("t-ok"), store.tombstones().map { it.messageId })
    }

    private class FakeSwarmDao : SwarmDao {
        val contents = mutableMapOf<Pair<String, String>, SwarmContentEntity>()
        val tombstones = mutableMapOf<Pair<String, String>, SwarmTombstoneEntity>()

        override suspend fun upsertContent(entity: SwarmContentEntity) {
            contents[entity.root to entity.groupId] = entity
        }

        override suspend fun getContent(root: String, groupId: String): SwarmContentEntity? =
            contents[root to groupId]

        override suspend fun getContentByTransferId(transferId: String): SwarmContentEntity? =
            contents.values.firstOrNull { it.localTransferId == transferId }

        override suspend fun getContentByMessageId(groupId: String, messageId: String): SwarmContentEntity? =
            contents.values.firstOrNull { it.groupId == groupId && it.messageId == messageId }

        override suspend fun loadAllContent(): List<SwarmContentEntity> =
            contents.values.toList()

        override suspend fun loadContentForGroup(groupId: String): List<SwarmContentEntity> =
            contents.values.filter { it.groupId == groupId }

        override suspend fun updateBits(root: String, groupId: String, bits: ByteArray, bytesDone: Long, nowMs: Long) {
            contents[root to groupId]?.let {
                contents[root to groupId] = it.copy(bits = bits, bytesDone = bytesDone, lastProgressAtMs = nowMs)
            }
        }

        override suspend fun updateState(root: String, groupId: String, state: String, waitReason: String?, failReason: String?, nowMs: Long) {
            contents[root to groupId]?.let {
                contents[root to groupId] = it.copy(state = state, waitReason = waitReason, failReason = failReason, lastProgressAtMs = nowMs)
            }
        }

        override suspend fun finalizeContent(root: String, groupId: String, finalPath: String, identitySize: Long, identityModifiedMs: Long, state: String, nowMs: Long) {
            contents[root to groupId]?.let {
                contents[root to groupId] = it.copy(finalPath = finalPath, identitySize = identitySize, identityModifiedMs = identityModifiedMs, state = state, waitReason = null, failReason = null, lastProgressAtMs = nowMs)
            }
        }

        override suspend fun deleteContent(root: String, groupId: String) {
            contents.remove(root to groupId)
        }

        override suspend fun upsertTombstone(entity: SwarmTombstoneEntity) {
            tombstones[entity.groupId to entity.messageId] = entity
        }

        override suspend fun getTombstone(groupId: String, messageId: String): SwarmTombstoneEntity? =
            tombstones[groupId to messageId]

        override suspend fun getTombstonesForGroup(groupId: String): List<SwarmTombstoneEntity> =
            tombstones.values.filter { it.groupId == groupId }

        override suspend fun getTombstonesForContent(root: String, groupId: String): List<SwarmTombstoneEntity> =
            tombstones.values.filter { it.root == root && it.groupId == groupId }

        override suspend fun loadAllTombstones(): List<SwarmTombstoneEntity> =
            tombstones.values.toList()

        override suspend fun deleteTombstone(groupId: String, messageId: String) {
            tombstones.remove(groupId to messageId)
        }

        override suspend fun purgeExpiredContent(nowMs: Long): Int {
            val toRemove = contents.filter { (_, v) ->
                v.expiresAtMs in 1 until nowMs && v.state in setOf("COMPLETE", "CANCELLED", "FAILED")
            }.keys
            toRemove.forEach { contents.remove(it) }
            return toRemove.size
        }

        override suspend fun purgeExpiredTombstones(nowMs: Long): Int {
            val toRemove = tombstones.filter { (_, v) ->
                v.expiresAtMs in 1 until nowMs
            }.keys
            toRemove.forEach { tombstones.remove(it) }
            return toRemove.size
        }
    }
}
