package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.certs.ChainStatus
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.HubRoles
import com.transfer.flash.core.hubprotocol.certs.OrgMemberStatement
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.certs.toHubHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(FlashInternalApi::class)
class OrgMemberStatementTest {

    // Golden Vector 1: normal member with department
    private val statementNormal = OrgMemberStatement(
        deviceId = "dev-alpha",
        subjectPublicKey = Base64.encode("subject-key".encodeToByteArray()),
        departmentId = "dept-eng",
        role = HubRoles.MEMBER,
        issuedAtMs = 1_700_000_000_000L,
        statementSerial = 42L,
        issuerSignature = "",
    )

    // Golden Vector 2: admin with empty department
    private val statementNoDept = OrgMemberStatement(
        deviceId = "dev-beta",
        subjectPublicKey = Base64.encode("subject-key".encodeToByteArray()),
        departmentId = "",
        role = HubRoles.ADMIN,
        issuedAtMs = 1_700_000_000_000L,
        statementSerial = 100L,
        issuerSignature = "",
    )

    // Golden Vector 3: non-ASCII unicode deviceId and department
    private val statementUnicode = OrgMemberStatement(
        deviceId = "dev-üñîçødë-🚀",
        subjectPublicKey = Base64.encode("subject-key".encodeToByteArray()),
        departmentId = "dept-ünit",
        role = HubRoles.DEPT_ADMIN,
        issuedAtMs = 1_700_000_000_000L,
        statementSerial = 7L,
        issuerSignature = "",
    )

    @Test
    fun orgMemberStatementCanonicalBytesGoldenVectors() {
        val hexNormal = statementNormal.canonicalBytes().toHubHex()
        val hexNoDept = statementNoDept.canonicalBytes().toHubHex()
        val hexUnicode = statementUnicode.canonicalBytes().toHubHex()

        // Pinned wire constants (ORGT-U01)
        assertEquals(
            "00000013666c6173682d6f72672d6d656d6265722d7631000000096465762d616c7068610000000b7375626a6563742d6b657900000008646570742d656e67000000066d656d626572000000080000018bcfe5680000000008000000000000002a",
            hexNormal,
        )
        assertEquals(
            "00000013666c6173682d6f72672d6d656d6265722d7631000000086465762d626574610000000b7375626a6563742d6b6579000000000000000561646d696e000000080000018bcfe56800000000080000000000000064",
            hexNoDept,
        )
        assertEquals(
            "00000013666c6173682d6f72672d6d656d6265722d7631000000166465762dc3bcc3b1c3aec3a7c3b864c3ab2df09f9a800000000b7375626a6563742d6b65790000000a646570742dc3bc6e69740000000a646570745f61646d696e000000080000018bcfe56800000000080000000000000007",
            hexUnicode,
        )
    }

    @Test
    fun signatureIsNotPartOfCanonicalBytes() {
        val s1 = statementNormal.copy(issuerSignature = "")
        val s2 = statementNormal.copy(issuerSignature = "c2lnbmF0dXJl")
        assertEquals(s1.canonicalBytes().toHubHex(), s2.canonicalBytes().toHubHex())
    }

    @Test
    fun validStatementVerifiesSuccessfully() {
        val root = FakeSigs.keyOf("root")
        val delegation = FakeSigs.delegation(root = root, scopes = setOf("enrol:member"))
        val statement = FakeSigs.statement(deviceId = "dev-1", role = HubRoles.MEMBER, issuedAt = FakeSigs.T0)

        val status = statement.verify(
            delegationChain = listOf(delegation),
            rootPublicKey = root,
            verifier = FakeSigs,
            nowMs = FakeSigs.T0 + 10_000L,
        )
        assertEquals(ChainStatus.OK, status)
        assertTrue(status.isOk)
    }

    @Test
    fun futureIssuedAtIsRejected() {
        val root = FakeSigs.keyOf("root")
        val delegation = FakeSigs.delegation(root = root)
        val now = FakeSigs.T0
        // Statement issued 6 minutes in the future (> 5 min tolerance)
        val statement = FakeSigs.statement(issuedAt = now + 6 * 60 * 1000L)

        val status = statement.verify(
            delegationChain = listOf(delegation),
            rootPublicKey = root,
            verifier = FakeSigs,
            nowMs = now,
        )
        assertEquals(ChainStatus.NOT_YET_VALID, status)
    }

    @Test
    fun offlineStatementRemainsValidEvenWhenDelegationExpiredAtNowMs() {
        // B0.5 test: statement issued when delegation was valid must NOT fail when delegation
        // expires on calendar later, because offline lease governs freshness
        val root = FakeSigs.keyOf("root")
        val delegationValidFrom = FakeSigs.T0
        val delegationValidTo = FakeSigs.T0 + 30 * FakeSigs.DAY
        val delegation = FakeSigs.delegation(
            root = root,
            validFrom = delegationValidFrom,
            validTo = delegationValidTo,
        )

        // Statement issued on day 10 (when delegation was valid)
        val statement = FakeSigs.statement(issuedAt = delegationValidFrom + 10 * FakeSigs.DAY)

        // Now is day 45 (delegation is expired at nowMs, but client is offline within grace lease)
        val now = delegationValidFrom + 45 * FakeSigs.DAY
        val status = statement.verify(
            delegationChain = listOf(delegation),
            rootPublicKey = root,
            verifier = FakeSigs,
            nowMs = now,
        )
        assertEquals(ChainStatus.OK, status)
    }

