@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.enterprise.DirectorySyncPageResult
import com.transfer.flash.core.enterprise.EnrollmentOutcome
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import com.transfer.flash.server.directory.MemberRole
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * SRVHUB-13 (DIR-04) through the real route and the real client: a page of the client's own size is larger than the 64 KiB
 * default body bound, and the unit tests with a fake API never encode a body, which is how the defect survived. This test
 * fails if either side is put back to the old bounds.
 */
class E2eDirectorySyncSizeTest {

    private val hubs = ArrayList<E2eHub>()
    private val devices = ArrayList<E2eDevice>()

    @AfterTest
    fun cleanUp() {
        devices.forEach { it.shutdown() }
        hubs.forEach { it.close() }
    }

    private fun spki(seed: String): String = Base64.encode(seed.padEnd(32, '0').encodeToByteArray())

    @Test
    fun aFullPageOfMembersReachesTheRealClientAndEveryMemberArrivesOnce() = runBlocking<Unit> {
        val hub = E2eHub().provision().start().also { hubs += it }
        val dir = hub.services.directory
        dir.createDepartment("eng", "Engineering", emptyList(), DirectoryActor.CONSOLE)
        repeat(450) {
            val id = "m-" + it.toString().padStart(4, '0')
            dir.addDepartmentMember("eng", id, "Colleague number $it", MemberRole.MEMBER, spki("SPKI-$id"))
        }
        val alice = E2eDevice("alice", hub).also { devices += it }
        assertIs<EnrollmentOutcome.Enrolled>(alice.client.enroll(hub.invite(department = "eng")))

        val ids = ArrayList<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val result = alice.client.directory.fetchDirectorySync(hub.url, 0L, null, cursor, HubContract.DIRECTORY_SYNC_PAGE_LIMIT)
            val page = assertIs<DirectorySyncPageResult.Success>(result, "page $pages: $result").dto
            ids += page.members.map { it.deviceId }
            cursor = page.nextCursor
            pages++
        } while (cursor != null)

        assertTrue(pages >= 3, "451 members need at least three pages of ${HubContract.DIRECTORY_SYNC_PAGE_LIMIT}: $pages")
        assertEquals(ids.size, ids.toSet().size, "no member twice")
        assertTrue(ids.containsAll(List(450) { "m-" + it.toString().padStart(4, '0') }), "every colleague arrived")
    }

    @Test
    fun aDeviceInMoreGroupsThanTheHubCarriesIsToldSoOnTheLastPage() = runBlocking<Unit> {
        val hub = E2eHub().provision().start().also { hubs += it }
        val dir = hub.services.directory
        dir.createDepartment("eng", "Engineering", emptyList(), DirectoryActor.CONSOLE)
        val alice = E2eDevice("alice", hub).also { devices += it }
        assertIs<EnrollmentOutcome.Enrolled>(alice.client.enroll(hub.invite(department = "eng")))
        repeat(HubContract.MAX_DIRECTORY_SYNC_ROSTERS + 5) {
            dir.createGroup("g-" + it.toString().padStart(3, '0'), "G$it", "eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, listOf("alice"), DirectoryActor.CONSOLE)
        }
        var cursor: String? = null
        var last: DirectorySyncPageResult.Success
        do {
            last = assertIs(alice.client.directory.fetchDirectorySync(hub.url, 0L, null, cursor, HubContract.DIRECTORY_SYNC_PAGE_LIMIT))
            cursor = last.dto.nextCursor
        } while (cursor != null)
        assertEquals(HubContract.MAX_DIRECTORY_SYNC_ROSTERS, last.dto.groupRosters.size)
        assertTrue(last.dto.rostersTruncated, "the client is told the list is incomplete")
    }
}
