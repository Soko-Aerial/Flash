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
 * DR1 (ADR-047), the Android twin of the desktop's `JvmRouteObserverTest`: what [WsFlashNetwork]
 * tells its [RouteObserver] over real loopback TLS with the production [TofuPinVerifier]. The two
 * networks are duplicated code that has drifted before, so each carries the same cases.
 */
class RouteObserverTest {

    private val serverIdentity = SoftwareCertMaker.newIdentity("CN=server")
    private val dialerIdentity = SoftwareCertMaker.newIdentity("CN=dialer")
    private val impostorIdentity = SoftwareCertMaker.newIdentity("CN=impostor")

    private val serverId = "device-server"
    private val dialerId = "device-dialer"
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

    private fun server(identity: SoftwareCertMaker.TestIdentity = serverIdentity) = WsFlashNetwork(
        context = null,
        localDeviceId = serverId,
        localFriendlyName = "Server",
        tlsOptions = tls(identity, serverPins),
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
    fun `a named dial that proves the pinned key reports the route it used`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerIdentity)
        dialerPins[serverId] = fingerprint(serverIdentity)
        val port = start(server())
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port, serverId)

        assertTrue("the dial must succeed: $outcome", outcome is FlashResult.Success)
        assertEquals(listOf(Triple(serverId, "127.0.0.1", port)), recorder.authenticated)
        assertEquals(emptyList<Triple<String, String, Int>>(), recorder.mismatched)
    }

    @Test(timeout = 20_000L)
    fun `an unnamed dial is reported once HELLO names the peer and the pin holds`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerIdentity)
        dialerPins[serverId] = fingerprint(serverIdentity)
        val port = start(server())
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port)

        assertTrue("the dial must succeed: $outcome", outcome is FlashResult.Success)
        assertEquals(listOf(Triple(serverId, "127.0.0.1", port)), recorder.authenticated)
    }

    @Test(timeout = 20_000L)
    fun `a named dial answered by the wrong key reports a mismatch and no route`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerIdentity)
        dialerPins[serverId] = fingerprint(serverIdentity)
        val port = start(server(identity = impostorIdentity))
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port, serverId)

        assertTrue("the impostor must not get a session: $outcome", outcome is FlashResult.Failure)
        assertEquals(listOf(Triple(serverId, "127.0.0.1", port)), recorder.mismatched)
        assertEquals(emptyList<Triple<String, String, Int>>(), recorder.authenticated)
    }

    @Test(timeout = 20_000L)
    fun `an unnamed dial answered by the wrong key reports a mismatch after HELLO`() = runBlocking {
        serverPins[dialerId] = fingerprint(dialerIdentity)
        dialerPins[serverId] = fingerprint(serverIdentity)
        val port = start(server(identity = impostorIdentity))
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", port)

        assertTrue("the impostor must not get a session: $outcome", outcome is FlashResult.Failure)
        assertEquals(listOf(Triple(serverId, "127.0.0.1", port)), recorder.mismatched)
        assertEquals(emptyList<Triple<String, String, Int>>(), recorder.authenticated)
    }

    @Test(timeout = 20_000L)
    fun `a dial to a closed port reports nothing`() = runBlocking {
        val recorder = Recorder()
        val dialer = dialer(recorder).also { start(it) }

        val outcome = dialer.connectManual("127.0.0.1", 1, serverId)

        assertTrue(outcome is FlashResult.Failure)
        assertEquals(emptyList<Triple<String, String, Int>>(), recorder.mismatched)
        assertEquals(emptyList<Triple<String, String, Int>>(), recorder.authenticated)
    }
}
