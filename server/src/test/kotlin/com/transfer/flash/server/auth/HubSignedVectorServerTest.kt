@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.auth

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.toHubHex
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.enterprise.HubClientAuth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The SERVER half of the cross-check: the hub's java.security verifier ([JvmEcdsaVerifier]) must accept the very same
 * signed vector that the shared module (`SignedVectorJvmTest`) and the client's `FlashCrypto` verifier
 * (`:core:security` `HubSignedVectorTest`) accept. The constants are copied, not shared, on purpose: a change on one
 * side that is not mirrored on the other fails one of the three tests.
 */
class HubSignedVectorServerTest {

    private val signed = DeviceCert(
        deviceId = "dev-alpha",
        subjectPublicKey = "c3ViamVjdC1rZXk=",
        role = "member",
        department = "dept-eng",
        validFrom = 1_700_000_000_000,
        validTo = 1_700_604_800_000,
        serial = 42,
        issuerPublicKey = ISSUER,
        issuerSignature = SIGNATURE,
    )

    @Test
    fun canonicalBytesAreThePinnedOnes() {
        assertEquals(SIGNED_HEX, signed.canonicalBytes().toHubHex())
    }

    @Test
    fun theServerVerifierAcceptsThePinnedVector() {
        assertTrue(signed.verifySignature(), "the hub must accept a certificate the clients accept")
    }

    @Test
    fun theServerVerifierRejectsAnyChangeToASignedField() {
        assertFalse(signed.copy(role = "admin").verifySignature())
        assertFalse(signed.copy(serial = 43).verifySignature())
        assertFalse(signed.copy(validTo = signed.validTo + 1).verifySignature())
    }

    @Test
    fun aSignatureMadeByTheServerIsAcceptedByTheServerAndHasTheDerShapeClientsExpect() {
        val key = FlashCrypto.generateEcKeyPair()
        val cert = DeviceCert.issue(
            issuerPrivateKey = key.private,
            issuerPublicKey = key.toBase64Spki(),
            deviceId = "dev-x",
            subjectPublicKey = "c3ViamVjdC1rZXk=",
            serial = 1L,
        )
        assertTrue(cert.verifySignature())
        val der = java.util.Base64.getDecoder().decode(cert.issuerSignature)
        assertEquals(0x30, der[0].toInt() and 0xFF, "ECDSA signatures are DER SEQUENCEs on the wire")
    }

    @Test
    fun serverAndClientAgreeOnTheRootFingerprint() {
        // The relay CHALLENGE.serverId, the invite `root=` and the client's pin are all this value (upper-case hex).
        assertEquals(HubClientAuth.rootFingerprintHex(ISSUER), FlashCrypto.sha256FingerprintHex(ISSUER))
    }

    private companion object {
        const val ISSUER =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEd46cSeP8LsDK6MSrpYejrOTVCHTrEYLYa6zPZhDVbKaH9TnO9oeNaM0stW6cBUDa/B4Jpds4WsOc8NmYkbQkNw=="
        const val SIGNATURE =
            "MEYCIQC9XihmCMS9jMcKcalvelErSfmYzCXdjoH2edq1h6MtcwIhAPsY8p2XL0L+iqB+o1MBWccm3N+z8lub1HXCUkMow2EP"
        const val SIGNED_HEX =
            "00000014666c6173682d6465766963652d636572742d7631000000096465762d616c7068610000001063335669616d566a644331725a586b3d" +
                "000000066d656d62657200000008646570742d656e67000000080000018bcfe56800000000080000018bf3f1ec0000000008000000000000002a" +
                "0000007c4d466b77457759484b6f5a497a6a3043415159494b6f5a497a6a3044415163445167414564343663536550384c73444b364d53727059" +
                "656a724f54564348547245594c5961367a505a684456624b614839546e4f396f654e614d307374573663425544612f42344a7064733457734f" +
                "63384e6d596b62516b4e773d3d"
    }
}
