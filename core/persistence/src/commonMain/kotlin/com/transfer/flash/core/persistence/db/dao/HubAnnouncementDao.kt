package com.transfer.flash.core.persistence.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.transfer.flash.core.persistence.db.entity.HubAnnouncementEntity
import com.transfer.flash.core.persistence.db.entity.HubAnnouncementStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room data access object for organization announcements and sync state (Plan A3 §4.2).
 */
@Dao
public interface HubAnnouncementDao {

    @Query("SELECT * FROM hub_announcement ORDER BY pinned DESC, createdAtMs DESC")
    public fun observeAll(): Flow<List<HubAnnouncementEntity>>

    @Query("SELECT COUNT(*) FROM hub_announcement WHERE readAtMs IS NULL AND (expiresAtMs IS NULL OR expiresAtMs > :nowMs)")
    public fun observeUnreadCount(nowMs: Long): Flow<Int>

    @Query("SELECT * FROM hub_announcement WHERE id = :id")
    public suspend fun getById(id: String): HubAnnouncementEntity?

    @Query("UPDATE hub_announcement SET readAtMs = :readAtMs WHERE id = :id AND readAtMs IS NULL")
    public suspend fun markRead(id: String, readAtMs: Long)

    @Upsert
    public suspend fun upsert(announcements: List<HubAnnouncementEntity>)

    @Upsert
    public suspend fun upsertOne(announcement: HubAnnouncementEntity)

    @Query("DELETE FROM hub_announcement WHERE id = :id")
    public suspend fun deleteById(id: String)

    @Query("DELETE FROM hub_announcement WHERE id IN (:ids)")
    public suspend fun deleteByIds(ids: List<String>)

    @Query("UPDATE hub_announcement SET pinned = :pinned WHERE id = :id")
    public suspend fun setPinned(id: String, pinned: Boolean)

    @Query("SELECT * FROM hub_announcement WHERE searchFolded LIKE '%' || :queryFolded || '%' AND (expiresAtMs IS NULL OR expiresAtMs > :nowMs) ORDER BY pinned DESC, createdAtMs DESC")
    public suspend fun search(queryFolded: String, nowMs: Long): List<HubAnnouncementEntity>

    @Query("SELECT * FROM hub_announcement_state WHERE id = 1")
    public suspend fun getState(): HubAnnouncementStateEntity?

    @Upsert
    public suspend fun setState(state: HubAnnouncementStateEntity)

    @Query("DELETE FROM hub_announcement_state")
    public suspend fun clearState()

    @Query("DELETE FROM hub_announcement")
    public suspend fun clearAnnouncements()

    @Query("SELECT id FROM hub_announcement WHERE scope = :scope AND (:departmentId IS NULL OR departmentId = :departmentId) ORDER BY pinned DESC, createdAtMs DESC LIMIT -1 OFFSET :keepCount")
    public suspend fun findOlderThanKeepLimit(scope: String, departmentId: String?, keepCount: Int): List<String>

    /** Every scope together; pinned first, then unread, then newest, so a read old item goes before an unread one (plan 3.4). */
    @Query("SELECT id FROM hub_announcement ORDER BY pinned DESC, (readAtMs IS NULL) DESC, createdAtMs DESC LIMIT -1 OFFSET :keepCount")
    public suspend fun findBeyondKeepLimit(keepCount: Int): List<String>

    @Transaction
    public suspend fun pruneOldAnnouncements(maxKeep: Int = 500) {
        val idsToDelete = findBeyondKeepLimit(maxKeep)
        if (idsToDelete.isNotEmpty()) {
            deleteByIds(idsToDelete)
        }
    }

    @Transaction
    public suspend fun clearAll() {
        clearAnnouncements()
        clearState()
    }
}
