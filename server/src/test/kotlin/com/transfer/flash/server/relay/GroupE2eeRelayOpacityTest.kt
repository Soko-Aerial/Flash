@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.enterprise.GroupEpochKey
import com.transfer.flash.core.security.enterprise.GroupKeyDistPayload
import com.transfer.flash.core.security.enterprise.GroupRelaySealing
import com.transfer.flash.core.security.enterprise.HubSealing
import com.transfer.flash.core.security.enterprise.HubSealingKeyPair
import com.transfer.flash.server.mailbox.DeviceRegistry
import com.transfer.flash.server.mailbox.MailboxLimits
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.testsupport.fakeSession
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * HR10 server opacity verification: proves that the real Hub [RelayFrameRouter] + [MailboxQueue]
 * delivers [HubSealing.ENVELOPE_TYPE] (`SEALED_V1` key distribution) and [GroupRelaySealing.ENVELOPE_TYPE]
 * (`GROUP_SEALED_V1` payloads) for online and offline group members without ever possessing the plaintext
 * message body, plaintext epoch key bytes, or inner payload in RAM or SQLite `mailbox_envelopes`.
 */
class GroupE2eeRelayOpacityTest {

    private val databases = mutableListOf<ServerDatabase>()

    @After
    fun tearDown() {
        databases.forEach { runCatching { it.close() } }
    }

    private class TestCrypto : FlashCrypto {
        private val pair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair()

        override val identityPublicKeyEncoded: ByteArray get() = pair.public.encoded

        override fun sign(data: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").apply {
                initSign(pair.private)
                update(data)
            }.sign()

        override fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean = try {
            val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
            Signature.getInstance("SHA256withECDSA").apply {
                initVerify(key)
                update(data)
            }.verify(signature)
        } catch (_: Throwable) {
            false
        }

        override fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair = throw UnsupportedOperationException()
        override fun ecdhSessionKey(selfEphemeral: FlashEcKeyPair, peerEphemeralPublicKey: ByteArray): ByteArray =
            throw UnsupportedOperationException()
    }

