@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.rest.DepartmentDto
import com.transfer.flash.core.hubprotocol.rest.GroupDto
import com.transfer.flash.core.hubprotocol.rest.HubDirectoryValues
import com.transfer.flash.core.hubprotocol.rest.MemberDto

// The enrolment invite moved to `:core:hub-protocol` (`com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite`)
// so the clients and the server parse it with one implementation (ADR-107, CLI-17).

/**
 * Organization directory group entry returned by the hub: the client's view of a `GroupDto` (CLI-03: the old parser
 * looked for `groupId`/`department`/`requiresApproval`, none of which the hub ever sent, so every group arrived with
 * an empty department and `requiresApproval = false`).
 */
@FlashInternalApi
public data class EnterpriseDirectoryGroup(
    public val groupId: String,
    public val name: String,
    public val department: String,
    public val visibility: String,
    public val description: String = "",
    public val requiresApproval: Boolean = false,
    public val memberCount: Int = 0,
    /** One of `HubDirectoryValues.JOIN_*`; empty when the hub did not say. */
    public val joinMode: String = "",
    public val onlineCount: Int? = null,
    /** Pending join requests; only present for the group's admins. */
    public val pendingCount: Int? = null,
)

/**
 * Organization member entry returned by the hub.
 */
@FlashInternalApi
public data class EnterpriseDirectoryMember(
    public val deviceId: String,
    public val friendlyName: String,
    public val department: String,
    public val role: String = "member",
    public val online: Boolean = false,
    /** Base64 SPKI of the member's certified device key (for authenticated callers in scope); null when the hub did not send it. */
    public val subjectPublicKey: String? = null,
    /** The member's end-to-end sealing key and the identity-key signature binding it (ADR-121; served by the hub since ADR-124, null until the member published one). Verify the binding before use. */
    public val sealingPublicKey: String? = null,
    public val sealingSignature: String? = null,
)

/**
 * Department entry returned by the hub.
 */
@FlashInternalApi
public data class EnterpriseDepartment(
    public val departmentId: String,
    public val name: String,
    public val description: String = "",
    public val memberCount: Int = 0,
    public val groupCount: Int = 0,
)

internal fun GroupDto.toDirectoryGroup(): EnterpriseDirectoryGroup = EnterpriseDirectoryGroup(
    groupId = id,
    name = name,
    department = departmentId,
    visibility = visibility,
    requiresApproval = joinMode == HubDirectoryValues.JOIN_REQUEST_APPROVAL,
    memberCount = if (memberCount > 0) memberCount else memberDeviceIds.size,
    joinMode = joinMode,
    onlineCount = onlineCount,
    pendingCount = pendingCount,
)

internal fun MemberDto.toDirectoryMember(): EnterpriseDirectoryMember = EnterpriseDirectoryMember(
    deviceId = deviceId,
    friendlyName = displayName.ifBlank { deviceId },
    department = departmentId,
    role = role,
    online = online,
    subjectPublicKey = subjectPublicKey,
    sealingPublicKey = sealingPublicKey,
    sealingSignature = sealingSignature,
)

internal fun DepartmentDto.toDepartment(): EnterpriseDepartment = EnterpriseDepartment(
    departmentId = id,
    name = name.ifBlank { id },
    memberCount = memberCount,
    groupCount = groupCount,
)
