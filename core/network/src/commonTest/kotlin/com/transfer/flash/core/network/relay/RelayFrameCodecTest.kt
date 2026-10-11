@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.network.relay

import com.transfer.flash.core.common.protocol.FlashEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RelayFrameCodecTest {

    @Test
    fun testHelloEncodingAndDecoding() {
        val hello = RelayFrame.Hello(
            deviceId = "dev-alice-123",
            friendlyName = "Alice Laptop",
            role = "admin",
            authSignature = "sig-abc-123",
            clientNonce = "nonce-456",
        )
        val encoded = RelayFrameCodec.encode(hello)
        assertTrue(encoded.startsWith("FLASH_HUB_HELLO"))
        assertTrue(encoded.contains("deviceId=dev-alice-123"))

        val decoded = RelayFrameCodec.decode(encoded)
        assertNotNull(decoded)
        assertTrue(decoded is RelayFrame.Hello)
        assertEquals("dev-alice-123", decoded.deviceId)
        assertEquals("Alice Laptop", decoded.friendlyName)
        assertEquals("admin", decoded.role)
        assertEquals("sig-abc-123", decoded.authSignature)
        assertEquals("nonce-456", decoded.clientNonce)
    }

    @Test
    fun testWelcomeEncodingAndDecoding() {
        val welcome = RelayFrame.Welcome(
            deviceId = "dev-bob-789",
            status = "CONNECTED",
            timestamp = 1728600000000L,
        )
        val encoded = RelayFrameCodec.encode(welcome)
        val decoded = RelayFrameCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(decoded is RelayFrame.Welcome)
        assertEquals("dev-bob-789", decoded.deviceId)
        assertEquals("CONNECTED", decoded.status)
        assertEquals(1728600000000L, decoded.timestamp)
    }

    @Test
    fun testDataEncodingAndDecodingRoundTrip() {
        val envelope = FlashEnvelope(
            id = "msg-uuid-001",
            type = "MSG_TEXT",
            payloadJson = """{"text":"Hello Enterprise Hub!"}""",
            senderId = "dev-sender-1",
            sentAt = 1728600000500L,
        )
        val data = RelayFrame.Data.fromEnvelope("dev-recipient-2", envelope)
        val encoded = RelayFrameCodec.encode(data)
        val decoded = RelayFrameCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(decoded is RelayFrame.Data)
        assertEquals("dev-recipient-2", decoded.recipientId)
        assertEquals("msg-uuid-001", decoded.id)
        assertEquals("MSG_TEXT", decoded.type)
        assertEquals("dev-sender-1", decoded.senderId)
        assertEquals(1728600000500L, decoded.sentAt)
        assertEquals("""{"text":"Hello Enterprise Hub!"}""", decoded.payload)

        val restoredEnvelope = decoded.toEnvelope()
        assertEquals(envelope.id, restoredEnvelope.id)
        assertEquals(envelope.type, restoredEnvelope.type)
        assertEquals(envelope.payloadJson, restoredEnvelope.payloadJson)
        assertEquals(envelope.senderId, restoredEnvelope.senderId)
        assertEquals(envelope.sentAt, restoredEnvelope.sentAt)
    }

    @Test
    fun testAckEncodingAndDecoding() {
        val ack = RelayFrame.Ack(
            id = "msg-uuid-001",
            recipientId = "dev-recipient-2",
            status = RelayFrame.Ack.STATUS_DELIVERED,
            timestamp = 1728600001000L,
        )
        val encoded = RelayFrameCodec.encode(ack)
        val decoded = RelayFrameCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(decoded is RelayFrame.Ack)
        assertEquals("msg-uuid-001", decoded.id)
        assertEquals("dev-recipient-2", decoded.recipientId)
        assertEquals(RelayFrame.Ack.STATUS_DELIVERED, decoded.status)
        assertTrue(decoded.isDelivered)
    }

    @Test
    fun testPingPongEncodingAndDecoding() {
        val ping = RelayFrame.Ping(timestamp = 1728600002000L)
        val encodedPing = RelayFrameCodec.encode(ping)
        val decodedPing = RelayFrameCodec.decode(encodedPing)
        assertNotNull(decodedPing)
        assertTrue(decodedPing is RelayFrame.Ping)
        assertEquals(1728600002000L, decodedPing.timestamp)

        val pong = RelayFrame.Pong(timestamp = 1728600002050L)
        val encodedPong = RelayFrameCodec.encode(pong)
        val decodedPong = RelayFrameCodec.decode(encodedPong)
        assertNotNull(decodedPong)
        assertTrue(decodedPong is RelayFrame.Pong)
        assertEquals(1728600002050L, decodedPong.timestamp)
    }

    @Test
    fun testMetaWirePaddingRefinementPRef1() {
        val meta = RelayFrame.Meta(
            action = RelayFrame.Meta.ACTION_TYPING,
            conversationId = "conv-finance-dept",
            senderId = "dev-alice-123",
            timestamp = 1728600003000L,
        )
        val encoded = RelayFrameCodec.encode(meta)
        val byteLength = encoded.encodeToByteArray().size

        // P-REF-1 requires wire padding to 256 or 512 byte boundaries
        assertEquals(256, byteLength, "Metadata frame must be padded to exactly 256 bytes")

        val decoded = RelayFrameCodec.decode(encoded)
        assertNotNull(decoded)
        assertTrue(decoded is RelayFrame.Meta)
        assertEquals(RelayFrame.Meta.ACTION_TYPING, decoded.action)
        assertEquals("conv-finance-dept", decoded.conversationId)
        assertEquals("dev-alice-123", decoded.senderId)
        assertTrue(decoded.padLength > 0, "Padded length must be non-zero")
    }

    @Test
    fun testErrorEncodingAndDecoding() {
        val error = RelayFrame.Error(
            code = RelayFrame.Error.ERR_REVOKED,
            message = "Device certificate has been permanently revoked by administrator",
        )
        val encoded = RelayFrameCodec.encode(error)
        val decoded = RelayFrameCodec.decode(encoded)

        assertNotNull(decoded)
        assertTrue(decoded is RelayFrame.Error)
        assertEquals(RelayFrame.Error.ERR_REVOKED, decoded.code)
        assertEquals("Device certificate has been permanently revoked by administrator", decoded.message)
    }

    @Test
    fun testJsonEnvelopeFallbackDecoding() {
        val json = """
            {
              "recipientId": "dev-target-9",
              "id": "json-msg-123",
              "type": "MSG_FILE_DESCRIPTOR",
              "senderId": "dev-source-8",
              "sentAt": 1728600004000,
              "payloadJson": "{\"name\":\"budget_q4.pdf\",\"size\":102400}"
            }
        """.trimIndent()

        val decoded = RelayFrameCodec.decode(json)
        assertNotNull(decoded)
        assertTrue(decoded is RelayFrame.Data)
        assertEquals("dev-target-9", decoded.recipientId)
        assertEquals("json-msg-123", decoded.id)
        assertEquals("MSG_FILE_DESCRIPTOR", decoded.type)
        assertEquals("dev-source-8", decoded.senderId)
        assertEquals(1728600004000L, decoded.sentAt)
    }

    @Test
    fun testMtuBoundsAndChunkingPRef2() {
        val smallControl = RelayFrame.Ping(timestamp = 1000L)
        assertTrue(RelayFrameCodec.isWithinControlBounds(smallControl))

        val smallData = RelayFrame.Data(
            recipientId = "rec",
            id = "1",
            type = "T",
            senderId = "s",
            sentAt = 10L,
            payload = "small payload",
        )
        assertTrue(RelayFrameCodec.isWithinPayloadBounds(smallData))

        // Create large 70 KB payload
        val largePayload = "X".repeat(70 * 1024)
        val chunks = RelayFrameCodec.chunkPayload(largePayload, chunkSize = 32 * 1024)
        assertEquals(3, chunks.size, "70 KB payload should split into 3 chunks (32KB + 32KB + 6KB)")
        assertEquals(32 * 1024, chunks[0].length)
        assertEquals(32 * 1024, chunks[1].length)
        assertEquals(6 * 1024, chunks[2].length)
        assertEquals(largePayload, chunks.joinToString(""))
    }
}
