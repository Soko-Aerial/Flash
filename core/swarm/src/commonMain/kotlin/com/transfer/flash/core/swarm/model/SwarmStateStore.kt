package com.transfer.flash.core.swarm.model

/**
 * Durable store for swarm content records and tombstones (§5.2, §5.4, SW-6).
 */
public interface SwarmStateStore {
    public suspend fun loadAll(): List<SwarmContentRecord>
    public suspend fun upsert(record: SwarmContentRecord)
    public suspend fun setBits(root: ContentRoot, groupId: String, bits: ByteArray, bytesDone: Long)
    public suspend fun putTombstone(tombstone: SwarmTombstone)
    public suspend fun tombstones(): List<SwarmTombstone>
    public suspend fun delete(root: ContentRoot, groupId: String)
    public suspend fun purgeExpired(nowMs: Long)
}
