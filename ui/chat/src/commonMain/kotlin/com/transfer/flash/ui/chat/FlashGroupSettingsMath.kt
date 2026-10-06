package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.protocol.GroupPolicy
import com.transfer.flash.core.messaging.protocol.GroupSettings

/**
 * Pure math and formatting logic for Group Settings (UI-053).
 * Unit-testable without instrumentation.
 */
public object FlashGroupSettingsMath {

    /** True if caller is owner or admin who may modify signed group rules. */
    public fun canEditGroupRules(isOwner: Boolean, isAdmin: Boolean): Boolean = isOwner || isAdmin

    /** Clamps max members between 2 and [GroupPolicy.MAX_MEMBERS_V2] (50). */
    public fun clampMaxMembers(current: Int, delta: Int, min: Int = 2, max: Int = GroupPolicy.MAX_MEMBERS_V2): Int =
        (current + delta).coerceIn(min, max)

    /** Human-readable label for join policy. */
    public fun joinPolicyLabel(policy: String): String =
        if (policy.equals(GroupSettings.POLICY_APPROVE, ignoreCase = true)) {
            "Approval required"
        } else {
            "Open to anyone with link"
        }

    /** Detail sentence explaining join policy. */
    public fun joinPolicyDescription(policy: String): String =
        if (policy.equals(GroupSettings.POLICY_APPROVE, ignoreCase = true)) {
            "New members need an admin's approval to join."
        } else {
            "Anyone with the group invite link can join immediately."
        }

    /** Human-readable label for invite sharers. */
    public fun inviteSharersLabel(sharers: String): String =
        if (sharers.equals(GroupSettings.SHARERS_ADMINS, ignoreCase = true)) {
            "Admins only"
        } else {
            "All members"
        }

    /** Detail sentence explaining invite sharers. */
    public fun inviteSharersDescription(sharers: String): String =
        if (sharers.equals(GroupSettings.SHARERS_ADMINS, ignoreCase = true)) {
            "Only group admins can generate and share invite links."
        } else {
            "Any active member can share an invite link."
        }

    /** Detail sentence for membersMayAdd permission. */
    public fun membersMayAddDescription(allowed: Boolean): String =
        if (allowed) {
            "Active members can directly add other people."
        } else {
            "Only admins can add members directly."
        }

    /** Detail sentence for swarmServing flag. */
    public fun swarmServingDescription(enabled: Boolean): String =
        if (enabled) {
            "Group members cooperatively share downloaded file pieces."
        } else {
            "File swarming is disabled for this group."
        }

    /** Detail sentence for local serveToGroup preference. */
    public fun serveToGroupDescription(enabled: Boolean): String =
        if (enabled) {
            "Help share group files with other members from this device."
        } else {
            "This device will not seed or share file pieces with others."
        }

    /** Detail sentence for local serveWifiOnly preference. */
    public fun serveWifiOnlyDescription(enabled: Boolean): String =
        if (enabled) {
            "Only share files when connected to Wi-Fi."
        } else {
            "Share files over Wi-Fi and mobile data."
        }

    /** Formatted label for battery threshold. */
    public fun batteryThresholdLabel(percent: Int): String = "$percent%"

    /** Detail sentence for battery threshold. */
    public fun batteryThresholdDescription(percent: Int): String =
        "Pause file sharing when battery drops below $percent%."

    /** Formatted label for retention duration. */
    public fun keepAvailableDaysLabel(days: Int): String =
        if (days == 1) "1 day" else "$days days"

    /** Detail sentence for retention duration. */
    public fun keepAvailableDaysDescription(days: Int): String =
        if (days == 1) {
            "Keep completed files available for 1 day."
        } else {
            "Keep completed files available for $days days."
        }

    /** Formatted capacity string. */
    public fun maxMembersLabel(max: Int): String = "$max members"
}
