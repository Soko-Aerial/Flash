package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.FakeSigs.DAY
import com.transfer.flash.core.hubprotocol.FakeSigs.T0
import com.transfer.flash.core.hubprotocol.FakeSigs.keyOf
import com.transfer.flash.core.hubprotocol.certs.ChainStatus
import com.transfer.flash.core.hubprotocol.certs.HubRoles
import com.transfer.flash.core.hubprotocol.certs.HubScopes
import com.transfer.flash.core.hubprotocol.certs.RenewalPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(FlashInternalApi::class)
class CertChainTest {
    private val root = keyOf("root")
    private val hub = keyOf("hub")

    private fun status(
        leaf: com.transfer.flash.core.hubprotocol.certs.DeviceCert,
        delegation: com.transfer.flash.core.hubprotocol.certs.DelegationCert? = FakeSigs.delegation(),
        at: Long = T0 + DAY,
        skew: Long = 0L,
        rev: com.transfer.flash.core.hubprotocol.certs.RevocationList? = null,
        grace: Long = 0L,
    ) = leaf.checkChain(delegation, root, FakeSigs, at, rev, skew, grace)

    @Test
    fun validDelegatedChainIsOk() {
        assertEquals(ChainStatus.OK, status(FakeSigs.device()))
    }

    @Test
    fun rootSignedLeafWithoutDelegationIsOk() {
        val leaf = FakeSigs.device(issuer = root)
        assertEquals(ChainStatus.OK, status(leaf, delegation = null))
    }

    @Test
    fun roleOutsideClosedSetNeverVerifiesEvenWhenSigned() {
        for (bad in listOf("org_admin", "ADMIN", "root", "", "member ", "superuser")) {
            assertEquals(ChainStatus.ROLE_INVALID, status(FakeSigs.device(role = bad)), "role '$bad'")
        }
        assertEquals(setOf("member", "dept_admin", "admin"), HubRoles.ALL)
    }

    @Test
    fun forgedLeafSignatureIsRejected() {
        val leaf = FakeSigs.device(signer = keyOf("attacker"))
        assertEquals(ChainStatus.BAD_SIGNATURE, status(leaf))
    }

    @Test
    fun leafSignedByUnrelatedKeyIsUntrusted() {
        val other = keyOf("other-hub")
        val leaf = FakeSigs.device(issuer = other)
        assertEquals(ChainStatus.UNTRUSTED_ISSUER, status(leaf))
        assertEquals(ChainStatus.UNTRUSTED_ISSUER, status(leaf, delegation = null))
    }

    @Test
    fun foreignRootDelegationIsRejected() {
        val foreign = FakeSigs.delegation(root = keyOf("foreign-root"))
        assertEquals(ChainStatus.DELEGATION_INVALID, status(FakeSigs.device(), delegation = foreign))
    }

    @Test
    fun delegationNotSignedByRootIsRejected() {
        val forged = FakeSigs.delegation(signer = keyOf("attacker"))
        assertEquals(ChainStatus.DELEGATION_INVALID, status(FakeSigs.device(), delegation = forged))
    }

    @Test
    fun scopeRulesAreOneRuleForEverySide() {
        val memberOnly = FakeSigs.delegation(scopes = setOf("enrol:member"))
        assertEquals(ChainStatus.OK, status(FakeSigs.device(role = "member"), delegation = memberOnly))
        assertEquals(ChainStatus.SCOPE_DENIED, status(FakeSigs.device(role = "dept_admin"), delegation = memberOnly))

        val prefix = FakeSigs.delegation(scopes = setOf("enrol:*"))
        assertEquals(ChainStatus.OK, status(FakeSigs.device(role = "dept_admin"), delegation = prefix))

        val star = FakeSigs.delegation(scopes = setOf("*"))
        assertEquals(ChainStatus.OK, status(FakeSigs.device(role = "member"), delegation = star))
    }

    @Test
    fun adminRoleIsNeverGrantedByAWildcard() {
        for (scopes in listOf(setOf("*"), setOf("enrol:*"))) {
            val d = FakeSigs.delegation(scopes = scopes)
            assertEquals(ChainStatus.SCOPE_DENIED, status(FakeSigs.device(role = "admin"), delegation = d), "scopes $scopes")
        }
        val explicit = FakeSigs.delegation(scopes = setOf("enrol:admin"))
        assertEquals(ChainStatus.OK, status(FakeSigs.device(role = "admin"), delegation = explicit))
    }

