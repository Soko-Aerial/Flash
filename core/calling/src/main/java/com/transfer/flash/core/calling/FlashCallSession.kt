package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.AudioTrack
import com.shepeliev.webrtckmp.CameraPermissionException
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.MediaStreamTrackKind
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.PeerConnectionState
import com.shepeliev.webrtckmp.RecordAudioPermissionException
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.VideoTrack
import com.shepeliev.webrtckmp.audioTracks
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onTrack
import com.shepeliev.webrtckmp.videoTracks
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.logging.FlashLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One 1:1 WebRTC call session (C7, ADR-025).
 *
 * Owns the [PeerConnection], local media (getUserMedia), the SDP/ICE exchange, and the
 * call state machine. Signaling frames are sent through [sendFrame] (host wires this to
 * the WS mesh) and delivered inbound through [onInboundFrame].
 *
 * The session is single-use: once [state] reaches [FlashCallState.ENDED] it cannot be
 * restarted; the host creates a new session per call.
 *
 * Media transport: WebRTC with EMPTY iceServers — Flash is LAN/hotspot-only, host
 * candidates connect on-link (ADR-025). No STUN/TURN.
 *
 * Verified against webrtc-kmp 0.125.11 commonMain/androidMain sources:
 * - `signalingState` is a plain property (not a Flow).
 * - `addTrack(track, vararg streams: MediaStream)`.
 * - `onTrack` emits [com.shepeliev.webrtckmp.TrackEvent] (track + streams list).
 * - `createOffer/createAnswer(options)` require [OfferAnswerOptions].
 * - SDP types are [SessionDescriptionType] (no "SdpType" in 0.125.11).
 * - `IceCandidate(sdpMid: String, ...)` — mid is non-null on the wire ("" when absent).
 * - `VideoTrack.switchCamera()` is suspend.
 * - Permissions are checked at getUserMedia time (throws on missing).
 */
@OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)
public class FlashCallSession(
    public val callId: String,
    public val peerId: String,
    public val peerName: String,
    public val direction: FlashCallDirection,
    public val video: Boolean,
    /**
     * This device's id — every outbound frame's `from` field (protocol.md: `from` is
     * always the SENDER's device id; the WS session's peer id is the conversation).
     */
    private val localDeviceId: String,
    /** Local display name — the invite's `name` field. */
    private val localName: String,
    private val scope: CoroutineScope,
    private val sendFrame: suspend (CallWireFrame) -> Boolean,
    /** Called exactly once when the session terminates, on the session scope. */
    private val onEnded: (FlashCallSession) -> Unit = {},
    /** Ring timeout for outgoing calls (ms) — auto NO_ANSWER hangup. */
    private val dialTimeoutMs: Long = 45_000L,
    /** Grace window after ICE Disconnected before the call is declared lost (ms). */
    private val disconnectGraceMs: Long = 5_000L,
) {
    private val _state = MutableStateFlow(
        FlashCallUiState(
            callId = callId,
            peerId = peerId,
            peerName = peerName,
            direction = direction,
            video = video,
            state = if (direction == FlashCallDirection.OUTGOING) {
                FlashCallState.DIALING
            } else {
                FlashCallState.RINGING
            },
        ),
    )
    public val state: StateFlow<FlashCallUiState> = _state.asStateFlow()

    /** Local audio track once media is started; null before/after. */
    public val localAudioTrack: AudioTrack? get() = localStream?.audioTracks?.firstOrNull()

    /** Local video track once media is started; null for audio calls / after release. */
    public val localVideoTrack: VideoTrack? get() = localStream?.videoTracks?.firstOrNull()

    /** Remote audio track once the peer's stream arrives; null before/after. */
    public val remoteAudioTrack: AudioTrack? get() = remoteStream?.audioTracks?.firstOrNull()

    /** Remote video track once the peer's stream arrives; null before/after. */
    public val remoteVideoTrack: VideoTrack? get() = remoteStream?.videoTracks?.firstOrNull()

    private var peerConnection: PeerConnection? = null
    private var localStream: MediaStream? = null
    private var remoteStream: MediaStream? = null

    /** ICE candidates that arrived before the remote description was set (trickle buffer). */
    private val pendingIce = mutableListOf<IceCandidate>()
    private val iceMutex = Mutex()

    private val eventJobs = mutableListOf<Job>()
    private var dialTimeoutJob: Job? = null
    private var disconnectGraceJob: Job? = null
    private var ended = false

    // ------------------------------------------------------------------ outbound

    /**
     * OUTGOING call entry point: sends the invite. The host calls this after creating
     * the session; media is NOT started until the callee accepts (permissions are only
     * requested once the call is actually going ahead).
     */
    public suspend fun startOutgoing(): Boolean {
        check(direction == FlashCallDirection.OUTGOING) { "startOutgoing on incoming session" }
        val sent = sendFrame(
            CallWireFrame.Invite(
                callId = callId,
                from = localDeviceId,
                callerName = localName,
                video = video,
            ),
        )
        if (!sent) {
            end(FlashCallEndReason.ERROR, notifyPeer = false)
            return false
        }
        dialTimeoutJob = scope.launch {
            delay(dialTimeoutMs)
            if (_state.value.state == FlashCallState.DIALING) {
                end(FlashCallEndReason.NO_ANSWER, notifyPeer = true)
            }
        }
        return true
    }

    /**
     * INCOMING call: local user accepted. Sends accept, starts media + WebRTC, then
     * waits for the caller's offer. Returns false if media could not start (permissions
     * denied / device error) — the session is ended in that case.
     */
    public suspend fun accept(): Boolean {
        if (_state.value.state != FlashCallState.RINGING) return false
        val sent = sendFrame(CallWireFrame.Accept(callId = callId, from = localDeviceId))
        if (!sent) {
            end(FlashCallEndReason.ERROR, notifyPeer = false)
            return false
        }
        _state.value = _state.value.copy(state = FlashCallState.CONNECTING)
        if (!startMedia()) {
            end(FlashCallEndReason.ERROR, notifyPeer = true)
            return false
        }
        return true
    }

    /** INCOMING call: local user declined. */
    public suspend fun decline() {
        if (_state.value.state != FlashCallState.RINGING) return
        sendFrame(CallWireFrame.Decline(callId = callId, from = localDeviceId))
        end(FlashCallEndReason.NORMAL, notifyPeer = false)
    }

    /** Either side: hang up an in-progress call. */
    public suspend fun hangUp() {
        if (_state.value.state == FlashCallState.ENDED) return
        sendFrame(CallWireFrame.Hangup(callId = callId, from = localDeviceId))
        end(FlashCallEndReason.NORMAL, notifyPeer = false)
    }

    /** Toggle local mic mute. Returns the new muted state; no-op (returns current) without media. */
    public fun toggleMute(): Boolean {
        val track = localAudioTrack ?: return _state.value.micMuted
        track.enabled = !track.enabled
        val muted = !track.enabled
        _state.value = _state.value.copy(micMuted = muted)
        return muted
    }

    /** Toggle local camera on/off (video calls). Returns the new off state. */
    public fun toggleCamera(): Boolean {
        val track = localVideoTrack ?: return _state.value.cameraOff
        track.enabled = !track.enabled
        val off = !track.enabled
        _state.value = _state.value.copy(cameraOff = off)
        return off
    }

    /** Switch front/back camera (video calls). No-op without a video track. */
    public suspend fun switchCamera() {
        localVideoTrack?.switchCamera()
    }

    /** Speakerphone toggle state (audio routing is host-owned; ADR-025). */
    public fun setSpeaker(on: Boolean) {
        _state.value = _state.value.copy(speakerOn = on)
    }

    // ------------------------------------------------------------------ inbound

    /**
     * Host delivers every decoded [CallWireFrame] whose [CallWireFrame.callId] matches
     * this session. Mismatched frames are the host's problem, not ours.
     */
    public suspend fun onInboundFrame(frame: CallWireFrame) {
        when (frame) {
            is CallWireFrame.Accept -> onAccept()
            is CallWireFrame.Decline -> end(FlashCallEndReason.DECLINED, notifyPeer = false)
            is CallWireFrame.Hangup -> end(FlashCallEndReason.NORMAL, notifyPeer = false)
            is CallWireFrame.Offer -> onOffer(frame)
            is CallWireFrame.Answer -> onAnswer(frame)
            is CallWireFrame.IceCandidate -> onIce(frame)
            is CallWireFrame.Invite -> Unit // duplicate invite — host keys sessions by callId
        }
    }

    private suspend fun onAccept() {
        if (_state.value.state != FlashCallState.DIALING) return
        dialTimeoutJob?.cancel()
        _state.value = _state.value.copy(state = FlashCallState.CONNECTING)
        if (!startMedia()) {
            end(FlashCallEndReason.ERROR, notifyPeer = true)
            return
        }
        // Caller is the offerer (glare-free: only the caller offers — ADR-025).
        val pc = peerConnection ?: return
        try {
            val offer = pc.createOffer(
                OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = video),
            )
            logSdp("local offer", offer.sdp)
            pc.setLocalDescription(offer)
            sendFrame(
                CallWireFrame.Offer(
                    callId = callId,
                    from = localDeviceId,
                    sdp = offer.sdp,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never crash on an SDP failure — tear the call down cleanly (ERROR-024).
            FlashLog.e("CALL", "onAccept SDP flow failed: ${e.message}", e)
            end(FlashCallEndReason.ERROR, notifyPeer = true)
        }
    }

    private suspend fun onOffer(frame: CallWireFrame.Offer) {
        val pc = peerConnection
        if (pc == null || _state.value.state != FlashCallState.CONNECTING) return
        try {
            logSdp("remote offer", frame.sdp)
            pc.setRemoteDescription(SessionDescription(SessionDescriptionType.Offer, frame.sdp))
            flushPendingIce()
            val answer = pc.createAnswer(
                OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = video),
            )
            logSdp("local answer", answer.sdp)
            pc.setLocalDescription(answer)
            sendFrame(
                CallWireFrame.Answer(
                    callId = callId,
                    from = localDeviceId,
                    sdp = answer.sdp,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FlashLog.e("CALL", "onOffer SDP flow failed: ${e.message}", e)
            end(FlashCallEndReason.ERROR, notifyPeer = true)
        }
    }

    private suspend fun onAnswer(frame: CallWireFrame.Answer) {
        val pc = peerConnection ?: return
        if (_state.value.state != FlashCallState.CONNECTING) return
        try {
            logSdp("remote answer", frame.sdp)
            pc.setRemoteDescription(SessionDescription(SessionDescriptionType.Answer, frame.sdp))
            flushPendingIce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FlashLog.e("CALL", "onAnswer SDP flow failed: ${e.message}", e)
            end(FlashCallEndReason.ERROR, notifyPeer = true)
        }
    }

    /**
     * Logs SDP diagnostics (length, first line, empty flag) without logging the full
     * body — SDP contains IPs/candidates but no secrets; still, keep logs lean.
     */
    private fun logSdp(label: String, sdp: String) {
        val firstLine = sdp.lineSequence().firstOrNull().orEmpty()
        FlashLog.i(
            "CALL",
            "$label sdp len=${sdp.length} empty=${sdp.isEmpty()} first=${firstLine.take(80)}",
        )
    }

    private suspend fun onIce(frame: CallWireFrame.IceCandidate) {
        val pc = peerConnection ?: return
        val candidate = IceCandidate(
            sdpMid = frame.sdpMid ?: "",
            sdpMLineIndex = frame.sdpMLineIndex,
            candidate = frame.candidate,
        )
        iceMutex.withLock {
            if (pc.remoteDescription != null) {
                pc.addIceCandidate(candidate)
            } else {
                // Remote description not applied yet — buffer (webrtc-kmp sample pattern).
                pendingIce.add(candidate)
            }
        }
    }

    private suspend fun flushPendingIce() {
        val pc = peerConnection ?: return
        iceMutex.withLock {
            while (pendingIce.isNotEmpty()) {
                pc.addIceCandidate(pendingIce.removeAt(0))
            }
        }
    }

    // ------------------------------------------------------------------ media

    /**
     * Starts getUserMedia + PeerConnection. Called on accept (both sides) — permissions
     * are requested at call time (webrtc-kmp throws from getUserMedia if missing).
     */
    private suspend fun startMedia(): Boolean {
        return try {
            val stream = MediaDevices.getUserMedia(audio = true, video = video)
            localStream = stream
            val pc = PeerConnection(
                RtcConfiguration(
                    iceServers = emptyList(), // LAN-only: host candidates (ADR-025).
                ),
            )
            peerConnection = pc
            pc.addTrack(stream.tracks.first { it.kind == MediaStreamTrackKind.Audio }, stream)
            if (video) {
                pc.addTrack(stream.tracks.first { it.kind == MediaStreamTrackKind.Video }, stream)
            }
            observeEvents(pc)
            true
        } catch (e: CameraPermissionException) {
            false
        } catch (e: RecordAudioPermissionException) {
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun observeEvents(pc: PeerConnection) {
        eventJobs += scope.launch {
            pc.onTrack.collect { event ->
                event.streams.firstOrNull()?.let { remoteStream = it }
            }
        }
        eventJobs += scope.launch {
            pc.onConnectionStateChange.collect { cs ->
                when (cs) {
                    PeerConnectionState.Connected -> {
                        dialTimeoutJob?.cancel()
                        disconnectGraceJob?.cancel()
                        _state.value = _state.value.copy(
                            state = FlashCallState.ACTIVE,
                            connectedAt = System.currentTimeMillis(),
                        )
                    }
                    PeerConnectionState.Disconnected -> {
                        // ICE is trying to recover; give it a grace window before
                        // declaring the call lost. Cancellable: a reconnect inside
                        // the window revives the call (Connected cancels this job).
                        disconnectGraceJob?.cancel()
                        disconnectGraceJob = scope.launch {
                            delay(disconnectGraceMs)
                            if (_state.value.state != FlashCallState.ENDED) {
                                end(FlashCallEndReason.DISCONNECTED, notifyPeer = false)
                            }
                        }
                    }
                    PeerConnectionState.Failed -> {
                        if (_state.value.state != FlashCallState.ENDED) {
                            end(FlashCallEndReason.ERROR, notifyPeer = false)
                        }
                    }
                    PeerConnectionState.Closed -> {
                        if (_state.value.state != FlashCallState.ENDED) {
                            end(FlashCallEndReason.DISCONNECTED, notifyPeer = false)
                        }
                    }
                    PeerConnectionState.New,
                    PeerConnectionState.Connecting,
                    -> Unit
                }
            }
        }
        eventJobs += scope.launch {
            pc.onIceCandidate.collect { candidate ->
                sendFrame(
                    CallWireFrame.IceCandidate(
                        callId = callId,
                        from = localDeviceId,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.candidate,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ teardown

    /**
     * Terminates the call. Idempotent. [notifyPeer] sends a hangup frame so the remote
     * side does not wait on a dead session (skipped when the peer already told us, or
     * when the session itself is unsendable).
     */
    public fun end(reason: FlashCallEndReason, notifyPeer: Boolean = true) {
        if (ended) return
        ended = true
        dialTimeoutJob?.cancel()
        disconnectGraceJob?.cancel()
        eventJobs.forEach { it.cancel() }
        eventJobs.clear()
        _state.value = _state.value.copy(
            state = FlashCallState.ENDED,
            endReason = reason,
        )
        runCatching { peerConnection?.close() }
        peerConnection = null
        runCatching { localStream?.release() }
        localStream = null
        remoteStream = null
        if (notifyPeer) {
            scope.launch {
                sendFrame(CallWireFrame.Hangup(callId = callId, from = localDeviceId))
            }
        }
        onEnded(this)
    }

    /** Host calls this when the WS signaling session dies — the call cannot survive it. */
    public fun onSignalingLost() {
        end(FlashCallEndReason.DISCONNECTED, notifyPeer = false)
    }
}
