package com.transfer.flash.core.calling.model

import com.transfer.flash.core.common.perf.FlashNetworkBand

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

    /** Local error (SDP, transport, WebRTC failure). Media failures have their own reasons below. */
    ERROR,

    /** ERROR-105: this device could not start the call because the microphone permission is not granted. */
    MIC_DENIED,

    /** ERROR-105: this device could not start the call because the microphone could not be opened (busy or missing). */
    MIC_UNAVAILABLE,

    /**
     * Group calls (G7): the call already had [FlashGroupCallLimits.maxParticipants] people when this
     * device tried to join or accept, so a participant turned it away.
     */
    FULL,
}

/** ERROR-105: a one-line, informational message about this device's own call media; the call carries on. */
public enum class FlashCallNotice {
    /** The camera permission is not granted, so this device joined without sending video. */
    CAMERA_DENIED_AUDIO_ONLY,

    /** The camera could not be opened (busy or missing), so this device joined without sending video. */
    CAMERA_UNAVAILABLE_AUDIO_ONLY,
}

/** ERROR-105: something went wrong with this device's own camera during a call. The call carries on (audio). */
public enum class FlashCameraProblem {
    /** The camera stopped delivering (taken by another app, unplugged, a driver error): it is off until restarted. */
    FAILED,

    /** Switching to the other camera did not work; the current camera still runs. Clears itself after a few seconds. */
    SWITCH_FAILED,

    /** ADR-078: turning the camera on in a call (adding it mid-call) did not work; the call carries on. Clears itself. */
    UPGRADE_FAILED,
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
    /** Whether this is a multi-participant group call. */
    public val isGroup: Boolean = false,
    /** Group chat id if this is a group call. */
    public val groupId: String? = null,
    /** Current roster of participants in the call with their connection and speaking status. */
    public val participants: List<FlashCallParticipantUi> = emptyList(),
    /**
     * Group video (G5, UI-050c): this device receives one video at a time (LOW tier, or a health
     * cap), so the screen shows one main tile and a strip instead of a grid.
     */
    public val compactVideo: Boolean = false,
    /** Group video (G5): the participant the user pinned, or null while following the speaker. */
    public val videoFocusPeerId: String? = null,
    /** Group video (G5): whom the main tile shows: the pinned participant, else the followed speaker. */
    public val videoMainPeerId: String? = null,
    /** Group video (G6, UI-050d): why the device is struggling, or null when it is fine. */
    public val healthWarning: FlashCallHealthWarning? = null,
    /** Group video (G6): receiving is capped at one video, by "Show fewer" or automatically when hot. */
    public val showingFewerVideos: Boolean = false,
    /**
     * Group video (ADR-053): the user's "Send smaller video in groups" setting is on, so the CPU
     * banner no longer offers it.
     */
    public val smallerVideoForMany: Boolean = false,
    /**
     * ADR-067 (UI-050f): the other person's microphone is muted. 1:1 calls only; a group call carries it on
     * [FlashCallParticipantUi.isMuted]. False until the peer says otherwise (an older client never does).
     */
    public val peerMicMuted: Boolean = false,
    /** ADR-067: the other person turned their camera off. 1:1 video calls only (see [FlashCallParticipantUi.cameraOff]). */
    public val peerCameraOff: Boolean = false,
    /** ADR-067: the other person's hand is raised. 1:1 calls only (see [FlashCallParticipantUi.handRaised]). */
    public val peerHandRaised: Boolean = false,
    /** ADR-067: this device's own hand is raised. */
    public val handRaised: Boolean = false,
    /**
     * ADR-067 data saver: this device receives no video (the call carries on as audio). In a 1:1 call the peer is told and
     * stops encoding for us; in a group the router asks nobody for video.
     */
    public val dataSaver: Boolean = false,
    /** ADR-067: the other person is on data saver, so this device is not sending video to them. 1:1 calls only. */
    public val peerDataSaver: Boolean = false,
    /** ADR-067: reactions sent in the last few seconds (ours and theirs), oldest first; expired ones are removed. */
    public val reactions: List<FlashCallReaction> = emptyList(),
    /** ERROR-105: why this device is on a video call without sending video, or null. */
    public val notice: FlashCallNotice? = null,
    /** ERROR-105: the local camera stopped or could not switch, or null. [FlashCameraProblem.FAILED] also sets [cameraOff]. */
    public val cameraProblem: FlashCameraProblem? = null,
    /**
     * ADR-078: a live 1:1 voice call whose peer can take a camera added mid-call (`cv1`), so the dock may offer
     * "Turn on camera". False on a video call, a group call, a call that is not connected and for an older peer.
     */
    public val canUpgradeToVideo: Boolean = false,
) {
    /**
     * The devices this call needs a session with right now; the connection-mode controller keeps
     * exactly these alive and dials them first. A 1:1 call: [peerId]. A group call: its [peerId]
     * is the *group* id, which is no device, so asking for it protected nobody and the call's
     * real sessions could be parked (ERROR-086 (k)); the participants are listed instead. A
     * participant that is only INVITED counts while the call is still ringing or dialing (it is
     * who the call is waiting for) and not once the call is live (then INVITED means "not in the
     * call", e.g. a member given up on). Empty for an ended call.
     */
    public val busyPeerIds: Set<String>
        get() = when {
            state == FlashCallState.ENDED -> emptySet()
            !isGroup -> setOf(peerId)
            else -> {
                val waiting = state == FlashCallState.DIALING || state == FlashCallState.RINGING
                participants.filter { p ->
                    when (p.state) {
                        FlashCallParticipantState.CONNECTING,
                        FlashCallParticipantState.CONNECTED,
                        FlashCallParticipantState.DISCONNECTED,
                        -> true
                        FlashCallParticipantState.INVITED -> waiting
                        FlashCallParticipantState.LEFT -> false
                    }
                }.mapTo(LinkedHashSet()) { it.peerId }
            }
        }
}

