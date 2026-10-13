@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.security.crypto.SoftwareFlashCrypto
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The client half of the cross-check: the SAME real P-256 signed certificate that `:core:hub-protocol`
 * (`SignedVectorJvmTest`) and `:server` (`HubSignedVectorServerTest`) assert, verified here through [FlashCrypto] /
 * [FlashCryptoEcdsaVerifier]. If any of the three diverge on what a valid signature is, one of them fails.
 */
class HubSignedVectorTest {

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
    fun clientVerifierAcceptsThePinnedSignedVector() {
        assertTrue(signed.verifySignature(FlashCryptoEcdsaVerifier(SoftwareFlashCrypto())))
    }

    @Test
    fun clientVerifierRejectsTamperedVector() {
        val v = FlashCryptoEcdsaVerifier(SoftwareFlashCrypto())
        assertFalse(signed.copy(role = "admin").verifySignature(v))
        assertFalse(signed.copy(serial = 43).verifySignature(v))
        assertFalse(signed.copy(issuerSignature = "").verifySignature(v))
    }

    companion object {
        // Keep identical to core/hub-protocol SignedVectorJvmTest.
        const val ISSUER =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEd46cSeP8LsDK6MSrpYejrOTVCHTrEYLYa6zPZhDVbKaH9TnO9oeNaM0stW6cBUDa/B4Jpds4WsOc8NmYkbQkNw=="
        const val SIGNATURE =
            "MEYCIQC9XihmCMS9jMcKcalvelErSfmYzCXdjoH2edq1h6MtcwIhAPsY8p2XL0L+iqB+o1MBWccm3N+z8lub1HXCUkMow2EP"
    }
}
