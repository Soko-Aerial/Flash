package com.transfer.flash.core.calling.protocol

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
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
 *
 * ### SDP transport hardening (ERROR-024, ADR-027)
 *
 * `sdp` fields in [CallWireFrame.Offer] / [CallWireFrame.Answer] are base64-encoded
 * (RFC 4648, see [Base64]) before escaping. This makes SDP payloads immune to any
 * whitespace/newline/escape artifact in the text-framing layer: the wire only ever
 * carries `[A-Za-z0-9+/=]` for the SDP body. Older peers that still send raw SDP
 * (pre-hardening builds) are handled by a fallback in [decode].
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
                // Base64-encoded so no escape/trim artifact can corrupt the SDP (ERROR-024).
                "sdp" to Base64.encodeUtf8(frame.sdp),
            )
            is CallWireFrame.Answer -> listOf(
                "action" to "answer",
                "callId" to frame.callId,
                "from" to frame.from,
                "sdp" to Base64.encodeUtf8(frame.sdp),
            )
            is CallWireFrame.IceCandidate -> listOf(
                "action" to "ice",
                "callId" to frame.callId,
                "from" to frame.from,
                "mid" to (frame.sdpMid ?: ""),
                "index" to frame.sdpMLineIndex.toString(),
                "candidate" to frame.candidate,
            )
            is CallWireFrame.GroupInvite -> buildList {
                add("action" to "ginvite")
                add("callId" to frame.callId)
                add("groupId" to frame.groupId)
                add("from" to frame.from)
                add("name" to frame.callerName)
                add("video" to frame.video.toString())
                if (frame.members.isNotEmpty()) {
                    add("members" to frame.members.joinToString(","))
                }
            }
            is CallWireFrame.GroupAccept -> listOf(
                "action" to "gaccept",
                "callId" to frame.callId,
                "groupId" to frame.groupId,
                "from" to frame.from,
            )
            is CallWireFrame.GroupDecline -> listOf(
                "action" to "gdecline",
                "callId" to frame.callId,
                "groupId" to frame.groupId,
                "from" to frame.from,
            )
            is CallWireFrame.GroupJoin -> listOf(
                "action" to "gjoin",
                "callId" to frame.callId,
                "groupId" to frame.groupId,
                "from" to frame.from,
                "name" to frame.participantName,
            )
            is CallWireFrame.GroupHangup -> listOf(
                "action" to "ghangup",
                "callId" to frame.callId,
                "groupId" to frame.groupId,
                "from" to frame.from,
            )
            is CallWireFrame.GroupPresence -> listOf(
                "action" to "gpresence",
                "callId" to frame.callId,
                "groupId" to frame.groupId,
                "from" to frame.from,
                "name" to frame.callerName,
                "video" to frame.video.toString(),
                "count" to frame.participantCount.toString(),
            )
            is CallWireFrame.GroupQuery -> listOf(
                "action" to "gquery",
                "callId" to frame.callId,
                "groupId" to frame.groupId,
                "from" to frame.from,
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
                sdp = decodeSdp(fields["sdp"]) ?: return null,
            )
            "answer" -> CallWireFrame.Answer(
                callId = callId,
                from = from,
                sdp = decodeSdp(fields["sdp"]) ?: return null,
            )
            "ice" -> CallWireFrame.IceCandidate(
                callId = callId,
                from = from,
                sdpMid = fields["mid"]?.ifBlank { null },
                sdpMLineIndex = fields["index"]?.toIntOrNull() ?: 0,
                candidate = fields["candidate"] ?: return null,
            )
            "ginvite" -> CallWireFrame.GroupInvite(
                callId = callId,
                from = from,
                groupId = fields["groupId"] ?: return null,
                callerName = fields["name"] ?: "Group Member",
                video = fields["video"]?.toBooleanStrictOrNull() ?: false,
                members = fields["members"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            )
            "gaccept" -> CallWireFrame.GroupAccept(
                callId = callId,
                from = from,
                groupId = fields["groupId"] ?: return null,
            )
            "gdecline" -> CallWireFrame.GroupDecline(
                callId = callId,
                from = from,
                groupId = fields["groupId"] ?: return null,
            )
            "gjoin" -> CallWireFrame.GroupJoin(
                callId = callId,
                from = from,
                groupId = fields["groupId"] ?: return null,
                participantName = fields["name"] ?: "Group Member",
            )
            "ghangup" -> CallWireFrame.GroupHangup(
                callId = callId,
                from = from,
                groupId = fields["groupId"] ?: return null,
            )
            "gpresence" -> CallWireFrame.GroupPresence(
                callId = callId,
                from = from,
                groupId = fields["groupId"] ?: return null,
                callerName = fields["name"] ?: "Group Member",
                video = fields["video"]?.toBooleanStrictOrNull() ?: false,
                participantCount = fields["count"]?.toIntOrNull() ?: 1,
            )
            "gquery" -> CallWireFrame.GroupQuery(
                callId = callId,
                from = from,
                groupId = fields["groupId"] ?: return null,
            )
            else -> null
        }
    }

    /**
     * Decodes a [raw] `sdp` field.
     *
     * Newer builds base64-encode the SDP (see [Base64]). Older builds send raw text.
     * Try base64 first; if that fails (not valid base64), fall back to the raw value.
     * Returns null if the field is absent or empty.
     */
    private fun decodeSdp(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // Try base64 first (newer encoding).
        try {
            return Base64.decodeUtf8(raw)
        } catch (_: IllegalArgumentException) {
            // Not valid base64 — probably sent by a pre-hardening peer.
            return raw
        }
    }
}
