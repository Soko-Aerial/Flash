package com.transfer.flash.server.directory

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.GroupRosterStatement
import com.transfer.flash.core.hubprotocol.certs.OrgMemberStatement
import com.transfer.flash.core.hubprotocol.rest.DirectorySyncDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.storage.ServerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SRVHUB-13 / SRVHUB-14, server half: no directory sync page exceeds what the client is defined to accept (DIR-04, DIR-05). */
class DirectorySyncTransportTest {
    private val root = FlashCrypto.generateEcKeyPair()
    private val delegate = FlashCrypto.generateEcKeyPair()
    private val now = System.currentTimeMillis()
    private val delegation = DelegationCert.issue(
        rootPrivateKey = root.private,
        rootPublicKey = root.toBase64Spki(),
        delegatePublicKey = delegate.toBase64Spki(),
        scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
        validFrom = now - 10_000,
        validTo = now + 90L * 86_400_000L,
        maxDevices = 10,
        serial = 1L,
    )
    private val admin = DirectoryActor("dev-admin", role = MemberRole.ORG_ADMIN)

    private fun spki(seed: String): String = Base64.encode(seed.padEnd(32, '0').encodeToByteArray())

    private fun directory(members: Int, sign: Boolean = true): OrganizationDirectory {
        val dir = OrganizationDirectory.open(ServerDatabase.inMemory())
        dir.statementSigner = OrgMemberStatementSigner { deviceId, subjectPublicKey, departmentId, role, nowMs, serial ->
            OrgMemberStatement.issue(
                issuerPrivateKey = delegate.private,
                deviceId = deviceId,
                subjectPublicKey = subjectPublicKey,
                departmentId = departmentId,
                role = role,
                issuedAtMs = nowMs,
                statementSerial = serial,
            ) to delegation.serial
        }
        if (sign) dir.rosterSigner = GroupRosterSigner { GroupRosterStatement.issue(delegate.private, it) }
        dir.createDepartment("dept-eng", "Engineering", listOf("dev-head"), isOrgAdmin = true)
        repeat(members) {
            val id = "dev-" + it.toString().padStart(5, '0')
            dir.addDepartmentMember("dept-eng", id, "Colleague number $it", MemberRole.MEMBER, spki("SPKI-$id"))
        }
        return dir
    }

    private fun bytes(dto: DirectorySyncDto): Int = HubRestCodec.encode(dto).toByteArray(Charsets.UTF_8).size

    private fun allPages(dir: OrganizationDirectory, limit: Int, actor: DirectoryActor): List<DirectorySyncDto> {
        val pages = ArrayList<DirectorySyncDto>()
        var cursor: String? = null
        do {
            val page = dir.getSyncChanges(0, cursor, limit, actor, listOf(delegation))
            pages += page
            cursor = page.nextCursor
        } while (cursor != null)
        return pages
    }

    @Test
    fun everyPageFitsTheClientBoundEvenWhenTheCallerAsksForTheMaximum() { // SRVHUB-13
        val dir = directory(members = 2500)
        val pages = allPages(dir, limit = 1000, actor = DirectoryActor("dev-00000", "dept-eng"))
        assertTrue(pages.size > 1, "2500 signed members cannot fit one page: ${pages.size}")
        for ((i, p) in pages.withIndex()) {
            assertTrue(bytes(p) <= HubContract.MAX_DIRECTORY_SYNC_BODY_BYTES, "page $i is ${bytes(p)} bytes")
            assertTrue(p.members.size <= 1000)
        }
        val ids = pages.flatMap { it.members }.map { it.deviceId }
        assertEquals(2500, ids.size)
        assertEquals(2500, ids.toSet().size, "no member is repeated or skipped across pages")
    }

