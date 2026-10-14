@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.PeoplePageDto
import com.transfer.flash.core.hubprotocol.rest.PersonDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan A2 client: PRIV-U09/U10 (standing rules, fallback, forward compat). */
class DirectoryPrivacyClientTest {

    @Test
    fun standingIsMemberViaSnapshotOrLookupUnknownOn404UnavailableOffline() {
        val member = EnterpriseDirectoryMember("bob-01", "Bob", "dept-b")
        assertEquals(HubPeerStanding.MEMBER, resolvePrivacyStanding(true, FlashResult.Success(null), hubReachable = false))
        assertEquals(HubPeerStanding.MEMBER, resolvePrivacyStanding(false, FlashResult.Success(member), hubReachable = true))
        assertEquals(HubPeerStanding.UNKNOWN, resolvePrivacyStanding(false, FlashResult.Success(null), hubReachable = true))
        assertEquals(HubPeerStanding.UNAVAILABLE, resolvePrivacyStanding(false, hubTooOldFailure(), hubReachable = false))
    }

    @Test
    fun hubTooOldSentenceIsExact() {
        assertEquals("Your company server is out of date. Some people may not be listed.", hubTooOldSentence())
        assertTrue(isHubTooOldStatus(404))
        assertTrue(isHubTooOldStatus(405))
        assertFalse(isHubTooOldStatus(200))
    }

    @Test
    fun personDtoMapsWithDepartmentNameAndOldBodiesStillDecode() {
        val page = decodePeoplePage(HubRestCodec.encode(PeoplePageDto(listOf(PersonDto("a-01", "Alice", "dept-a", "Alpha")))))
        assertEquals("Alpha", page?.people?.first()?.departmentName)
        // Forward compat: a body with an unknown field decodes (ignoreUnknownKeys).
        val withExtra = """{"people":[{"deviceId":"a-01","displayName":"Alice","departmentId":"dept-a","departmentName":"Alpha","role":"MEMBER","futureField":1}],"nextCursor":null}"""
        assertEquals(1, decodePeoplePage(withExtra)?.people?.size)
    }

    // PRIV-U09: cross-department sender is MEMBER via lookup, UNKNOWN on 404, UNAVAILABLE offline.
    @Test
    fun hubPeerDirectoryResolvesCrossDepartmentSendersByKnownId() = kotlinx.coroutines.test.runTest {
        val ownDept = listOf(EnterpriseDirectoryMember("alice-01", "Alice", "dept-a"))
        val hiddenBob = EnterpriseDirectoryMember("bob-01", "Bob", "dept-b", departmentName = "")
        var lookups = 0
        val fake = object : HubDirectoryApi, DirectoryPrivacyApi {
            override suspend fun fetchVisibleGroups(hubUrl: String, deviceId: String, department: String?): FlashResult<List<EnterpriseDirectoryGroup>> =
                FlashResult.Success(emptyList())
            override suspend fun fetchDepartmentMembers(hubUrl: String, departmentId: String) = FlashResult.Success(ownDept)
            override suspend fun fetchDepartments(hubUrl: String): FlashResult<List<EnterpriseDepartment>> = FlashResult.Success(emptyList())
            override suspend fun requestJoin(hubUrl: String, groupId: String, message: String?) = throw UnsupportedOperationException()
            override suspend fun fetchJoinRequests(hubUrl: String, groupId: String?): FlashResult<List<EnterpriseJoinRequest>> =
                FlashResult.Success(emptyList())
            override suspend fun decideJoinRequest(hubUrl: String, requestId: String, approve: Boolean, reason: String?) = throw UnsupportedOperationException()
            override suspend fun fetchPeople(hubUrl: String, cursor: String?, limit: Int, q: String?, departmentId: String?) =
                FlashResult.Success(PeoplePage(emptyList(), null))
            override suspend fun lookupPerson(hubUrl: String, deviceId: String): FlashResult<EnterpriseDirectoryMember?> {
                lookups++
                return FlashResult.Success(if (deviceId == "bob-01") hiddenBob else null)
            }
        }
        val dir = HubPeerDirectory(fake, { "https://hub.example" }, clock = { 0L })
        assertEquals(HubPeerStanding.MEMBER, dir.standing("bob-01"))
        assertEquals(1, lookups)
        assertEquals(HubPeerStanding.MEMBER, dir.standing("bob-01"), "cached hit needs no second lookup")
        assertEquals(1, lookups)
        assertEquals(HubPeerStanding.UNKNOWN, dir.standing("ghost-01"))
        val offline = HubPeerDirectory(fake, { null }, clock = { 0L })
        assertEquals(HubPeerStanding.UNAVAILABLE, offline.standing("nobody-01"))
    }
}
