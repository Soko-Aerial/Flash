package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.GroupJoinRequestEntity

/**
 * Data access object for group join requests (GM-2).
 */
@Dao
public interface GroupJoinRequestDao {

    @Upsert
    public suspend fun upsert(entity: GroupJoinRequestEntity)

    @Query("SELECT * FROM group_join_request WHERE groupId = :groupId AND subjectId = :subjectId AND subjectKey = :subjectKey")
    public suspend fun get(groupId: String, subjectId: String, subjectKey: String): GroupJoinRequestEntity?

    @Query("SELECT * FROM group_join_request WHERE groupId = :groupId ORDER BY requestedAtMs DESC")
    public suspend fun getAllForGroup(groupId: String): List<GroupJoinRequestEntity>

    @Query("UPDATE group_join_request SET state = :state, decidedBy = :decidedBy, decidedAtMs = :decidedAtMs WHERE groupId = :groupId AND subjectId = :subjectId AND subjectKey = :subjectKey")
    public suspend fun updateDecision(
        groupId: String,
        subjectId: String,
        subjectKey: String,
        state: String,
        decidedBy: String?,
        decidedAtMs: Long?,
    )

    @Query("DELETE FROM group_join_request WHERE requestedAtMs < :olderThanMs")
    public suspend fun deleteExpired(olderThanMs: Long)

    @Query("DELETE FROM group_join_request WHERE groupId = :groupId")
    public suspend fun deleteForGroup(groupId: String)

    @Query("SELECT * FROM group_join_request")
    public suspend fun getAll(): List<GroupJoinRequestEntity>
}