/**
 * How many people a group call holds, this device included (G7, `docs/calling/GROUP-VIDEO-PLAN.md` §5;
 * owner decision Q6). Every device runs one connection per other participant (a mesh), so the caps
 * follow what a LOW phone can carry: 8 in a video call, 12 in a voice call (15 only after a measured
 * 12-person test).
 */
public object FlashGroupCallLimits {
    public const val MAX_VIDEO_PARTICIPANTS: Int = 8
    public const val MAX_VOICE_PARTICIPANTS: Int = 12

    public fun maxParticipants(video: Boolean): Int = if (video) MAX_VIDEO_PARTICIPANTS else MAX_VOICE_PARTICIPANTS
}

/** A group video call's health warning (G6, `docs/calling/GROUP-VIDEO-PLAN.md` §4.5). */
public enum class FlashCallHealthWarning {
    /** The phone is warming up (thermal MODERATE). "Show fewer" is offered. */
    WARM,

    /** The phone is hot (thermal SEVERE or worse): it already receives only one video. */
    HOT,

    /** The call has kept the processor busy for a while. "Show fewer" is offered. */
    CPU,

    /** Videos are decoded in software while two or more arrive. "Show fewer" is offered. */
    SOFTWARE_DECODE,
}

/** Connection and presence status of a participant in a group call. */
public enum class FlashCallParticipantState {
    INVITED,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    LEFT,
}

/** One participant in a group call (Phase 2). */
public data class FlashCallParticipantUi(
    public val peerId: String,
    public val name: String,
    public val isSpeaking: Boolean = false,
    public val isMuted: Boolean = false,
    public val state: FlashCallParticipantState = FlashCallParticipantState.CONNECTED,
    /** Whether this device is getting the participant's video in a group video call (G3). */
    public val video: FlashParticipantVideo = FlashParticipantVideo.OFF,
    /**
     * False while this device invited the participant but its invite has not gone out on a live session (ERROR-088: no
     * session could be dialed yet). The caller sees "Not reachable yet" instead of "Invited".
     */
    public val reachable: Boolean = true,
    /**
     * Why this device cannot call the participant at all (ERROR-095), for example "Not paired with you": a member of
     * the group this device left out of the call because nothing lets it trust them. Shown instead of the state word,
     * so a caller sees who is missing and why instead of a call that quietly leaves someone out.
     */
    public val note: String? = null,
    /** ADR-067: the participant turned their camera off (they said so; false until they do). Video calls only. */
    public val cameraOff: Boolean = false,
    /** ADR-067: the participant's hand is raised. */
    public val handRaised: Boolean = false,
)

