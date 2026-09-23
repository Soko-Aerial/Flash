package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.tls.FlashPinVerifier
import com.transfer.flash.core.network.tls.SoftwareCertMaker
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.TofuPinVerifier
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit S1 regression: an inbound peer must prove, with its TLS certificate, the id its HELLO claims.
 *
 * Before the fix the server never requested a client certificate and took the HELLO `deviceId` at face
 * value, so any LAN device could claim a paired contact's id (broadcast in mDNS TXT) and replace that
 * contact's session. These tests run real TLS over loopback with the production `TofuPinVerifier`.
 */
class InboundIdentityBindingTest {

    private val victimIdentity = SoftwareCertMaker.newIdentity("CN=victim")
    private val contactIdentity = SoftwareCertMaker.newIdentity("CN=contact")
    private val attackerIdentity = SoftwareCertMaker.newIdentity("CN=attacker")

    private val contactId = "device-contact"
    private val victimId = "device-victim"

    private val networks = mutableListOf<WsFlashNetwork>()

    @After
    fun tearDown() = runBlocking { networks.forEach { it.stop() } }

    private fun fingerprint(identity: SoftwareCertMaker.TestIdentity): String =
        MessageDigest.getInstance("SHA-256").digest(identity.certificate.publicKey.encoded)
            .joinToString("") { "%02X".format(it) }

    /** Victim's trust store: already paired with the contact, so the contact's key is pinned. */
    private val victimPins = ConcurrentHashMap<String, String>()

    private fun victim(): WsFlashNetwork = WsFlashNetwork(
        context = null,
        localDeviceId = victimId,
        localFriendlyName = "Victim",
        tlsOptions = TlsOptions(
            pinVerifier = TofuPinVerifier(
                lookupPin = { victimPins[it] },
                recordPin = { id, pin -> victimPins[id] = pin },
            ),
            keyManagers = victimIdentity.keyManagers,
        ),
    ).also { networks += it }

    /** A dialer that accepts whatever server certificate it sees and claims [claimedId] in HELLO. */
    private fun dialer(identity: SoftwareCertMaker.TestIdentity, claimedId: String): WsFlashNetwork = WsFlashNetwork(
        context = null,
        localDeviceId = claimedId,
        localFriendlyName = "Dialer",
        tlsOptions = TlsOptions(
            pinVerifier = FlashPinVerifier { _, _ -> true },
            keyManagers = identity.keyManagers,
        ),
    ).also { networks += it }

    private suspend fun start(network: WsFlashNetwork): Int =
        (network.start(0) as FlashResult.Success).value

    @Test(timeout = 20_000L)
    fun `attacker claiming a paired contact's id is refused`() = runBlocking {
        victimPins[contactId] = fingerprint(contactIdentity)
        val victim = victim()
        val port = start(victim)

        val attacker = dialer(attackerIdentity, claimedId = contactId)
        start(attacker)
        val outcome = attacker.connectManual("127.0.0.1", port, victimId)

        assertTrue("the attacker must not get a session: $outcome", outcome is FlashResult.Failure)
        delay(300)
        assertFalse(
            "victim must not register the forged contact",
            victim.activeSessions.value.containsKey(FlashDeviceId(contactId)),
        )
        assertEquals("the contact's pin must be untouched", fingerprint(contactIdentity), victimPins[contactId])
    }

    @Test(timeout = 20_000L)
    fun `the real contact with the pinned key is admitted`() = runBlocking {
        victimPins[contactId] = fingerprint(contactIdentity)
        val victim = victim()
        val port = start(victim)

        val contact = dialer(contactIdentity, claimedId = contactId)
        start(contact)
        val outcome = contact.connectManual("127.0.0.1", port, victimId)

        assertTrue("the genuine contact must connect: $outcome", outcome is FlashResult.Success)
        var admitted = false
        repeat(40) {
            if (victim.activeSessions.value.containsKey(FlashDeviceId(contactId))) admitted = true
            if (!admitted) delay(50)
        }
        assertTrue("victim must register the genuine contact", admitted)
    }

    @Test(timeout = 20_000L)
    fun `first contact from an unknown device is pinned (TOFU), then enforced`() = runBlocking {
        val victim = victim()
        val port = start(victim)

        val newcomer = dialer(contactIdentity, claimedId = "device-new")
        start(newcomer)
        assertTrue(newcomer.connectManual("127.0.0.1", port, victimId) is FlashResult.Success)
        assertEquals("first contact records the key", fingerprint(contactIdentity), victimPins["device-new"])

        // A different key now claiming the same id is refused.
        val impostor = dialer(attackerIdentity, claimedId = "device-new")
        start(impostor)
        val outcome = impostor.connectManual("127.0.0.1", port, victimId)
        assertTrue("a second key for a pinned id must be refused: $outcome", outcome is FlashResult.Failure)
    }
}
