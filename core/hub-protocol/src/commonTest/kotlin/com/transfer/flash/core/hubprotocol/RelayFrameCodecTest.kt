package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashEnvelope
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.relay.QueueEvent
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec.Decoded
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec.RejectReason
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(FlashInternalApi::class)
class RelayFrameCodecTest {

    private fun roundTrip(frame: RelayFrame): RelayFrame? = RelayFrameCodec.decode(RelayFrameCodec.encode(frame))

    private val hello = RelayFrame.Hello(
        deviceId = "dev-1",
        clientNonce = "cn",
        name = "Alice",
        deviceCert = FakeSigs.device(),
        delegationChain = listOf(FakeSigs.delegation()),
        signature = "c2ln",
    )

    @Test
    fun everyFrameRoundTrips() {
        val frames = listOf(
            RelayFrame.Challenge(serverId = "AB12", serverNonce = "nonce", issuedAt = 5),
            hello,
            RelayFrame.Welcome(principal = HubPrincipal("dev-1", "member", "dept-1", 10), serverTime = 9),
            RelayFrame.Error("ERR_AUTH", "no"),
            RelayFrame.Send("dev-2", "m1", "MSG_TEXT", 1, "{\"a\":1}"),
            RelayFrame.Deliver("dev-1", "m1", "MSG_TEXT", 1, "{\"a\":1}", replay = true),
            RelayFrame.RecvAck("m1"),
            RelayFrame.Ack("m1", "dev-2", AckStatus.DUPLICATE_QUEUED, 3, "r"),
            RelayFrame.QueueState("m1", "dev-2", QueueEvent.EXPIRED, 3),
            RelayFrame.Ping(1),
            RelayFrame.Pong(2),
            RelayFrame.Revoked("revoked", 12),
            RelayFrame.Kick("superseded"),
            RelayFrame.AnnouncementEvent("ann-1", 10L, "org", null, "new", "ep-1"),
            RelayFrame.AnnouncementEvent("ann-2", 11L, "department", "dept-1", "edit", "ep-1"),
        )
        for (f in frames) assertEquals(f, roundTrip(f), f::class.simpleName)
    }

    @Test
    fun wireIsJsonWithTypeDiscriminator() {
        val text = RelayFrameCodec.encode(RelayFrame.RecvAck("m1"))
        assertTrue(text.contains("\"t\":\"FLASH_RECV_ACK\""), text)
        assertTrue(RelayFrameCodec.encode(RelayFrame.Challenge(serverId = "S", serverNonce = "N", issuedAt = 1)).contains("\"v\":2"))
    }

    @Test
    fun announcementEventRoundTripsAndValidates() {
        val ann = RelayFrame.AnnouncementEvent(
            id = "ann-uuid-1",
            seq = 42L,
            scope = "department",
            departmentId = "dept-engineering",
            change = "new",
            epoch = "epoch-12345",
        )
        val encoded = RelayFrameCodec.encode(ann)
        assertTrue(encoded.contains("\"t\":\"FLASH_ANNOUNCEMENT\""))
        assertTrue(encoded.contains("\"id\":\"ann-uuid-1\""))
        assertTrue(encoded.contains("\"seq\":42"))
        val decoded = RelayFrameCodec.decode(encoded)
        assertEquals(ann, decoded)
    }

    @Test
    fun helloWithFeaturesRoundTrips() {
        val helloWithFeat = hello.copy(features = listOf("ann1"))
        val encoded = RelayFrameCodec.encode(helloWithFeat)
        assertTrue(encoded.contains("\"features\":[\"ann1\"]"))
        val decoded = RelayFrameCodec.decode(encoded)
        assertEquals(helloWithFeat, decoded)
    }


    @Test
    fun sendFrameHasNoSenderField() {
        val text = RelayFrameCodec.encode(RelayFrame.Send("dev-2", "m1", "MSG_TEXT", 1, "{}"))
        assertTrue(!text.contains("from") && !text.contains("sender"), text)
    }

