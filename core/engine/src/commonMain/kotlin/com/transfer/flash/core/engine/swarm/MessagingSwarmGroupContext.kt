package com.transfer.flash.core.engine.swarm

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.messaging.group.GroupGate
import com.transfer.flash.core.messaging.group.GroupTraffic
import com.transfer.flash.core.messaging.protocol.GroupCrypto
import com.transfer.flash.core.persistence.db.dao.GroupMemberDao
import com.transfer.flash.core.swarm.driver.SwarmGroupContext
import kotlinx.coroutines.flow.Flow

/**
 * Concrete [SwarmGroupContext] backed by the messaging module's [GroupGate], [GroupCrypto], and persistence (§5.2, SW-8).
 */
public class MessagingSwarmGroupContext(
    private val localDeviceId: String,
    private val groupGate: GroupGate,
    private val groupMemberDao: suspend () -> GroupMemberDao?,
    private val groupCrypto: GroupCrypto,
) : SwarmGroupContext {

    override suspend fun isPeerAllowed(groupId: String, peerId: String): Boolean {
        // Delegates to GroupGate GM-5 (INV-3 / GINV-6) for inbound/active swarm traffic
        return groupGate.allows(groupId, peerId, GroupTraffic.FILE_RECEIVE)
    }

    override suspend fun isServeAllowed(groupId: String, peerId: String): Boolean {
        // FILE_SERVE adds the signed `swarmServing` setting and this device's `serveToGroup` preference (GM-9).
        return groupGate.allows(groupId, peerId, GroupTraffic.FILE_SERVE)
    }

    override suspend fun isLocalActiveMember(groupId: String): Boolean {
        return groupGate.isMember(groupId, localDeviceId)
    }

    override suspend fun signStatement(groupId: String, statement: ByteArray): ByteArray? {
        return try {
            groupCrypto.sign(statement)
        } catch (_: Throwable) {
            null
        }
    }

    override fun verifyStatement(authorKey: String, statement: ByteArray, signature: ByteArray): Boolean {
        return try {
            val keyBytes = Base64.decode(authorKey) ?: return false
            groupCrypto.verify(signature, statement, keyBytes)
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun authorKey(groupId: String, authorId: String): String? {
        val dao = groupMemberDao() ?: return null
        return dao.member(groupId, authorId)?.subjectKey
    }

    override val membershipChanges: Flow<String> = groupGate.changes
}