    @Test
    fun wildcardGrammarHasNoPartialOrLeadingForms() {
        assertTrue(HubScopes.grants(setOf("enrol:member"), "enrol:member"))
        assertTrue(HubScopes.grants(setOf("enrol:*"), "enrol:member"))
        assertTrue(HubScopes.grants(setOf("*"), "anything:at-all"))
        assertFalse(HubScopes.grants(setOf("enr*"), "enrol:member"))
        assertFalse(HubScopes.grants(setOf("*:member"), "enrol:member"))
        assertFalse(HubScopes.grants(setOf("enrol:*"), "revoke:member"))
        assertFalse(HubScopes.grants(setOf("enrol:*"), "enrol:"))
        assertFalse(HubScopes.grants(setOf("ENROL:MEMBER"), "enrol:member"))
        assertFalse(HubScopes.grants(emptySet(), "enrol:member"))
    }

    @Test
    fun leafMayNotOutliveItsDelegation() {
        // SEC-18: delegation ends at T0+2d, the leaf claims 7d.
        val shortDelegation = FakeSigs.delegation(validTo = T0 + 2 * DAY)
        val leaf = FakeSigs.device(validTo = T0 + 7 * DAY)
        assertEquals(ChainStatus.LEAF_OUTSIDE_DELEGATION, status(leaf, delegation = shortDelegation, at = T0 + DAY))
        assertEquals(T0 + 2 * DAY, leaf.effectiveValidTo(shortDelegation))

        val clamped = FakeSigs.device(validTo = T0 + 2 * DAY)
        assertEquals(ChainStatus.OK, status(clamped, delegation = shortDelegation, at = T0 + DAY))
    }

    @Test
    fun expiredDelegationEvenWhenLeafWindowIsOpen() {
        val d = FakeSigs.delegation(validTo = T0 + 2 * DAY)
        val leaf = FakeSigs.device(validTo = T0 + 2 * DAY)
        assertEquals(ChainStatus.EXPIRED, status(leaf, delegation = d, at = T0 + 3 * DAY))
    }

    @Test
    fun skewIsToleratedAtBothEnds() {
        val leaf = FakeSigs.device(validFrom = T0, validTo = T0 + DAY)
        val d = FakeSigs.delegation()
        val skew = 60_000L
        assertEquals(ChainStatus.NOT_YET_VALID, status(leaf, d, at = T0 - skew - 1, skew = skew))
        assertEquals(ChainStatus.OK, status(leaf, d, at = T0 - skew, skew = skew))
        assertEquals(ChainStatus.OK, status(leaf, d, at = T0 + DAY + skew, skew = skew))
        assertEquals(ChainStatus.EXPIRED, status(leaf, d, at = T0 + DAY + skew + 1, skew = skew))
        // isValidAt applies it at both ends too (the old code only before validFrom)
        assertTrue(leaf.isValidAt(T0 + DAY + 30_000L))
        assertFalse(leaf.isValidAt(T0 + DAY + 61_000L))
    }

    @Test
    fun zeroSkewIsExact() {
        val leaf = FakeSigs.device(validFrom = T0, validTo = T0 + DAY)
        assertTrue(leaf.isValidAt(T0 + DAY, 0))
        assertFalse(leaf.isValidAt(T0 + DAY + 1, 0))
        assertFalse(leaf.isValidAt(T0 - 1, 0))
    }

    @Test
    fun renewalGraceAcceptsRecentlyExpiredLeafOnly() {
        val leaf = FakeSigs.device(validTo = T0 + 2 * DAY)
        val d = FakeSigs.delegation(validTo = T0 + 30 * DAY)
        assertEquals(ChainStatus.EXPIRED, status(leaf, d, at = T0 + 3 * DAY))
        assertEquals(ChainStatus.OK, status(leaf, d, at = T0 + 3 * DAY, grace = 72 * 3600_000L))
    }

    @Test
    fun revokedSerialOrDeviceOrDelegationIsRejectedBeforeTime() {
        val leaf = FakeSigs.device(serial = 77, deviceId = "dev-9", validTo = T0 + 2 * DAY)
        val d = FakeSigs.delegation(serial = 5)
        assertEquals(ChainStatus.REVOKED, status(leaf, d, rev = FakeSigs.revocations(serials = setOf(77))))
        assertEquals(ChainStatus.REVOKED, status(leaf, d, rev = FakeSigs.revocations(ids = setOf("dev-9"))))
        assertEquals(ChainStatus.REVOKED, status(leaf, d, rev = FakeSigs.revocations(delegationSerials = setOf(5))))
        assertEquals(ChainStatus.OK, status(leaf, d, rev = FakeSigs.revocations(serials = setOf(1), delegationSerials = setOf(6))))
        // revoked AND expired reports revoked
        assertEquals(ChainStatus.REVOKED, status(leaf, d, at = T0 + 10 * DAY, rev = FakeSigs.revocations(serials = setOf(77))))
    }

