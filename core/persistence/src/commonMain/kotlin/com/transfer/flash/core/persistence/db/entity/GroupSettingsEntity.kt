package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Storage for signed group settings shared across the group (ADR-074, GM-9).
 *
 * Each entry is signed by an admin (owner or co-owner/admin) under canonical domain `"flash-gset-v1"`.
 * Highest valid [version] wins, with tie-break on smaller [opId].
 */
@Entity(tableName = "group_settings")
public data class GroupSettingsEntity(
    @PrimaryKey val groupId: String,
    val version: Long,
    val joinPolicy: String,
    val inviteSharers: String,
    val maxMembers: Int,
    val swarmServing: Boolean,
    val membersMayAdd: Boolean,
    val opId: String,
    val signerId: String,
    val sig: String,
    /** ADR-100: the signed history ceiling (`NONE|H24|D7|D30|ALL`); rows from before the column read as `D30`. */
    val historyCeiling: String = "D30",
)
