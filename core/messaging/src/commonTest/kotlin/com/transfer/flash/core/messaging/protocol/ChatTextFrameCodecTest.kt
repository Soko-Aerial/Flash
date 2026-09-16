package com.transfer.flash.core.messaging.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [ChatTextFrameCodec] before both Android hosts switch to it (slice 3).
 *
 * The field mapping is transcribed verbatim from `DiscoveryEngineHolder` and `Flash.kt`
 * (verified identical); these tests are what prove the transcription — especially the
 * drop-vs-fallthrough rule ([DecodeResult.RecognizedButInvalid]) and the per-field
 * defaults, which are the parts a careless extraction would silently change.
 */
class ChatTextFrameCodecTest {

    @Test
    fun `text message round-trips including reply metadata`() {
        val frame = MessageWireFrame.TextMessage(
            localId = "l1",
            conversationId = "c1",
            senderId = "s1",
            senderName = "Sam",
            text = "hello: with colon",
            sentAt = 123L,
            replyToId = "l0",
            replyToPreview = "quoted",
        )
        val decoded = ChatTextFrameCodec.decode(ChatTextFrameCodec.encode(frame)!!, NOW, PEER)
        assertEquals(ChatTextFrameCodec.DecodeResult.Frame(frame), decoded)
    }

    @Test
    fun `blank reply fields decode as null for legacy peers`() {
        val frame = MessageWireFrame.TextMessage(
            localId = "l1",
            conversationId = "c1",
            senderId = "s1",
            senderName = "Sam",
            text = "hi",
            sentAt = 123L,
        )
        val decoded = ChatTextFrameCodec.decode(ChatTextFrameCodec.encode(frame)!!, NOW, PEER)
        assertEquals(ChatTextFrameCodec.DecodeResult.Frame(frame), decoded)
    }

    @Test
    fun `receipt read react and typing round-trip`() {
        val frames = listOf(
            MessageWireFrame.DeliveryReceipt("m1", "c1", "peer", 7L),
            MessageWireFrame.ReadReceipt("c1", "peer", "m1", 8L),
            MessageWireFrame.ReactionFrame("m1", "c1", "peer", "+1", true),
            MessageWireFrame.TypingFrame("c1", "peer", "Pat", true, 9L),
        )
        frames.forEach { frame ->
            val decoded = ChatTextFrameCodec.decode(ChatTextFrameCodec.encode(frame)!!, NOW, PEER)
            assertEquals(ChatTextFrameCodec.DecodeResult.Frame(frame), decoded)
        }
    }

    @Test
    fun `a text frame without localId is invalid rather than routable`() {
        // Both holders `?: return` here — the frame is dropped, never passed on.
        assertEquals(
            ChatTextFrameCodec.DecodeResult.RecognizedButInvalid,
            ChatTextFrameCodec.decode("FLASH_MSG conversationId=c1 senderId=s1 text=hi sentAt=1", NOW, PEER),
        )
    }

    @Test
    fun `receipt and typing without key fields are invalid`() {
        assertEquals(
            ChatTextFrameCodec.DecodeResult.RecognizedButInvalid,
            ChatTextFrameCodec.decode("FLASH_RCPT conversationId=c1 memberId=peer deliveredAt=1", NOW, PEER),
        )
        assertEquals(
            ChatTextFrameCodec.DecodeResult.RecognizedButInvalid,
            ChatTextFrameCodec.decode("FLASH_TYPING conversationId=c1 memberName=Pat isTyping=true", NOW, PEER),
        )
    }

    @Test
    fun `unknown prefixes are not ours`() {
        assertNull(ChatTextFrameCodec.decode("FLASH_XFER action=offer transferId=t", NOW, PEER))
        assertNull(ChatTextFrameCodec.decode("not a frame at all", NOW, PEER))
    }

    @Test
    fun `missing free-form fields take holder defaults`() {
        val decoded = ChatTextFrameCodec.decode("FLASH_MSG localId=l1", NOW, PEER)
        val frame = (decoded as ChatTextFrameCodec.DecodeResult.Frame).frame
                as MessageWireFrame.TextMessage
        assertEquals("", frame.conversationId)
        assertEquals("Peer", frame.senderName)
        assertEquals("", frame.text)
        assertEquals(NOW, frame.sentAt)
        assertNull(frame.replyToId)
    }

    @Test
    fun `typing without a conversation id falls back to the transport peer`() {
        val decoded = ChatTextFrameCodec.decode(
            "FLASH_TYPING memberId=peer memberName=Pat isTyping=false timestampMs=1",
            NOW,
            PEER,
        )
        val frame = (decoded as ChatTextFrameCodec.DecodeResult.Frame).frame
                as MessageWireFrame.TypingFrame
        assertEquals(PEER, frame.conversationId)
    }

    @Test
    fun `delete-for-everyone is not this codec's frame`() {
        assertNull(
            ChatTextFrameCodec.encode(
                MessageWireFrame.DeleteForEveryone("m1", "c1", "peer"),
            ),
        )
    }

    @Test
    fun `absent sender name defaults, blank stays blank`() {
        val absent = ChatTextFrameCodec.decode("FLASH_MSG localId=l1", NOW, PEER)
        assertEquals(
            "Peer",
            ((absent as ChatTextFrameCodec.DecodeResult.Frame).frame
                as MessageWireFrame.TextMessage).senderName,
        )
        // Blank is carried through, not defaulted: both holders only default ABSENT names
        // (`f["senderName"] ?: "Peer"` — a present-but-empty field is not null).
        val blank = ChatTextFrameCodec.decode("FLASH_MSG localId=l1 senderName=", NOW, PEER)
        assertEquals(
            "",
            ((blank as ChatTextFrameCodec.DecodeResult.Frame).frame
                as MessageWireFrame.TextMessage).senderName,
        )
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val PEER = "peer-device"
    }
}
