package com.transfer.flash.core.network.ws

import com.transfer.flash.core.network.tls.FlashPinVerifier
import com.transfer.flash.core.network.tls.SoftwareCertMaker
import com.transfer.flash.core.network.tls.TlsOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Full [WsTransferServer]+[WsTransferClient] pair over REAL TLS on loopback (C4.1 stream A).
 * Uses test-only `SoftwareCertMaker` identities + matching-pin verifiers; targets < 15 s total.
 *
 * NOTE: runs on the JVM, so the Android `Log` calls inside the WS classes are exercised through
 * the no-op-safe `WsLog` shim and the client is constructed with a null Context (loopback
 * routing only — production always passes a real Context and gets Wi-Fi/Ethernet pinning).
 */
class SecureWsTransferLoopbackTest {

    private val serverIdentity = SoftwareCertMaker.newIdentity("CN=flash-ws-server")
    private val clientIdentity = SoftwareCertMaker.newIdentity("CN=flash-ws-client")
    private val serverFpHex = fingerprintHex(serverIdentity)
    private val deviceA = "device-a"

    private var server: WsTransferServer? = null
    private var client: WsTransferClient? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun fingerprintHex(identity: SoftwareCertMaker.TestIdentity): String =
        MessageDigest.getInstance("SHA-256").digest(identity.certificate.publicKey.encoded)
            .joinToString("") { "%02X".format(it) }

    private class RecordingListener(
        val received: MutableMap<WsConnection, MutableList<String>> = mutableMapOf(),
    ) : WsConnection.Listener {
        override fun onTextMessage(connection: WsConnection, text: String) {
            synchronized(received) { received.getOrPut(connection) { mutableListOf() }.add(text) }
        }

        override fun onBinaryMessage(connection: WsConnection, data: ByteArray) = Unit

        override fun onConnectionClosed(connection: WsConnection, reason: String) = Unit
    }

    @Test(timeout = 15_000L)
    fun `text message round-trips over real TLS between server and client`() = runBlocking {
        val serverListener = RecordingListener()
        var accepted: WsConnection? = null

        val tlsServer = TlsOptions(
            pinVerifier = FlashPinVerifier { _, _ -> true }, // server does not request client-auth
            keyManagers = serverIdentity.keyManagers,
        )
        server = WsTransferServer(
            connectionListener = serverListener,
            onConnection = { connection ->
                accepted = connection
                connection.start()
            },
            tls = tlsServer,
        ).also { it.start() }
        assertTrue(server!!.listenPort > 0)

        val clientListener = RecordingListener()
        val tlsClient = TlsOptions(
            pinVerifier = FlashPinVerifier { _, fp -> fp == serverFpHex },
            keyManagers = clientIdentity.keyManagers,
            expectedDeviceId = deviceA,
        )
        client = WsTransferClient(null, clientListener, tlsClient)
        val connection = client!!.connect("127.0.0.1", server!!.listenPort)
        connection.start()

        // Server observed the (decrypted) client message…
        connection.sendText("ping-over-tls")
        awaitUntil("server accept") { accepted != null }
        awaitUntil("server receive") {
            synchronized(serverListener.received) {
                serverListener.received[accepted!!]?.singleOrNull() == "ping-over-tls"
            }
        }
        // …and the client observes the encrypted server reply.
        accepted!!.sendText("pong-over-tls")
        awaitUntil("client receive") {
            synchronized(clientListener.received) {
                clientListener.received[connection]?.singleOrNull() == "pong-over-tls"
            }
        }
    }

    @Test(timeout = 15_000L)
    fun `wrong-pin verifier fails the connect closed with certificate error`() = runBlocking {
        val serverListener = RecordingListener()

        server = WsTransferServer(
            connectionListener = serverListener,
            onConnection = { it.start() },
            tls = TlsOptions(
                pinVerifier = FlashPinVerifier { _, _ -> true },
                keyManagers = serverIdentity.keyManagers,
            ),
        ).also { it.start() }

        val wrongPinClient = WsTransferClient(
            null,
            RecordingListener(),
            TlsOptions(
                pinVerifier = FlashPinVerifier { _, _ -> false }, // pinned to some OTHER key
                keyManagers = clientIdentity.keyManagers,
                expectedDeviceId = deviceA,
            ),
        )

        try {
            wrongPinClient.connect("127.0.0.1", server!!.listenPort)
            fail("connect must fail closed when the presented certificate does not match the pin")
        } catch (error: Exception) {
            val chain = generateSequence<Throwable>(error) { it.cause }.toList()
            val certIndicator = chain.any { failure ->
                failure is java.security.cert.CertificateException ||
                    failure.message?.contains("TOFU", ignoreCase = true) == true
            } || chain.any { failure ->
                failure is javax.net.ssl.SSLException &&
                    failure.message.orEmpty().let {
                        it.contains("cert", ignoreCase = true) ||
                            it.contains("handshake", ignoreCase = true) ||
                            it.contains("alert", ignoreCase = true)
                    }
            }
            assertTrue(
                "expected a cert/pin indicator in: " + chain.joinToString(" <- ") { "${it::class.java.simpleName}: ${it.message}" },
                certIndicator,
            )
        }
    }

    @Test(timeout = 15_000L)
    fun `no-tls construction path still compiles and works unchanged`() = runBlocking {
        // R4 guard: existing plaintext callers are untouched by the additive TLS options.
        val listener = RecordingListener()
        val started = CountDownLatch(1)
        var acceptedConn: WsConnection? = null
        server = WsTransferServer(listener, { connection ->
            acceptedConn = connection
            connection.start()
            started.countDown()
        }).also { it.start() }

        client = WsTransferClient(null, listener)
        val conn = client!!.connect("127.0.0.1", server!!.listenPort)
        conn.start()
        conn.sendText("plaintext-dev-mode")
        assertTrue(started.await(5, TimeUnit.SECONDS))
        awaitUntil("plain receive") {
            synchronized(listener.received) {
                listener.received[acceptedConn!!]?.singleOrNull() == "plaintext-dev-mode"
            }
        }
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out waiting for $what")
            Thread.sleep(20)
        }
    }
}
