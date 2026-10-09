package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.remembered.RouteObserver
import com.transfer.flash.core.network.tls.FlashCertMaker
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.TofuPinVerifier
import java.security.KeyPair
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * R-02 (sweep 2026-10-09, HARD-20): an outbound dial that NAMES a peer must be answered by that peer.
 *
 * The TLS pin binds the key to the NAMED id, but the HELLO device id is an unauthenticated text field. Before the
 * fix a peer holding the key pinned for X could answer a dial to X with `deviceId=Y` and be registered as Y.
 * The inbound side already enforced the binding (`inboundIdentityFailure`); this pins the outbound side.
 * Twin of the Android `DialedIdBindingTest`: the two networks are duplicated code and must carry the same cases.
 */
class JvmDialedIdBindingTest {

    private val serverKeys = FlashCertMaker.newEcKeyPair()
    private val dialerKeys = FlashCertMaker.newEcKeyPair()

    private val serverId = "device-server"
    private val dialerId = "device-dialer"
    private val namedButWrongId = "device-named-but-not-the-server"
    private val serverPins = ConcurrentHashMap<String, String>()
    private val dialerPins = ConcurrentHashMap<String, String>()
    private val networks = mutableListOf<JvmWsFlashNetwork>()

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

    @AfterTest
    fun tearDown() = runBlocking { networks.forEach { it.stop() } }

    private fun fingerprint(keys: KeyPair): String =
        MessageDigest.getInstance("SHA-256").digest(keys.public.encoded).joinToString("") { "%02X".format(it) }

    private fun tls(id: String, keys: KeyPair, pins: ConcurrentHashMap<String, String>) = TlsOptions(
        pinVerifier = TofuPinVerifier(lookupPin = { pins[it] }, recordPin = { peer, pin -> pins[peer] = pin }),
        keyManagers = FlashCertMaker.createKeyManagers(keys, cn = "CN=$id"),
    )

    private fun server() = JvmWsFlashNetwork(
        localDeviceId = serverId,
        localFriendlyName = "Server",
        tlsOptions = tls(serverId, serverKeys, serverPins),
    ).also { networks += it }

    private fun dialer(observer: RouteObserver) = JvmWsFlashNetwork(
        localDeviceId = dialerId,
        localFriendlyName = "Dialer",
        tlsOptions = tls(dialerId, dialerKeys, dialerPins),
    ).also {
        it.routeObserver = observer
        networks += it
    }

    private suspend fun start(network: JvmWsFlashNetwork): Int = (network.start(0) as FlashResult.Success).value

    @Test
    fun `a named dial answered with a different HELLO id is refused and registers no session`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerKeys)
        // TLS passes: the key that answers IS the key pinned for the id the dial named...
        dialerPins[namedButWrongId] = fingerprint(serverKeys)
        val port = start(server())
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        // ...but its HELLO says it is `serverId`.
        val outcome = dialer.connectManual("127.0.0.1", port, namedButWrongId)

        assertTrue(outcome is FlashResult.Failure, "the HELLO id must match the dialed id: $outcome")
        assertEquals(emptySet(), dialer.activeSessions.value.keys.map { it.value }.toSet(), "no session may be registered")
        assertEquals(listOf(Triple(namedButWrongId, "127.0.0.1", port)), recorder.mismatched)
        assertEquals(emptyList(), recorder.authenticated, "an answer from the wrong device proves nothing about the route")
    }

    @Test
    fun `a named dial answered with the same HELLO id still succeeds`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerKeys)
        dialerPins[serverId] = fingerprint(serverKeys)
        val port = start(server())
        val dialer = dialer(Recorder()).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port, serverId)

        assertTrue(outcome is FlashResult.Success, "the dial must succeed: $outcome")
        assertEquals(setOf(serverId), dialer.activeSessions.value.keys.map { it.value }.toSet())
    }

    @Test
    fun `an unnamed dial still takes the TOFU path (ADR-040) and succeeds`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerKeys)
        val port = start(server())
        val dialer = dialer(Recorder()).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port)

        assertTrue(outcome is FlashResult.Success, "first contact must still pin and succeed: $outcome")
        assertEquals(fingerprint(serverKeys), dialerPins[serverId], "first contact records the pin for the HELLO id")
    }

    @Test
    fun `without TLS a named dial with a different HELLO id is refused as well`() = runBlocking {
        val plainServer = JvmWsFlashNetwork(localDeviceId = serverId, localFriendlyName = "Server").also { networks += it }
        val port = start(plainServer)
        val plainDialer = JvmWsFlashNetwork(localDeviceId = dialerId, localFriendlyName = "Dialer")
            .also { networks += it; start(it) }

        val wrong = plainDialer.connectManual("127.0.0.1", port, namedButWrongId)
        val right = plainDialer.connectManual("127.0.0.1", port, serverId)

        assertTrue(wrong is FlashResult.Failure, "a different HELLO id must be refused even without TLS: $wrong")
        assertTrue(right is FlashResult.Success, "the matching id must still connect: $right")
    }
}
