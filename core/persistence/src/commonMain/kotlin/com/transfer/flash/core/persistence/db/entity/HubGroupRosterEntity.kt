package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The latest hub-signed roster statement of one hub group (Plan B §10, Phase B4, schema step 16).
 * [statementJson] is the exact signed statement; every reader re-verifies it, the columns are only an index.
 * The whole set is replaced atomically by a directory sync commit and wiped with the snapshot.
 */
@Entity(tableName = "hub_group_roster")
public data class HubGroupRosterEntity(
    @PrimaryKey val groupId: String,
    val version: Long,
    val statementJson: String,
    /** The verified delegation chain the statement was checked with; a peer offered the roster needs it to verify too. */
    val delegationChainJson: String,
    val validUntilMs: Long,
)
