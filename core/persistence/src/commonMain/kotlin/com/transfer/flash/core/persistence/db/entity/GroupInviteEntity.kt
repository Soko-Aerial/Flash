package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An accepted group invite (GM-2).
 *
 * This table is the trust root that `checkCharter` reads (GM-4, GINV-3):
 * an unknown v2 group is accepted when the device holds an accepted invite for that group id.
 *
 * [state] is one of `PENDING_CONTACT`, `PENDING_APPROVAL`, `APPROVED`, `JOINED`, `REFUSED`, `ABANDONED`,
 * `STALE` (the link's secret is older than the group's) or `INVALID` (the proof was rejected; retried on reconnect).
 */
@Entity(
    tableName = "group_invite",
    indices = [Index(value = ["state"])],
)
public data class GroupInviteEntity(
    @PrimaryKey val groupId: String,
    val inviterId: String,
    val inviterFingerprint: String,
    val acceptedAtMs: Long,
    val state: String,
)
