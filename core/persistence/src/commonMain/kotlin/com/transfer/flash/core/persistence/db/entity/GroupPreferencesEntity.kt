package com.transfer.flash.core.persistence.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Storage for device-local preferences for a group (ADR-074, GM-9).
 *
 * These settings never leave the device and never appear in any wire frame.
 */
@Entity(tableName = "group_preferences")
public data class GroupPreferencesEntity(
    @PrimaryKey val groupId: String,
    val serveToGroup: Boolean,
    val serveWifiOnly: Boolean,
    val batteryThresholdPercent: Int,
    val keepAvailableDays: Int,
    val autoAcceptSizeBytes: Long,
)
