package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persisted organization announcement on client devices (Plan A3, ADR-138).
 * Stored as plaintext on the hub and on the device.
 */
@Entity(
    tableName = "hub_announcement",
    indices = [
        Index("scope", "departmentId", "createdAtMs"),
        Index("readAtMs"),
        Index("searchFolded"),
    ],
)
public data class HubAnnouncementEntity(
    @PrimaryKey val id: String,
    val scope: String,
    val departmentId: String?,
    val title: String,
    val body: String,
    val authorName: String,
    val authorRole: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val edited: Boolean,
    val pinned: Boolean,
    val expiresAtMs: Long?,
    val seq: Long,
    val readAtMs: Long?,
    val searchFolded: String,
)
