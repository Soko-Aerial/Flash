@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.enterprise.EnterpriseDepartment
import com.transfer.flash.core.enterprise.EnterpriseDirectoryGroup
import com.transfer.flash.core.enterprise.EnterpriseDirectoryMember
import com.transfer.flash.core.enterprise.EnterpriseJoinRequest
import com.transfer.flash.core.enterprise.EnterpriseJoinResult
import com.transfer.flash.core.enterprise.EnterpriseJoinStatus
import com.transfer.flash.core.enterprise.HubDirectoryApi
import com.transfer.flash.core.security.enterprise.UnsealedPayload

/**
 * A stand-in for [DirectoryHubSealing] in hub file tests (ADR-216): the envelope is the inner type and the body on two
 * lines, readable to the test, and the status is scripted. The real sealing is proven by `HubSealingBridgeTest`.
 */
class ScriptedHubSealing(var status: HubSealingStatus = HubSealingStatus.AVAILABLE) : HubSealingPort {
    override suspend fun canSealFor(recipientDeviceId: String): Boolean = status == HubSealingStatus.AVAILABLE
    override suspend fun statusFor(recipientDeviceId: String): HubSealingStatus = status
    override suspend fun seal(recipientDeviceId: String, msgId: String, sentAt: Long, innerType: String, body: String): String? =
        if (status == HubSealingStatus.AVAILABLE) "$innerType\n$body" else null

    override suspend fun open(senderDeviceId: String, msgId: String, sentAt: Long, payload: String): UnsealedPayload? {
        val cut = payload.indexOf('\n').takeIf { it > 0 } ?: return null
        return UnsealedPayload(payload.substring(0, cut), payload.substring(cut + 1))
    }
}

/** A directory that lists [memberIds] (department "Ops") and nothing else. */
class ListedHubDirectory(private val memberIds: Set<String>) : HubDirectoryApi {
    override suspend fun fetchVisibleGroups(hubUrl: String, deviceId: String, department: String?) =
        FlashResult.Success(emptyList<EnterpriseDirectoryGroup>())

    override suspend fun fetchDepartmentMembers(hubUrl: String, departmentId: String) =
        FlashResult.Success(memberIds.map { EnterpriseDirectoryMember(deviceId = it, friendlyName = it, department = "Ops") })

    override suspend fun fetchDepartments(hubUrl: String) = FlashResult.Success(emptyList<EnterpriseDepartment>())

    override suspend fun requestJoin(hubUrl: String, groupId: String, message: String?) =
        FlashResult.Success(EnterpriseJoinResult(EnterpriseJoinStatus.PENDING, groupId))

    override suspend fun fetchJoinRequests(hubUrl: String, groupId: String?) = FlashResult.Success(emptyList<EnterpriseJoinRequest>())

    override suspend fun decideJoinRequest(hubUrl: String, requestId: String, approve: Boolean, reason: String?) =
        FlashResult.Success(EnterpriseJoinResult(EnterpriseJoinStatus.JOINED, requestId))
}
