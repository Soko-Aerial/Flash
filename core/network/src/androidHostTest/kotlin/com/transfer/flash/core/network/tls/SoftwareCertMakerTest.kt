package com.transfer.flash.core.network.tls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException

class SoftwareCertMakerTest {

    @Test
    fun generatesParsableSelfSignedCertificateWithMatchingKeys() {
        val identity = SoftwareCertMaker.newIdentity("CN=flash-a")

        assertEquals("CN=flash-a", identity.certificate.subjectX500Principal.name)
        assertEquals("CN=flash-a", identity.certificate.issuerX500Principal.name)
        assertTrue(identity.certificate.publicKey.algorithm, identity.certificate.publicKey.algorithm == "EC")
        assertArrayEquals(
            identity.keyPair.public.encoded,
            identity.certificate.publicKey.encoded,
        )
        assertNotNull(identity.keyManagers.isNotEmpty())
    }

    @Test
    fun distinctIdentitiesProduceDistinctFingerprints() {
        val a = SoftwareCertMaker.newIdentity("CN=flash-a")
        val b = SoftwareCertMaker.newIdentity("CN=flash-b")

        fun fp(i: SoftwareCertMaker.TestIdentity) =
            MessageDigest.getInstance("SHA-256").digest(i.certificate.publicKey.encoded)
                .joinToString("") { "%02X".format(it) }

        assertTrue(fp(a) != fp(b))
    }

    @Test
    fun certificateIsValidRightNow() {
        val identity = SoftwareCertMaker.newIdentity()
        identity.certificate.checkValidity()
    }

    @Test(expected = CertificateException::class)
    fun garbageDerIsRejectedByParser() {
        // Sanity guard that the holder really parses DER rather than accepting anything.
        val bad = byteArrayOf(0x30, 0x03, 0x02, 0x01) // truncated SEQUENCE
        java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(bad.inputStream())
    }
}
