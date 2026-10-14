package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row state table for organization directory sync progress (Plan B, ADR-134).
 * Always stored with [id] = 1.
 */
@Entity(tableName = "org_sync_state")
public data class OrgSyncStateEntity(
    @PrimaryKey val id: Int = 1,
    val directoryEpoch: String,
    val lastSyncedVersion: Long,
    val offlineGraceDays: Int,
    val lastSyncSuccessMs: Long,
    val lastSyncAttemptMs: Long,
)
