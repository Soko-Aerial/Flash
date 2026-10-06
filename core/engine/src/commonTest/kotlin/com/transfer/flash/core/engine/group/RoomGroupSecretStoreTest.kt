package com.transfer.flash.core.engine.group

import com.transfer.flash.core.messaging.group.GroupSecretSource
import com.transfer.flash.core.messaging.group.StoredGroupSecret
import com.transfer.flash.core.persistence.db.dao.GroupSecretDao
import com.transfer.flash.core.persistence.db.entity.GroupSecretEntity
import com.transfer.flash.core.security.group.GroupSecret
import com.transfer.flash.core.security.group.GroupSecretCommit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomGroupSecretStoreTest {

    private class FakeGroupSecretDao : GroupSecretDao {
        private val records = mutableMapOf<Pair<String, Long>, GroupSecretEntity>()

        override suspend fun upsert(entity: GroupSecretEntity) {
            records[entity.groupId to entity.epoch] = entity
        }

        override suspend fun getByGroupAndEpoch(groupId: String, epoch: Long): GroupSecretEntity? {
            return records[groupId to epoch]
        }

        override suspend fun getLatestForGroup(groupId: String): GroupSecretEntity? {
            return records.values
                .filter { it.groupId == groupId }
                .maxByOrNull { it.epoch }
        }

        override suspend fun getAllForGroup(groupId: String): List<GroupSecretEntity> {
            return records.values
                .filter { it.groupId == groupId }
                .sortedBy { it.epoch }
        }

        override suspend fun deleteForGroup(groupId: String) {
            records.keys.removeAll { it.first == groupId }
        }

        override suspend fun getAll(): List<GroupSecretEntity> {
            return records.values.toList()
        }
    }

    private val dao = FakeGroupSecretDao()
    private val store = RoomGroupSecretStore(dao)

    private val secretBytes1 = ByteArray(32) { (it + 1).toByte() }
    private val secret1 = GroupSecret.fromBytes(secretBytes1)
    private val commit1 = GroupSecretCommit.ofHex("g2-crew", 1L, secret1)

    private val secretBytes2 = ByteArray(32) { (it + 0x40).toByte() }
    private val secret2 = GroupSecret.fromBytes(secretBytes2)
    private val commit2 = GroupSecretCommit.ofHex("g2-crew", 2L, secret2)

    @Test
    fun roundtrip_and_current_advances_with_epoch() = runTest {
        assertNull(store.current("g2-crew"))

        val record1 = StoredGroupSecret(
            groupId = "g2-crew",
            epoch = 1L,
            secret = secret1,
            commit = commit1,
            source = GroupSecretSource.CREATED,
            receivedAtMs = 1000L,
        )
        store.put(record1)

        val current1 = store.current("g2-crew")
        assertNotNull(current1)
        assertEquals(1L, current1.epoch)
        assertEquals(commit1, current1.commit)
        assertTrue(secret1.constantTimeEquals(current1.secret))
        assertEquals(GroupSecretSource.CREATED, current1.source)

        // Advance to epoch 2
        val record2 = StoredGroupSecret(
            groupId = "g2-crew",
            epoch = 2L,
            secret = secret2,
            commit = commit2,
            source = GroupSecretSource.ROTATED,
            receivedAtMs = 2000L,
        )
        store.put(record2)

        val current2 = store.current("g2-crew")
        assertNotNull(current2)
        assertEquals(2L, current2.epoch)
        assertTrue(secret2.constantTimeEquals(current2.secret))

        // Querying older epoch 1 still works
        val oldRecord = store.get("g2-crew", 1L)
        assertNotNull(oldRecord)
        assertEquals(1L, oldRecord.epoch)
        assertTrue(secret1.constantTimeEquals(oldRecord.secret))

        // Forget group clears all epochs
        store.forget("g2-crew")
        assertNull(store.current("g2-crew"))
        assertNull(store.get("g2-crew", 1L))
        assertNull(store.get("g2-crew", 2L))
    }

    @Test
    fun stored_group_secret_toString_never_leaks_secret() {
        val rawSecret = byteArrayOf(
            0xfe.toByte(), 0xed.toByte(), 0xfa.toByte(), 0xce.toByte(),
            0xba.toByte(), 0xbe.toByte(), 0x01, 0x02,
            0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a,
            0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12,
            0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x20,
        )
        val sec = GroupSecret.fromBytes(rawSecret)
        val com = GroupSecretCommit.ofHex("g2-crew", 1L, sec)
        val stored = StoredGroupSecret(
            groupId = "g2-crew",
            epoch = 1L,
            secret = sec,
            commit = com,
            source = GroupSecretSource.INVITE,
            receivedAtMs = 123456789L,
        )

        val str = stored.toString()
        assertFalse(str.contains("feedface"), "toString must not leak secret hex")
        assertFalse(str.contains("babe0102"), "toString must not leak secret hex")
        assertTrue(str.contains("commit=$com"), "toString should contain commit hex")
    }
}
