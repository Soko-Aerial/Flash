package com.transfer.flash.core.hubprotocol.rest

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlinx.serialization.Serializable

/**
 * Directory administration DTOs (hub phase 2, SEC-03 / REL-10). The caller is ALWAYS the authenticated bearer
 * principal: none of these bodies carries a caller or department field that the hub would believe.
 */

/** `GET /api/v1/directory/me`: the caller's identity as the hub derives it from the certificate and the directory. */
@FlashInternalApi
@Serializable
public data class MeDto(
    public val deviceId: String,
    /** Directory role string, one of [HubDirectoryValues] `ROLE_*`. */
    public val role: String = HubDirectoryValues.ROLE_MEMBER,
    public val departmentId: String = "",
    public val displayName: String = "",
    /** Ids of the groups the caller is a member or admin of. */
    public val groupIds: List<String> = emptyList(),
    /** Ids of the departments the caller administers. */
    public val adminOfDepartments: List<String> = emptyList(),
    public val certSerial: Long = 0L,
    /** Epoch ms the caller's certificate stops being honoured (its `validTo` clamped to the delegation). */
    public val certExpiresAt: Long = 0L,
)

/** Body of `POST /api/v1/departments`. */
@FlashInternalApi
@Serializable
public data class CreateDepartmentBody(
    public val id: String,
    public val name: String,
    public val adminDeviceIds: List<String> = emptyList(),
)

/** Body of `POST /api/v1/groups` (create). [departmentId] blank = an organisation-wide group (organisation admins only). */
@FlashInternalApi
@Serializable
public data class CreateGroupBody(
    public val id: String,
    public val name: String,
    public val departmentId: String = "",
    public val visibility: String = HubDirectoryValues.VISIBILITY_LISTED,
    public val joinMode: String = HubDirectoryValues.JOIN_REQUEST_APPROVAL,
    public val adminDeviceIds: List<String> = emptyList(),
)

/** Body of `PUT /api/v1/groups/{id}`: a null field is left unchanged. */
@FlashInternalApi
@Serializable
public data class UpdateGroupBody(
    public val name: String? = null,
    public val visibility: String? = null,
    public val joinMode: String? = null,
)

/** Body of `POST /api/v1/groups/members`. */
@FlashInternalApi
@Serializable
public data class GroupMemberBody(
    public val groupId: String,
    public val deviceId: String,
    /** True adds the device, false removes it. */
    public val add: Boolean = true,
)
