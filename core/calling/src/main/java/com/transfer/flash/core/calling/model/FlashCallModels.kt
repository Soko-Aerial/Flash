package com.transfer.flash.core.calling.model

/**
 * Lifecycle states of a Flash call (C7, ADR-025). One [FlashCallSession.state] machine
 * per call; transitions are driven by signaling frames and WebRTC connection events.
 */
public enum class FlashCallState {
    /** Outgoing: invite sent, waiting for the callee to accept. */
    DIALING,

    /** Incoming: invite received, waiting for the local user to accept/decline. */
    RINGING,

    /** Accepted: SDP exchange / ICE connectivity in progress. */
    CONNECTING,

    /** Media flowing. */
    ACTIVE,

    /** Terminated normally (hangup) or abnormally (decline, error, disconnect). */
    ENDED,
}

/** Direction of the call relative to this device. */
public enum class FlashCallDirection {
    /** This device placed the call. */
    OUTGOING,

    /** This device received the call. */
    INCOMING,
}

/** Why a call ended — surfaced to the UI as the final status line. */
public enum class FlashCallEndReason {
    /** Local or remote user hung up after connecting. */
    NORMAL,

    /** Callee declined the invite. */
    DECLINED,

    /** Callee never answered (timeout). */
    NO_ANSWER,

    /** Signaling session died mid-call. */
    DISCONNECTED,

    /** Local error (permissions, device media, WebRTC failure). */
    ERROR,
}

/**
 * Immutable snapshot of a call, exposed to the UI as a StateFlow from
 * [com.transfer.flash.core.calling.FlashCallSession].
 */
public data class FlashCallUiState(
    public val callId: String,
    /** Peer device id — doubles as the conversation id (WS mesh identity). */
    public val peerId: String,
    public val peerName: String,
    public val direction: FlashCallDirection,
    public val video: Boolean,
    public val state: FlashCallState,
    public val endReason: FlashCallEndReason? = null,
    /** Milliseconds since epoch when the call became ACTIVE; null before that. */
    public val connectedAt: Long? = null,
    /** Local mic muted. */
    public val micMuted: Boolean = false,
    /** Local camera disabled (video calls only). */
    public val cameraOff: Boolean = false,
    /** Speakerphone on (audio routing is app-owned; ADR-025). */
    public val speakerOn: Boolean = false,
)
