package com.transfer.flash.core.messaging.group

import com.transfer.flash.core.persistence.db.entity.GroupPreferencesEntity
import com.transfer.flash.core.persistence.db.entity.GroupSettingsEntity
import com.transfer.flash.core.messaging.protocol.GroupSettings

/**
 * Device-local preferences for a group (ADR-074, GM-9).
 *
 * These settings never leave the device and never appear in any wire frame.
 *
 * @property groupId The group these preferences apply to.
 * @property serveToGroup Whether to serve swarm files to peers in this group.
 * @property serveWifiOnly Whether to only serve swarm files when connected to Wi-Fi.
 * @property batteryThresholdPercent Minimum battery percentage required to serve swarm files (0..100).
 * @property keepAvailableDays How many days to keep completed files available for swarming.
 * @property autoAcceptSizeBytes Maximum file size in bytes to automatically accept for inbound transfers.
 */
public data class GroupLocalPreferences(
    val groupId: String,
    val serveToGroup: Boolean = true,
    val serveWifiOnly: Boolean = false,
    val batteryThresholdPercent: Int = 20,
    val keepAvailableDays: Int = 7,
    val autoAcceptSizeBytes: Long = DEFAULT_AUTO_ACCEPT_BYTES,
) {
    public companion object {
        public const val DEFAULT_AUTO_ACCEPT_BYTES: Long = 100L * 1024L * 1024L // 100 MB

        public fun defaults(groupId: String): GroupLocalPreferences = GroupLocalPreferences(groupId = groupId)
    }
}

public fun GroupLocalPreferences.toEntity(): GroupPreferencesEntity = GroupPreferencesEntity(
    groupId = groupId,
    serveToGroup = serveToGroup,
    serveWifiOnly = serveWifiOnly,
    batteryThresholdPercent = batteryThresholdPercent,
    keepAvailableDays = keepAvailableDays,
    autoAcceptSizeBytes = autoAcceptSizeBytes,
)

public fun GroupPreferencesEntity.toPreferences(): GroupLocalPreferences = GroupLocalPreferences(
    groupId = groupId,
    serveToGroup = serveToGroup,
    serveWifiOnly = serveWifiOnly,
    batteryThresholdPercent = batteryThresholdPercent,
    keepAvailableDays = keepAvailableDays,
    autoAcceptSizeBytes = autoAcceptSizeBytes,
)

public fun GroupSettings.toEntity(): GroupSettingsEntity = GroupSettingsEntity(
    groupId = groupId,
    version = version,
    joinPolicy = joinPolicy,
    inviteSharers = inviteSharers,
    maxMembers = maxMembers,
    swarmServing = swarmServing,
    membersMayAdd = membersMayAdd,
    opId = opId,
    signerId = signerId,
    sig = sig,
)

public fun GroupSettingsEntity.toSettings(): GroupSettings = GroupSettings(
    groupId = groupId,
    version = version,
    joinPolicy = joinPolicy,
    inviteSharers = inviteSharers,
    maxMembers = maxMembers,
    swarmServing = swarmServing,
    membersMayAdd = membersMayAdd,
    opId = opId,
    signerId = signerId,
    sig = sig,
)
