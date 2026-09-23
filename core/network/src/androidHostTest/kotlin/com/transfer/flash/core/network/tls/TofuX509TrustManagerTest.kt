package com.transfer.flash.core.network.tls

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class TofuX509TrustManagerTest {

    private val cert = SoftwareCertMaker.newIdentity("CN=flash-leaf").certificate

    private fun fingerprintHex(c: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.publicKey.encoded)
            .joinToString("") { "%02X".format(it) }

    @Test(expected = CertificateException::class)
    fun emptyChainFailsClosedWithoutCallback() {
        val events = mutableListOf<String>()
        TofuX509TrustManager({ _, _ -> true }, "device-a", events::add)
            .checkServerTrusted(arrayOf<X509Certificate>(), "ECDHE_ECDSA")
    }

    @Test
    fun missingDeviceIdFailsClosedAndReportsLeafFingerprint() {
        val events = mutableListOf<String>()
        val tm = TofuX509TrustManager({ _, _ -> true }, null, events::add)

        // Fail closed: throws; leaf fingerprint reported to onKeyChanged first.
        val outcome = runCatching { tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") }

        assertTrue(outcome.exceptionOrNull() is java.security.cert.CertificateException)
        assertEquals(listOf(fingerprintHex(cert)), events)
    }

    @Test
    fun deferralIsOffByDefaultSoAnUnknownDeviceIdStillFailsClosed() {
        // Guards the ADR-040 opt-in: every dial that is NOT a manual IP must keep failing closed.
        val observed = mutableListOf<String>()
        val tm = TofuX509TrustManager({ _, _ -> true }, null, {}, onLeafObserved = observed::add)

        val outcome = runCatching { tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") }

        assertTrue(outcome.exceptionOrNull() is CertificateException)
        assertTrue("deferral must not report a leaf when disabled", observed.isEmpty())
    }

    @Test
    fun deferredManualDialAcceptsAndReportsTheLeafForPostHelloBinding() {
        // ADR-040: no device id is known yet (manual IP dial), so the handshake is allowed and the
        // leaf is handed back; WsFlashNetwork runs isPinned() once HELLO names the peer.
        val observed = mutableListOf<String>()
        val keyChanged = mutableListOf<String>()
        val tm = TofuX509TrustManager(
            pinVerifier = { _, _ -> error("pin evaluation must not run without a device id") },
            expectedDeviceId = null,
            onKeyChanged = keyChanged::add,
            deferPinWhenDeviceIdUnknown = true,
            onLeafObserved = observed::add,
        )

        tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")

        assertEquals(listOf(fingerprintHex(cert)), observed)
        assertTrue("deferral is not a key-change event", keyChanged.isEmpty())
    }

    @Test
    fun deferralDoesNotApplyOnceADeviceIdIsKnown() {
        // With an id present the normal pin check runs even when deferral is enabled.
        val observed = mutableListOf<String>()
        val tm = TofuX509TrustManager(
            pinVerifier = { _, _ -> false },
            expectedDeviceId = "device-a",
            onKeyChanged = {},
            deferPinWhenDeviceIdUnknown = true,
            onLeafObserved = observed::add,
        )

        val outcome = runCatching { tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA") }

        assertTrue(outcome.exceptionOrNull() is CertificateException)
        assertTrue(observed.isEmpty())
    }

    @Test
    fun matchingPinAccepts() {
        val fp = fingerprintHex(cert)
        val events = mutableListOf<String>()
        val tm = TofuX509TrustManager(
            { _, presented -> FlashPinVerifier.normalize(presented) == fp },
            "device-a",
            events::add,
        )

        tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
        tm.checkClientTrusted(arrayOf(cert), "ECDHE_ECDSA")

        assertTrue(events.isEmpty())
    }

    @Test
    fun mismatchFailsClosedAndInvokesCallbackExactlyOnceWithLeafFingerprint() {
        val events = mutableListOf<String>()
        val tm = TofuX509TrustManager({ _, _ -> false }, "device-a", events::add)

        try {
            tm.checkServerTrusted(arrayOf(cert), "ECDHE_ECDSA")
            throw AssertionError("expected CertificateException")
        } catch (e: CertificateException) {
            assertEquals(listOf(fingerprintHex(cert)), events)
            assertTrue(e.message.orEmpty().contains("TOFU"))
        }
    }

    @Test
    fun chainFallbackAcceptsWhenAnyChainCertPins() {
        val other = SoftwareCertMaker.newIdentity("CN=flash-other").certificate
        // Leaf is unknown; the issuer's key is the pinned one.
        val tm = TofuX509TrustManager(
            { _, presented -> presented == fingerprintHex(other) },
            "device-a",
        )

        tm.checkServerTrusted(arrayOf(cert, other), "ECDHE_ECDSA")
    }

    @Test
    fun acceptedIssuersIsEmptyArrayNeverNull() {
        val issuers = TofuX509TrustManager({ _, _ -> false }, "device-a").acceptedIssuers
        assertArrayEquals(emptyArray<X509Certificate>(), issuers)
    }
}
