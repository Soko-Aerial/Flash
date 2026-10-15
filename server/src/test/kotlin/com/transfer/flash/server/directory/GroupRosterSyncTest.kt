package com.transfer.flash.server.directory

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.GroupRosterStatement
import com.transfer.flash.core.hubprotocol.certs.RosterStatus
import com.transfer.flash.core.hubprotocol.rest.GroupRosterDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.auth.JvmEcdsaVerifier
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.storage.ServerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Plan B Phase B4, server side: the hub signs one roster statement per group the caller belongs to (ORGT-U51B..). */
class GroupRosterSyncTest {
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

    private fun directory(sign: Boolean = true): OrganizationDirectory {
        val dir = OrganizationDirectory.open(ServerDatabase.inMemory())
        if (sign) dir.rosterSigner = GroupRosterSigner { GroupRosterStatement.issue(delegate.private, it) }
        dir.createDepartment("dept-eng", "Engineering", listOf("dev-head"), isOrgAdmin = true)
        return dir
    }

    private fun statements(dir: OrganizationDirectory, who: String, graceDays: Int = 30): List<GroupRosterStatement> =
        dir.getSyncChanges(0, null, 500, DirectoryActor(who), listOf(delegation), offlineGraceDays = graceDays)
            .groupRosters.map { HubRestCodec.decode<GroupRosterStatement>(it.statement) }

    @Test
    fun aMemberGetsAVerifiableStatementOfEachOfItsGroupsAndNobodyElseDoes() { // member, outsider
        val dir = directory()
        dir.createGroup("grp-1", "Platform", "dept-eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, listOf("dev-head"), admin)
        dir.addMemberToGroup("grp-1", "dev-b", actor = admin)
        val s = statements(dir, "dev-b").single()
        assertEquals("grp-1", s.groupId)
        assertEquals(listOf("dev-admin", "dev-b", "dev-head"), s.memberDeviceIds) // the creator is a member too
        assertEquals(listOf("dev-admin", "dev-head"), s.adminDeviceIds)
        assertEquals(RosterStatus.OK, s.verify(listOf(delegation), root.toBase64Spki(), JvmEcdsaVerifier, now))
        assertEquals(emptyList(), statements(dir, "dev-outsider"), "a device outside the group learns nothing about it")
    }

    @Test
    fun theVersionOnlyGrowsAndTheRemovedMemberIsOutOfTheNextStatement() { // ORGT-U43 server half
        val dir = directory()
        dir.createGroup("grp-1", "Platform", "dept-eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, listOf("dev-head"), admin)
        dir.addMemberToGroup("grp-1", "dev-b", actor = admin)
        val before = statements(dir, "dev-head").single()
        dir.removeMemberFromGroup("grp-1", "dev-b", actor = admin)
        val after = statements(dir, "dev-head").single()
        assertTrue(after.version > before.version, "version ${before.version} -> ${after.version}")
        assertTrue("dev-b" in before.memberDeviceIds)
        assertTrue("dev-b" !in after.memberDeviceIds)
        assertEquals(emptyList(), statements(dir, "dev-b"), "the removed device gets no roster for that group")
    }

    @Test
    fun theLeaseFollowsTheOfflineGracePeriod() {
        val dir = directory()
        dir.createGroup("grp-1", "Platform", "dept-eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, listOf("dev-head"), admin)
        val s = statements(dir, "dev-head", graceDays = 7).single()
        assertEquals(7L * 86_400_000L, s.validUntilMs - s.issuedAtMs)
    }

    @Test
    fun aGroupAboveTheOfflineLimitHasNoStatementAndIsCounted() { // ORGT-U49 server half
        val dir = directory()
        dir.createGroup("grp-big", "Everyone", "dept-eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, listOf("dev-head"), admin)
        repeat(GroupRosterStatement.MAX_MEMBERS) { dir.addMemberToGroup("grp-big", "dev-m" + it.toString().padStart(4, '0'), actor = admin) }
        assertEquals(emptyList(), statements(dir, "dev-head"))
        assertEquals(1, dir.rostersSkippedTooLarge)
    }

    @Test
    fun withoutASigningRightNoStatementIsSentAndNothingBreaks() {
        val dir = directory(sign = false)
        dir.createGroup("grp-1", "Platform", "dept-eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, listOf("dev-head"), admin)
        assertEquals(emptyList(), statements(dir, "dev-head"))
    }

    @Test
    fun onlyTheLastPageCarriesTheRosters() {
        val dir = directory()
        dir.createGroup("grp-1", "Platform", "dept-eng", GroupVisibility.LISTED, JoinMode.ADMIN_ONLY, listOf("dev-head"), admin)
        repeat(3) { dir.addDepartmentMember("dept-eng", "dev-p$it", "P$it", MemberRole.MEMBER, "SPKI-P$it".padEnd(32, '0')) }
        val first = dir.getSyncChanges(0, null, 1, DirectoryActor("dev-head", "dept-eng"), listOf(delegation))
        assertTrue(first.nextCursor != null)
        assertEquals(emptyList<GroupRosterDto>(), first.groupRosters, "a page with a next page carries no rosters")
        var cursor = first.nextCursor
        var last = first
        while (cursor != null) {
            last = dir.getSyncChanges(0, cursor, 1, DirectoryActor("dev-head", "dept-eng"), listOf(delegation))
            cursor = last.nextCursor
        }
        assertEquals(1, last.groupRosters.size)
    }
}
