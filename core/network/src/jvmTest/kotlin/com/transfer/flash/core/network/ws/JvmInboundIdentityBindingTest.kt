package com.transfer.flash.core.network.ws

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.network.tls.FlashCertMaker
import com.transfer.flash.core.network.tls.FlashPinVerifier
import com.transfer.flash.core.network.tls.TlsOptions
import com.transfer.flash.core.network.tls.TofuPinVerifier
import java.security.KeyPair
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Audit S1 regression for the desktop twin (`JvmWsFlashNetwork`): same scenarios as the Android
 * `InboundIdentityBindingTest`, over real TLS with the production `TofuPinVerifier`.
 */
class JvmInboundIdentityBindingTest {

    private val victimKeys = FlashCertMaker.newEcKeyPair()
    private val contactKeys = FlashCertMaker.newEcKeyPair()
    private val attackerKeys = FlashCertMaker.newEcKeyPair()

    private val contactId = "device-contact"
    private val victimId = "device-victim"
    private val victimPins = ConcurrentHashMap<String, String>()
    private val networks = mutableListOf<JvmWsFlashNetwork>()

    @AfterTest
    fun tearDown() = runBlocking { networks.forEach { it.stop() } }

    private fun fingerprint(keys: KeyPair): String =
        MessageDigest.getInstance("SHA-256").digest(keys.public.encoded).joinToString("") { "%02X".format(it) }

    private fun victim() = JvmWsFlashNetwork(
        localDeviceId = victimId,
        localFriendlyName = "Victim",
        tlsOptions = TlsOptions(
            pinVerifier = TofuPinVerifier(lookupPin = { victimPins[it] }, recordPin = { id, pin -> victimPins[id] = pin }),
            keyManagers = FlashCertMaker.createKeyManagers(victimKeys, cn = "CN=$victimId"),
        ),
    ).also { networks += it }

    private fun dialer(keys: KeyPair, claimedId: String) = JvmWsFlashNetwork(
        localDeviceId = claimedId,
        localFriendlyName = "Dialer",
        tlsOptions = TlsOptions(
            pinVerifier = FlashPinVerifier { _, _ -> true },
            keyManagers = FlashCertMaker.createKeyManagers(keys, cn = "CN=$claimedId"),
        ),
    ).also { networks += it }

    private suspend fun start(network: JvmWsFlashNetwork): Int = (network.start(0) as FlashResult.Success).value

    @Test
    fun attackerClaimingAPairedContactsIdIsRefused() = runBlocking {
        victimPins[contactId] = fingerprint(contactKeys)
        val victim = victim()
        val port = start(victim)
        val attacker = dialer(attackerKeys, contactId)
        start(attacker)

        val outcome = attacker.connectManual("127.0.0.1", port, victimId)

        assertTrue(outcome is FlashResult.Failure, "the attacker must not get a session: $outcome")
        delay(300)
        assertFalse(victim.activeSessions.value.containsKey(FlashDeviceId(contactId)))
        assertEquals(fingerprint(contactKeys), victimPins[contactId])
    }

    @Test
    fun theRealContactWithThePinnedKeyIsAdmitted() = runBlocking {
        victimPins[contactId] = fingerprint(contactKeys)
        val victim = victim()
        val port = start(victim)
        val contact = dialer(contactKeys, contactId)
        start(contact)

        assertTrue(contact.connectManual("127.0.0.1", port, victimId) is FlashResult.Success)
        var admitted = false
        repeat(40) {
            if (victim.activeSessions.value.containsKey(FlashDeviceId(contactId))) admitted = true
            if (!admitted) delay(50)
        }
        assertTrue(admitted, "victim must register the genuine contact")
    }
}