    @Test
    fun clientSuppliedSenderOnASendIsIgnoredNotRead() {
        val spoof = """{"t":"FLASH_RELAY","to":"dev-2","id":"m1","type":"MSG_TEXT","sentAt":1,"payload":"{}","from":"admin","sender":"admin","senderId":"admin"}"""
        val frame = assertIs<RelayFrame.Send>(RelayFrameCodec.decode(spoof))
        assertEquals(RelayFrame.Send("dev-2", "m1", "MSG_TEXT", 1, "{}"), frame, "there is no field a spoofed sender could land in")
    }

    @Test
    fun deliverConvertsToEnvelopeWithStampedSender() {
        val env = RelayFrame.Deliver("dev-1", "m1", "MSG_TEXT", 5, "{\"x\":1}").toEnvelope()
        assertEquals(FlashEnvelope("m1", "MSG_TEXT", "{\"x\":1}", "dev-1", 5), env)
        assertEquals(RelayFrame.Send("dev-2", "m1", "MSG_TEXT", 5, "{\"x\":1}"), RelayFrame.Send.fromEnvelope("dev-2", env))
    }

    @Test
    fun malformedAndUnknownAreRejectedNotThrown() {
        assertNull(RelayFrameCodec.decode(""))
        assertNull(RelayFrameCodec.decode("not json"))
        assertNull(RelayFrameCodec.decode("[1,2]"))
        assertNull(RelayFrameCodec.decode("""{"t":"FLASH_HUB_WELCOME"}"""))
        val unknown = RelayFrameCodec.decodeResult("""{"t":"FLASH_NOPE","a":1}""")
        assertEquals(RejectReason.UNKNOWN_TYPE, assertIs<Decoded.Rejected>(unknown).reason)
        val legacy = RelayFrameCodec.decodeResult("FLASH_RELAY recipient=a id=b sender=c")
        assertIs<Decoded.Rejected>(legacy)
    }

    @Test
    fun unknownFieldsAreIgnoredForForwardCompatibility() {
        val f = RelayFrameCodec.decode("""{"t":"FLASH_PING","ts":4,"newField":{"x":1}}""")
        assertEquals(RelayFrame.Ping(4), f)
    }

    @Test
    fun unknownAckStatusCoercesToError() {
        val f = assertIs<RelayFrame.Ack>(RelayFrameCodec.decode("""{"t":"FLASH_ACK","id":"m1","to":"d","status":"FROM_THE_FUTURE","ts":1}"""))
        assertEquals(AckStatus.ERROR, f.status)
        val q = assertIs<RelayFrame.QueueState>(RelayFrameCodec.decode("""{"t":"FLASH_QUEUE_STATE","id":"m1","state":"TELEPORTED"}"""))
        assertEquals(QueueEvent.UNKNOWN, q.state)
    }

    @Test
    fun wrongVersionIsRejected() {
        val r = RelayFrameCodec.decodeResult("""{"t":"FLASH_HUB_CHALLENGE","v":1,"serverId":"S","serverNonce":"N","issuedAt":1}""")
        assertEquals(RejectReason.UNSUPPORTED_VERSION, assertIs<Decoded.Rejected>(r).reason)
    }

    @Test
    fun oversizeFrameIsRejectedOnDecodeAndEncode() {
        val big = "x".repeat(HubContract.MAX_FRAME_BYTES)
        val text = """{"t":"FLASH_ERROR","code":"ERR_AUTH","message":"$big"}"""
        assertEquals(RejectReason.TOO_LARGE, assertIs<Decoded.Rejected>(RelayFrameCodec.decodeResult(text)).reason)
        assertFailsWith<IllegalArgumentException> { RelayFrameCodec.encode(RelayFrame.Error("ERR_AUTH", big)) }
    }

