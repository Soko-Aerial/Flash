package com.transfer.flash.core.messaging.group

import com.transfer.flash.core.persistence.db.dao.GroupMemberDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Concrete [GroupGate] backed by the group member DAO and signed roster certificates (§5.7, ADR-075).
 */
public class RosterGroupGate(
    private val localDeviceId: String,
    private val members: suspend () -> GroupMemberDao?,
    private val isTrustedPeer: suspend (String) -> Boolean,
    private val isVouchedMember: suspend (groupId: String, deviceId: String, sessionKey: ByteArray?) -> Boolean,
    private val hasMemberKey: suspend (groupId: String, deviceId: String) -> Boolean,
    private val peerIdentityKey: suspend (String) -> ByteArray?,
    private val swarmServingEnabled: suspend (groupId: String) -> Boolean = { true },
) : GroupGate {

    private val _changes = MutableSharedFlow<String>(extraBufferCapacity = 64)
    override val changes: Flow<String> = _changes.asSharedFlow()

    public fun notifyChanged(groupId: String) {
        _changes.tryEmit(groupId)
    }

    override suspend fun allows(groupId: String, peerId: String, kind: GroupTraffic): Boolean {
        val memberDao = members() ?: return isTrustedPeer(peerId)
        val selfRow = memberDao.member(groupId, localDeviceId)
        if (selfRow?.isActive == false) return false

        val peerRow = memberDao.member(groupId, peerId)
        if (peerRow?.isActive != true) return false

        val isV2 = groupId.startsWith("g2-")
        val isTrusted = if (isV2) {
            isTrustedPeer(peerId) || isVouchedMember(groupId, peerId, peerIdentityKey(peerId))
        } else {
            isTrustedPeer(peerId)
        }

        if (!isTrusted) return false

        if (kind == GroupTraffic.FILE_SERVE) {
            return swarmServingEnabled(groupId)
        }

        return true
    }

    override suspend fun isMember(groupId: String, deviceId: String): Boolean {
        val memberDao = members() ?: return isTrustedPeer(deviceId)
        val selfRow = memberDao.member(groupId, localDeviceId)
        if (selfRow?.isActive == false) return false

        val peerRow = memberDao.member(groupId, deviceId)
        if (peerRow?.isActive != true) return false

        val isV2 = groupId.startsWith("g2-")
        return if (isV2) {
            isTrustedPeer(deviceId) || hasMemberKey(groupId, deviceId)
        } else {
            isTrustedPeer(deviceId)
        }
    }
}
