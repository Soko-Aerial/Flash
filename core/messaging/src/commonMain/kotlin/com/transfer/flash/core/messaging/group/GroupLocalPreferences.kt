package com.transfer.flash.core.messaging.group

import com.transfer.flash.core.persistence.db.entity.GroupPreferencesEntity
import com.transfer.flash.core.persistence.db.entity.GroupSettingsEntity
import com.transfer.flash.core.messaging.protocol.GroupHistoryCeiling
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
    historyCeiling = historyCeilingColumn(),
)

/** Separates the ceiling name from its signature inside the `historyCeiling` column; not a base64 character. */
private const val CEILING_SIG_SEPARATOR: Char = '~'

/**
 * ADR-105: the ceiling's own signature is stored next to its name in the existing `historyCeiling` column (`D7~<base64>`),
 * so the schema stays at 13 and a device that relays the settings can send the signature on. A default ceiling is just `D30`.
 */
private fun GroupSettings.historyCeilingColumn(): String =
    if (historyCeiling == GroupHistoryCeiling.DEFAULT || historyCeilingSig.isEmpty()) {
        historyCeiling.name
    } else {
        historyCeiling.name + CEILING_SIG_SEPARATOR + historyCeilingSig
    }

/**
 * A stored non-default ceiling without its signature (a development database written before ADR-105) reads as the default:
 * it could not be relayed, and an object that states an unsigned ceiling is refused by every receiver.
 */
public fun GroupSettingsEntity.toSettings(): GroupSettings {
    val name = GroupHistoryCeiling.fromName(historyCeiling.substringBefore(CEILING_SIG_SEPARATOR)) ?: GroupHistoryCeiling.DEFAULT
    val ceilingSig = historyCeiling.substringAfter(CEILING_SIG_SEPARATOR, "")
    val signed = name == GroupHistoryCeiling.DEFAULT || ceilingSig.isNotEmpty()
    return GroupSettings(
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
        historyCeiling = if (signed) name else GroupHistoryCeiling.DEFAULT,
        historyCeilingSig = if (signed && name != GroupHistoryCeiling.DEFAULT) ceilingSig else "",
    )
}
