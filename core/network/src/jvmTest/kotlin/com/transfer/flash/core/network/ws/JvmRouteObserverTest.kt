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
 * DR1 (ADR-047): what [JvmWsFlashNetwork] tells its [RouteObserver], over real loopback TLS with the
 * production [TofuPinVerifier]. The policy that consumes these reports is `RememberedRoutesTest`;
 * this pins the part that class cannot see: that a route is reported only when the dial proved the
 * key, and that a wrong key is reported as a mismatch on both paths (named dial, and HELLO after an
 * unnamed one).
 */
class JvmRouteObserverTest {

    private val serverKeys = FlashCertMaker.newEcKeyPair()
    private val dialerKeys = FlashCertMaker.newEcKeyPair()
    private val impostorKeys = FlashCertMaker.newEcKeyPair()

    private val serverId = "device-server"
    private val dialerId = "device-dialer"
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

    private fun server(keys: KeyPair = serverKeys) = JvmWsFlashNetwork(
        localDeviceId = serverId,
        localFriendlyName = "Server",
        tlsOptions = tls(serverId, keys, serverPins),
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
    fun `a named dial that proves the pinned key reports the route it used`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerKeys)
        dialerPins[serverId] = fingerprint(serverKeys)
        val port = start(server())
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port, serverId)

        assertTrue(outcome is FlashResult.Success, "the dial must succeed: $outcome")
        assertEquals(listOf(Triple(serverId, "127.0.0.1", port)), recorder.authenticated)
        assertEquals(emptyList(), recorder.mismatched)
    }

    @Test
    fun `an unnamed dial is reported once HELLO names the peer and the pin holds`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerKeys)
        dialerPins[serverId] = fingerprint(serverKeys)
        val port = start(server())
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port)

        assertTrue(outcome is FlashResult.Success, "the dial must succeed: $outcome")
        assertEquals(listOf(Triple(serverId, "127.0.0.1", port)), recorder.authenticated)
    }

    @Test
    fun `a named dial answered by the wrong key reports a mismatch and no route`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerKeys)
        // The dialer pinned the server's real key, but a different key now answers at this address.
        dialerPins[serverId] = fingerprint(serverKeys)
        val port = start(server(keys = impostorKeys))
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port, serverId)

        assertTrue(outcome is FlashResult.Failure, "the impostor must not get a session: $outcome")
        assertEquals(listOf(Triple(serverId, "127.0.0.1", port)), recorder.mismatched)
        assertEquals(emptyList(), recorder.authenticated)
    }

    @Test
    fun `an unnamed dial answered by the wrong key reports a mismatch after HELLO`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerKeys)
        dialerPins[serverId] = fingerprint(serverKeys)
        val port = start(server(keys = impostorKeys))
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port)

        assertTrue(outcome is FlashResult.Failure, "the impostor must not get a session: $outcome")
        assertEquals(listOf(Triple(serverId, "127.0.0.1", port)), recorder.mismatched)
        assertEquals(emptyList(), recorder.authenticated)
    }

    @Test
    fun `a dial to a closed port reports nothing`() = runBlocking {
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", 1, serverId)

        assertTrue(outcome is FlashResult.Failure)
        assertEquals(emptyList(), recorder.mismatched, "a refused connection says nothing about identity")
        assertEquals(emptyList(), recorder.authenticated)
    }

    @Test
    fun `without TLS nothing was proven, so nothing is reported`() = runBlocking {
        val plainServer = JvmWsFlashNetwork(localDeviceId = serverId, localFriendlyName = "Server").also { networks += it }
        val port = start(plainServer)
        val recorder = Recorder()
        val plainDialer = JvmWsFlashNetwork(localDeviceId = dialerId, localFriendlyName = "Dialer")
            .also {
                it.routeObserver = recorder
                networks += it
                start(it)
            }

        val outcome = plainDialer.connectManual("127.0.0.1", port)

        assertTrue(outcome is FlashResult.Success, "the plain dial must succeed: $outcome")
        assertEquals(emptyList(), recorder.authenticated)
    }
}
