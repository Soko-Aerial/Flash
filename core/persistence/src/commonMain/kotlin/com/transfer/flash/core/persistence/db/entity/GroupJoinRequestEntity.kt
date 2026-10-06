package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * A join request received or relayed for a group (GM-2, GM-4).
 *
 * Primary key: `(groupId, subjectId, subjectKey)`.
 */
@Entity(
    tableName = "group_join_request",
    primaryKeys = ["groupId", "subjectId", "subjectKey"],
    indices = [
        Index(value = ["groupId"]),
        Index(value = ["state"]),
    ],
)
public data class GroupJoinRequestEntity(
    val groupId: String,
    val subjectId: String,
    val subjectKey: String,
    val label: String,
    val requestSig: String,
    val viaPeerId: String?,
    val requestedAtMs: Long,
    val state: String,
    val decidedBy: String?,
    val decidedAtMs: Long?,
)
