package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.remembered.RouteObserver
import com.transfer.flash.core.network.tls.SoftwareCertMaker
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.TofuPinVerifier
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-02 (sweep 2026-10-09, HARD-20), the Android twin of the desktop's `JvmDialedIdBindingTest`: an outbound
 * dial that NAMES a peer must be answered by that peer. TLS pins the key to the named id, but the HELLO device id
 * is an unauthenticated text field; before the fix a peer holding the key pinned for X could answer a dial to X
 * with `deviceId=Y` and be registered as Y. The two networks are duplicated code that has drifted before, so each
 * carries the same cases.
 */
class DialedIdBindingTest {

    private val serverIdentity = SoftwareCertMaker.newIdentity("CN=server")
    private val dialerIdentity = SoftwareCertMaker.newIdentity("CN=dialer")

    private val serverId = "device-server"
    private val dialerId = "device-dialer"
    private val namedButWrongId = "device-named-but-not-the-server"
    private val serverPins = ConcurrentHashMap<String, String>()
    private val dialerPins = ConcurrentHashMap<String, String>()
    private val networks = mutableListOf<WsFlashNetwork>()

    private class Recorder : RouteObserver {
        val authenticated = CopyOnWriteArrayList<Triple<String, String, Int>>()
        val mismatched = CopyOnWriteArrayList<Triple<String, String, Int>>()
        override fun onAuthenticated(deviceId: String, host: String, port: Int) {
            authenticated += Triple(deviceId, host, port)
        }
        override fun onIdentityMismatch(deviceId: String, host: String, port: Int) {
            mismatched += Triple(deviceId, host, port)
        }
    }

    @After
    fun tearDown() = runBlocking { networks.forEach { it.stop() } }

    private fun fingerprint(identity: SoftwareCertMaker.TestIdentity): String =
        MessageDigest.getInstance("SHA-256").digest(identity.certificate.publicKey.encoded)
            .joinToString("") { "%02X".format(it) }

    private fun tls(identity: SoftwareCertMaker.TestIdentity, pins: ConcurrentHashMap<String, String>) = TlsOptions(
        pinVerifier = TofuPinVerifier(lookupPin = { pins[it] }, recordPin = { peer, pin -> pins[peer] = pin }),
        keyManagers = identity.keyManagers,
    )

    private fun server() = WsFlashNetwork(
        context = null,
        localDeviceId = serverId,
        localFriendlyName = "Server",
        tlsOptions = tls(serverIdentity, serverPins),
    ).also { networks += it }

    private fun dialer(observer: RouteObserver) = WsFlashNetwork(
        context = null,
        localDeviceId = dialerId,
        localFriendlyName = "Dialer",
        tlsOptions = tls(dialerIdentity, dialerPins),
    ).also {
        it.routeObserver = observer
        networks += it
    }

    private suspend fun start(network: WsFlashNetwork): Int = (network.start(0) as FlashResult.Success).value

    @Test(timeout = 20_000L)
    fun `a named dial answered with a different HELLO id is refused and registers no session`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerIdentity)
        // TLS passes: the key that answers IS the key pinned for the id the dial named...
        dialerPins[namedButWrongId] = fingerprint(serverIdentity)
        val port = start(server())
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        // ...but its HELLO says it is `serverId`.
        val outcome = dialer.connectManual("127.0.0.1", port, namedButWrongId)

        assertTrue("the HELLO id must match the dialed id: $outcome", outcome is FlashResult.Failure)
        assertEquals(
            "no session may be registered",
            emptySet<String>(),
            dialer.activeSessions.value.keys.map { it.value }.toSet(),
        )
        assertEquals(listOf(Triple(namedButWrongId, "127.0.0.1", port)), recorder.mismatched)
        assertEquals(
            "an answer from the wrong device proves nothing about the route",
            emptyList<Triple<String, String, Int>>(),
            recorder.authenticated,
        )
    }

    @Test(timeout = 20_000L)
    fun `a named dial answered with the same HELLO id still succeeds`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerIdentity)
        dialerPins[serverId] = fingerprint(serverIdentity)
        val port = start(server())
        val dialer = dialer(Recorder()).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port, serverId)

        assertTrue("the dial must succeed: $outcome", outcome is FlashResult.Success)
        assertEquals(setOf(serverId), dialer.activeSessions.value.keys.map { it.value }.toSet())
    }

    @Test(timeout = 20_000L)
    fun `an unnamed dial still takes the TOFU path (ADR-040) and succeeds`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerIdentity)
        val port = start(server())
        val dialer = dialer(Recorder()).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port)

        assertTrue("first contact must still pin and succeed: $outcome", outcome is FlashResult.Success)
        assertEquals(fingerprint(serverIdentity), dialerPins[serverId])
    }

    @Test(timeout = 20_000L)
    fun `without TLS a named dial with a different HELLO id is refused as well`() = runBlocking {
        val plainServer = WsFlashNetwork(context = null, localDeviceId = serverId, localFriendlyName = "Server")
            .also { networks += it }
        val port = start(plainServer)
        val plainDialer = WsFlashNetwork(context = null, localDeviceId = dialerId, localFriendlyName = "Dialer")
            .also { networks += it; start(it) }

        val wrong = plainDialer.connectManual("127.0.0.1", port, namedButWrongId)
        val right = plainDialer.connectManual("127.0.0.1", port, serverId)

        assertTrue("a different HELLO id must be refused even without TLS: $wrong", wrong is FlashResult.Failure)
        assertTrue("the matching id must still connect: $right", right is FlashResult.Success)
    }
}
