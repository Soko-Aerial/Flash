package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.certs.ChainStatus
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.security.crypto.FlashCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DelegatedAuthTest {

    @Test
    fun testDelegationCertVerification() {
        val rootKey = FlashCrypto.generateEcKeyPair()
        val delegateKey = FlashCrypto.generateEcKeyPair()

        val rootPublicKey = rootKey.toBase64Spki()
        val delegatePublicKey = delegateKey.toBase64Spki()

        val now = System.currentTimeMillis()
        val delegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootPublicKey,
            delegatePublicKey = delegatePublicKey,
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER, DelegationCert.SCOPE_REVOKE_MEMBER),
            validFrom = now - 10_000,
            validTo = now + 100_000,
            maxDevices = 50,
            serial = 1L,
        )

        // 1. Signature must verify against root key
        assertTrue(delegation.verifySignature(), "Valid delegation cert signature must verify")
        assertTrue(delegation.isValidAt(now, 0L), "Delegation cert must be valid at current time")
        assertFalse(delegation.isValidAt(now - 20_000, 0L), "Delegation must not be valid before validFrom")
        assertFalse(delegation.isValidAt(now + 200_000, 0L), "Delegation must not be valid after validTo")

        // 2. Scopes
        assertTrue(delegation.hasScope(DelegationCert.SCOPE_ENROL_MEMBER))
        assertTrue(delegation.hasScope(DelegationCert.SCOPE_REVOKE_MEMBER))
        assertFalse(delegation.hasScope(DelegationCert.SCOPE_ENROL_ADMIN))

        // 3. Tampered delegation must fail verification
        val tampered = delegation.copy(maxDevices = 100)
        assertFalse(tampered.verifySignature(), "Tampered maxDevices must invalidate signature")

        val tamperedKey = delegation.copy(rootPublicKey = delegatePublicKey)
        assertFalse(tamperedKey.verifySignature(), "Tampered rootPublicKey must invalidate signature")
    }

    @Test
    fun testDeviceCertVerificationWithValidAndExpiredDelegation() {
        val rootKey = FlashCrypto.generateEcKeyPair()
        val delegateKey = FlashCrypto.generateEcKeyPair()
        val deviceKey = FlashCrypto.generateEcKeyPair()

        val rootPublicKey = rootKey.toBase64Spki()
        val delegatePublicKey = delegateKey.toBase64Spki()
        val devicePublicKey = deviceKey.toBase64Spki()

        val now = System.currentTimeMillis()

        // Valid delegation
        val validDelegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootPublicKey,
            delegatePublicKey = delegatePublicKey,
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 5_000,
            validTo = now + 60_000,
            maxDevices = 10,
            serial = 1L,
        )

        // Valid device cert issued by delegate
        val deviceCert = DeviceCert.issue(
            issuerPrivateKey = delegateKey.private,
            issuerPublicKey = delegatePublicKey,
            deviceId = "dev-alpha-001",
            subjectPublicKey = devicePublicKey,
            role = DeviceCert.ROLE_MEMBER,
            department = "GH-ARMY-1ST-BN",
            validFrom = now - 1_000,
            validTo = now + 30_000,
            serial = 101L,
        )

        // 1. Direct device cert signature verification
        assertTrue(deviceCert.verifySignature(), "Device cert signature must verify against delegate key")

        // 2. Full chain verification with valid delegation
        assertTrue(
            deviceCert.verifyChain(validDelegation, rootPublicKey, JvmEcdsaVerifier, now, clockSkewToleranceMs = 0L),
            "Valid device cert with valid delegation must verify against root"
        )

        // 3. Expired delegation must fail chain verification
        val expiredDelegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootPublicKey,
            delegatePublicKey = delegatePublicKey,
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 20_000,
            validTo = now - 5_000,
            maxDevices = 10,
            serial = 2L,
        )
        assertFalse(
            deviceCert.verifyChain(expiredDelegation, rootPublicKey, JvmEcdsaVerifier, now, clockSkewToleranceMs = 0L),
            "Expired delegation must fail chain verification"
        )

        // 4. Expired device cert must fail chain verification
        val expiredDeviceCert = DeviceCert.issue(
            issuerPrivateKey = delegateKey.private,
            issuerPublicKey = delegatePublicKey,
            deviceId = "dev-alpha-002",
            subjectPublicKey = devicePublicKey,
            role = DeviceCert.ROLE_MEMBER,
            department = "GH-ARMY-1ST-BN",
            validFrom = now - 30_000,
            validTo = now - 1_000,
            serial = 102L,
        )
        assertFalse(
            expiredDeviceCert.verifyChain(validDelegation, rootPublicKey, JvmEcdsaVerifier, now, clockSkewToleranceMs = 0L),
            "Expired device cert must fail chain verification"
        )

        // 5. Delegated key attempting to issue "admin" role without admin scope must fail
        val illegalAdminCert = DeviceCert.issue(
            issuerPrivateKey = delegateKey.private,
            issuerPublicKey = delegatePublicKey,
            deviceId = "dev-rogue-admin",
            subjectPublicKey = devicePublicKey,
            role = DeviceCert.ROLE_ADMIN,
            department = "HQ",
            validFrom = now - 1_000,
            validTo = now + 30_000,
            serial = 103L,
        )
        assertFalse(
            illegalAdminCert.verifyChain(validDelegation, rootPublicKey, JvmEcdsaVerifier, now, clockSkewToleranceMs = 0L),
            "Delegated key cannot issue admin cert without admin delegation scope"
        )

        // 6. Direct root-issued admin cert without delegation must succeed
        val directAdminCert = DeviceCert.issue(
            issuerPrivateKey = rootKey.private,
            issuerPublicKey = rootPublicKey,
            deviceId = "dev-root-admin",
            subjectPublicKey = devicePublicKey,
            role = DeviceCert.ROLE_ADMIN,
            department = "HQ",
            validFrom = now - 1_000,
            validTo = now + 30_000,
            serial = 104L,
        )
        assertTrue(
            directAdminCert.verifyChain(null, rootPublicKey, JvmEcdsaVerifier, now, clockSkewToleranceMs = 0L),
            "Direct root-issued cert must verify without delegation"
        )
    }

    @Test
    fun testRevocationListRejection() {
        val rootKey = FlashCrypto.generateEcKeyPair()
        val rootPublicKey = rootKey.toBase64Spki()

        val devKey1 = FlashCrypto.generateEcKeyPair()
        val devKey2 = FlashCrypto.generateEcKeyPair()

        val now = System.currentTimeMillis()

        val cert1 = DeviceCert.issue(
            issuerPrivateKey = rootKey.private,
            issuerPublicKey = rootPublicKey,
            deviceId = "device-accra-1",
            subjectPublicKey = devKey1.toBase64Spki(),
            role = DeviceCert.ROLE_MEMBER,
            serial = 501L,
        )

        val cert2 = DeviceCert.issue(
            issuerPrivateKey = rootKey.private,
            issuerPublicKey = rootPublicKey,
            deviceId = "device-kumasi-2",
            subjectPublicKey = devKey2.toBase64Spki(),
            role = DeviceCert.ROLE_MEMBER,
            serial = 502L,
        )

        // Issue revocation list revoking cert1's serial (501) and device-kumasi-2 by ID
        val revList = RevocationList.issue(
            issuerPrivateKey = rootKey.private,
            issuerPublicKey = rootPublicKey,
            version = 1L,
            revokedSerials = setOf(501L),
            revokedDeviceIds = setOf("device-kumasi-2"),
        )

        assertTrue(revList.verifySignature(), "Revocation list signature must verify")
        assertEquals(1L, revList.version)

        // cert1 revoked by serial
        assertTrue(revList.isRevoked(cert1), "cert1 must be revoked by serial 501")
        assertTrue(revList.isRevoked(501L, "device-other"), "Serial 501 must be revoked")

        // cert2 revoked by deviceId
        assertTrue(revList.isRevoked(cert2), "cert2 must be revoked by deviceId device-kumasi-2")
        assertTrue(revList.isRevoked(999L, "device-kumasi-2"), "deviceId device-kumasi-2 must be revoked")

        // Unrevoked device and serial
        assertFalse(revList.isRevoked(503L, "device-tamale-3"), "Unlisted serial and device must not be revoked")
    }

    @Test
    fun leafMayNotOutliveItsDelegationAndSkewAppliesAtBothEnds() {
        val rootKey = FlashCrypto.generateEcKeyPair()
        val delegateKey = FlashCrypto.generateEcKeyPair()
        val deviceKey = FlashCrypto.generateEcKeyPair()
        val now = System.currentTimeMillis()
        val delegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootKey.toBase64Spki(),
            delegatePublicKey = delegateKey.toBase64Spki(),
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 60_000,
            validTo = now + 3_600_000,
            maxDevices = 5,
            serial = 3L,
        )
        fun leaf(validFrom: Long, validTo: Long) = DeviceCert.issue(
            issuerPrivateKey = delegateKey.private,
            issuerPublicKey = delegateKey.toBase64Spki(),
            deviceId = "dev-win",
            subjectPublicKey = deviceKey.toBase64Spki(),
            validFrom = validFrom,
            validTo = validTo,
            serial = 9L,
        )
        val root = rootKey.toBase64Spki()

        // SEC-18: a 7 day leaf under a 1 hour delegation is refused, even though both are individually valid now.
        val tooLong = leaf(now - 1_000, now + 7L * 24 * 3600 * 1000)
        assertEquals(
            ChainStatus.LEAF_OUTSIDE_DELEGATION,
            tooLong.checkChain(delegation, root, JvmEcdsaVerifier, now, clockSkewToleranceMs = 0L),
        )

        // Skew is applied at BOTH ends: 30 s after validTo is fine with a 60 s tolerance, not with 0.
        val ends = leaf(now - 10_000, now + 3_000_000)
        assertEquals(ChainStatus.OK, ends.checkChain(delegation, root, JvmEcdsaVerifier, ends.validTo + 30_000))
        assertEquals(ChainStatus.EXPIRED, ends.checkChain(delegation, root, JvmEcdsaVerifier, ends.validTo + 30_000, clockSkewToleranceMs = 0L))
        assertEquals(ChainStatus.OK, ends.checkChain(delegation, root, JvmEcdsaVerifier, ends.validFrom - 30_000))
        assertEquals(ChainStatus.NOT_YET_VALID, ends.checkChain(delegation, root, JvmEcdsaVerifier, ends.validFrom - 30_000, clockSkewToleranceMs = 0L))
    }

    @Test
    fun aRevokedDelegationSerialRevokesEveryCertificateItIssued() {
        val rootKey = FlashCrypto.generateEcKeyPair()
        val delegateKey = FlashCrypto.generateEcKeyPair()
        val deviceKey = FlashCrypto.generateEcKeyPair()
        val now = System.currentTimeMillis()
        val delegation = DelegationCert.issue(
            rootPrivateKey = rootKey.private,
            rootPublicKey = rootKey.toBase64Spki(),
            delegatePublicKey = delegateKey.toBase64Spki(),
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER),
            validFrom = now - 60_000,
            validTo = now + 3_600_000,
            maxDevices = 5,
            serial = 11L,
        )
        val cert = DeviceCert.issue(
            issuerPrivateKey = delegateKey.private,
            issuerPublicKey = delegateKey.toBase64Spki(),
            deviceId = "dev-del",
            subjectPublicKey = deviceKey.toBase64Spki(),
            validFrom = now - 1_000,
            validTo = now + 600_000,
            serial = 12L,
        )
        val root = rootKey.toBase64Spki()
        val list = RevocationList.issue(
            issuerPrivateKey = rootKey.private,
            issuerPublicKey = root,
            version = 2L,
            revokedSerials = emptySet(),
            revokedDeviceIds = emptySet(),
            revokedDelegationSerials = setOf(11L),
        )
        assertTrue(list.verifySignature())
        assertEquals(ChainStatus.OK, cert.checkChain(delegation, root, JvmEcdsaVerifier, now))
        assertEquals(ChainStatus.REVOKED, cert.checkChain(delegation, root, JvmEcdsaVerifier, now, revocations = list))
    }
}
