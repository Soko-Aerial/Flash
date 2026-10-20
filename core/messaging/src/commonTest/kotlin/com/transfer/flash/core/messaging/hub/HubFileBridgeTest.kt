@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.messaging.hub

import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.enterprise.HubPeerStanding
import com.transfer.flash.core.enterprise.files.HubFileTicket
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.HubFileFrame
import com.transfer.flash.core.messaging.protocol.HubFileFrameCodec
import com.transfer.flash.core.messaging.protocol.MessageWireFrame
import com.transfer.flash.core.security.enterprise.HubFileKey
import com.transfer.flash.core.security.enterprise.HubSealing
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** HUBFILE-U15..U18 (ADR-216): a hub file ticket travels sealed or not at all, in both directions. */
class HubFileBridgeTest {

    private val relay = FakeHubRelay()

    private class FileSink(private val takes: Boolean = true) : HubInboundSink {
        val files = mutableListOf<Pair<HubFileFrame, String>>()
        override suspend fun direct(frame: MessageWireFrame, from: String) = Unit
        override suspend fun group(frame: GroupWireFrame, from: String) = Unit
        override suspend fun hubFile(frame: HubFileFrame, from: String): Boolean {
            if (takes) files += frame to from
            return takes
        }
    }

    private fun bridge(
        sealing: HubSealingPort? = ScriptedHubSealing(),
        member: Boolean = true,
        content: Boolean = true,
    ) = HubChatBridge(
        localDeviceId = "me",
        port = relay,
        contentViaHub = { content },
        sealing = sealing,
        standing = { if (member) HubPeerStanding.MEMBER else HubPeerStanding.UNKNOWN },
    )

    private val key = HubFileKey.generate()

    private fun frame(sender: String = "alice") = HubFileFrame(
        localId = "m-1",
        senderId = sender,
        senderName = "Alice",
        sentAt = 1_000L,
        ticket = HubFileTicket(
            fileId = "0123456789abcdef0123456789abcdef",
            key = key,
            cipherSha256 = "ab".repeat(32),
            plainBytes = 10L,
            cipherChunkBytes = 1_048_576,
            chunkCount = 1,
            expiresAtMs = 5_000L,
        ),
        fileName = "a.txt",
        mime = "text/plain",
        meta = "",
    )

    private fun inbound(type: String, payload: String, from: String = "alice") =
        HubInbound(from = from, id = "m-1", type = type, sentAt = 1_000L, payload = payload)

    @Test
    fun `HUBFILE-U15 the route needs a member and a sealing key, and the readable opt-in does not count`() = runTest {
        assertEquals(HubFileRoute.READY, bridge().fileRoute("alice"))
        assertEquals(HubFileRoute.UNAVAILABLE, bridge(member = false).fileRoute("alice"))
        relay.usable = false
        assertEquals(HubFileRoute.UNAVAILABLE, bridge().fileRoute("alice"))
        relay.usable = true
        assertEquals(HubFileRoute.NEEDS_SEALING, bridge(sealing = null, content = true).fileRoute("alice"))
        for (status in listOf(HubSealingStatus.NOT_PUBLISHED, HubSealingStatus.KEY_CHANGED, HubSealingStatus.IDENTITY_MISMATCH)) {
            assertEquals(HubFileRoute.NEEDS_SEALING, bridge(sealing = ScriptedHubSealing(status), content = true).fileRoute("alice"), "$status")
        }
    }

    @Test
    fun `HUBFILE-U16 a ticket is sent sealed and never readable`() = runTest {
        assertTrue(bridge().sendHubFile("alice", "m-1", 1_000L, frame(sender = "me")))
        val sent = relay.sent.single()
        assertEquals(HubSealing.ENVELOPE_TYPE, sent.type)
        assertTrue(sent.payload.startsWith(HubChatBridge.HUB_FILE + "\n"))

        // No key, or a key the user has not confirmed: nothing goes, even with readable content switched on.
        assertFalse(bridge(sealing = null, content = true).sendHubFile("alice", "m-2", 1_000L, frame(sender = "me")))
        assertFalse(bridge(sealing = ScriptedHubSealing(HubSealingStatus.KEY_CHANGED), content = true).sendHubFile("alice", "m-3", 1_000L, frame(sender = "me")))
        relay.usable = false
        assertFalse(bridge().sendHubFile("alice", "m-4", 1_000L, frame(sender = "me")))
        assertEquals(1, relay.sent.size, "nothing else reached the relay")
        assertFalse(relay.sent.any { it.payload.contains(key.encode()) && it.type != HubSealing.ENVELOPE_TYPE })
    }

    @Test
    fun `HUBFILE-U17 a sealed ticket from its author is stored and acknowledged`() = runTest {
        val sink = FileSink()
        val body = HubFileFrameCodec.encode(frame())
        val outcome = bridge().handleInbound(inbound(HubSealing.ENVELOPE_TYPE, HubChatBridge.HUB_FILE + "\n" + body), sink)
        assertEquals(HubInboundOutcome.STORED, outcome)
        assertEquals("alice", sink.files.single().second)
        assertEquals(key.encode(), sink.files.single().first.ticket.key.encode())
        assertEquals(listOf("m-1"), relay.recvAcks)
    }

    @Test
    fun `HUBFILE-U18 a readable, forged, malformed or unwanted ticket is rejected and acknowledged`() = runTest {
        val body = HubFileFrameCodec.encode(frame())

        // Readable, even with readable content switched on: the key must never have crossed the hub in the open.
        val readable = FileSink()
        assertEquals(HubInboundOutcome.REJECTED, bridge(content = true).handleInbound(inbound(HubChatBridge.HUB_FILE, body), readable))
        assertTrue(readable.files.isEmpty())

        // Sealed by bob but naming alice as the author.
        val forged = FileSink()
        val asBob = inbound(HubSealing.ENVELOPE_TYPE, HubChatBridge.HUB_FILE + "\n" + body, from = "bob").copy(id = "m-2")
        assertEquals(HubInboundOutcome.REJECTED, bridge().handleInbound(asBob, forged))
        assertTrue(forged.files.isEmpty())

        // A recognised frame that breaks a rule, and text that is no ticket at all.
        val fields = FlashTextFraming.parseFields(body, HubFileFrameCodec.PREFIX)!!.toMutableMap()
        fields["name"] = "../x"
        val bad = FlashTextFraming.encodeFields(HubFileFrameCodec.PREFIX, fields.toList())
        val malformed = FileSink()
        assertEquals(
            HubInboundOutcome.REJECTED,
            bridge().handleInbound(inbound(HubSealing.ENVELOPE_TYPE, HubChatBridge.HUB_FILE + "\n" + bad).copy(id = "m-3"), malformed),
        )
        assertEquals(
            HubInboundOutcome.REJECTED,
            bridge().handleInbound(inbound(HubSealing.ENVELOPE_TYPE, HubChatBridge.HUB_FILE + "\nhello").copy(id = "m-4"), malformed),
        )
        assertTrue(malformed.files.isEmpty())

        // A device whose repository takes no hub files.
        assertEquals(
            HubInboundOutcome.REJECTED,
            bridge().handleInbound(inbound(HubSealing.ENVELOPE_TYPE, HubChatBridge.HUB_FILE + "\n" + body).copy(id = "m-5"), FileSink(takes = false)),
        )
        assertEquals(listOf("m-1", "m-2", "m-3", "m-4", "m-5"), relay.recvAcks, "every rejection is acknowledged so the hub stops redelivering")
    }
}
