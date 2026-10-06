package com.transfer.flash.core.messaging.group

import kotlinx.coroutines.flow.Flow

/**
 * Traffic kinds gated by [GroupGate] (§5.7, ADR-075).
 */
public enum class GroupTraffic {
    ROSTER,
    CHAT,
    CALL,
    FILE_SEND,
    FILE_RECEIVE,
    FILE_SERVE,
}

/**
 * Unified gate for all group traffic (§5.7, ADR-075).
 * Evaluated per frame / per request (GINV-6).
 */
public interface GroupGate {
    /**
     * May the live session of [peerId] exchange [kind] traffic of [groupId] with this device right now?
     * Checked for every send and inbound frame (INV-3, GINV-6).
     */
    public suspend fun allows(groupId: String, peerId: String, kind: GroupTraffic): Boolean

    /**
     * Is [deviceId] somebody this group's traffic is addressed to?
     * Needs no live session; used to decide who to dial and invite.
     */
    public suspend fun isMember(groupId: String, deviceId: String): Boolean

    /**
     * Emits a group ID whenever its roster, its epoch, or a member's trust changed.
     */
    public val changes: Flow<String>
}
