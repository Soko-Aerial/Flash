@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.PeoplePageDto
import com.transfer.flash.core.hubprotocol.rest.PersonDto

/**
 * Plan A2 client: the org-wide people list and the single lookup by known id,
 * with the old-route fallback for hubs without the new routes (404 on
 * `/directory/people` → capability detection, Hub tab sentence:
 * "Your company server is out of date. Some people may not be listed.").
 */
@FlashInternalApi
public interface DirectoryPrivacyApi {
    public suspend fun fetchPeople(
        hubUrl: String,
        cursor: String? = null,
        limit: Int = 100,
        q: String? = null,
        departmentId: String? = null,
    ): FlashResult<PeoplePage>

    public suspend fun lookupPerson(hubUrl: String, deviceId: String): FlashResult<EnterpriseDirectoryMember?>
}

@FlashInternalApi
public data class PeoplePage(
    public val people: List<EnterpriseDirectoryMember>,
    public val nextCursor: String? = null,
    /** True when the hub answered 404: it predates Plan A2. */
    public val hubTooOld: Boolean = false,
)

@FlashInternalApi
public fun PersonDto.toDirectoryMember(): EnterpriseDirectoryMember = EnterpriseDirectoryMember(
    deviceId = deviceId,
    friendlyName = displayName.ifBlank { deviceId },
    department = departmentId,
    role = role,
    online = online ?: false,
    departmentName = departmentName,
)

/**
 * Standing rules (A2 §3.6): MEMBER when the id is in the Plan B snapshot or the
 * single lookup finds it; UNKNOWN only when the lookup returns 404 (null);
 * UNAVAILABLE when the hub cannot be reached and the snapshot has no row.
 */
@FlashInternalApi
public fun resolvePrivacyStanding(
    snapshotHit: Boolean,
    lookup: FlashResult<EnterpriseDirectoryMember?>,
    hubReachable: Boolean,
): HubPeerStanding = when {
    snapshotHit -> HubPeerStanding.MEMBER
    lookup is FlashResult.Success && lookup.value != null -> HubPeerStanding.MEMBER
    lookup is FlashResult.Success && lookup.value == null -> HubPeerStanding.UNKNOWN
    !hubReachable -> HubPeerStanding.UNAVAILABLE
    else -> HubPeerStanding.UNKNOWN
}

/** Sentence for the Hub tab when the hub predates the people routes. */
@FlashInternalApi
public fun hubTooOldSentence(): String = "Your company server is out of date. Some people may not be listed."

/** Decodes a people page body; null when the body is not a page. */
@FlashInternalApi
public fun decodePeoplePage(body: String): PeoplePage? {
    val dto = HubRestCodec.decodeOrNull<PeoplePageDto>(body) ?: return null
    return PeoplePage(dto.people.map { it.toDirectoryMember() }, dto.nextCursor)
}

/** True when an HTTP status means "hub predates Plan A2". */
@FlashInternalApi
public fun isHubTooOldStatus(statusCode: Int): Boolean = statusCode == 404 || statusCode == 405

/** Builds the not-supported sentence result for callers that need a typed fallback. */
@FlashInternalApi
public fun <T> hubTooOldFailure(): FlashResult<T> =
    FlashResult.Failure(FlashError.Unknown("Your company server is out of date. Some people may not be listed."))

internal fun directoryPeoplePath(): String = HubContract.PATH_DIRECTORY_PEOPLE