    @OptIn(ExperimentalEncodingApi::class)
    @Test
    fun hubRouterAndOfflineMailboxStoreOnlyCiphertextForGroupE2eeMessages() = runBlocking<Unit> {
        val db = ServerDatabase.inMemory(poolSize = 1).also { databases += it }
        val nowMs = 1_750_000_000_000L
        val mailbox = MailboxQueue(db, MailboxLimits(), registry = DeviceRegistry.AcceptAll, clock = { nowMs })
        val router = RelayFrameRouter(
            connectionPool = RelayConnectionPool(),
            mailboxQueue = mailbox,
            clock = { nowMs },
        )

        val aliceCrypto = TestCrypto()
        val bobCrypto = TestCrypto()
        val bobSealing = HubSealingKeyPair.generate()

        val (aliceSession, aliceWs) = fakeSession("alice", Channel.UNLIMITED, 64)
        router.register(aliceSession)

        // Bob is OFFLINE initially so the Hub persists key distribution and group ciphertext into SQLite
        val epochBytes = ByteArray(32) { i -> (0x31 + i).toByte() }
        val keyDistWire = GroupRelaySealing.encodeKeyDist(
            GroupKeyDistPayload(
                version = GroupRelaySealing.FORMAT_VERSION,
                groupId = "g-hr10",
                epoch = 1L,
                keyId = "k-1",
                keyBytes = epochBytes.copyOf(),
                rosterVersion = 1L,
                memberDeviceIds = setOf("alice", "bob"),
                creatorDeviceId = "alice",
                createdAtMs = nowMs,
            ),
        )
        val keyDistMsgId = "keydist-1"
        val keyDistSentAt = nowMs
        val sealedKeyDist = assertNotNull(
            HubSealing.seal(
                crypto = aliceCrypto,
                senderDeviceId = "alice",
                recipientDeviceId = "bob",
                recipientSealingPublicKey = bobSealing.publicKeyEncoded,
                msgId = keyDistMsgId,
                sentAt = keyDistSentAt,
                innerType = GroupRelaySealing.KEY_DIST_INNER_TYPE,
                body = keyDistWire,
            ),
        )

        val plaintextSecret = "executive acquisition offer $42M confidential"
        val epochKey = GroupEpochKey(
            groupId = "g-hr10",
            epoch = 1L,
            keyId = "k-1",
            keyBytes = epochBytes.copyOf(),
            rosterVersion = 1L,
            memberDeviceIds = setOf("alice", "bob"),
            creatorDeviceId = "alice",
            createdAtMs = nowMs,
            canSend = true,
        )
        val groupMsgId = "grpmsg-1"
        val groupSentAt = nowMs + 10L
        val sealedGroupEnvelope = assertNotNull(
            GroupRelaySealing.seal(
                crypto = aliceCrypto,
                epochKey = epochKey,
                senderDeviceId = "alice",
                msgId = groupMsgId,
                sentAt = groupSentAt,
                innerType = "GROUP_FRAME",
                body = "{\"groupId\":\"g-hr10\",\"text\":\"$plaintextSecret\"}",
            ),
        )

        router.processFrame(
            aliceSession,
            RelayFrameCodec.encode(
                RelayFrame.Send(
                    to = "bob",
                    id = keyDistMsgId,
                    type = HubSealing.ENVELOPE_TYPE,
                    sentAt = keyDistSentAt,
                    payload = sealedKeyDist,
                ),
            ),
        )
        router.processFrame(
            aliceSession,
            RelayFrameCodec.encode(
                RelayFrame.Send(
                    to = "bob",
                    id = groupMsgId,
                    type = GroupRelaySealing.ENVELOPE_TYPE,
                    sentAt = groupSentAt,
                    payload = sealedGroupEnvelope,
                ),
            ),
        )

        assertEquals(AckStatus.QUEUED, aliceWs.nextOf<RelayFrame.Ack>().status)
        assertEquals(AckStatus.QUEUED, aliceWs.nextOf<RelayFrame.Ack>().status)
        assertEquals(2L, mailbox.queuedCount())

        // Inspect SQLite mailbox_envelopes directly: Hub has zero plaintext secret or epoch key bytes
        val epochB64 = Base64.encode(epochBytes)
        db.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT kind, msg_id, payload FROM mailbox_envelopes").use { rs ->
                    var count = 0
                    while (rs.next()) {
                        count++
                        val kind = rs.getString("kind")
                        val payloadStr = rs.getBytes("payload").decodeToString()
                        assertTrue(kind == HubSealing.ENVELOPE_TYPE || kind == GroupRelaySealing.ENVELOPE_TYPE)
                        assertFalse(plaintextSecret in payloadStr)
                        assertFalse("acquisition" in payloadStr)
                        assertFalse(epochB64 in payloadStr)
                    }
                    assertEquals(2, count)
                }
            }
        }

        // Bob comes online and drains offline mailbox over the relay
        val (bobSession, bobWs) = fakeSession("bob", Channel.UNLIMITED, 64)
        bobSession.draining = true
        router.register(bobSession)
        router.requestDrain(bobSession)

        val d1 = bobWs.nextOf<RelayFrame.Deliver>()
        val d2 = bobWs.nextOf<RelayFrame.Deliver>()
        val deliveries = listOf(d1, d2).associateBy { it.type }

        val keyDistDelivery = assertNotNull(deliveries[HubSealing.ENVELOPE_TYPE])
        val openedKeyDist = HubSealing.open(
            recipientKey = bobSealing,
            senderIdentityPublicKey = aliceCrypto.identityPublicKeyEncoded,
            senderDeviceId = "alice",
            recipientDeviceId = "bob",
            msgId = keyDistDelivery.id,
            sentAt = keyDistDelivery.sentAt,
            payload = keyDistDelivery.payload,
        )
        assertNotNull(openedKeyDist)
        assertEquals(GroupRelaySealing.KEY_DIST_INNER_TYPE, openedKeyDist.innerType)
        val decodedDist = assertNotNull(GroupRelaySealing.decodeKeyDist(openedKeyDist.body))
        assertTrue(epochBytes.contentEquals(decodedDist.keyBytes))

        val groupDelivery = assertNotNull(deliveries[GroupRelaySealing.ENVELOPE_TYPE])
        val unsealedGroup = GroupRelaySealing.open(
            crypto = bobCrypto,
            epochKey = GroupEpochKey(
                groupId = decodedDist.groupId,
                epoch = decodedDist.epoch,
                keyId = decodedDist.keyId,
                keyBytes = decodedDist.keyBytes,
                rosterVersion = decodedDist.rosterVersion,
                memberDeviceIds = decodedDist.memberDeviceIds,
                creatorDeviceId = decodedDist.creatorDeviceId,
                createdAtMs = decodedDist.createdAtMs,
                canSend = true,
            ),
            senderIdentityPublicKey = aliceCrypto.identityPublicKeyEncoded,
            senderDeviceId = "alice",
            expectedGroupId = "g-hr10",
            msgId = groupDelivery.id,
            sentAt = groupDelivery.sentAt,
            payload = groupDelivery.payload,
        )
        assertNotNull(unsealedGroup)
        assertTrue(plaintextSecret in unsealedGroup.body)
    }
}