    @Test
    fun statementIssuedOutsideDelegationWindowIsRejected() {
        val root = FakeSigs.keyOf("root")
        val delegationValidFrom = FakeSigs.T0
        val delegationValidTo = FakeSigs.T0 + 30 * FakeSigs.DAY
        val delegation = FakeSigs.delegation(
            root = root,
            validFrom = delegationValidFrom,
            validTo = delegationValidTo,
        )

        // Statement issued AFTER delegation expired
        val statement = FakeSigs.statement(issuedAt = delegationValidTo + 10 * FakeSigs.DAY)
        val status = statement.verify(
            delegationChain = listOf(delegation),
            rootPublicKey = root,
            verifier = FakeSigs,
            nowMs = delegationValidTo + 10 * FakeSigs.DAY,
        )
        assertEquals(ChainStatus.DELEGATION_EXPIRED, status)
    }

    @Test
    fun tamperedFieldsRejectVerification() {
        val root = FakeSigs.keyOf("root")
        val delegation = FakeSigs.delegation(root = root)
        val valid = FakeSigs.statement(deviceId = "dev-1")

        // Tamper deviceId
        assertEquals(
            ChainStatus.DELEGATION_INVALID,
            valid.copy(deviceId = "dev-2").verify(listOf(delegation), root, FakeSigs, FakeSigs.T0),
        )

        // Tamper subjectPublicKey
        assertEquals(
            ChainStatus.DELEGATION_INVALID,
            valid.copy(subjectPublicKey = FakeSigs.keyOf("tampered")).verify(listOf(delegation), root, FakeSigs, FakeSigs.T0),
        )

        // Tamper statementSerial
        assertEquals(
            ChainStatus.DELEGATION_INVALID,
            valid.copy(statementSerial = 999L).verify(listOf(delegation), root, FakeSigs, FakeSigs.T0),
        )

        // Tamper role to invalid
        assertEquals(
            ChainStatus.ROLE_INVALID,
            valid.copy(role = "superadmin").verify(listOf(delegation), root, FakeSigs, FakeSigs.T0),
        )
    }

    @Test
    fun malformedFieldsRejectVerification() {
        val root = FakeSigs.keyOf("root")
        val delegation = FakeSigs.delegation(root = root)
        val valid = FakeSigs.statement(deviceId = "dev-1")

        assertEquals(ChainStatus.MALFORMED, valid.copy(deviceId = "").verify(listOf(delegation), root, FakeSigs, FakeSigs.T0))
        assertEquals(ChainStatus.MALFORMED, valid.copy(subjectPublicKey = "").verify(listOf(delegation), root, FakeSigs, FakeSigs.T0))
        assertEquals(ChainStatus.MALFORMED, valid.copy(issuerSignature = "").verify(listOf(delegation), root, FakeSigs, FakeSigs.T0))
        assertEquals(ChainStatus.MALFORMED, valid.verify(listOf(delegation), "", FakeSigs, FakeSigs.T0))
    }

    @Test
    fun scopeCheckEnforcesRolePermissions() {
        val root = FakeSigs.keyOf("root")
        // Delegation only grants enrol:member
        val memberOnlyDelegation = FakeSigs.delegation(root = root, scopes = setOf("enrol:member"))
        val adminStatement = FakeSigs.statement(role = HubRoles.ADMIN)

        // Admin statement verified against member-only delegation fails with SCOPE_DENIED
        val status = adminStatement.verify(listOf(memberOnlyDelegation), root, FakeSigs, FakeSigs.T0)
        assertEquals(ChainStatus.SCOPE_DENIED, status)
    }

    @Test
    fun revocationListEnforcesDeviceAndDelegationRevocation() {
        val root = FakeSigs.keyOf("root")
        val delegation = FakeSigs.delegation(root = root, serial = 42)
        val statement = FakeSigs.statement(deviceId = "revoked-device")

        val revocationsDevice = FakeSigs.revocations(ids = setOf("revoked-device"))
        val status1 = statement.verify(
            delegationChain = listOf(delegation),
            rootPublicKey = root,
            verifier = FakeSigs,
            nowMs = FakeSigs.T0,
            revocations = revocationsDevice,
        )
        assertEquals(ChainStatus.REVOKED, status1)

        val revocationsDelegation = FakeSigs.revocations(delegationSerials = setOf(42L))
        val status2 = statement.verify(
            delegationChain = listOf(delegation),
            rootPublicKey = root,
            verifier = FakeSigs,
            nowMs = FakeSigs.T0,
            revocations = revocationsDelegation,
        )
        assertEquals(ChainStatus.REVOKED, status2)
    }
}
