package com.transfer.flash.core.calling.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(FlashInternalApi::class)
class CallFrameCodecTest {

    private val callId = "0199c0de-1111-4222-8333-444455556666"
    private val from = "device-uuid-7777"

    @Test
    fun invite_round_trip() {
        val frame = CallWireFrame.Invite(
            callId = callId,
            from = from,
            callerName = "Alice's Phone (5G)",
            video = true,
        )
        assertEquals(frame, CallFrameCodec.decode(CallFrameCodec.encode(frame)))
    }

    @Test
    fun accept_round_trip() {
        val frame = CallWireFrame.Accept(callId = callId, from = from)
        assertEquals(frame, CallFrameCodec.decode(CallFrameCodec.encode(frame)))
    }

    @Test
    fun decline_round_trip() {
        val frame = CallWireFrame.Decline(callId = callId, from = from)
        assertEquals(frame, CallFrameCodec.decode(CallFrameCodec.encode(frame)))
    }

    @Test
    fun hangup_round_trip() {
        val frame = CallWireFrame.Hangup(callId = callId, from = from)
        assertEquals(frame, CallFrameCodec.decode(CallFrameCodec.encode(frame)))
    }

    @Test
    fun offer_round_trip_with_sdp_special_characters() {
        // Real SDP contains spaces, '=' and '%' — all must survive the round trip.
        // Note: the framing layer trims the frame, so trailing CRLF is lost (harmless
        // for libwebrtc — documented in CallFrameCodec).
        val sdp = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\na=group:BUNDLE 0 1"
        val frame = CallWireFrame.Offer(callId = callId, from = from, sdp = sdp)
        val decoded = CallFrameCodec.decode(CallFrameCodec.encode(frame))
        assertEquals(frame, decoded)
        assertTrue(CallFrameCodec.encode(frame).startsWith("FLASH_CALL "))
    }

    @Test
    fun answer_round_trip_with_sdp_special_characters() {
        val sdp = "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=rtpmap:111 opus/48000/2"
        val frame = CallWireFrame.Answer(callId = callId, from = from, sdp = sdp)
        assertEquals(frame, CallFrameCodec.decode(CallFrameCodec.encode(frame)))
    }

    @Test
    fun ice_candidate_round_trip() {
        val frame = CallWireFrame.IceCandidate(
            callId = callId,
            from = from,
            sdpMid = "0",
            sdpMLineIndex = 0,
            candidate = "candidate:1 1 UDP 2122252543 192.168.1.42 54321 typ host",
        )
        assertEquals(frame, CallFrameCodec.decode(CallFrameCodec.encode(frame)))
    }

    @Test
    fun ice_candidate_with_null_mid_round_trip() {
        val frame = CallWireFrame.IceCandidate(
            callId = callId,
            from = from,
            sdpMid = null,
            sdpMLineIndex = 1,
            candidate = "candidate:2 1 UDP 2122252543 192.168.1.42 54322 typ host",
        )
        assertEquals(frame, CallFrameCodec.decode(CallFrameCodec.encode(frame)))
    }

    @Test
    fun decode_returns_null_for_non_call_frame() {
        assertNull(CallFrameCodec.decode("FLASH_MSG from=abc body=hello"))
    }

    @Test
    fun decode_returns_null_for_unknown_action() {
        val line = "FLASH_CALL action=futureaction callId=$callId from=$from"
        assertNull(CallFrameCodec.decode(line))
    }

    @Test
    fun decode_invite_defaults_missing_optional_fields() {
        // Forward compatibility: old/foreign senders may omit name/video.
        val line = "FLASH_CALL action=invite callId=$callId from=$from"
        assertEquals(
            CallWireFrame.Invite(callId = callId, from = from, callerName = "Peer", video = false),
            CallFrameCodec.decode(line),
        )
    }

    @Test
    fun decode_returns_null_when_required_fields_missing() {
        assertNull(CallFrameCodec.decode("FLASH_CALL action=invite from=$from"))
        assertNull(CallFrameCodec.decode("FLASH_CALL action=offer callId=$callId from=$from"))
    }
}
