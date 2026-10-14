@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.desktop

import com.transfer.flash.core.engine.awaitReady
import com.transfer.flash.core.network.tls.FlashCertMaker
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan B Phase B0.1 real proof test (Desktop):
 * Boots a real [DesktopEngine] on a temporary state directory, reads its live bound TLS port,
 * connects an SSL client socket over loopback, completes the TLS handshake, extracts the
 * leaf certificate presented by the live engine, and asserts that the presented leaf public key
 * bytes and SHA-256 fingerprint strictly equal `engine.crypto.identityPublicKeyEncoded`.
 */
class DesktopEngineTlsBindingTest {

    private var stateDir: File? = null
    private var receivedRoot: File? = null
    private var engine: DesktopEngine? = null

    @AfterTest
    fun tearDown() {
        engine?.stop()
        engine = null
        stateDir?.deleteRecursively()
        receivedRoot?.deleteRecursively()
    }

    @Test
    fun `live desktop engine TLS server presents leaf certificate bound to identityPublicKeyEncoded`() = runBlocking {
        val state = Files.createTempDirectory("flash-desktop-tls-state").toFile()
        val received = Files.createTempDirectory("flash-desktop-tls-received").toFile()
        stateDir = state
        receivedRoot = received

        val booted = testDesktopEngine(receivedRoot = received, stateDir = state)
        engine = booted

        booted.start()
        booted.awaitReady()
        assertTrue(booted.ready.value, "DesktopEngine must be ready")
        val port = booted.boundPort
        assertTrue(port > 0, "DesktopEngine boundPort must be > 0")

        // In test code ONLY: a TrustManager that accepts any certificate so the test client can
        // connect to the local loopback engine, complete the TLS handshake, and capture the
        // actual leaf certificate presented by DesktopEngine to verify its key binding.
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        // DesktopEngine requires client authentication (needClientAuth = true in SecureSocketUpgrader.wrapAccepted):
        val clientKeyPair = FlashCertMaker.newEcKeyPair()
        val clientKm = FlashCertMaker.createKeyManagers(clientKeyPair, cn = "CN=test-client")

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(clientKm, arrayOf(trustAll), SecureRandom())
        }

        val socket = sslContext.socketFactory.createSocket("127.0.0.1", port) as SSLSocket
        socket.soTimeout = 10_000

        try {
            socket.startHandshake()
            val session = socket.session
            val peerCerts = session.peerCertificates
            assertTrue(peerCerts.isNotEmpty(), "Server must present peer certificates")
            val leaf = peerCerts.first() as X509Certificate

            val enginePublicKey = booted.crypto.identityPublicKeyEncoded
            val leafPublicKey = leaf.publicKey.encoded

            assertContentEquals(
                enginePublicKey,
                leafPublicKey,
                "Live TLS leaf certificate public key must strictly match DesktopEngine.crypto.identityPublicKeyEncoded byte-for-byte",
            )

            val digest = MessageDigest.getInstance("SHA-256")
            val expectedFp = digest.digest(enginePublicKey).joinToString("") { "%02X".format(it) }
            val actualFp = MessageDigest.getInstance("SHA-256").digest(leafPublicKey).joinToString("") { "%02X".format(it) }

            assertEquals(
                expectedFp,
                actualFp,
                "Live TLS leaf certificate public key SHA-256 fingerprint must match identity key fingerprint",
            )
        } finally {
            runCatching { socket.close() }
            booted.close()
            booted.stop()
        }
    }
}
