package com.transfer.flash.core.calling.protocol

import com.transfer.flash.core.common.perf.FlashNetworkBand

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
        /** The sender's network band (G2); null from a client that predates it. */
        public val band: FlashNetworkBand? = null,
        /** The sender understands the G3 video request protocol (`vr=1`); false from a client that predates it. */
        public val videoRequests: Boolean = false,
    ) : CallWireFrame

    /** Group call: peer accepted and joined the call. */
    public data class GroupAccept(
        override val callId: String,
        override val from: String,
        public val groupId: String,
        /** The sender's network band (G2); null from a client that predates it. */
        public val band: FlashNetworkBand? = null,
        /** The sender understands the G3 video request protocol (`vr=1`); false from a client that predates it. */
        public val videoRequests: Boolean = false,
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
        /** The joiner's network band (G2); null when relayed or from a client that predates it. */
        public val band: FlashNetworkBand? = null,
        /** The sender understands the G3 video request protocol (`vr=1`); false from a client that predates it. */
        public val videoRequests: Boolean = false,
    ) : CallWireFrame

    /** Group call: peer hung up / left the call. */
    public data class GroupHangup(
        override val callId: String,
        override val from: String,
        public val groupId: String,
    ) : CallWireFrame

    /** Group call: presence announcement of an active ongoing call. */
    public data class GroupPresence(
        override val callId: String,
        override val from: String,
        public val groupId: String,
        public val callerName: String,
        public val video: Boolean,
        public val participantCount: Int = 1,
        /** The sender's network band (G2), refreshed with every announcement; null from an old client. */
        public val band: FlashNetworkBand? = null,
        /** The sender understands the G3 video request protocol (`vr=1`); false from a client that predates it. */
        public val videoRequests: Boolean = false,
        /**
         * How many more watchers the sender would serve right now (G3, `vfree=`); 0 while its camera
         * is off or it is at its send cap; null when not stated (old client, audio call).
         */
        public val videoFree: Int? = null,
    ) : CallWireFrame

    /**
     * Group video (G3): the receiver asks one participant for its video, at up to [quality]
     * (a picture height: 720, 540 or 360). Sent only to a peer that announced `vr=1`.
     * [seq] grows with every request or release this receiver sends to anyone, and is
     * wall-clock based, so a request from a rejoined session is never taken for a stale one.
     * [focus] marks a request the user pinned (a tap), which a talking sender will not evict.
     */
    public data class VideoRequest(
        override val callId: String,
        override val from: String,
        public val seq: Long,
        public val quality: Int,
        public val focus: Boolean = false,
    ) : CallWireFrame

    /** Group video (G3): the sender is sending to the requester, at up to [quality]. Echoes the request's [seq]. */
    public data class VideoGrant(
        override val callId: String,
        override val from: String,
        public val seq: Long,
        public val quality: Int,
    ) : CallWireFrame

    /**
     * Group video (G3): the sender refused the request with [seq], or stopped a grant it had
     * given under that [seq] (a talking sender making room). The receiver retries when the
     * sender announces free capacity (`GroupPresence.videoFree`).
     */
    public data class VideoDeny(
        override val callId: String,
        override val from: String,
        public val seq: Long,
        public val reason: VideoDenyReason,
    ) : CallWireFrame

    /** Group video (G3): the receiver no longer wants the sender's video. */
    public data class VideoRelease(
        override val callId: String,
        override val from: String,
        public val seq: Long,
    ) : CallWireFrame

    /** Group call: query whether an active call is ongoing in the group. */
    public data class GroupQuery(
        override val callId: String = "",
        override val from: String,
        public val groupId: String,
    ) : CallWireFrame
}

/** Why a group participant would not send its video (G3). */
public enum class VideoDenyReason(public val wire: String) {
    CAMERA_OFF("camera"),
    SENDER_AT_CAPACITY("busy"),
    /** Reserved for G6 (a hot sender); not sent yet. */
    THERMAL("thermal"),
    ;

    public companion object {
        /** An unknown reason reads as [SENDER_AT_CAPACITY]: the receiver retries on free capacity either way. */
        public fun fromWire(value: String?): VideoDenyReason = entries.firstOrNull { it.wire == value } ?: SENDER_AT_CAPACITY
    }
}
