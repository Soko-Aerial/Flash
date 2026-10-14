package com.transfer.flash.core.hubprotocol.rest

import com.transfer.flash.core.common.annotation.FlashInternalApi
import kotlinx.serialization.Serializable

/**
 * Directory privacy v1 (Plan A2, option C): the org-wide people list and the
 * single lookup by known id. `PersonDto` carries NO keys: it is the listing
 * shape. Keys travel only in `MemberDto` (in-scope callers) and in the Plan B
 * sync statements.
 */
@FlashInternalApi
@Serializable
public data class PersonDto(
    public val deviceId: String,
    public val displayName: String = "",
    public val departmentId: String = "",
    public val departmentName: String = "",
    public val role: String = HubDirectoryValues.ROLE_MEMBER,
    public val online: Boolean? = null,
)

@FlashInternalApi
@Serializable
public data class PeoplePageDto(
    public val people: List<PersonDto> = emptyList(),
    public val nextCursor: String? = null,
)
