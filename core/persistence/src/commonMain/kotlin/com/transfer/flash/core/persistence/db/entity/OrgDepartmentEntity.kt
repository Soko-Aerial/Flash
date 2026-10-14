package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persisted organization department (Plan B, ADR-134).
 */
@Entity(tableName = "org_department")
public data class OrgDepartmentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val memberCount: Int,
    val headDeviceIdsJson: String,
    val hidden: Boolean,
)
