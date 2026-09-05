package com.transfer.flash.core.network.tls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Pure-JVM localhost handshake tests for the C4.1 TOFU TLS stack.
 * Ephemeral ports, 3-5 s per-operation timeouts; whole suite targets < 10 s.
 */
class TofuTlsHandshakeTest {

    private val serverIdentity = SoftwareCertMaker.newIdentity("CN=flash-server")
    private val serverFpHex = fingerprintHex(serverIdentity)

    private data class HandshakeResult(
        val error: Throwable?,
        val protocol: String?,
        val peerFingerprint: String?,
    )

    private fun fingerprintHex(identity: SoftwareCertMaker.TestIdentity): String =
        MessageDigest.getInstance("SHA-256").digest(identity.certificate.publicKey.encoded)
            .joinToString("") { "%02X".format(it) }

    /** Runs one full client↔server handshake on a loopback ephemeral port. */
    private fun handshake(
        verifier: FlashPinVerifier,
        expectedDeviceId: String?,
        keyChangeEvents: MutableList<String>,
    ): HandshakeResult {
        // Server side accepts everything (no client-auth requested); the pin verification under
        // test happens in the CLIENT trust manager, which is where the spec's cases live.
        val serverCtx = FlashTlsContextFactory.serverContext(
            FlashPinVerifier { _, _ -> true },
            keyManagers = serverIdentity.keyManagers,
        )
        val server = serverCtx.serverSocketFactory
            .createServerSocket(0, 8, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.soTimeout = TIMEOUT_MS

        val executor = Executors.newSingleThreadExecutor()
        val serverDone = executor.submit(
            Callable {
                server.accept().use { raw ->
                    val socket = raw as SSLSocket
                    socket.soTimeout = TIMEOUT_MS
                    FlashTlsContextFactory.configure(socket)
                    socket.startHandshake()
                }
            },
        )
        var result = HandshakeResult(null, null, null)
        try {
            val clientCtx = FlashTlsContextFactory.clientContext(
                verifier,
                expectedDeviceId,
                keyManagers = SoftwareCertMaker.newIdentity("CN=flash-client").keyManagers,
                onKeyChanged = { keyChangeEvents.add(it) },
            )
            Socket().use { plain ->
                plain.connect(InetSocketAddress(server.inetAddress, server.localPort), CONNECT_TIMEOUT_MS)
                val ssl = clientCtx.socketFactory.createSocket(plain, "localhost", server.localPort, true) as SSLSocket
                ssl.use { s ->
                    s.soTimeout = TIMEOUT_MS
                    FlashTlsContextFactory.configure(s)
                    s.startHandshake()
                    result = HandshakeResult(null, s.session.protocol, fingerprintOf(s))
                }
            }
        } catch (t: Throwable) {
            result = HandshakeResult(t, null, null)
        } finally {
            runCatching { serverDone.get(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS) }
            executor.shutdownNow()
            server.close()
        }
        return result
    }

    private fun fingerprintOf(socket: SSLSocket): String? =
        socket.session.peerCertificates.firstOrNull()?.publicKey?.encoded
            ?.let { MessageDigest.getInstance("SHA-256").digest(it) }
            ?.joinToString("") { byte -> "%02X".format(byte) }

    private fun findTofuFailure(t: Throwable): CertificateException? {
        var current: Throwable? = t
        while (current != null) {
            if (current is CertificateException && current.message?.contains("TOFU") == true) {
                return current
            }
            current = current.cause
        }
        return null
    }

    @Test(timeout = 10_000L)
    fun matchingPinCompletesHandshakeAndExposesPresentedFingerprint() {
        val events = mutableListOf<String>()
        val verifier = FlashPinVerifier { _, fp -> fp == serverFpHex }

        val result = handshake(verifier, "device-a", events)

        assertNull("handshake should succeed with matching pin", result.error)
        assertEquals(serverFpHex, result.peerFingerprint)
        assertTrue("no key-change event expected on success", events.isEmpty())
    }

    @Test(timeout = 10_000L)
    fun wrongPinFailsClosedAndFiresOnKeyChangedOnce() {
        val events = mutableListOf<String>()
        val verifier = FlashPinVerifier { _, _ -> false } // pinned to some OTHER key

        val result = handshake(verifier, "device-a", events)

        assertNotNull("handshake must fail closed on pin mismatch", result.error)
        val tofu = findTofuFailure(result.error!!)
        assertNotNull("failure chain must carry TofuX509TrustManager CertificateException", tofu)
        assertEquals(listOf(serverFpHex), events)
    }

    @Test(timeout = 10_000L)
    fun missingPinRecordFailsClosed() {
        val events = mutableListOf<String>()
        val verifier = FlashPinVerifier { _, _ -> false } // no record at all

        val result = handshake(verifier, "device-a", events)

        assertNotNull("unknown peer must not be trusted (fail closed)", result.error)
        assertNotNull(findTofuFailure(result.error!!))
        // Uniform rejection reporting: consumer-side store distinguishes FirstConnect vs KeyChanged.
        assertEquals(listOf(serverFpHex), events)
    }

    @Test(timeout = 10_000L)
    fun tls13NegotiatedWhenAvailable() {
        val supported = SSLContext.getDefault().createSSLEngine().supportedProtocols
        assumeTrue("JVM lacks TLSv1.3; skipping assertion", supported.contains("TLSv1.3"))

        val events = mutableListOf<String>()
        val result = handshake(FlashPinVerifier { _, fp -> fp == serverFpHex }, "device-a", events)

        assertNull(result.error)
        assertEquals("TLSv1.3", result.protocol)
    }

    private companion object {
        const val TIMEOUT_MS = 5_000
        const val CONNECT_TIMEOUT_MS = 3_000
    }
}