    @Test
    fun oversizePayloadIsRejected() {
        val payload = "y".repeat(HubContract.MAX_PAYLOAD_BYTES + 1)
        val text = """{"t":"FLASH_RELAY","to":"d","id":"m","sentAt":1,"payload":"$payload"}"""
        assertEquals(RejectReason.PAYLOAD_TOO_LARGE, assertIs<Decoded.Rejected>(RelayFrameCodec.decodeResult(text)).reason)
        assertFailsWith<IllegalArgumentException> { RelayFrameCodec.encode(RelayFrame.Send("d", "m", "MSG_TEXT", 1, payload)) }
        // exactly at the limit is fine
        val ok = RelayFrame.Send("d", "m", "MSG_TEXT", 1, "y".repeat(HubContract.MAX_PAYLOAD_BYTES))
        assertEquals(ok, roundTrip(ok))
    }

    @Test
    fun multiByteBudgetIsMeasuredInBytesNotChars() {
        // 3-byte chars: char count under the limit, byte count over it.
        val payload = "€".repeat(HubContract.MAX_PAYLOAD_BYTES / 3 + 1)
        assertTrue(payload.length < HubContract.MAX_PAYLOAD_BYTES)
        assertFailsWith<IllegalArgumentException> { RelayFrameCodec.encode(RelayFrame.Send("d", "m", "MSG_TEXT", 1, payload)) }
    }

    @Test
    fun handshakeSizeBoundIsEnforcedWhenRequested() {
        val big = hello.copy(name = "n".repeat(100)).copy(signature = "s".repeat(HubContract.MAX_HANDSHAKE_FRAME_BYTES))
        val text = RelayFrameCodec.encode(big)
        assertEquals(
            RejectReason.TOO_LARGE,
            assertIs<Decoded.Rejected>(RelayFrameCodec.decodeResult(text, HubContract.MAX_HANDSHAKE_FRAME_BYTES)).reason,
        )
    }

    @Test
    fun fieldValidationRejectsBlankAndOverlongIdentifiers() {
        fun reason(json: String) = assertIs<Decoded.Rejected>(RelayFrameCodec.decodeResult(json)).reason
        assertEquals(RejectReason.INVALID_FIELD, reason("""{"t":"FLASH_RELAY","to":"","id":"m","sentAt":1,"payload":"{}"}"""))
        assertEquals(RejectReason.INVALID_FIELD, reason("""{"t":"FLASH_RELAY","to":"d","id":"${"i".repeat(129)}","sentAt":1,"payload":"{}"}"""))
        assertEquals(RejectReason.INVALID_FIELD, reason("""{"t":"FLASH_RELAY","to":"d","id":"m","sentAt":0,"payload":"{}"}"""))
        assertEquals(RejectReason.INVALID_FIELD, reason("""{"t":"FLASH_RECV_ACK","msgId":" "}"""))
    }

    @Test
    fun metaFramesArePaddedToFixedBoundaries() {
        val small = RelayFrame.Meta(action = "TYPING", conversationId = "c1", to = "dev-2", ts = 1)
        val a = RelayFrameCodec.encode(small)
        val b = RelayFrameCodec.encode(small.copy(conversationId = "a-much-longer-conversation-id-with-more-bytes"))
        assertEquals(256, RelayFrameCodec.utf8Size(a))
        assertEquals(256, RelayFrameCodec.utf8Size(b))
        val big = RelayFrameCodec.encode(small.copy(conversationId = "z".repeat(220)))
        assertEquals(512, RelayFrameCodec.utf8Size(big))
        val decoded = assertIs<RelayFrame.Meta>(RelayFrameCodec.decode(a))
        assertEquals("c1", decoded.conversationId)
        assertEquals("dev-2", decoded.to)
    }