/** An emoji reaction sent during a call (ADR-067). The wire names are part of `docs/protocol.md`. */
public enum class FlashCallReactionKind(public val wire: String) {
    LIKE("like"),
    LOVE("love"),
    WOW("wow"),
    ;

    public companion object {
        /** An unknown name is not a reaction: a newer client's kind must not be shown as something else. */
        public fun fromWire(value: String?): FlashCallReactionKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One reaction on screen. [id] is local and unique within the call, so a UI can key an animation by it;
 * [peerId] is who sent it (this device's own id for ours); [atMs] is when it arrived.
 */
public data class FlashCallReaction(
    public val id: Long,
    public val peerId: String,
    public val kind: FlashCallReactionKind,
    public val atMs: Long,
)

/** How good the link to the other end looks, from [FlashCallStats] (ADR-067 connection chip). */
public enum class FlashLinkQuality { UNKNOWN, GOOD, FAIR, POOR }

/**
 * Grades the link: RTT and packet loss, the two numbers a person can feel. Thresholds are a first guess for a LAN
 * (GOOD under 60 ms and 2 % loss, POOR over 200 ms or 8 % loss) and are not measured yet; see MEAS in the test backlog.
 */
public fun FlashCallStats.linkQuality(): FlashLinkQuality {
    val rtt = rttMs
    val loss = packetLoss
    if (rtt == null && loss == null) return FlashLinkQuality.UNKNOWN
    if ((rtt != null && rtt > LINK_POOR_RTT_MS) || (loss != null && loss > LINK_POOR_LOSS)) return FlashLinkQuality.POOR
    if ((rtt != null && rtt > LINK_GOOD_RTT_MS) || (loss != null && loss > LINK_GOOD_LOSS)) return FlashLinkQuality.FAIR
    return FlashLinkQuality.GOOD
}

private const val LINK_GOOD_RTT_MS = 60
private const val LINK_POOR_RTT_MS = 200
private const val LINK_GOOD_LOSS = 0.02
private const val LINK_POOR_LOSS = 0.08

/** This device's view of one participant's video in a group call (G3 request protocol). */
public enum class FlashParticipantVideo {
    /** Not asked for (outside the receive limit, or not a video call). */
    OFF,

    /** Asked for; no answer yet. */
    REQUESTED,

    /** The participant granted the request and is sending. */
    RECEIVING,

    /** The participant is at its send limit; retried when it announces room. */
    BUSY,

    /** The participant's device is too hot to send more video; retried when it announces room. */
    SENDER_HOT,

    /** Asked for, and still unanswered after several tries (the request or its answer keeps getting lost). */
    NO_RESPONSE,

    /** The participant's camera is off; retried when it turns the camera on. */
    CAMERA_OFF,

    /** An older client that sends its video to everyone without being asked. */
    UNMANAGED,
}

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
    /**
     * Group calls (G2): the slowest participant link's band, the slower end of each connection.
     * Null on a 1:1 call and before any participant has announced one.
     */
    public val networkBand: FlashNetworkBand? = null,
) {
    /** True once anything at all has been measured (used to gate the UI readout). */
    public val hasData: Boolean
        get() = rttMs != null || inboundKbps != null || outboundKbps != null || fps != null || audioJitterMs != null || packetLoss != null || sendWidth != null

    /** `"1080p"`-style label for the received video, or null before the first frame. */
    public val remoteResolutionLabel: String?
        get() {
            val w = remoteWidth ?: return null
            val h = remoteHeight ?: return null
            if (w <= 0 || h <= 0) return null
            return "${minOf(w, h)}p"
        }

    /** `"1080p"`-style label for the video being sent, or null before first frame. */
    public val sendResolutionLabel: String?
        get() {
            val w = sendWidth ?: return null
            val h = sendHeight ?: return null
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

/**
 * UI representation of an ongoing group call announced by peers in a group chat.
 */
public data class OngoingGroupCallUi(
    public val callId: String,
    public val groupId: String,
    public val groupName: String,
    public val initiatorId: String,
    public val video: Boolean,
    public val participantCount: Int = 1,
    public val lastSeenTimestamp: Long = System.currentTimeMillis(),
)

