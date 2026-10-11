@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.security.crypto.SoftwareFlashCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

        // Verify direct signature
        assertTrue(signedCert.verifySignature(rootCrypto))
        assertTrue(signedCert.isValidAt(2000L))
        assertFalse(signedCert.isValidAt(6000L))

        // Verify chain with direct root (delegation = null)
        assertTrue(signedCert.verifyChain(delegation = null, rootPublicKey = rootPublicKeyBase64, crypto = rootCrypto, timestamp = 2000L))

        // Wrong root fails
        assertFalse(signedCert.verifyChain(delegation = null, rootPublicKey = "wrongRootKey", crypto = rootCrypto, timestamp = 2000L))
    }

    @Test
    fun testDelegatedAuthorityChainVerification() {
        val rootCrypto = SoftwareFlashCrypto()
        val rootPublicKeyBase64 = Base64.encode(rootCrypto.identityPublicKeyEncoded)

        val serverCrypto = SoftwareFlashCrypto()
        val serverPublicKeyBase64 = Base64.encode(serverCrypto.identityPublicKeyEncoded)

        val clientCrypto = SoftwareFlashCrypto()
        val clientPublicKeyBase64 = Base64.encode(clientCrypto.identityPublicKeyEncoded)

        // 1. Root delegates to Server
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

        assertTrue(delegation.verifySignature(rootCrypto))
        assertTrue(delegation.hasScope(DelegationCert.SCOPE_ENROL_MEMBER))
        assertFalse(delegation.hasScope(DelegationCert.SCOPE_ENROL_ADMIN))

        // 2. Server issues member certificate to Client
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

        // 3. Verify complete chain (DeviceCert -> DelegationCert -> Root)
        assertTrue(
            deviceCert.verifyChain(
                delegation = delegation,
                rootPublicKey = rootPublicKeyBase64,
                crypto = rootCrypto,
                timestamp = 3000L,
            )
        )

        // 4. Delegated server cannot issue admin role without SCOPE_ENROL_ADMIN
        val forgedAdminCert = deviceCert.copy(
            role = DeviceCert.ROLE_ADMIN,
            issuerSignature = Base64.encode(serverCrypto.sign(deviceCert.copy(role = DeviceCert.ROLE_ADMIN).canonicalBytes())),
        )
        assertFalse(
            forgedAdminCert.verifyChain(
                delegation = delegation,
                rootPublicKey = rootPublicKeyBase64,
                crypto = rootCrypto,
                timestamp = 3000L,
            ),
            "Delegated server without admin scope must not issue admin certificates"
        )
    }
}