    @Test
    fun chunkPayloadSplitsOnCodePointBoundariesAndReassembles() {
        val emoji = "😀" // 4 bytes
        val text = ("a" + emoji + "€" + "b").repeat(500)
        for (max in listOf(4, 5, 7, 16, 100, 1000)) {
            val chunks = RelayFrameCodec.chunkPayload(text, max)
            assertEquals(text, chunks.joinToString(""))
            for (c in chunks) {
                assertTrue(RelayFrameCodec.utf8Size(c) <= max, "chunk of ${RelayFrameCodec.utf8Size(c)} > $max")
                assertContentEquals(c.encodeToByteArray(), c.encodeToByteArray().decodeToString().encodeToByteArray(), "valid UTF-8")
                assertTrue(!c.last().isHighSurrogate(), "no split surrogate pair")
                assertTrue(!c.first().isLowSurrogate(), "no split surrogate pair")
            }
        }
        assertEquals(listOf("short"), RelayFrameCodec.chunkPayload("short"))
        assertFailsWith<IllegalArgumentException> { RelayFrameCodec.chunkPayload("x", 3) }
    }

    @Test
    fun ackToFieldIsTheOriginalRecipient() {
        val ack = assertIs<RelayFrame.Ack>(RelayFrameCodec.decode("""{"t":"FLASH_ACK","id":"m1","to":"dev-2","status":"QUEUED","ts":1}"""))
        assertEquals("dev-2", ack.to)
        assertTrue(ack.status.isAccepted)
        assertTrue(!AckStatus.REJECTED.isAccepted && !AckStatus.QUOTA_EXCEEDED.isAccepted)
    }

    // ---- group relay (ADR-111, REL-14) ------------------------------------------------------------------

    @Test
    fun groupSendDeliverAndAggregateAckRoundTrip() {
        val send = RelayFrame.Send(id = "g1", type = "MSG_TEXT", sentAt = 5, payload = "{}", group = "team")
        assertEquals(send, roundTrip(send))
        assertEquals("", send.to, "a group send needs no single recipient")

        val deliver = RelayFrame.Deliver(from = "alice", id = "g1", sentAt = 5, payload = "{}", group = "team")
        assertEquals("team", assertIs<RelayFrame.Deliver>(roundTrip(deliver)).group)

        val ack = RelayFrame.Ack(id = "g1", to = "team", status = AckStatus.QUEUED, reason = "partial", delivered = 2, queued = 1, failed = 3)
        val back = assertIs<RelayFrame.Ack>(roundTrip(ack))
        assertEquals(Triple(2, 1, 3), Triple(back.delivered, back.queued, back.failed))
    }

    @Test
    fun aSendWithoutGroupStillNeedsARecipientAndAnOldAckStillDecodes() {
        val rejected = RelayFrameCodec.decodeResult("""{"t":"FLASH_RELAY","v":2,"to":"","id":"m1","type":"MSG_TEXT","sentAt":1,"payload":"{}"}""")
        assertEquals("to", assertIs<Decoded.Rejected>(rejected).detail)

        // A hub of the previous generation sends no counts: they default to zero, no field is required.
        val ack = assertIs<RelayFrame.Ack>(RelayFrameCodec.decode("""{"t":"FLASH_ACK","id":"m1","to":"dev-2","status":"DELIVERED","ts":1}"""))
        assertEquals(Triple(0, 0, 0), Triple(ack.delivered, ack.queued, ack.failed))
    }

    @Test
    fun anOverlongOrBlankGroupIdIsRejected() {
        val long = "g".repeat(HubContract.MAX_ID_LENGTH + 1)
        val tooLong = RelayFrameCodec.decodeResult("""{"t":"FLASH_RELAY","v":2,"id":"m1","type":"MSG_TEXT","sentAt":1,"payload":"{}","group":"$long"}""")
        assertEquals("group", assertIs<Decoded.Rejected>(tooLong).detail)
        val blank = RelayFrameCodec.decodeResult("""{"t":"FLASH_RELAY","v":2,"id":"m1","type":"MSG_TEXT","sentAt":1,"payload":"{}","group":" "}""")
        assertEquals("group", assertIs<Decoded.Rejected>(blank).detail)
    }
}
