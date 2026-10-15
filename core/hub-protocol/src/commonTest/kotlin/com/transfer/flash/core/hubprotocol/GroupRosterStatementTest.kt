package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.GroupRosterStatement
import com.transfer.flash.core.hubprotocol.certs.RosterStatus
import com.transfer.flash.core.hubprotocol.certs.toHubHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan B Phase B4: ORGT-U40 (golden vectors), U41 (verify rejects), U49 (size limit). */
@OptIn(FlashInternalApi::class)
class GroupRosterStatementTest {
    private val root = FakeSigs.keyOf("root")
    private val hub = FakeSigs.keyOf("hub")
    private val chain = listOf(FakeSigs.delegation())
    private val now = FakeSigs.T0 + FakeSigs.DAY

    private fun signed(
        members: List<String> = listOf("dev-a", "dev-b"),
        admins: List<String> = listOf("dev-a"),
        issuedAt: Long = FakeSigs.T0 + 1000,
        validUntil: Long = FakeSigs.T0 + 20 * FakeSigs.DAY,
        version: Long = 3,
        signer: String = hub,
    ): GroupRosterStatement {
        val unsigned = GroupRosterStatement("grp-1", "Engineering", "dept-eng", version, members, admins, issuedAt, validUntil)
        return unsigned.copy(issuerSignature = FakeSigs.sign(signer, unsigned.canonicalBytes()))
    }

    private fun status(s: GroupRosterStatement, at: Long = now, delegations: List<com.transfer.flash.core.hubprotocol.certs.DelegationCert> = chain) =
        s.verify(delegations, root, FakeSigs, at)

    @Test
    fun canonicalBytesGoldenVectors() { // ORGT-U40; the hex was computed independently (python struct, not this code)
        val v1 = GroupRosterStatement("grp-1", "Engineering", "dept-eng", 3, listOf("dev-a", "dev-b", "dev-c"), listOf("dev-a"), 1_700_000_000_000L, 1_702_592_000_000L)
        assertEquals(
            "00000013666c6173682d6875622d726f737465722d7631000000056772702d310000000b456e67696e656572696e6700000008646570742d656e670000000800000000000000030000000400000003000000056465762d61000000056465762d62000000056465762d630000000400000001000000056465762d61000000080000018bcfe56800000000080000018c6a643000",
            v1.canonicalBytes().toHubHex(),
        )
        val v2 = GroupRosterStatement("grp-ünï", "Équipe 🚀", "", 0, emptyList(), emptyList(), 1_700_000_000_000L, 1_700_000_001_000L)
        assertEquals(
            "00000013666c6173682d6875622d726f737465722d7631000000096772702dc3bc6ec3af0000000cc389717569706520f09f9a800000000000000008000000000000000000000004000000000000000400000000000000080000018bcfe56800000000080000018bcfe56be8",
            v2.canonicalBytes().toHubHex(),
        )
    }

    @Test
    fun theSignatureIsNotPartOfTheSignedBytes() {
        val s = signed()
        assertEquals(s.canonicalBytes().toHubHex(), s.copy(issuerSignature = "other").canonicalBytes().toHubHex())
    }

    @Test
    fun aValidStatementVerifiesAndAnswersMembership() { // ORGT-U41 positive
        val s = signed()
        assertEquals(RosterStatus.OK, status(s))
        assertTrue(s.isMember("dev-b"))
        assertFalse(s.isMember("dev-x"))
    }

    @Test
    fun everyTamperedFieldIsRejected() { // ORGT-U41
        val s = signed()
        for (t in listOf(
            s.copy(memberDeviceIds = listOf("dev-a", "dev-b", "dev-z")),
            s.copy(adminDeviceIds = listOf("dev-b")),
            s.copy(version = 4),
            s.copy(groupId = "grp-2"),
            s.copy(groupName = "x"),
            s.copy(departmentId = "other"),
            s.copy(validUntilMs = s.validUntilMs + 1),
            s.copy(issuedAtMs = s.issuedAtMs + 1),
        )) {
            assertEquals(RosterStatus.DELEGATION_INVALID, status(t), "tampered: $t")
        }
    }

    @Test
    fun aWrongSignerAnExpiredAndAFutureStatementAreRejected() { // ORGT-U41
        assertEquals(RosterStatus.DELEGATION_INVALID, status(signed(signer = FakeSigs.keyOf("attacker"))))
        assertEquals(RosterStatus.EXPIRED, status(signed(), at = FakeSigs.T0 + 21 * FakeSigs.DAY))
        assertEquals(RosterStatus.NOT_YET_VALID, status(signed(issuedAt = now + 10 * 60_000, validUntil = now + FakeSigs.DAY)))
        assertEquals(RosterStatus.DELEGATION_INVALID, status(signed(), delegations = emptyList()))
    }

    @Test
    fun aDelegationThatCouldNotYetOrNoLongerSignIsRejected() { // ORGT-U41
        val expired = FakeSigs.delegation(validFrom = FakeSigs.T0 - 40 * FakeSigs.DAY, validTo = FakeSigs.T0 - 10 * FakeSigs.DAY)
        assertEquals(RosterStatus.DELEGATION_EXPIRED, status(signed(), delegations = listOf(expired)))
        val noScope = FakeSigs.delegation(scopes = setOf("revoke:member"))
        assertEquals(RosterStatus.SCOPE_DENIED, status(signed(), delegations = listOf(noScope)))
        val otherRoot = FakeSigs.delegation(root = FakeSigs.keyOf("other-root"), signer = FakeSigs.keyOf("other-root"))
        assertEquals(RosterStatus.DELEGATION_INVALID, status(signed(), delegations = listOf(otherRoot)))
    }

    @Test
    fun aRevokedDelegationIsRejected() { // ORGT-U41
        val list = FakeSigs.revocations(delegationSerials = setOf(1L))
        assertEquals(RosterStatus.REVOKED, signed().verify(chain, root, FakeSigs, now, revocations = list))
    }

    @Test
    fun unsortedDuplicateOrBlankIdsAreMalformed() { // canonical form only
        assertEquals(RosterStatus.MALFORMED, status(signed(members = listOf("dev-b", "dev-a"))))
        assertEquals(RosterStatus.MALFORMED, status(signed(members = listOf("dev-a", "dev-a"))))
        assertEquals(RosterStatus.MALFORMED, status(signed(members = listOf("dev-a", ""))))
        assertEquals(RosterStatus.MALFORMED, status(signed().copy(issuerSignature = "")))
        assertEquals(RosterStatus.MALFORMED, status(signed(validUntil = FakeSigs.T0 + 1000)))
    }

    @Test
    fun theSizeLimitIsTwoHundredMembers() { // ORGT-U49
        fun ids(n: Int) = (0 until n).map { "dev-" + it.toString().padStart(4, '0') }
        assertEquals(RosterStatus.OK, status(signed(members = ids(GroupRosterStatement.MAX_MEMBERS), admins = emptyList())))
        assertEquals(RosterStatus.TOO_LARGE, status(signed(members = ids(GroupRosterStatement.MAX_MEMBERS + 1), admins = emptyList())))
        assertTrue(signed(members = ids(GroupRosterStatement.MAX_MEMBERS)).canonicalBytes().size <= GroupRosterStatement.MAX_CANONICAL_BYTES)
    }
}
