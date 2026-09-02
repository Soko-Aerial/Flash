package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.AudioTrack
import com.shepeliev.webrtckmp.BundlePolicy
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
import com.shepeliev.webrtckmp.RtcpMuxPolicy
import com.shepeliev.webrtckmp.RtpSender
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
import com.transfer.flash.core.calling.model.FlashCallStats
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.logging.FlashLog
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt
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
import org.webrtc.Priority
import org.webrtc.RtpParameters.DegradationPreference

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
    /**
     * Ceiling on the CONNECTING phase (ms). SDP+ICE on a LAN completes in well under a
     * second; if it has not, something is wrong (lost signaling frame, blocked ICE, peer
     * crash) and the call must fail VISIBLY instead of hanging on "Connecting…" forever.
     */
    private val connectTimeoutMs: Long = 30_000L,
    /**
     * Reads the user's "Prioritise voice quality" setting (Settings, default on) — a lambda,
     * not a value, because it is consulted once a second for the life of the call and must
     * follow a mid-call flip of the switch.
     *
     * When it returns true, voice outranks video in the bandwidth allocator and
     * [CallQualityGovernor] trades picture away to keep speech intelligible. When false, both
     * senders keep WebRTC's symmetric defaults and the governor never runs.
     *
     * A lambda also keeps this module free of any persistence dependency (ADR-024): the host
     * owns the DataStore and passes a reader in.
     */
    private val prioritiseVoice: () -> Boolean = { true },
) : FlashCallMedia {
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
            // A video call is held at arm's length, so it starts on the speaker; a voice
            // call starts on the earpiece. The host applies the actual routing (ADR-025).
            speakerOn = video,
        ),
    )
    public val state: StateFlow<FlashCallUiState> = _state.asStateFlow()

    private val _stats = MutableStateFlow<FlashCallStats?>(null)

    /**
     * Live transport metrics, resampled every second while the call is connected; null
     * before the first sample and after teardown.
     *
     * This is the call screen's latency readout. Sampling is driven from here rather than
     * from the UI because `getStats()` needs the [PeerConnection], which the UI never sees,
     * and because a metric stream that survives recomposition must not be owned by a
     * composable.
     */
    override val stats: StateFlow<FlashCallStats?> = _stats.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)

    /**
     * Local camera track — OBSERVABLE, because the renderer that binds it is composed
     * before the track can possibly exist.
     *
     * This used to be a plain getter, i.e. a snapshot: the call screen read it once at
     * composition time, got null (media takes ~130 ms to start, the screen appears
     * immediately) and nothing ever told it to look again. Null for audio calls and after
     * [releaseMedia].
     */
    override val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)

    /**
     * Remote camera track, published from [PeerConnection.onTrack].
     *
     * Fed from the event's own track rather than `event.streams.first().videoTracks`:
     * webrtc-kmp builds a FRESH wrapper [MediaStream] on every `onAddTrack` callback out
     * of whatever the native stream holds at that instant, so the audio callback's wrapper
     * carries no video track at all and the ordering of the two callbacks decides whether
     * a stream snapshot ever contains video. The track in hand always does.
     */
    override val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private var localStream: MediaStream? = null
    private var remoteAudio: AudioTrack? = null

    /**
     * Our own RTP senders, kept because tuning them is not a one-shot act (D8).
     *
     * The audio sender used to be thrown away — `pc.addTrack(...)`'s return value was simply
     * unused — which meant voice had no priority, no ceiling and no way to be protected. The
     * video sender is retained so the governor can retune it once a second without walking
     * `pc.getSenders()`.
     */
    private var audioSender: RtpSender? = null
    private var videoSender: RtpSender? = null

    /** Decides when to trade video away for voice. Pure; see [CallQualityGovernor]. */
    private val governor = CallQualityGovernor()

    /** Local microphone track once media is started; null before/after. Not public: an audio
     *  track is not renderable, so nothing outside this module has a use for it. */
    private val localAudioTrack: AudioTrack? get() = localStream?.audioTracks?.firstOrNull()

    /** ICE candidates that arrived before the remote description was set (trickle buffer). */
    private val pendingIce = mutableListOf<IceCandidate>()
    private val iceMutex = Mutex()

    /**
     * Serialises the signaling state machine: media startup vs. inbound frames.
     *
     * The bug this closes: [accept] used to announce readiness (send `Accept`) and only
     * THEN await [startMedia]. The caller offers the instant it sees `Accept` — one LAN
     * RTT (~2 ms) — while `getUserMedia` plus the first `PeerConnectionFactory` init take
     * >100 ms. So the offer landed while `peerConnection` was still null, [onOffer]
     * returned silently, no answer was ever produced, and BOTH devices sat in CONNECTING
     * forever. Media now starts before `Accept` leaves the device, and inbound signaling
     * waits behind media startup instead of racing it.
     */
    private val signalMutex = Mutex()

    /**
     * Signaling frames that arrived before the [PeerConnection] existed. Replayed in
     * order once media is up — never dropped: a dropped offer is an unrecoverable call
     * that hangs in CONNECTING, which is exactly the failure this session is designed
     * to make impossible.
     *
     * Copy-on-write because [end] can clear it from a WebRTC callback thread while the
     * signaling path is appending under [signalMutex].
     */
    private val deferredFrames = CopyOnWriteArrayList<CallWireFrame>()

    private val eventJobs = CopyOnWriteArrayList<Job>()
    private var dialTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var disconnectGraceJob: Job? = null
    private var statsJob: Job? = null

    /** Previous stats sample, for differencing byte counters into a bitrate. */
    private var lastStatsAtUs: Long = 0L
    private var lastBytesReceived: Long = 0L
    private var lastBytesSent: Long = 0L

    /**
     * Previous packet counters, and the loss fraction over the LAST interval only.
     *
     * [FlashCallStats.packetLoss] is cumulative over the whole call — right for a readout, wrong
     * for a control loop, because a cumulative fraction can only be paid down and one early
     * burst would pin the governor at its lowest rung forever.
     */
    private var lastPacketsLost: Long = 0L
    private var lastPacketsReceived: Long = 0L
    private var lastIntervalLoss: Double? = null
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
     * INCOMING call: local user accepted. Starts media + WebRTC FIRST, then sends accept,
     * then waits for the caller's offer. Returns false if media could not start
     * (permissions denied / device error) — the session is ended in that case.
     *
     * Ordering is load-bearing: the caller sends its offer as soon as it sees `Accept`,
     * so the [PeerConnection] must already exist when that frame goes out. Any frame that
     * still races in (duplicate accept, early ICE) is buffered by [onInboundFrame] and
     * replayed here.
     */
    public suspend fun accept(): Boolean = signalMutex.withLock {
        if (_state.value.state != FlashCallState.RINGING) return@withLock false
        // CONNECTING before the slow media start: a second Accept tap becomes a no-op and
        // the UI stops advertising a call we have already answered.
        _state.value = _state.value.copy(state = FlashCallState.CONNECTING)
        armConnectTimeout()
        if (!startMedia()) {
            FlashLog.w("CALL", "accept: media start failed, declining call=$callId")
            // Tell the caller explicitly — otherwise it rings until its dial timeout.
            sendFrame(CallWireFrame.Decline(callId = callId, from = localDeviceId))
            end(FlashCallEndReason.ERROR, notifyPeer = false)
            return@withLock false
        }
        if (!sendFrame(CallWireFrame.Accept(callId = callId, from = localDeviceId))) {
            FlashLog.w("CALL", "accept: Accept frame could not be sent, call=$callId")
            end(FlashCallEndReason.ERROR, notifyPeer = false)
            return@withLock false
        }
        replayDeferredFrames()
        true
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
        val track = _localVideoTrack.value ?: return _state.value.cameraOff
        track.enabled = !track.enabled
        val off = !track.enabled
        _state.value = _state.value.copy(cameraOff = off)
        return off
    }

    /** Switch front/back camera (video calls). No-op without a video track. */
    public suspend fun switchCamera() {
        _localVideoTrack.value?.switchCamera()
    }

    /** Speakerphone toggle state (audio routing is host-owned; ADR-025). */
    public fun setSpeaker(on: Boolean) {
        _state.value = _state.value.copy(speakerOn = on)
    }

    // ------------------------------------------------------------------ inbound

    /**
     * Host delivers every decoded [CallWireFrame] whose [CallWireFrame.callId] matches
     * this session. Mismatched frames are the host's problem, not ours.
     *
     * Frames that need a live [PeerConnection] and arrive before there is one are
     * BUFFERED (see [deferredFrames]), never dropped.
     */
    public suspend fun onInboundFrame(frame: CallWireFrame) {
        // Terminal frames bypass the signaling gate: they are idempotent, touch no media
        // state, and must land even while media is still starting up.
        when (frame) {
            is CallWireFrame.Decline -> {
                end(FlashCallEndReason.DECLINED, notifyPeer = false)
                return
            }
            is CallWireFrame.Hangup -> {
                end(FlashCallEndReason.NORMAL, notifyPeer = false)
                return
            }
            else -> Unit
        }
        signalMutex.withLock {
            if (ended) return@withLock
            if (peerConnection == null && needsPeerConnection(frame)) {
                if (deferredFrames.size >= MAX_DEFERRED_FRAMES) {
                    FlashLog.w("CALL", "deferred buffer full, dropping ${frame.javaClass.simpleName}")
                    return@withLock
                }
                deferredFrames += frame
                FlashLog.i(
                    "CALL",
                    "deferred ${frame.javaClass.simpleName} until media is ready " +
                        "(buffered=${deferredFrames.size}, state=${_state.value.state})",
                )
                return@withLock
            }
            handleFrame(frame)
        }
    }

    /** Dispatch for a frame that is cleared to run (caller holds [signalMutex]). */
    private suspend fun handleFrame(frame: CallWireFrame) {
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

    /** True for frames that are meaningless without a [PeerConnection] to apply them to. */
    private fun needsPeerConnection(frame: CallWireFrame): Boolean =
        frame is CallWireFrame.Offer ||
            frame is CallWireFrame.Answer ||
            frame is CallWireFrame.IceCandidate

    /** Applies frames buffered during media startup (caller holds [signalMutex]). */
    private suspend fun replayDeferredFrames() {
        if (deferredFrames.isEmpty()) return
        val replay = deferredFrames.toList()
        deferredFrames.clear()
        FlashLog.i("CALL", "replaying ${replay.size} deferred signaling frame(s)")
        for (frame in replay) {
            if (ended) return
            handleFrame(frame)
        }
    }

    private suspend fun onAccept() {
        if (_state.value.state != FlashCallState.DIALING) return
        dialTimeoutJob?.cancel()
        _state.value = _state.value.copy(state = FlashCallState.CONNECTING)
        armConnectTimeout()
        if (!startMedia()) {
            FlashLog.w("CALL", "onAccept: media start failed, ending call=$callId")
            end(FlashCallEndReason.ERROR, notifyPeer = true)
            return
        }
        // Caller is the offerer (glare-free: only the caller offers — ADR-025).
        val pc = peerConnection ?: return
        try {
            val offer = pc.createOffer(
                OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = video),
            )
            val applied = setLocalDescriptionTuned(pc, offer)
            sendFrame(
                CallWireFrame.Offer(
                    callId = callId,
                    from = localDeviceId,
                    sdp = applied.sdp,
                ),
            )
            replayDeferredFrames()
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
        if (pc == null || _state.value.state != FlashCallState.CONNECTING) {
            // Loud on purpose: a silently discarded offer is a call stuck in CONNECTING.
            FlashLog.w(
                "CALL",
                "ignoring offer: media=${pc != null} state=${_state.value.state} call=$callId",
            )
            return
        }
        try {
            setRemoteDescriptionTuned(pc, SessionDescriptionType.Offer, frame.sdp)
            flushPendingIce()
            val answer = pc.createAnswer(
                OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = video),
            )
            val applied = setLocalDescriptionTuned(pc, answer)
            sendFrame(
                CallWireFrame.Answer(
                    callId = callId,
                    from = localDeviceId,
                    sdp = applied.sdp,
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
        val pc = peerConnection
        if (pc == null || _state.value.state != FlashCallState.CONNECTING) {
            FlashLog.w(
                "CALL",
                "ignoring answer: media=${pc != null} state=${_state.value.state} call=$callId",
            )
            return
        }
        try {
            setRemoteDescriptionTuned(pc, SessionDescriptionType.Answer, frame.sdp)
            flushPendingIce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FlashLog.e("CALL", "onAnswer SDP flow failed: ${e.message}", e)
            end(FlashCallEndReason.ERROR, notifyPeer = true)
        }
    }

    /**
     * Applies [desc] after [CallSdp.tune], returning the description that was actually
     * installed — that, not the original, is what goes on the wire, so the peer sees the
     * same body the local ICE agent is working from.
     *
     * Falls back to the untuned description if WebRTC rejects the rewrite. SDP munging is
     * the only route to these knobs on this stack, but it is still munging: a fallback is
     * the difference between a call at default bitrate and no call at all.
     */
    private suspend fun setLocalDescriptionTuned(
        pc: PeerConnection,
        desc: SessionDescription,
    ): SessionDescription {
        val tunedSdp = runCatching { CallSdp.tune(desc.sdp) }.getOrNull()
        if (tunedSdp != null && tunedSdp != desc.sdp) {
            val tuned = SessionDescription(desc.type, tunedSdp)
            try {
                pc.setLocalDescription(tuned)
                logSdp("local ${desc.type} (tuned)", tuned.sdp)
                return tuned
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                FlashLog.w("CALL", "tuned local SDP rejected, using original: ${t.message}")
            }
        }
        logSdp("local ${desc.type}", desc.sdp)
        pc.setLocalDescription(desc)
        return desc
    }

    /**
     * Installs a remote description with the same tuning applied.
     *
     * Load-bearing direction: an encoder takes its bitrate and packetization from the
     * description it RECEIVES, so this — not [setLocalDescriptionTuned] — is what makes the
     * local encoder start at [CallSdp.VIDEO_START_BITRATE_KBPS] and the local mic emit
     * 10 ms packets. Tuning both directions makes the pair symmetric.
     */
    private suspend fun setRemoteDescriptionTuned(
        pc: PeerConnection,
        type: SessionDescriptionType,
        sdp: String,
    ) {
        val tuned = runCatching { CallSdp.tune(sdp) }.getOrNull()
        if (tuned != null && tuned != sdp) {
            try {
                pc.setRemoteDescription(SessionDescription(type, tuned))
                logSdp("remote $type (tuned)", tuned)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                FlashLog.w("CALL", "tuned remote SDP rejected, using original: ${t.message}")
            }
        }
        logSdp("remote $type", sdp)
        pc.setRemoteDescription(SessionDescription(type, sdp))
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
        val pc = peerConnection
        if (pc == null) {
            FlashLog.w("CALL", "ignoring ICE candidate: no media for call=$callId")
            return
        }
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
     *
     * Idempotent: a second call with media already up is a no-op success. Every failure
     * path LOGS — these used to return a bare `false`, which made a denied mic or a busy
     * camera indistinguishable from a signaling bug in a field logcat.
     *
     * Capture is requested at [CAPTURE_WIDTH]x[CAPTURE_HEIGHT]; webrtc-kmp's default is
     * 1280x720 (`CameraVideoCapturerController.selectVideoSize` falls back to those two
     * literals when the constraints carry no size), which is why the ceiling used to be
     * 720p. The camera enumerator snaps the request to the closest format it actually
     * supports, so a device without a 1080p mode degrades instead of failing.
     */
    private suspend fun startMedia(): Boolean {
        if (peerConnection != null) return true
        return try {
            FlashLog.i("CALL", "startMedia video=$video call=$callId")
            val stream = MediaDevices.getUserMedia {
                audio(true)
                if (video) {
                    video {
                        width(CAPTURE_WIDTH)
                        height(CAPTURE_HEIGHT)
                        frameRate(CAPTURE_FPS.toDouble())
                    }
                }
            }
            localStream = stream
            // Publish before the PeerConnection exists: the preview renderer is already
            // composed and waiting on this flow.
            _localVideoTrack.value = stream.videoTracks.firstOrNull()
            val pc = PeerConnection(
                RtcConfiguration(
                    iceServers = emptyList(), // LAN-only: host candidates (ADR-025).
                    // Both endpoints are Flash, so bundling is a foregone conclusion —
                    // demanding it up front means ONE ICE check list and ONE DTLS
                    // handshake for audio+video instead of two of each.
                    bundlePolicy = BundlePolicy.MaxBundle,
                    rtcpMuxPolicy = RtcpMuxPolicy.Require,
                    // Start gathering host candidates now rather than at
                    // setLocalDescription — that is ~130 ms of media startup earlier.
                    iceCandidatePoolSize = 1,
                ),
            )
            peerConnection = pc
            val audio = pc.addTrack(
                stream.tracks.first { it.kind == MediaStreamTrackKind.Audio },
                stream,
            )
            audioSender = audio
            tuneAudioSender(audio)
            if (video) {
                val sender = pc.addTrack(
                    stream.tracks.first { it.kind == MediaStreamTrackKind.Video },
                    stream,
                )
                videoSender = sender
                tuneVideoSender(sender)
            }
            observeEvents(pc)
            if (ended) {
                // A Hangup/Decline bypasses signalMutex on purpose, so the call can die
                // during these ~130 ms of native init. end()'s teardown ran before this
                // connection existed — release it here or the mic stays hot forever.
                FlashLog.i("CALL", "media became ready after the call ended — releasing")
                releaseMedia()
                return false
            }
            FlashLog.i(
                "CALL",
                "media ready audio=${stream.audioTracks.size} video=${stream.videoTracks.size}",
            )
            true
        } catch (e: CameraPermissionException) {
            FlashLog.e("CALL", "startMedia: CAMERA permission not granted", e)
            false
        } catch (e: RecordAudioPermissionException) {
            FlashLog.e("CALL", "startMedia: RECORD_AUDIO permission not granted", e)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Throwable, not Exception: the first PeerConnectionFactory touch loads
            // libjingle_peerconnection_so, so a device with a missing/mismatched ABI
            // fails with UnsatisfiedLinkError/NoClassDefFoundError. A call that cannot
            // start media must end cleanly, not take the process down.
            FlashLog.e("CALL", "startMedia failed: ${t.message}", t)
            false
        }
    }

    /**
     * Gives voice priority over video inside the bandwidth allocator (D8).
     *
     * The answer to "is audio prioritised over video in a video call?" used to be **no**: this
     * sender did not exist, because `pc.addTrack(audio, stream)`'s return value was discarded.
     * Video got a degradation preference, a ceiling and a floor; audio got whatever was left
     * over, which on a congested hotspot is nothing.
     *
     * Two knobs, and they are not the same knob:
     * - `networkPriority` = [Priority.HIGH] is the *pacer's* ordering — which queue drains first
     *   when the send window is short.
     * - `bitratePriority` is the *allocator's* weight — how the estimated bandwidth is divided
     *   between the two streams before either of them is paced at all.
     *
     * Setting only one of them is the trap: a high pacing priority on a stream that was never
     * allocated any bandwidth changes nothing, and a large allocation share that queues behind a
     * 2.5 Mbit/s video burst still arrives late.
     *
     * DSCP is deliberately absent. [BundlePolicy.MaxBundle] plus [RtcpMuxPolicy.Require] means
     * audio and video share ONE 5-tuple, so any DiffServ marking would apply to both streams
     * identically and mark nothing apart — the priority has to be expressed where the two
     * streams are still distinguishable, which is the allocator, not the IP header.
     *
     * [AUDIO_MAX_BITRATE_BPS] is a ceiling, not a target: Opus at 32 kbit/s with 10 ms packets
     * and in-band FEC (see [CallSdp]) is already transparent for speech, and capping it stops a
     * generous bandwidth estimate from handing voice bitrate it cannot use.
     *
     * Best-effort, like [tuneVideoSender]: a failure here costs priority, not the call.
     */
    private fun tuneAudioSender(sender: RtpSender) {
        if (!prioritiseVoice()) {
            FlashLog.i("CALL", "voice priority off — leaving symmetric sender defaults")
            return
        }
        try {
            val native = sender.android
            val params = native.parameters
            if (params.encodings.isEmpty()) {
                FlashLog.w("CALL", "audio sender has no encodings to tune")
                return
            }
            params.encodings.forEach { encoding ->
                encoding.active = true
                encoding.networkPriority = Priority.HIGH
                encoding.bitratePriority = AUDIO_BITRATE_PRIORITY
                encoding.maxBitrateBps = AUDIO_MAX_BITRATE_BPS
            }
            val applied = native.setParameters(params)
            FlashLog.i(
                "CALL",
                "audio sender tuned applied=$applied max=${AUDIO_MAX_BITRATE_BPS / 1000}kbps " +
                    "networkPriority=HIGH bitratePriority=$AUDIO_BITRATE_PRIORITY",
            )
        } catch (t: Throwable) {
            FlashLog.w("CALL", "audio sender tuning failed: ${t.message}")
        }
    }

    /**
     * Raises the video sender's bitrate ceiling and tells it how to shed quality.
     *
     * `MAINTAIN_FRAMERATE` is the whole answer to "1080p that comes down under a bandwidth
     * constraint": when congestion control or the CPU quality-scaler says the current
     * target is unaffordable, WebRTC drops **resolution** (1080p → 720p → 540p → …) and
     * keeps the frame rate, which is what a moving talking head needs. The default,
     * `BALANCED`, throws away frame rate first and makes motion stutter.
     *
     * Without a [VIDEO_MAX_BITRATE_BPS] ceiling this is academic: libwebrtc caps a VP8
     * stream around 2.5 Mbit/s from its internal codec table, which starves 1080p no matter
     * what the link can carry.
     *
     * With "Prioritise voice quality" on, video is also explicitly *demoted* — the mirror image
     * of [tuneAudioSender]. Capping video is not the same as ordering the two streams: a cap
     * still lets video take its share first and leave voice to fit in the remainder, which is
     * how a 32 kbit/s stream ends up unintelligible next to a picture that looks fine.
     *
     * Reaches through [RtpSender.android] because webrtc-kmp's own `RtpParameters` wrapper
     * exposes no degradation preference. Best-effort by design: a failure here costs
     * bitrate, not the call.
     */
    private fun tuneVideoSender(sender: RtpSender) {
        try {
            val voiceFirst = prioritiseVoice()
            val native = sender.android
            val params = native.parameters
            params.degradationPreference = DegradationPreference.MAINTAIN_FRAMERATE
            if (params.encodings.isEmpty()) {
                FlashLog.w("CALL", "video sender has no encodings to tune")
                return
            }
            params.encodings.forEach { encoding ->
                encoding.active = true
                encoding.maxBitrateBps = VIDEO_MAX_BITRATE_BPS
                encoding.minBitrateBps = VIDEO_MIN_BITRATE_BPS
                encoding.maxFramerate = CAPTURE_FPS
                // Send at capture resolution; adaptation drives this down on its own.
                encoding.scaleResolutionDownBy = 1.0
                if (voiceFirst) {
                    encoding.networkPriority = Priority.LOW
                    encoding.bitratePriority = VIDEO_BITRATE_PRIORITY
                }
            }
            val applied = native.setParameters(params)
            FlashLog.i(
                "CALL",
                "video sender tuned applied=$applied max=${VIDEO_MAX_BITRATE_BPS / 1000}kbps " +
                    "fps=$CAPTURE_FPS degradation=MAINTAIN_FRAMERATE voiceFirst=$voiceFirst",
            )
        } catch (t: Throwable) {
            FlashLog.w("CALL", "video sender tuning failed: ${t.message}")
        }
    }

    /**
     * Bounds the CONNECTING phase so a lost signaling frame can no longer hang the call.
     * Nothing else covers it: [onAccept] cancels the caller's dial timeout the moment the
     * callee answers, and the callee never had a timer of its own.
     */
    private fun armConnectTimeout() {
        connectTimeoutJob?.cancel()
        connectTimeoutJob = scope.launch {
            delay(connectTimeoutMs)
            if (_state.value.state == FlashCallState.CONNECTING) {
                FlashLog.w("CALL", "connect timeout after ${connectTimeoutMs}ms, call=$callId")
                end(FlashCallEndReason.ERROR, notifyPeer = true)
            }
        }
    }

    private fun observeEvents(pc: PeerConnection) {
        eventJobs += scope.launch {
            pc.onTrack.collect { event ->
                // The event's own track, not a snapshot of event.streams — see the
                // [remoteVideoTrack] doc for why the stream wrapper is unreliable here.
                val track = event.track
                if (track is VideoTrack) {
                    FlashLog.i("CALL", "remote video track id=${track.id} call=$callId")
                    _remoteVideoTrack.value = track
                }
                if (track is AudioTrack) {
                    FlashLog.i("CALL", "remote audio track id=${track.id} call=$callId")
                    remoteAudio = track
                }
            }
        }
        eventJobs += scope.launch {
            pc.onConnectionStateChange.collect { cs ->
                FlashLog.i("CALL", "peer connection state=$cs call=$callId")
                when (cs) {
                    PeerConnectionState.Connected -> {
                        dialTimeoutJob?.cancel()
                        connectTimeoutJob?.cancel()
                        disconnectGraceJob?.cancel()
                        _state.value = _state.value.copy(
                            state = FlashCallState.ACTIVE,
                            connectedAt = System.currentTimeMillis(),
                        )
                        armStatsPolling(pc)
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

    // ------------------------------------------------------------------ metrics

    /**
     * Starts the once-a-second [stats] sampler. Armed on Connected rather than at media
     * start because `getStats()` has nothing to say about a transport that has not selected
     * a candidate pair yet.
     */
    private fun armStatsPolling(pc: PeerConnection) {
        statsJob?.cancel()
        lastStatsAtUs = 0L
        lastBytesReceived = 0L
        lastBytesSent = 0L
        lastPacketsLost = 0L
        lastPacketsReceived = 0L
        lastIntervalLoss = null
        statsJob = scope.launch {
            while (!ended) {
                val sample = try {
                    sampleStats(pc)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // A closed PeerConnection rejects getStats; that is teardown, not news.
                    FlashLog.w("CALL", "getStats failed: ${t.message}")
                    null
                }
                if (sample != null) {
                    _stats.value = sample
                    applyVoicePriority(sample)
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    /**
     * Audio-protective governor: one rung of video traded away per sustained bad second, and
     * handed back per sustained clean five (D8).
     *
     * Rides the [stats] sampler rather than a timer of its own — the numbers it needs are the
     * numbers already being collected for the UI readout, and a second control loop polling
     * `getStats()` would double the cost of the thing it is trying to protect.
     *
     * The decision itself lives in [CallQualityGovernor], which is pure and unit-tested; this
     * function only applies verdicts. It is a no-op on a voice call (no video to give up) and
     * when the user has turned "Prioritise voice quality" off.
     */
    private fun applyVoicePriority(sample: FlashCallStats) {
        if (!video || !prioritiseVoice()) return
        val next = governor.onSample(
            CallQualitySample(
                rttMs = sample.rttMs,
                audioJitterMs = sample.audioJitterMs,
                lossFraction = lastIntervalLoss,
            ),
        ) ?: return
        applyVideoConcession(next)
    }

    /**
     * Pushes one rung of the [VideoConcession] ladder into the live video sender.
     *
     * Pausing is `encoding.active = false`, NOT [toggleCamera]. Toggling the camera would flip
     * `cameraOff` in the UI state, so the camera button would start lying about what the user
     * chose, and recovery would switch a camera back on that the user may have deliberately
     * turned off. `active` stops the encoding without touching the track, and leaves the local
     * preview running so the user can see the call is still theirs.
     */
    private fun applyVideoConcession(level: VideoConcession) {
        _state.value = _state.value.copy(videoLimitReason = level.reason)
        val sender = videoSender ?: return
        val ceiling = (VIDEO_MAX_BITRATE_BPS * level.bitrateScale).toInt()
        try {
            val native = sender.android
            val params = native.parameters
            if (params.encodings.isEmpty()) return
            params.encodings.forEach { encoding ->
                encoding.active = level.videoActive
                encoding.maxBitrateBps = ceiling
                encoding.minBitrateBps = if (level.holdsBitrateFloor) VIDEO_MIN_BITRATE_BPS else null
                encoding.scaleResolutionDownBy = level.scaleResolutionDownBy
            }
            val applied = native.setParameters(params)
            FlashLog.i(
                "CALL",
                "voice priority: video → $level applied=$applied max=${ceiling / 1000}kbps " +
                    "scaleDown=${level.scaleResolutionDownBy} active=${level.videoActive}",
            )
        } catch (t: Throwable) {
            FlashLog.w("CALL", "video concession $level failed: ${t.message}")
        }
    }

    /**
     * One `getStats()` pass reduced to the handful of numbers the call screen shows.
     *
     * Byte counters are cumulative, so bitrate needs two samples — the first pass reports
     * null for it rather than a made-up number. Numeric members arrive as `Long`,
     * `Integer`, `Double` or `BigInteger` depending on the field's IDL type, hence the
     * uniform [Number] handling.
     *
     * Returns null when the connection has no report to give (closed transport).
     */
    private suspend fun sampleStats(pc: PeerConnection): FlashCallStats? {
        val report = pc.getStats() ?: return null
        val all = report.stats.values

        // RTT lives on the SELECTED candidate pair. The transport stat names it outright;
        // the nominated-and-succeeded pair is the fallback for older report shapes.
        val selectedId = all.firstOrNull { it.type == "transport" }
            ?.members?.get("selectedCandidatePairId") as? String
        val pairs = all.filter { it.type == "candidate-pair" }
        val pair = pairs.firstOrNull { it.id == selectedId }
            ?: pairs.firstOrNull {
                it.members.bool("nominated") == true && it.members.str("state") == "succeeded"
            }
            ?: pairs.firstOrNull { it.members.num("currentRoundTripTime") != null }

        val inbound = all.filter { it.type == "inbound-rtp" }
        val outbound = all.filter { it.type == "outbound-rtp" }
        val audioIn = inbound.firstOrNull { it.members.str("kind") == "audio" }
        val videoIn = inbound.firstOrNull { it.members.str("kind") == "video" }
        val videoOut = outbound.firstOrNull { it.members.str("kind") == "video" }

        val bytesIn = inbound.sumOf { it.members.num("bytesReceived")?.toLong() ?: 0L }
        val bytesOut = outbound.sumOf { it.members.num("bytesSent")?.toLong() ?: 0L }
        val nowUs = report.timestampUs
        val elapsedUs = if (lastStatsAtUs > 0L) nowUs - lastStatsAtUs else 0L
        val inboundKbps = kbps(bytesIn - lastBytesReceived, elapsedUs)
        val outboundKbps = kbps(bytesOut - lastBytesSent, elapsedUs)
        lastStatsAtUs = nowUs
        lastBytesReceived = bytesIn
        lastBytesSent = bytesOut

        val lost = inbound.sumOf { it.members.num("packetsLost")?.toLong() ?: 0L }
        val received = inbound.sumOf { it.members.num("packetsReceived")?.toLong() ?: 0L }

        // Interval loss for the governor, differenced like the byte counters and gated on the
        // same "is there a previous sample" test. Null on the first pass rather than a figure
        // computed against a zero baseline.
        lastIntervalLoss = if (elapsedUs > 0L) {
            val lostDelta = (lost - lastPacketsLost).coerceAtLeast(0L)
            val receivedDelta = (received - lastPacketsReceived).coerceAtLeast(0L)
            val total = lostDelta + receivedDelta
            if (total > 0L) lostDelta.toDouble() / total.toDouble() else null
        } else {
            null
        }
        lastPacketsLost = lost
        lastPacketsReceived = received

        return FlashCallStats(
            // currentRoundTripTime is seconds (double) — the whole round trip.
            rttMs = pair?.members?.num("currentRoundTripTime")?.let { (it * 1000).roundToInt() },
            audioJitterMs = audioIn?.members?.num("jitter")?.let { (it * 1000).roundToInt() },
            videoJitterMs = videoIn?.members?.num("jitter")?.let { (it * 1000).roundToInt() },
            fps = videoIn?.members?.num("framesPerSecond")?.roundToInt(),
            remoteWidth = videoIn?.members?.num("frameWidth")?.toInt(),
            remoteHeight = videoIn?.members?.num("frameHeight")?.toInt(),
            inboundKbps = inboundKbps,
            outboundKbps = outboundKbps,
            sendWidth = videoOut?.members?.num("frameWidth")?.toInt(),
            sendHeight = videoOut?.members?.num("frameHeight")?.toInt(),
            packetLoss = if (received + lost > 0L) {
                lost.toDouble() / (received + lost).toDouble()
            } else {
                null
            },
        )
    }

    /** Bits/s from a byte delta over a microsecond delta, as kbit/s; null on the first pass. */
    private fun kbps(byteDelta: Long, elapsedUs: Long): Int? {
        if (elapsedUs <= 0L) return null
        return (byteDelta.coerceAtLeast(0L) * 8_000.0 / elapsedUs).roundToInt()
    }

    private fun Map<String, Any>.num(key: String): Double? = (this[key] as? Number)?.toDouble()

    private fun Map<String, Any>.str(key: String): String? = this[key] as? String

    private fun Map<String, Any>.bool(key: String): Boolean? = this[key] as? Boolean

    // ------------------------------------------------------------------ teardown

    /**
     * Terminates the call. Idempotent. [notifyPeer] sends a hangup frame so the remote
     * side does not wait on a dead session (skipped when the peer already told us, or
     * when the session itself is unsendable).
     */
    public fun end(reason: FlashCallEndReason, notifyPeer: Boolean = true) {
        if (ended) return
        ended = true
        FlashLog.i("CALL", "call ended reason=$reason notifyPeer=$notifyPeer call=$callId")
        dialTimeoutJob?.cancel()
        connectTimeoutJob?.cancel()
        disconnectGraceJob?.cancel()
        deferredFrames.clear()
        _state.value = _state.value.copy(
            state = FlashCallState.ENDED,
            endReason = reason,
        )
        releaseMedia()
        if (notifyPeer) {
            scope.launch {
                sendFrame(CallWireFrame.Hangup(callId = callId, from = localDeviceId))
            }
        }
        onEnded(this)
    }

    /**
     * Closes the PeerConnection, stops the event collectors and releases the microphone
     * and camera. Idempotent, and safe to call from any thread — the only caller that is
     * not [end] is [startMedia] unwinding media it created for an already-dead call.
     */
    private fun releaseMedia() {
        statsJob?.cancel()
        statsJob = null
        _stats.value = null
        eventJobs.forEach { it.cancel() }
        eventJobs.clear()
        // Unpublish first: the UI unbinds its renderer sinks off the back of these flows,
        // and every track they hold is about to be stopped.
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null
        remoteAudio = null
        // The governor's rung and the encoder's parameters have to agree, so the only place the
        // governor is allowed to forget is the place the encoder ceases to exist.
        audioSender = null
        videoSender = null
        governor.reset()
        lastIntervalLoss = null
        runCatching { peerConnection?.close() }
        peerConnection = null
        runCatching { localStream?.release() }
        localStream = null
    }

    /** Host calls this when the WS signaling session dies — the call cannot survive it. */
    public fun onSignalingLost() {
        end(FlashCallEndReason.DISCONNECTED, notifyPeer = false)
    }

    private companion object {
        /**
         * Cap on [deferredFrames]. A peer trickles a handful of host candidates on a LAN;
         * anything past this is a flood, not a race, and must not grow without bound.
         */
        const val MAX_DEFERRED_FRAMES = 64

        /**
         * Requested capture size. webrtc-kmp defaults to 1280x720 when the constraints
         * carry no size, which was the old ceiling; the camera enumerator snaps this to the
         * nearest format the hardware actually offers.
         */
        const val CAPTURE_WIDTH = 1920
        const val CAPTURE_HEIGHT = 1080
        const val CAPTURE_FPS = 30

        /**
         * Sender bitrate window for video, in bit/s. The floor is where the encoder stops
         * lowering bitrate and starts lowering resolution instead; the ceiling is sized for a
         * shared phone hotspot rather than for the camera (see [CallSdp.VIDEO_MAX_BITRATE_KBPS]).
         */
        const val VIDEO_MAX_BITRATE_BPS = CallSdp.VIDEO_MAX_BITRATE_KBPS * 1000
        const val VIDEO_MIN_BITRATE_BPS = CallSdp.VIDEO_MIN_BITRATE_KBPS * 1000

        /**
         * Voice ceiling, bit/s. Opus with 10 ms packets and in-band FEC is transparent for
         * speech well below this; the cap exists so a generous bandwidth estimate cannot hand
         * voice bitrate it has no use for, at video's expense.
         */
        const val AUDIO_MAX_BITRATE_BPS = 32_000

        /**
         * Allocator weights, and the reason a video call can be understood on a bad link.
         *
         * `bitratePriority` is relative and defaults to 1.0 for every stream, so the pair below
         * asks the allocator to serve voice roughly eight times more eagerly than video when the
         * estimate is too small for both. Voice needs ~32 kbit/s of a link that must be at least
         * a few hundred; the weighting only ever matters in the region where video was going to
         * be ugly regardless.
         */
        const val AUDIO_BITRATE_PRIORITY = 4.0
        const val VIDEO_BITRATE_PRIORITY = 0.5

        /** [stats] sampling period. One second matches the RTCP reporting interval. */
        const val STATS_INTERVAL_MS = 1_000L
    }
}
