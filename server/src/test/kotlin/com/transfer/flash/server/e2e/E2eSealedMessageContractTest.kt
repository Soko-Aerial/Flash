@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.security.enterprise.HubSealing
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Scenario (g) of the client <-> server contract (ADR-124): end-to-end sealing across the real hub. The hub carries a
 * `SEALED_V1` payload it cannot read, stores it unreadable while the recipient is offline, and stamps the sender so a
 * malicious enrolled device cannot pass itself off as someone else.
 */
class E2eSealedMessageContractTest {

    private val hubs = ArrayList<E2eHub>()
    private val devices = ArrayList<E2eDevice>()

    @AfterTest
    fun cleanUp() {
        devices.forEach { it.shutdown() }
        hubs.forEach { it.close() }
    }

    private class Party(val device: E2eDevice, val sealing: HubSealingKeyPair)

    private fun <T> FlashResult<T>.ok(): T = (this as? FlashResult.Success)?.value ?: error("expected success but got $this")

    private suspend fun party(hub: E2eHub, id: String): Party {
        val d = E2eDevice(id, hub).also { devices += it }.enrolAndConnect()
        val pair = HubSealingKeyPair.generate()
        d.client.directory.publishSealingKey(hub.url, Base64.getEncoder().encodeToString(pair.publicKeyEncoded), pair.bindingSignature(d.crypto, id)).ok()
        return Party(d, pair)
    }

    /** What the sending client does: look the recipient up in the authenticated directory and verify the binding. */
    private suspend fun recipientSealingKey(sender: E2eDevice, hub: E2eHub, recipient: String): ByteArray {
        val member = sender.client.directory.fetchDepartmentMembers(hub.url, "eng").ok().first { it.deviceId == recipient }
        val identity = Base64.getDecoder().decode(assertNotNull(member.subjectPublicKey))
        val sealing = Base64.getDecoder().decode(assertNotNull(member.sealingPublicKey, "the hub serves the published key"))
        assertTrue(HubSealingKeyPair.verifyBinding(identity, recipient, sealing, assertNotNull(member.sealingSignature)), "binding verifies on the client")
        return sealing
    }

    private suspend fun identityKeyOf(viewer: E2eDevice, hub: E2eHub, deviceId: String): ByteArray =
        Base64.getDecoder().decode(viewer.client.directory.fetchDepartmentMembers(hub.url, "eng").ok().first { it.deviceId == deviceId }.subjectPublicKey)

    private fun sealedEnvelope(sender: Party, recipient: String, recipientKey: ByteArray, id: String, text: String, sentAt: Long = System.currentTimeMillis()): FlashEnvelope {
        val payload = HubSealing.seal(sender.device.crypto, sender.device.id, recipient, recipientKey, id, sentAt, "MSG_TEXT", text)
        return FlashEnvelope(id = id, type = HubSealing.ENVELOPE_TYPE, payloadJson = payload, senderId = sender.device.id, sentAt = sentAt)
    }

