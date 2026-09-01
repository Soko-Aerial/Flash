package com.transfer.flash.core.calling.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.FlashTextFraming

/**
 * Encodes and decodes [CallWireFrame]s to/from `FLASH_CALL` text frames (C7, ADR-025).
 *
 * Wire format (docs/protocol.md "Calling"):
 * `FLASH_CALL action=<action> callId=<uuid> from=<id> [action-specific fields]`
 *
 * SDP payloads and ICE candidates are escaped with the standard Flash rules
 * (`%25`, `%20`, `%3D`), so multi-KB offers ride the same text-frame path as chat.
 *
 * Wire quirk: the framing layer trims leading/trailing whitespace from the whole
 * frame, so an SDP's final CRLF is lost in transit. libwebrtc's SDP parser splits
 * on CRLF and tolerates the missing terminator, so this is semantically harmless —
 * but callers comparing SDP strings byte-for-byte after a round trip must trim first.
 */
@OptIn(FlashInternalApi::class)
public object CallFrameCodec {

    public const val PREFIX: String = "FLASH_CALL"

    /** Encodes [frame] to a single-line text frame. */
    public fun encode(frame: CallWireFrame): String {
        val fields = when (frame) {
            is CallWireFrame.Invite -> listOf(
                "action" to "invite",
                "callId" to frame.callId,
                "from" to frame.from,
                "name" to frame.callerName,
                "video" to frame.video.toString(),
            )
            is CallWireFrame.Accept -> listOf(
                "action" to "accept",
                "callId" to frame.callId,
                "from" to frame.from,
            )
            is CallWireFrame.Decline -> listOf(
                "action" to "decline",
                "callId" to frame.callId,
                "from" to frame.from,
            )
            is CallWireFrame.Hangup -> listOf(
                "action" to "hangup",
                "callId" to frame.callId,
                "from" to frame.from,
            )
            is CallWireFrame.Offer -> listOf(
                "action" to "offer",
                "callId" to frame.callId,
                "from" to frame.from,
                "sdp" to frame.sdp,
            )
            is CallWireFrame.Answer -> listOf(
                "action" to "answer",
                "callId" to frame.callId,
                "from" to frame.from,
                "sdp" to frame.sdp,
            )
            is CallWireFrame.IceCandidate -> listOf(
                "action" to "ice",
                "callId" to frame.callId,
                "from" to frame.from,
                "mid" to (frame.sdpMid ?: ""),
                "index" to frame.sdpMLineIndex.toString(),
                "candidate" to frame.candidate,
            )
        }
        return FlashTextFraming.encodeFields(PREFIX, fields)
    }

    /**
     * Parses a text frame into a [CallWireFrame]. Returns null when [text] is not a
     * `FLASH_CALL` frame or carries an unknown action (forward compatibility: unknown
     * actions are ignored by callers).
     */
    public fun decode(text: String): CallWireFrame? {
        val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return null
        val action = fields["action"] ?: return null
        val callId = fields["callId"] ?: return null
        val from = fields["from"] ?: return null
        return when (action) {
            "invite" -> CallWireFrame.Invite(
                callId = callId,
                from = from,
                callerName = fields["name"] ?: "Peer",
                video = fields["video"]?.toBooleanStrictOrNull() ?: false,
            )
            "accept" -> CallWireFrame.Accept(callId, from)
            "decline" -> CallWireFrame.Decline(callId, from)
            "hangup" -> CallWireFrame.Hangup(callId, from)
            "offer" -> CallWireFrame.Offer(
                callId = callId,
                from = from,
                sdp = fields["sdp"] ?: return null,
            )
            "answer" -> CallWireFrame.Answer(
                callId = callId,
                from = from,
                sdp = fields["sdp"] ?: return null,
            )
            "ice" -> CallWireFrame.IceCandidate(
                callId = callId,
                from = from,
                sdpMid = fields["mid"]?.ifBlank { null },
                sdpMLineIndex = fields["index"]?.toIntOrNull() ?: 0,
                candidate = fields["candidate"] ?: return null,
            )
            else -> null
        }
    }
}
