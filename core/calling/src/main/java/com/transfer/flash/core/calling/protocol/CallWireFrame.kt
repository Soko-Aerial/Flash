package com.transfer.flash.core.calling.protocol

/**
 * Calling signaling frames exchanged over Flash WS mesh text frames (C7, ADR-025).
 *
 * Encoded under the `FLASH_CALL` prefix with [com.transfer.flash.core.common.protocol.FlashTextFraming]
 * field rules — see `docs/protocol.md` "Calling" section for the wire format.
 */
public sealed interface CallWireFrame {

    /** Common fields for every call frame. */
    public val callId: String
    public val from: String

    /**
     * Caller -> callee: start a call. [video] declares audio-only vs video intent.
     */
    public data class Invite(
        override val callId: String,
        override val from: String,
        public val callerName: String,
        public val video: Boolean,
    ) : CallWireFrame

    /** Callee -> caller: user accepted the incoming call. */
    public data class Accept(
        override val callId: String,
        override val from: String,
    ) : CallWireFrame

    /** Callee -> caller: user declined, or auto-declined (busy). */
    public data class Decline(
        override val callId: String,
        override val from: String,
    ) : CallWireFrame

    /** Either side: call is over (user hangup or local teardown). */
    public data class Hangup(
        override val callId: String,
        override val from: String,
    ) : CallWireFrame

    /** Caller -> callee: SDP offer (sent immediately after [Accept] arrives). */
    public data class Offer(
        override val callId: String,
        override val from: String,
        public val sdp: String,
    ) : CallWireFrame

    /** Callee -> caller: SDP answer. */
    public data class Answer(
        override val callId: String,
        override val from: String,
        public val sdp: String,
    ) : CallWireFrame

    /**
     * Either side: trickled ICE candidate. Receivers buffer until the remote description
     * is set (webrtc-kmp sample pattern).
     */
    public data class IceCandidate(
        override val callId: String,
        override val from: String,
        public val sdpMid: String?,
        public val sdpMLineIndex: Int,
        public val candidate: String,
    ) : CallWireFrame

    /** Group call: initiator invites group members. */
    public data class GroupInvite(
        override val callId: String,
        override val from: String,
        public val groupId: String,
        public val callerName: String,
        public val video: Boolean,
        public val members: List<String> = emptyList(),
    ) : CallWireFrame

    /** Group call: peer accepted and joined the call. */
    public data class GroupAccept(
        override val callId: String,
        override val from: String,
        public val groupId: String,
    ) : CallWireFrame

    /** Group call: peer declined the invitation. */
    public data class GroupDecline(
        override val callId: String,
        override val from: String,
        public val groupId: String,
    ) : CallWireFrame

    /** Group call: peer announces joining an active call. */
    public data class GroupJoin(
        override val callId: String,
        override val from: String,
        public val groupId: String,
        public val participantName: String,
    ) : CallWireFrame

    /** Group call: peer hung up / left the call. */
    public data class GroupHangup(
        override val callId: String,
        override val from: String,
        public val groupId: String,
    ) : CallWireFrame
}