    @Test
    fun aSealedMessageIsOpenedByTheRecipientAndTheOfflineMailboxHoldsNoPlaintext() = runBlocking<Unit> {
        val hub = E2eHub(limits = { com.transfer.flash.server.auth.HubRateLimits.unlimited() }).provision().start().also { hubs += it }
        val a = party(hub, "dev-a")
        val b = party(hub, "dev-b")
        val secret = "the-merger-closes-on-friday-0451"

        // B is offline: the hub must hold the message, and what it holds must be unreadable.
        b.device.client.relay.stop()
        assertTrue(eventually { !b.device.hubSeesOnline() })
        val key = recipientSealingKey(a.device, hub, "dev-b")
        val envelope = sealedEnvelope(a, "dev-b", key, "sealed-1", secret)
        assertTrue(a.device.client.relay.sendEnvelope("dev-b", envelope))
        assertEquals(AckStatus.QUEUED, assertNotNull(awaitItem(a.device.acks) { it.id == "sealed-1" }).status)

        val stored = hub.services.database.withConnection { c ->
            c.prepareStatement("SELECT payload FROM mailbox_envelopes WHERE target_device_id = ?").use { st ->
                st.setString(1, "dev-b")
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getBytes(1)) } }
            }
        }
        assertEquals(1, stored.size)
        assertTrue(indexOf(stored.single(), secret.toByteArray()) < 0, "the mailbox row contains no plaintext")
        hub.services.database.withConnection { c -> c.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") } }
        val files = hub.dataDir.walkTopDown().filter { it.isFile }.map(File::readBytes).toList()
        assertTrue(files.none { indexOf(it, secret.toByteArray()) >= 0 }, "the plaintext is nowhere in the hub's files")

        // B comes back: the mailbox replays it and B opens it with its private sealing key and A's certified identity key.
        b.device.client.relay.start()
        val delivered = assertNotNull(awaitItem(b.device.received) { it.id == "sealed-1" })
        assertEquals(HubSealing.ENVELOPE_TYPE, delivered.type)
        assertEquals("dev-a", delivered.from)
        val opened = assertNotNull(
            HubSealing.open(b.sealing, identityKeyOf(b.device, hub, delivered.from), delivered.from, "dev-b", delivered.id, delivered.sentAt, delivered.payload),
            "B opens what A sealed",
        )
        assertEquals("MSG_TEXT", opened.innerType)
        assertEquals(secret, opened.body)
    }

    @Test
    fun aPayloadChangedInTransitOrReplayedUnderAnotherIdIsRejectedByTheRecipient() = runBlocking<Unit> {
        val hub = E2eHub(limits = { com.transfer.flash.server.auth.HubRateLimits.unlimited() }).provision().start().also { hubs += it }
        val a = party(hub, "dev-a")
        val b = party(hub, "dev-b")
        val key = recipientSealingKey(a.device, hub, "dev-b")
        assertTrue(a.device.client.relay.sendEnvelope("dev-b", sealedEnvelope(a, "dev-b", key, "sealed-2", "hello")))
        val d = assertNotNull(awaitItem(b.device.received) { it.id == "sealed-2" })
        val aKey = identityKeyOf(b.device, hub, "dev-a")
        fun open(payload: String, id: String = d.id, sentAt: Long = d.sentAt, from: String = d.from) =
            HubSealing.open(b.sealing, aKey, from, "dev-b", id, sentAt, payload)

        assertEquals("hello", assertNotNull(open(d.payload)).body, "the untouched payload opens")
        val parts = d.payload.split('.').toMutableList()
        val ct = Base64.getDecoder().decode(parts[3]).also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        parts[3] = Base64.getEncoder().encodeToString(ct)
        assertNull(open(parts.joinToString(".")), "one flipped ciphertext byte is detected")
        assertNull(open(d.payload, id = "another-id"), "a hub that replays the payload under another message id gets nowhere")
        assertNull(open(d.payload, sentAt = d.sentAt + 1), "nor can it change the send time")
        assertNull(open(d.payload, from = "dev-c"), "nor can it attribute it to someone else")
    }

    @Test
    fun aMaliciousEnrolledDeviceCannotMakeTheRecipientBelieveAnotherAuthoredAMessage() = runBlocking<Unit> {
        val hub = E2eHub(limits = { com.transfer.flash.server.auth.HubRateLimits.unlimited() }).provision().start().also { hubs += it }
        val a = party(hub, "dev-a")
        val b = party(hub, "dev-b")
        val c = party(hub, "dev-c") // enrolled, hostile
        val key = recipientSealingKey(c.device, hub, "dev-b")
        val sentAt = System.currentTimeMillis()

        // C seals to B "as dev-a": the identity signature is C's, and the hub stamps the real sender on the frame.
        val payload = HubSealing.seal(c.device.crypto, "dev-a", "dev-b", key, "forged-1", sentAt, "MSG_TEXT", "pay me")
        val raw = """{"t":"FLASH_RELAY","to":"dev-b","from":"dev-a","senderId":"dev-a","id":"forged-1","type":"SEALED_V1","sentAt":$sentAt,"payload":"$payload"}"""
        assertTrue(c.device.client.relay.transport.sendRaw(raw))
        val d = assertNotNull(awaitItem(b.device.received) { it.id == "forged-1" })
        assertEquals("dev-c", d.from, "the hub, not the sender, says who sent it")
        assertNull(
            HubSealing.open(b.sealing, identityKeyOf(b.device, hub, "dev-a"), "dev-a", "dev-b", d.id, d.sentAt, d.payload),
            "claimed as dev-a: the signature is not dev-a's",
        )
        assertNull(
            HubSealing.open(b.sealing, identityKeyOf(b.device, hub, "dev-c"), "dev-c", "dev-b", d.id, d.sentAt, d.payload),
            "attributed to the real sender dev-c it still fails, because the envelope was bound to dev-a",
        )
        assertTrue(a.device.id == "dev-a")
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