    @Test
    fun theClientsOwnPageSizeFitsTheOldSixtyFourKibCapOnlyBecauseTheBoundIsRaised() { // SRVHUB-13: document the numbers
        val dir = directory(members = 400)
        val first = dir.getSyncChanges(0, null, HubContract.DIRECTORY_SYNC_PAGE_LIMIT, DirectoryActor("dev-00000", "dept-eng"), listOf(delegation))
        assertEquals(HubContract.DIRECTORY_SYNC_PAGE_LIMIT, first.members.size)
        assertTrue(bytes(first) > HubContract.MAX_REST_BODY_BYTES, "this is the DIR-04 case: ${bytes(first)} bytes")
        assertTrue(bytes(first) <= HubContract.MAX_DIRECTORY_SYNC_BODY_BYTES)
        assertNotNull(first.nextCursor)
    }

    @Test
    fun theByteBudgetEndsAPageBeforeTheRowLimitAndNeverDropsAMember() {
        // Real rows are ~0.6-1 KB, so 1000 of them stay under the 1 MiB budget; a small budget proves the mechanism.
        val dir = directory(members = 300)
        val actor = DirectoryActor("dev-00000", "dept-eng")
        val budget = 50_000
        val ids = ArrayList<String>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = dir.getSyncChanges(0, cursor, 1000, actor, listOf(delegation), memberBudgetBytes = budget)
            val spent = page.members.sumOf { HubRestCodec.encode(it).toByteArray(Charsets.UTF_8).size }
            assertTrue(page.members.isNotEmpty(), "a page always makes progress")
            assertTrue(page.members.size == 1 || spent <= budget, "page $pages spent $spent of $budget")
            ids += page.members.map { it.deviceId }
            cursor = page.nextCursor
            pages++
        } while (cursor != null)
        assertTrue(pages >= 3, "50 KB cannot hold 300 rows: $pages pages")
        assertEquals(300, ids.size)
        assertEquals(300, ids.toSet().size, "no member repeated or skipped across byte-budget pages")
    }

    @Test
    fun aBudgetSmallerThanOneRowStillAdvancesOneRowAtATime() {
        val dir = directory(members = 5)
        val ids = ArrayList<String>()
        var cursor: String? = null
        do {
            val page = dir.getSyncChanges(0, cursor, 1000, DirectoryActor("dev-00000", "dept-eng"), listOf(delegation), memberBudgetBytes = 1)
            assertEquals(1, page.members.size)
            ids += page.members.map { it.deviceId }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(5, ids.toSet().size)
    }

    @Test
    fun aDeviceInMoreGroupsThanTheLimitIsToldTheRosterListIsIncomplete() { // SRVHUB-14
        val dir = directory(members = 2)
        repeat(HubContract.MAX_DIRECTORY_SYNC_ROSTERS + 6) {
            dir.createGroup("grp-" + it.toString().padStart(3, '0'), "G$it", "dept-eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, listOf("dev-head"), admin)
        }
        val last = allPages(dir, 200, DirectoryActor("dev-head", "dept-eng")).last()
        assertEquals(HubContract.MAX_DIRECTORY_SYNC_ROSTERS, last.groupRosters.size)
        assertTrue(last.rostersTruncated)
        assertTrue(bytes(last) <= HubContract.MAX_DIRECTORY_SYNC_BODY_BYTES)
    }

    @Test
    fun aCompleteRosterListIsNotMarkedTruncated() {
        val dir = directory(members = 2)
        repeat(2) { dir.createGroup("grp-$it", "G$it", "dept-eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, listOf("dev-head"), admin) }
        val last = allPages(dir, 200, DirectoryActor("dev-head", "dept-eng")).last()
        assertEquals(2, last.groupRosters.size)
        assertFalse(last.rostersTruncated)
    }

    @Test
    fun withoutASignerAGroupMemberIsToldTheListIsIncomplete() {
        val dir = directory(members = 2, sign = false)
        dir.createGroup("grp-1", "G", "dept-eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, listOf("dev-head"), admin)
        val last = allPages(dir, 200, DirectoryActor("dev-head", "dept-eng")).last()
        assertEquals(0, last.groupRosters.size)
        assertTrue(last.rostersTruncated)
    }

    @Test
    fun aDeviceInNoGroupIsNeverToldTruncated() {
        val dir = directory(members = 2, sign = false)
        val last = allPages(dir, 200, DirectoryActor("dev-00001", "dept-eng")).last()
        assertFalse(last.rostersTruncated)
    }
}
