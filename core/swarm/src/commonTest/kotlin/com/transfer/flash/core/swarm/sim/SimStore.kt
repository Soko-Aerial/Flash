package com.transfer.flash.core.swarm.sim

import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.SwarmContentRecord
import com.transfer.flash.core.swarm.model.SwarmTombstone

/**
 * In-memory persistence mock for content records and tombstones (SW-5).
 */
class SimStore {
    private val records = LinkedHashMap<Pair<String, ContentRoot>, SwarmContentRecord>()
    private val tombstones = LinkedHashMap<Pair<String, String>, SwarmTombstone>()

    fun putRecord(record: SwarmContentRecord) {
        records[record.groupId to record.root] = record
    }

    fun getRecord(groupId: String, root: ContentRoot): SwarmContentRecord? {
        return records[groupId to root]
    }

    fun updateBits(groupId: String, root: ContentRoot, bits: ByteArray, bytesDone: Long) {
        val existing = records[groupId to root] ?: return
        records[groupId to root] = existing.copy(
            bits = bits,
            bytesDone = bytesDone,
        )
    }

    fun putTombstone(tombstone: SwarmTombstone) {
        tombstones[tombstone.groupId to tombstone.messageId] = tombstone
    }

    fun getTombstone(groupId: String, messageId: String): SwarmTombstone? {
        return tombstones[groupId to messageId]
    }

    fun getAllTombstones(): List<SwarmTombstone> = tombstones.values.toList()

    fun getAllRecords(): List<SwarmContentRecord> = records.values.toList()
}
