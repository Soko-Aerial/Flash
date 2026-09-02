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
    /**
     * Why outgoing video is currently being held back to protect call audio, or null when it
     * is not being held back at all.
     *
     * Set by the audio-protective governor (D8): on a congested link Flash spends the
     * available bitrate on voice first, which makes the picture visibly worse. Without this
     * string that looks like a bug in the app rather than a deliberate trade, so the call
     * screen renders it verbatim.
     */
    public val videoLimitReason: String? = null,
)

/**
 * Live transport metrics sampled from `PeerConnection.getStats()` once a second.
 *
 * This is what the call screen's latency readout renders. Every field is nullable because
 * WebRTC publishes each one only once the corresponding report exists: RTT needs the first
 * RTCP round trip on the selected candidate pair, framerate/resolution need a decoded
 * frame, and bitrate needs two samples to difference. `null` means "not measured yet",
 * never "zero".
 */
public data class FlashCallStats(
    /** Round-trip time on the selected ICE candidate pair, ms. One-way latency ≈ half. */
    public val rttMs: Int? = null,
    /** Inbound audio jitter, ms — the receiver's buffer has to absorb at least this much. */
    public val audioJitterMs: Int? = null,
    /** Inbound video jitter, ms. */
    public val videoJitterMs: Int? = null,
    /** Decode framerate of the remote video, fps. */
    public val fps: Int? = null,
    /** Remote video frame size as received — drops below capture size under constraint. */
    public val remoteWidth: Int? = null,
    public val remoteHeight: Int? = null,
    /** Inbound bitrate across audio+video, kbps, differenced over the sampling interval. */
    public val inboundKbps: Int? = null,
    /** Outbound bitrate across audio+video, kbps. */
    public val outboundKbps: Int? = null,
    /** Encoder frame size currently being sent — the adaptive-downscale readout. */
    public val sendWidth: Int? = null,
    public val sendHeight: Int? = null,
    /** Inbound packet loss over the whole call, as a fraction 0..1. */
    public val packetLoss: Double? = null,
) {
    /** True once anything at all has been measured (used to gate the UI readout). */
    public val hasData: Boolean
        get() = rttMs != null || inboundKbps != null || fps != null

    /** `"1080p"`-style label for the received video, or null before the first frame. */
    public val remoteResolutionLabel: String?
        get() {
            val w = remoteWidth ?: return null
            val h = remoteHeight ?: return null
            if (w <= 0 || h <= 0) return null
            return "${minOf(w, h)}p"
        }
}

/**
 * A finished call, as recorded in the chat thread.
 *
 * Deliberately a plain record with no messaging types in it: `core:calling` must not depend
 * on `core:messaging` (port/adapter inversion, ADR-024). The host receives this from
 * [com.transfer.flash.core.calling.CallCoordinator] and writes the chat row itself.
 *
 * Both devices already hold every field locally when a call ends, so each writes its own
 * row — no new wire frame, no protocol change.
 */
public data class FlashCallLogEntry(
    public val callId: String,
    /** Peer device id — doubles as the conversation id. */
    public val peerId: String,
    public val peerName: String,
    public val direction: FlashCallDirection,
    public val video: Boolean,
    public val endReason: FlashCallEndReason,
    /** How long media actually flowed, ms. Zero when the call never connected. */
    public val durationMs: Long,
    /** Epoch ms when the call ended. */
    public val endedAt: Long,
) {
    /**
     * True when an incoming call never carried media — the one case that deserves a
     * different colour in the thread. Covers both "declined" and "the caller gave up",
     * which the wire protocol does not distinguish (both end as
     * [FlashCallEndReason.NORMAL]) and which read the same way in a call log.
     */
    public val missed: Boolean
        get() = durationMs <= 0L && direction == FlashCallDirection.INCOMING
}
