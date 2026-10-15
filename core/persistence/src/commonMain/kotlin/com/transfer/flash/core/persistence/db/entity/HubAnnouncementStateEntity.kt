package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted sync state for organization announcements (Plan A3 §4.2).
 * Singleton row with [id] = 1.
 */
@Entity(tableName = "hub_announcement_state")
public data class HubAnnouncementStateEntity(
    @PrimaryKey val id: Int = 1,
    val epoch: String,
    val seq: Long,
    val lastSyncAtMs: Long,
)
