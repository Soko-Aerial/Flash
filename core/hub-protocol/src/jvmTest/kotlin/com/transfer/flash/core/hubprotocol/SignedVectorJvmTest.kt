package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.core.hubprotocol.certs.toHubHex
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A REAL P-256 signed certificate pinned as a vector. The very same constants are asserted by the client's
 * `FlashCrypto` verifier (`:core:security` `HubSignedVectorTest`) and by the server's java.security verifier
 * (`:server` `HubSignedVectorServerTest`), so the two ends can never silently diverge on what a valid signature is.
 */
@OptIn(FlashInternalApi::class)
class SignedVectorJvmTest {

    private object Jdk : EcdsaVerifier {
        override fun verify(publicKeySpki: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeySpki))
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(data)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
    }

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
    fun pinnedSignedVectorVerifies() {
        assertEquals(SIGNED_HEX, signed.canonicalBytes().toHubHex())
        assertTrue(signed.verifySignature(Jdk))
    }

    @Test
    fun anyChangeToASignedFieldBreaksIt() {
        assertFalse(signed.copy(role = "admin").verifySignature(Jdk))
        assertFalse(signed.copy(serial = 43).verifySignature(Jdk))
        assertFalse(signed.copy(validTo = signed.validTo + 1).verifySignature(Jdk))
        assertFalse(signed.copy(department = null).verifySignature(Jdk))
        assertFalse(signed.copy(deviceId = "dev-beta").verifySignature(Jdk))
    }

    @Test
    fun garbageSignatureNeverThrows() {
        assertFalse(signed.copy(issuerSignature = "!!!not base64!!!").verifySignature(Jdk))
        assertFalse(signed.copy(issuerSignature = "").verifySignature(Jdk))
        assertFalse(signed.copy(issuerPublicKey = "AAAA").verifySignature(Jdk))
    }

    @Test
    fun freshlyGeneratedJdkSignatureVerifiesThroughTheSharedModel() {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val spki = Base64.getEncoder().encodeToString(kp.public.encoded)
        val unsigned = signed.copy(issuerPublicKey = spki, issuerSignature = "")
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(kp.private)
            update(unsigned.canonicalBytes())
            sign()
        }
        assertTrue(unsigned.copy(issuerSignature = Base64.getEncoder().encodeToString(sig)).verifySignature(Jdk))
    }

    companion object {
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
