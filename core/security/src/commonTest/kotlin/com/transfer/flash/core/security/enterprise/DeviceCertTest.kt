@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.certs.ChainStatus
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.security.crypto.SoftwareFlashCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The certificate model moved to `:core:hub-protocol` (ADR-107). These tests exercise it with the REAL client crypto
 * ([SoftwareFlashCrypto] through [FlashCryptoEcdsaVerifier]); the pure logic is covered in hub-protocol's own tests.
 */
class DeviceCertTest {

    @Test
    fun testCanonicalBytesDeterministic() {
        val cert1 = DeviceCert(
            deviceId = "dev-101",
            subjectPublicKey = "pubKeyBase64Sample1",
            role = DeviceCert.ROLE_MEMBER,
            department = "Engineering",
            validFrom = 1000L,
            validTo = 2000L,
            serial = 1L,
            issuerPublicKey = "issuerKeyBase64Sample",
            issuerSignature = "sigBase64",
        )
        val cert2 = cert1.copy(issuerSignature = "differentSignature")

        // Canonical bytes must be identical regardless of issuerSignature
        assertTrue(cert1.canonicalBytes().contentEquals(cert2.canonicalBytes()))
    }

    @Test
    fun testSignAndVerifyDirectRootIssuance() {
        val rootCrypto = SoftwareFlashCrypto()
        val verifier = FlashCryptoEcdsaVerifier(rootCrypto)
        val rootPublicKeyBase64 = Base64.encode(rootCrypto.identityPublicKeyEncoded)

        val clientCrypto = SoftwareFlashCrypto()
        val clientPublicKeyBase64 = Base64.encode(clientCrypto.identityPublicKeyEncoded)

        val unsigned = DeviceCert(
            deviceId = "dev-alice-01",
            subjectPublicKey = clientPublicKeyBase64,
            role = DeviceCert.ROLE_MEMBER,
            department = "Security",
            validFrom = 1000L,
            validTo = 5000L,
            serial = 42L,
            issuerPublicKey = rootPublicKeyBase64,
            issuerSignature = "",
        )

        val signatureBytes = rootCrypto.sign(unsigned.canonicalBytes())
        val signedCert = unsigned.copy(issuerSignature = Base64.encode(signatureBytes))

        assertTrue(signedCert.verifySignature(verifier))
        // Exact window: skew 0 (the default tolerates 60 s at both ends).
        assertTrue(signedCert.isValidAt(2000L, 0L))
        assertFalse(signedCert.isValidAt(6000L, 0L))

        assertTrue(signedCert.verifyChain(delegation = null, rootPublicKey = rootPublicKeyBase64, verifier = verifier, timestamp = 2000L))
        assertFalse(signedCert.verifyChain(delegation = null, rootPublicKey = "wrongRootKey", verifier = verifier, timestamp = 2000L))
    }

    @Test
    fun testDelegatedAuthorityChainVerification() {
        val rootCrypto = SoftwareFlashCrypto()
        val verifier = FlashCryptoEcdsaVerifier(rootCrypto)
        val rootPublicKeyBase64 = Base64.encode(rootCrypto.identityPublicKeyEncoded)

        val serverCrypto = SoftwareFlashCrypto()
        val serverPublicKeyBase64 = Base64.encode(serverCrypto.identityPublicKeyEncoded)

        val clientCrypto = SoftwareFlashCrypto()
        val clientPublicKeyBase64 = Base64.encode(clientCrypto.identityPublicKeyEncoded)

        val unsignedDelegation = DelegationCert(
            rootPublicKey = rootPublicKeyBase64,
            delegatePublicKey = serverPublicKeyBase64,
            scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER, DelegationCert.SCOPE_REVOKE_MEMBER),
            validFrom = 1000L,
            validTo = 10000L,
            maxDevices = 50,
            serial = 10L,
            signature = "",
        )
        val delSig = rootCrypto.sign(unsignedDelegation.canonicalBytes())
        val delegation = unsignedDelegation.copy(signature = Base64.encode(delSig))

        assertTrue(delegation.verifySignature(verifier))
        assertTrue(delegation.hasScope(DelegationCert.SCOPE_ENROL_MEMBER))
        assertFalse(delegation.hasScope(DelegationCert.SCOPE_ENROL_ADMIN))

        val unsignedDeviceCert = DeviceCert(
            deviceId = "dev-bob-02",
            subjectPublicKey = clientPublicKeyBase64,
            role = DeviceCert.ROLE_MEMBER,
            department = "Logistics",
            validFrom = 1500L,
            validTo = 8000L,
            serial = 100L,
            issuerPublicKey = serverPublicKeyBase64,
            issuerSignature = "",
        )
        val certSig = serverCrypto.sign(unsignedDeviceCert.canonicalBytes())
        val deviceCert = unsignedDeviceCert.copy(issuerSignature = Base64.encode(certSig))

        assertTrue(
            deviceCert.verifyChain(delegation = delegation, rootPublicKey = rootPublicKeyBase64, verifier = verifier, timestamp = 3000L)
        )

        // A delegated server without the admin scope cannot issue admin certificates.
        val forgedAdminCert = deviceCert.copy(
            role = DeviceCert.ROLE_ADMIN,
            issuerSignature = Base64.encode(serverCrypto.sign(deviceCert.copy(role = DeviceCert.ROLE_ADMIN).canonicalBytes())),
        )
        assertEquals(
            ChainStatus.SCOPE_DENIED,
            forgedAdminCert.checkChain(delegation, rootPublicKeyBase64, verifier, 3000L),
            "Delegated server without admin scope must not issue admin certificates",
        )

        // SEC-18: the leaf must not outlive its delegation (delegation ends at 10000).
        val unsignedLong = unsignedDeviceCert.copy(validTo = 20_000L)
        val longCert = unsignedLong.copy(issuerSignature = Base64.encode(serverCrypto.sign(unsignedLong.canonicalBytes())))
        assertEquals(ChainStatus.LEAF_OUTSIDE_DELEGATION, longCert.checkChain(delegation, rootPublicKeyBase64, verifier, 3000L, clockSkewToleranceMs = 0L))
    }

    @Test
    fun credentialsExpiryIsComparedAgainstNowNotEnrolmentTime() {
        val cert = DeviceCert("d", "k", DeviceCert.ROLE_MEMBER, null, 1_000L, 5_000L, 1L, "i", "s")
        val creds = EnrollmentCredentials(
            hubUrl = "https://h", rootFingerprint = "F", rootPublicKey = "r", deviceCert = cert, enrolledAtMs = 2_000L,
        )
        // enrolledAtMs (2000) is inside the window; the old implementation therefore reported "not expired" for ever.
        assertFalse(creds.isExpiredAt(2_000L))
        assertFalse(creds.isExpiredAt(5_000L))
        assertTrue(creds.isExpiredAt(5_001L))
        assertTrue(creds.isExpired, "the real clock is long past 1970-01-06")
        assertTrue(creds.needsRenewalAt(5_001L))
    }
}
