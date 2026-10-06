package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.SwarmContentEntity
import com.transfer.flash.core.persistence.db.entity.SwarmTombstoneEntity

/**
 * Data access object for durable swarm content records and tombstones (§5.4, SW-6).
 */
@Dao
public interface SwarmDao {

    @Upsert
    public suspend fun upsertContent(entity: SwarmContentEntity)

    @Query("SELECT * FROM swarm_content WHERE root = :root AND groupId = :groupId")
    public suspend fun getContent(root: String, groupId: String): SwarmContentEntity?

    @Query("SELECT * FROM swarm_content WHERE localTransferId = :transferId")
    public suspend fun getContentByTransferId(transferId: String): SwarmContentEntity?

    @Query("SELECT * FROM swarm_content WHERE groupId = :groupId AND messageId = :messageId")
    public suspend fun getContentByMessageId(groupId: String, messageId: String): SwarmContentEntity?

    @Query("SELECT * FROM swarm_content ORDER BY createdAtMs DESC")
    public suspend fun loadAllContent(): List<SwarmContentEntity>

    @Query("SELECT * FROM swarm_content WHERE groupId = :groupId ORDER BY createdAtMs DESC")
    public suspend fun loadContentForGroup(groupId: String): List<SwarmContentEntity>

    @Query("UPDATE swarm_content SET bits = :bits, bytesDone = :bytesDone, lastProgressAtMs = :nowMs WHERE root = :root AND groupId = :groupId AND bytesDone <= :bytesDone")
    public suspend fun updateBits(root: String, groupId: String, bits: ByteArray, bytesDone: Long, nowMs: Long)

    @Query("UPDATE swarm_content SET state = :state, waitReason = :waitReason, failReason = :failReason, lastProgressAtMs = :nowMs WHERE root = :root AND groupId = :groupId")
    public suspend fun updateState(root: String, groupId: String, state: String, waitReason: String?, failReason: String?, nowMs: Long)

    @Query("UPDATE swarm_content SET finalPath = :finalPath, identitySize = :identitySize, identityModifiedMs = :identityModifiedMs, state = :state, waitReason = null, failReason = null, lastProgressAtMs = :nowMs WHERE root = :root AND groupId = :groupId")
    public suspend fun finalizeContent(root: String, groupId: String, finalPath: String, identitySize: Long, identityModifiedMs: Long, state: String, nowMs: Long)

    @Query("DELETE FROM swarm_content WHERE root = :root AND groupId = :groupId")
    public suspend fun deleteContent(root: String, groupId: String)

    @Upsert
    public suspend fun upsertTombstone(entity: SwarmTombstoneEntity)

    @Query("SELECT * FROM swarm_tombstone WHERE groupId = :groupId AND messageId = :messageId")
    public suspend fun getTombstone(groupId: String, messageId: String): SwarmTombstoneEntity?

    @Query("SELECT * FROM swarm_tombstone WHERE groupId = :groupId")
    public suspend fun getTombstonesForGroup(groupId: String): List<SwarmTombstoneEntity>

    @Query("SELECT * FROM swarm_tombstone WHERE root = :root AND groupId = :groupId")
    public suspend fun getTombstonesForContent(root: String, groupId: String): List<SwarmTombstoneEntity>

    @Query("SELECT * FROM swarm_tombstone")
    public suspend fun loadAllTombstones(): List<SwarmTombstoneEntity>

    @Query("DELETE FROM swarm_tombstone WHERE groupId = :groupId AND messageId = :messageId")
    public suspend fun deleteTombstone(groupId: String, messageId: String)

    @Query("DELETE FROM swarm_content WHERE expiresAtMs > 0 AND expiresAtMs < :nowMs AND state IN ('COMPLETE', 'CANCELLED', 'FAILED')")
    public suspend fun purgeExpiredContent(nowMs: Long): Int

    @Query("DELETE FROM swarm_tombstone WHERE expiresAtMs > 0 AND expiresAtMs < :nowMs")
    public suspend fun purgeExpiredTombstones(nowMs: Long): Int
}
