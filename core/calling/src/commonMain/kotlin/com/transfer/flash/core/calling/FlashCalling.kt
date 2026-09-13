package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.VideoStreamTrack
import com.transfer.flash.core.calling.model.FlashCallStats
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.OngoingGroupCallUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public voice/video calling contract (ADR-025) — the calling counterpart of `FlashDiscovery`
 * and `FlashNetwork`.
 *
 * Owns at most one live call at a time. A second inbound invite while a call is live is
 * auto-declined "busy" rather than queued, so a caller's UI never hangs on DIALING.
 *
 * Calling is **not** part of the `FlashEngine` facade: it needs a signaling channel the host
 * already owns, plus runtime microphone/camera permissions and a foreground service that only an
 * app can declare. Consumers therefore depend on `core-calling` directly and wire two
 * host-supplied seams:
 *
 *  - **outbound** — every frame this contract emits is handed to the host's `sendFrame` lambda,
 *    which is expected to deliver it to a specific peer over an already-established session.
 *  - **inbound** — the host passes every received text frame to [onInboundText], which returns
 *    true when the text was a call frame it consumed.
 *
 * Signaling is plain text (`FLASH_CALL|…`, see `docs/protocol.md`), so any duplex text transport
 * works — the Flash WebSocket mesh is simply the one the app happens to use.
 *
 * All suspend operations are safe to call from the main dispatcher; they hand off internally.
 */
public interface FlashCalling {

    /**
     * The active call, or null when no call is in flight. Emits on every state transition
     * (DIALING → RINGING → CONNECTING → ACTIVE → ENDED) and is the single input a navigation
     * layer needs to push and pop a call route.
     *
     * A finished call lingers as ENDED for a short grace window so the UI can render the end
     * reason, then goes null.
     */
    public val activeCall: StateFlow<FlashCallUiState?>

    /**
     * Known ongoing group calls advertised by peers, keyed by [OngoingGroupCallUi.groupId].
     */
    public val ongoingGroupCalls: StateFlow<Map<String, OngoingGroupCallUi>>
        get() = MutableStateFlow(emptyMap())

    /**
     * Renderable media for the active call, or null when no call is in flight. Split out from
     * [activeCall] because tracks and statistics are not state a `data class` can carry — they
     * are live objects bound to the platform renderer.
     */
    public val media: FlashCallMedia?

    /**
     * Places an outgoing call. Returns false when a call is already live (one at a time) or the
     * local media capture could not be acquired — typically a missing `RECORD_AUDIO` / `CAMERA`
     * runtime grant, which the host must obtain **before** calling this.
     */
    public suspend fun startCall(peerId: String, peerName: String, video: Boolean): Boolean

    /**
     * Places an outgoing group call to [memberIds] in [groupId]. Returns false when a call is
     * already live or local media capture could not be acquired.
     */
    public suspend fun startGroupCall(
        groupId: String,
        groupName: String,
        memberIds: List<String>,
        video: Boolean,
    ): Boolean = false

    /**
     * Joins an ongoing group call announced by peers in [groupId].
     */
    public suspend fun joinGroupCall(
        groupId: String,
        callId: String,
        memberIds: List<String>,
        video: Boolean = false,
    ): Boolean = false

    /**
     * Queries online members of [groupId] to discover if an active call is ongoing.
     */
    public suspend fun queryGroupCall(groupId: String, memberIds: List<String>): Unit {}

    /**
     * Accepts the ringing inbound call and starts local media. Returns false when there is
     * nothing to accept.
     *
     * The host must hold the runtime microphone grant (and the camera grant for a video call)
     * before this returns true, and should put the platform audio route into its
     * communication mode first — a microphone opened in the wrong mode does not switch later.
     */
    public suspend fun accept(): Boolean

    /** Declines the ringing inbound call. Returns false when there is nothing to decline. */
    public suspend fun decline(): Boolean

    /** Ends the live call. Returns false when there is no live call. */
    public suspend fun hangUp(): Boolean

    /** Toggles the local microphone. Returns the resulting muted state; false when idle. */
    public fun toggleMute(): Boolean

    /** Toggles the local camera. Returns the resulting camera-off state; false when idle. */
    public fun toggleCamera(): Boolean

    /** Flips between the front and rear camera. No-op when idle or on an audio-only call. */
    public suspend fun switchCamera()

    /**
     * Records the caller's speakerphone preference on [activeCall]. Selecting the physical
     * output device is the host's job — platform audio routing is not reachable from any
     * per-call API, so a consumer mirrors this flag onto its own `AudioManager` policy.
     */
    public fun setSpeaker(on: Boolean)

    /**
     * Feeds one inbound text frame in. Returns true when the text was a `FLASH_CALL` frame that
     * was consumed, false when it is not a call frame at all — letting a host chain this ahead
     * of its other text handlers.
     *
     * Frames for unknown or already-finished call ids are dropped silently.
     */
    public suspend fun onInboundText(peerId: String, text: String): Boolean

    /**
     * Notifies the contract that the signaling channel to [peerId] died.
     *
     * Opens a recovery window rather than ending the call outright (ERROR-033). A mesh Wi-Fi roam
     * takes the signaling session down as a matter of course — same radio, same association — and
     * the transport layer redials it within seconds. Ending the call on the spot meant a two-second
     * radio outage was indistinguishable from the peer hanging up. A call that is genuinely
     * unreachable still ends, once the window closes.
     *
     * Pair this with [onSignalingRestored] or the window is the only thing keeping the call, and
     * every roam costs the full grace period even when signaling came back immediately.
     */
    public fun onSignalingLost(peerId: String)

    /**
     * Notifies the contract that a signaling channel to [peerId] is live again — a host should
     * call this whenever a session comes up, not only after an [onSignalingLost].
     *
     * Closes the window [onSignalingLost] opened and lets the call resume renegotiating: the media
     * path still has to be rebuilt, and the ICE restart offer that does it needs this channel to
     * travel on.
     */
    public fun onSignalingRestored(peerId: String)
}

/**
 * Renderable media and live quality metrics for one call.
 *
 * Deliberately read-only: controls live on [FlashCalling] so a UI layer can bind video and a
 * latency readout without being handed the ability to mutate the call.
 *
 * Video tracks are exposed as `StateFlow` rather than plain values because a track's identity
 * changes mid-call — renegotiation, a camera flip, or the remote peer enabling video all swap
 * the object. A renderer must re-bind on every emission and must **not** be released on a track
 * change; releasing an `EglRenderer` is terminal and leaves the surface permanently black.
 *
 * [VideoStreamTrack] comes from webrtc-kmp. This is the one place Flash lets a third-party type through
 * a public boundary: a renderer has to be handed the real track, and any wrapper would have to
 * expose it again to be useful (ADR-025). `:core:calling` re-exports webrtc-kmp via `api()` so
 * consumers get the type transitively.
 */
public interface FlashCallMedia {

    /**
     * Live call quality, sampled about once a second while media flows: RTT, jitter, frame rate,
     * negotiated resolutions, bitrates and packet loss. Null before the first sample.
     */
    public val stats: StateFlow<FlashCallStats?>

    /** The local camera track, or null on an audio-only call or while the camera is off. */
    public val localVideoStreamTrack: StateFlow<VideoStreamTrack?>

    /** The remote camera track, or null until the peer publishes video. */
    public val remoteVideoStreamTrack: StateFlow<VideoStreamTrack?>
}
