package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Projected membership state for a group. A leave remains as a tombstone so stale add frames cannot
 * silently rejoin a member; only a strictly newer membership operation may change this row.
 */
@Entity(
    tableName = "group_members",
    primaryKeys = ["groupId", "deviceId"],
    indices = [Index(value = ["groupId", "isActive"])],
)
public data class GroupMemberEntity(
    val groupId: String,
    val deviceId: String,
    val displayName: String,
    /** Reserved for Phase 3; Phase 1 writes only `member` for remote peers. */
    val role: String = "member",
    val joinedAt: Long,
    val membershipVersion: Long,
    val operationId: String,
    val isActive: Boolean = true,
)