    @Test
    fun statusMapsToWireErrorCodes() {
        assertEquals(HubErrorCodes.ERR_REVOKED, ChainStatus.REVOKED.toErrorCode())
        assertEquals(HubErrorCodes.ERR_EXPIRED, ChainStatus.EXPIRED.toErrorCode())
        assertEquals(HubErrorCodes.ERR_AUTH, ChainStatus.BAD_SIGNATURE.toErrorCode())
    }

    // ---- revocation list ------------------------------------------------------------------------------------

    @Test
    fun revocationListVerifiesAndSupersedesMonotonically() {
        val v1 = FakeSigs.revocations(version = 1)
        val v2 = FakeSigs.revocations(version = 2)
        assertTrue(v1.verifySignature(FakeSigs))
        assertTrue(v2.supersedes(v1))
        assertFalse(v1.supersedes(v2))
        assertFalse(v2.supersedes(v2))
        assertTrue(v1.supersedes(null))
        assertFalse(v1.copy(revokedSerials = setOf(9)).verifySignature(FakeSigs), "tampering must break the signature")
        assertFalse(v1.copy(revokedDelegationSerials = setOf(9)).verifySignature(FakeSigs), "delegation serials are signed")
    }

    @Test
    fun revocationListFromDelegateNeedsScopeAndCannotRevokeDelegations() {
        val d = FakeSigs.delegation()
        val byHub = FakeSigs.revocations(serials = setOf(3), issuer = hub)
        assertTrue(byHub.verifyTrusted(root, FakeSigs, T0 + DAY, d, 0))
        assertFalse(byHub.verifyTrusted(root, FakeSigs, T0 + DAY, null, 0))
        assertFalse(byHub.verifyTrusted(root, FakeSigs, T0 + DAY, FakeSigs.delegation(scopes = setOf("enrol:member")), 0))
        assertFalse(byHub.verifyTrusted(root, FakeSigs, T0 + 40 * DAY, d, 0), "expired delegation")

        val hubRevokesDelegation = FakeSigs.revocations(delegationSerials = setOf(1), issuer = hub)
        assertFalse(hubRevokesDelegation.verifyTrusted(root, FakeSigs, T0 + DAY, d, 0))

        val byRoot = FakeSigs.revocations(delegationSerials = setOf(1), issuer = root)
        assertTrue(byRoot.verifyTrusted(root, FakeSigs, T0 + DAY, null, 0))
    }

    @Test
    fun renewalWindowIsEarlyToGrace() {
        val leaf = FakeSigs.device(validTo = T0 + 7 * DAY)
        assertFalse(RenewalPolicy.isRenewable(leaf, T0 + 5 * DAY))
        assertTrue(RenewalPolicy.isRenewable(leaf, T0 + 6 * DAY))
        assertTrue(RenewalPolicy.isRenewable(leaf, T0 + 7 * DAY + 3 * DAY))
        assertFalse(RenewalPolicy.isRenewable(leaf, T0 + 7 * DAY + 3 * DAY + 1))
        assertTrue(RenewalPolicy.shouldRenew(leaf, T0 + 6 * DAY))
        assertFalse(RenewalPolicy.shouldRenew(leaf, T0 + 5 * DAY))
    }

    @Test
    fun renewalWindowGrowsWithTheCertificatesOwnLifetime() {
        // F4: the client used a hardcoded 24 h / 72 h window while the hub derives both from the lifetime, so a 365-day certificate
        // (18.25 days of grace) was abandoned locally before the hub would have refused it.
        val leaf = FakeSigs.device(validFrom = T0, validTo = T0 + 365 * DAY)
        assertFalse(RenewalPolicy.shouldRenew(leaf, T0 + 300 * DAY))
        assertTrue(RenewalPolicy.shouldRenew(leaf, T0 + 330 * DAY), "inside the 36.5-day early window")
        assertTrue(RenewalPolicy.isRenewable(leaf, T0 + 365 * DAY + 10 * DAY))
        assertTrue(RenewalPolicy.isRenewable(leaf, T0 + 365 * DAY + 18 * DAY))
        assertFalse(RenewalPolicy.isRenewable(leaf, T0 + 365 * DAY + 19 * DAY), "past the 18.25-day grace")
        // An explicit window still wins, which is how the hub's configured overrides keep their meaning.
        assertFalse(RenewalPolicy.isRenewable(leaf, T0 + 365 * DAY + 4 * DAY, earlyWindowMs = DAY, graceMs = 3 * DAY))
    }
}
