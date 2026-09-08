package com.transfer.flash.core.network.tls

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loopback tests for [SecureSocketUpgrader] (C4.1 part 2, stream A).
 * Ephemeral ports; whole class targets < 15 s.
 */
class SecureSocketUpgraderTest {

    private val serverIdentity = SoftwareCertMaker.newIdentity("CN=flash-upgrade-server")

    private val executor = Executors.newSingleThreadExecutor()
    private var serverSocket: java.net.ServerSocket? = null

    @After
    fun tearDown() {
        executor.shutdownNow()
        serverSocket?.close()
    }

    /** Plain TCP accept loop that does NOT speak TLS — for negative client-wrap tests. */
    private fun startPlainServer(): Int {
        val server = java.net.ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        serverSocket = server
        executor.submit {
            runCatching {
                server.accept().use { raw ->
                    // Drain a little then hold; the TLS handshake will simply never succeed.
                    raw.soTimeout = 5_000
                    raw.getInputStream().read(ByteArray(64))
                }
            }
        }
        return server.localPort
    }

    private fun fingerprintHex(identity: SoftwareCertMaker.TestIdentity): String =
        MessageDigest.getInstance("SHA-256").digest(identity.certificate.publicKey.encoded)
            .joinToString("") { "%02X".format(it) }

    private fun connectTo(port: Int): Socket =
        Socket().apply { connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 3_000) }

    @Test(timeout = 15_000L)
    fun `wrapClient refuses socket whose plain streams were already obtained`() = runBlocking {
        val port = startPlainServer()

        val touched = SecureSocketUpgrader.withPlainStreamTracking(connectTo(port))
        touched.getInputStream() // pre-wrap access — must poison the upgrade

        val result = SecureSocketUpgrader.wrapClient(
            touched,
            peerDeviceId = "device-a",
            pinVerifier = FlashPinVerifier { _, _ -> true },
            keyManagers = null,
        )

        assertTrue("upgrade must fail closed", result.isFailure)
        val error = result.exceptionOrNull()!!
        assertTrue(
            "expected IllegalStateException, got ${error::class.java.simpleName}",
            error is IllegalStateException,
        )
        assertTrue(
            "message must explain the clean-boundary violation: ${error.message}",
            error.message?.contains("streams were accessed before wrapping") == true,
        )
        assertTrue("tainted socket must be closed on refusal", touched.isClosed)
    }

    @Test(timeout = 15_000L)
    fun `wrapAccepted refuses tainted accepted socket`() {
        val pair = loopbackPair()
        try {
            val (left, right) = pair
            left.getOutputStream().write(0x16) // someone already wrote plaintext into the wire
            val tracked = SecureSocketUpgrader.withPlainStreamTracking(right)
            tracked.getInputStream()

            val error = runCatching {
                SecureSocketUpgrader.wrapAccepted(
                    tracked,
                    FlashPinVerifier { _, _ -> true },
                    serverIdentity.keyManagers,
                )
            }.exceptionOrNull()

            assertNotNull(error)
            assertTrue("expected IllegalStateException", error is IllegalStateException)
        } finally {
            pair.forEach { runCatching { it.close() } }
        }
    }

    @Test(timeout = 15_000L)
    fun `clean tracking wrapper passes the taint guard`() {
        val pair = loopbackPair()
        try {
            val tracked = SecureSocketUpgrader.withPlainStreamTracking(pair[1])
            assertFalse((tracked as SecureSocketUpgrader.PlainStreamAccessAudited).plainStreamsAccessed)

            val ssl = SecureSocketUpgrader.wrapAccepted(
                tracked,
                FlashPinVerifier { _, _ -> true },
                serverIdentity.keyManagers,
            )
            assertTrue("untouched wrapper must be accepted (lazy handshake)", ssl.useClientMode == false)
            ssl.close()
        } finally {
            pair.forEach { runCatching { it.close() } }
        }
    }

    @Test(timeout = 15_000L)
    fun `wrapClient fails closed with TOFU certificate error on wrong pin`() = runBlocking {
        // A real TLS server whose cert is NOT pinned by the client verifier below.
        val sslServer = FlashTlsContextFactory.serverContext(
            FlashPinVerifier { _, _ -> true },
            keyManagers = serverIdentity.keyManagers,
        ).serverSocketFactory.createServerSocket(0, 8, InetAddress.getLoopbackAddress()) as javax.net.ssl.SSLServerSocket
        serverSocket = sslServer
        sslServer.soTimeout = 5_000
        executor.submit {
            runCatching {
                (sslServer.accept() as javax.net.ssl.SSLSocket).use {
                    it.soTimeout = 5_000
                    it.startHandshake()
                }
            }
        }

        val result = SecureSocketUpgrader.wrapClient(
            connectTo(sslServer.localPort),
            peerDeviceId = "device-a",
            pinVerifier = FlashPinVerifier { _, _ -> false }, // pinned to some OTHER key
            keyManagers = SoftwareCertMaker.newIdentity("CN=flash-client").keyManagers,
        )

        assertTrue("wrong pin must fail closed", result.isFailure)
        val tofu = generateSequence(result.exceptionOrNull() as Throwable?) { it.cause }
            .filterIsInstance<CertificateException>()
            .firstOrNull { it.message?.contains("TOFU") == true }
        assertNotNull(
            "failure chain must carry TofuX509TrustManager CertificateException, got: " +
                result.exceptionOrNull(),
            tofu,
        )
    }

    /** Two directly connected loopback sockets (no listener thread needed). */
    private fun loopbackPair(): List<Socket> {
        val server = java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val left = Socket()
        left.connect(server.localSocketAddress, 3_000)
        val right = server.accept()
        server.close()
        return listOf(left, right)
    }
}
