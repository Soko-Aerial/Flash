package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.AudioStreamTrack
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
import com.shepeliev.webrtckmp.VideoStreamTrack
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
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.perf.FlashTransportProfile
import com.transfer.flash.core.common.perf.FlashVideoProfile
import com.transfer.flash.core.common.time.SystemTimeSource
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * - `VideoStreamTrack.switchCamera()` is suspend.
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
    /**
     * Reads this device's performance tier (ERROR-033). Everything a weak device or a contended
     * radio cannot afford is derived from here: capture geometry, the encoder's bitrate window,
     * Opus packetization, the stats sampling rate and the three recovery windows below.
     *
     * A lambda for the same reasons as [prioritiseVoice] — a tier change (auto-detect resolving, or
     * the user pinning a mode) must reach the next call without re-wiring anything, and
     * `core:calling` must keep knowing nothing about DataStore (ADR-024).
     *
     * Defaults to [FlashPerformanceMode.HIGH], whose profiles are the pre-tiering constants, so a
     * caller that does not tier behaves as it did before.
     */
    private val performanceMode: () -> FlashPerformanceMode = { FlashPerformanceMode.HIGH },
    /**
     * Ring timeout for outgoing calls (ms) — auto NO_ANSWER hangup.
     *
     * Deliberately *not* tiered: this is how long a person is willing to let a phone ring, which
     * has nothing to do with how much RAM the phone has.
     */
    private val dialTimeoutMs: Long = 45_000L,
    /**
     * Grace window after ICE Disconnected before the call is declared lost (ms).
     *
     * Tier-sized, and the one recovery number that grew at *every* tier including HIGH: an ICE
     * restart now happens inside this window, and 5 s was not enough for one to complete on a
     * mesh roam (see [FlashTransportProfile.callDisconnectGraceMs]).
     */
    private val disconnectGraceMs: Long = performanceMode().transport.callDisconnectGraceMs,
    /**
     * Ceiling on the CONNECTING phase (ms). SDP+ICE on a LAN completes in well under a
     * second; if it has not, something is wrong (lost signaling frame, blocked ICE, peer
     * crash) and the call must fail VISIBLY instead of hanging on "Connecting…" forever.
     *
     * Longer at LOW: on a 2 GB handset the first `PeerConnectionFactory` init, the camera open and
     * the encoder init are all slow enough to eat a double-digit share of this budget.
     */
    private val connectTimeoutMs: Long = performanceMode().transport.callConnectTimeoutMs,
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

    private val _localVideoStreamTrack = MutableStateFlow<VideoStreamTrack?>(null)

    /**
     * Local camera track — OBSERVABLE, because the renderer that binds it is composed
     * before the track can possibly exist.
     *
     * This used to be a plain getter, i.e. a snapshot: the call screen read it once at
     * composition time, got null (media takes ~130 ms to start, the screen appears
     * immediately) and nothing ever told it to look again. Null for audio calls and after
     * [releaseMedia].
     */
    override val localVideoStreamTrack: StateFlow<VideoStreamTrack?> = _localVideoStreamTrack.asStateFlow()

    private val _remoteVideoStreamTrack = MutableStateFlow<VideoStreamTrack?>(null)

    /**
     * Remote camera track, published from [PeerConnection.onTrack].
     *
     * Fed from the event's own track rather than `event.streams.first().videoTracks`:
     * webrtc-kmp builds a FRESH wrapper [MediaStream] on every `onAddTrack` callback out
     * of whatever the native stream holds at that instant, so the audio callback's wrapper
     * carries no video track at all and the ordering of the two callbacks decides whether
     * a stream snapshot ever contains video. The track in hand always does.
     */
    override val remoteVideoStreamTrack: StateFlow<VideoStreamTrack?> = _remoteVideoStreamTrack.asStateFlow()

    private var peerConnection: PeerConnection? = null
    private var localStream: MediaStream? = null
    private var remoteAudio: AudioStreamTrack? = null

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
    private val localAudioStreamTrack: AudioStreamTrack? get() = localStream?.audioTracks?.firstOrNull()

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
     * Serializes media acquisition against native teardown: the next call's [startMedia] cannot
     * enter while the previous call's [releaseMedia] is still inside `close()`/track-stop, and
     * vice versa. Both already run on the single media thread (ordering), but a suspend point
     * inside either could otherwise interleave them on that thread (atomicity) — and cross-thread
     * end()/acquire pairs (UI hangup vs inbound-answer acquire) have no dispatcher ordering at
     * all. Non-reentrant by construction: holders never call a locking entry point (see
     * [releaseMediaLocked]).
     */
    private val mediaLifecycleMutex = Mutex()

    /**
     * Confines [block] to [callMediaDispatcher]: every native WebRTC touch in this session
     * goes through here. Hosts call into the session from UI threads, IO-pool collectors and
     * timer jobs; without this each of those would drive WASAPI/COM from a different OS
     * thread (silent buzz + dead capture on Windows). Nesting is safe — `withContext` on
     * the media thread from the media thread suspends and re-queues.
     */
    private suspend fun <T> onMediaThread(block: suspend CoroutineScope.() -> T): T =
        withContext(callMediaDispatcher, block)

    /**
     * Signaling frames that arrived before the [PeerConnection] existed. Replayed in
     * order once media is up — never dropped: a dropped offer is an unrecoverable call
     * that hangs in CONNECTING, which is exactly the failure this session is designed
     * to make impossible.
     *
     * Copy-on-write because [end] can clear it from a WebRTC callback thread while the
     * signaling path is appending under [signalMutex].
     */
    private val deferredFrames = SyncList<CallWireFrame>()

    private val eventJobs = SyncList<Job>()
    private var dialTimeoutJob: Job? = null
    private var connectTimeoutJob: Job? = null
    private var disconnectGraceJob: Job? = null
    private var signalingGraceJob: Job? = null
    private var statsJob: Job? = null

    /**
     * Override point for the `getStats()` sampler's dispatcher (tests). Defaults to
     * [callMediaDispatcher] at arming: the sampler's `getStats()` is a native call and belongs
     * on the pinned media thread, and sharing that thread keeps the sampler off the host's
     * transfer/crypto/Room pools (the S2e rationale, now satisfied without a second thread).
     * Nulled in [releaseMedia]; a finished call holds no thread.
     */
    private var statsDispatcher: CoroutineDispatcher? = null

    /**
     * Wall clock of the last ICE restart offer, so a link that flaps cannot become an offer storm.
     *
     * Deliberately survives a recovery episode rather than resetting with it: two `Disconnected`
     * transitions one second apart are one bad link, not two independent failures, and the second
     * one has nothing new to offer the peer.
     */
    private var lastIceRestartAtMs: Long = 0L

    /** Previous stats sample, for differencing byte counters into a bitrate. */
    private var lastStatsAtUs: Long = 0L
    private var lastBytesReceived: Long = 0L
    private var lastBytesSent: Long = 0L
    /** Previous mic witnesses (see sampleStats): split "no frames" from "silent frames". */
    private var lastAudioEnergy: Long = 0L
    private var lastAudioDurationS: Double = 0.0
    /**
     * One-shot report-shape dump, reset per arming. webrtc-java's report field names are
     * assumed (not verified) identical to Android's libwebrtc — if the desktop badge stays
     * empty while calls connect, this line says whether the fields exist at all and whether
     * any bytes are moving in either direction.
     */
    private var loggedReportShape: Boolean = false

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

    /**
     * Toggle local mic mute. Returns the new muted state; no-op (returns current) without media.
     *
     * The UI state flips synchronously so the button never lies; the native `enabled` flip hops
     * to the media thread (this is called straight from UI callbacks). A teardown racing the hop
     * finds no track and the runCatching drops it — mute-after-hangup is meaningless anyway.
     */
    public fun toggleMute(): Boolean {
        val muted = !_state.value.micMuted
        _state.value = _state.value.copy(micMuted = muted)
        val track = localAudioStreamTrack
        if (track != null) {
            scope.launch(callMediaDispatcher) {
                runCatching { track.enabled = !muted }
            }
        }
        return muted
    }

    /**
     * Toggle local camera on/off (video calls). Same optimistic-state + pinned-native shape
     * as [toggleMute].
     */
    public fun toggleCamera(): Boolean {
        val off = !_state.value.cameraOff
        _state.value = _state.value.copy(cameraOff = off)
        val track = _localVideoStreamTrack.value
        if (track != null) {
            scope.launch(callMediaDispatcher) {
                runCatching { track.enabled = !off }
            }
        }
        return off
    }

    /** Switch front/back camera (video calls). No-op without a video track. */
    public suspend fun switchCamera() {
        onMediaThread { _localVideoStreamTrack.value?.switchCamera() }
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
                deferredFrames.add(frame)
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
            is CallWireFrame.GroupInvite,
            is CallWireFrame.GroupAccept,
            is CallWireFrame.GroupDecline,
            is CallWireFrame.GroupJoin,
            is CallWireFrame.GroupHangup,
            is CallWireFrame.GroupPresence,
            is CallWireFrame.GroupQuery -> Unit // Group frames are managed by FlashGroupCallSession
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
        // Pinned: createOffer + setLocalDescription are native.
        val pc = peerConnection ?: return
        onMediaThread {
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
    }

    private suspend fun onOffer(frame: CallWireFrame.Offer) {
        val pc = peerConnection
        if (pc == null || !canNegotiate()) {
            // Loud on purpose: a silently discarded offer is a call stuck in CONNECTING.
            FlashLog.w(
                "CALL",
                "ignoring offer: media=${pc != null} state=${_state.value.state} call=$callId",
            )
            return
        }
        // Pinned: setRemote/createAnswer/setLocal are native.
        onMediaThread {
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
    }

    private suspend fun onAnswer(frame: CallWireFrame.Answer) {
        val pc = peerConnection
        if (pc == null || !canNegotiate()) {
            FlashLog.w(
                "CALL",
                "ignoring answer: media=${pc != null} state=${_state.value.state} call=$callId",
            )
            return
        }
        // Pinned: setRemoteDescription is native.
        onMediaThread {
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
    }

    /**
     * Whether a description may legitimately be applied right now.
     *
     * `CONNECTING` is the initial negotiation. `ACTIVE` is a *re*negotiation — the ICE restart in
     * [recoverIce], which by definition arrives on a call that already connected once. Rejecting an
     * offer while ACTIVE (which this used to do) meant the restart offer was logged and dropped by
     * the very peer it was sent to rescue.
     */
    private fun canNegotiate(): Boolean = when (_state.value.state) {
        FlashCallState.CONNECTING, FlashCallState.ACTIVE -> true
        else -> false
    }

    /**
     * Applies [desc] after [CallSdp.tuneLocal], returning the description that was actually
     * installed — that, not the original, is what goes on the wire, so the peer sees the
     * same body the local ICE agent is working from.
     *
     * The local rewrite *forces* this device's tier numbers, and that is also how the peer learns
     * what tier we are: what libwebrtc put in a description it generated is its own default, not a
     * statement anybody made. The peer folds our declaration into its own envelope — see [CallSdp].
     *
     * Falls back to the untuned description if WebRTC rejects the rewrite. SDP munging is
     * the only route to these knobs on this stack, but it is still munging: a fallback is
     * the difference between a call at default bitrate and no call at all.
     */
    private suspend fun setLocalDescriptionTuned(
        pc: PeerConnection,
        desc: SessionDescription,
    ): SessionDescription {
        val tunedSdp = runCatching { CallSdp.tuneLocal(desc.sdp, performanceMode()) }.getOrNull()
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
     * Installs a remote description, rewritten into the two-endpoint envelope
     * ([CallSdp.tuneRemote]).
     *
     * Load-bearing direction: an encoder takes its bitrate and packetization from the
     * description it RECEIVES, so this — not [setLocalDescriptionTuned] — is what actually
     * throttles this device. Which is exactly why it cannot simply force our own numbers: doing
     * that on a LOW-tier peer's offer would rewrite its `ptime:60` back to `ptime:10` and go on
     * flooding the radio it just asked us not to flood. The envelope takes the more conservative of
     * the two declarations per parameter, so both ends compute the same effective settings no
     * matter which of them offered.
     */
    private suspend fun setRemoteDescriptionTuned(
        pc: PeerConnection,
        type: SessionDescriptionType,
        sdp: String,
    ) {
        val tuned = runCatching { CallSdp.tuneRemote(sdp, performanceMode()) }.getOrNull()
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
            // Pinned: addIceCandidate is native (candidates may also arrive from the UI
            // thread via onInboundFrame — the pin, not the caller, decides the thread).
            onMediaThread {
            if (pc.remoteDescription != null) {
                pc.addIceCandidate(candidate)
            } else {
                // Remote description not applied yet — buffer (webrtc-kmp sample pattern).
                pendingIce.add(candidate)
            }
            }
        }
    }

    private suspend fun flushPendingIce() {
        val pc = peerConnection ?: return
        iceMutex.withLock {
            onMediaThread {
            while (pendingIce.isNotEmpty()) {
                pc.addIceCandidate(pendingIce.removeAt(0))
            }
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
     * ## Capture geometry is a tier decision, and the most expensive one (ERROR-033)
     *
     * The request comes from [FlashVideoProfile], so it is 1080p30 only on HIGH. It used to be
     * 1080p30 unconditionally, which on a 2 GB API-27 handset with a 480x640 screen meant ~62
     * megapixel/s of capture-side scale and colour conversion — paid on the CPU *before* the
     * encoder sees a frame, and paid regardless of what the encoder then decides to send. Neither
     * of the two adaptive mechanisms in this file helps with that: `MAINTAIN_FRAMERATE` and
     * [CallQualityGovernor] both act on the *encoder*, downstream of the cost. The only way not to
     * pay it is not to ask for the pixels.
     *
     * webrtc-kmp's default is 1280x720 (`CameraVideoCapturerController.selectVideoSize` falls back
     * to those two literals when the constraints carry no size). The camera enumerator snaps the
     * request to the closest format it actually supports, so a device without the requested mode
     * degrades instead of failing.
     */
    private suspend fun startMedia(): Boolean {
        if (peerConnection != null) return true
        val videoProfile = performanceMode().video
        // Pinned: factory init, ADM device select, PeerConnection(), addTrack and the sender
        // tuning all touch native audio from here. Lifecycle-locked against teardown (see
        // mediaLifecycleMutex): the ended-path unwind uses the Locked variant (no re-lock).
        return onMediaThread {
            mediaLifecycleMutex.withLock {
            try {
            FlashLog.i(
                "CALL",
                "startMedia video=$video tier=${performanceMode().key} " +
                    "capture=${videoProfile.label} call=$callId",
            )
            val stream = MediaDevices.getUserMedia {
                audio {
                    // Voice-call processing, stated explicitly: bare audio(true) leaves all
                    // three null, and the JVM backend maps null to false — the desktop then
                    // runs with no echo cancellation (open speakers howl), no noise
                    // suppression and no gain control. On Android the same trues land as
                    // goog* mandatory+optional constraints.
                    echoCancellation(true)
                    noiseSuppression(true)
                    autoGainControl(true)
                }
                if (video) {
                    video {
                        width(videoProfile.captureWidth)
                        height(videoProfile.captureHeight)
                        frameRate(videoProfile.captureFps.toDouble())
                    }
                }
            }
            localStream = stream
            // Publish before the PeerConnection exists: the preview renderer is already
            // composed and waiting on this flow.
            _localVideoStreamTrack.value = stream.videoTracks.firstOrNull()
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
                releaseMediaLocked()
                return@onMediaThread false
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
     * The audio ceiling is a ceiling, not a target: Opus with in-band FEC (see [CallSdp]) is
     * already transparent for speech well below it, and capping it stops a generous bandwidth
     * estimate from handing voice bitrate it cannot use. It comes from
     * [com.transfer.flash.core.common.perf.FlashVoiceProfile] so it drops with the tier — 16 kbit/s
     * on LOW, where the packet *rate* has been cut too and the two savings compound.
     *
     * Best-effort, like [tuneVideoSender]: a failure here costs priority, not the call.
     */
    private fun tuneAudioSender(sender: RtpSender) {
        if (!prioritiseVoice()) {
            FlashLog.i("CALL", "voice priority off — leaving symmetric sender defaults")
            return
        }
        val maxBitrateBps = performanceMode().voice.maxBitrateBps
        try {
            val applied = sender.applyAudioTuning(AudioSendTuning(maxBitrateBps = maxBitrateBps))
            if (!applied) {
                FlashLog.w("CALL", "audio sender has no encodings to tune")
                return
            }
            FlashLog.i(
                "CALL",
                "audio sender tuned applied=$applied max=${maxBitrateBps / 1000}kbps " +
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
     * Without a bitrate ceiling this is academic: libwebrtc caps a VP8 stream around 2.5 Mbit/s
     * from its internal codec table, which starves 1080p no matter what the link can carry. The
     * ceiling, the floor and the frame-rate cap all come from [FlashVideoProfile], so on LOW this
     * asks for 350 kbit/s of 480x360p15 rather than 2.5 Mbit/s of 1080p30.
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
        val profile = performanceMode().video
        try {
            val voiceFirst = prioritiseVoice()
            val applied = sender.applyVideoTuning(
                VideoSendTuning(
                    maxBitrateBps = profile.maxBitrateKbps * BPS_PER_KBPS,
                    minBitrateBps = profile.minBitrateKbps * BPS_PER_KBPS,
                    maxFramerate = profile.captureFps.toDouble(),
                    // Send at capture resolution; adaptation drives this down on its own.
                    scaleResolutionDownBy = 1.0,
                    demoteForVoice = voiceFirst,
                    maintainFramerate = true,
                ),
            )
            if (!applied) {
                FlashLog.w("CALL", "video sender has no encodings to tune")
                return
            }
            FlashLog.i(
                "CALL",
                "video sender tuned applied=$applied max=${profile.maxBitrateKbps}kbps " +
                    "fps=${profile.captureFps} degradation=MAINTAIN_FRAMERATE " +
                    "voiceFirst=$voiceFirst",
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
        // Collectors ride the media thread: the flows only emit, but pinning the
        // collection keeps every continuation touching call state on one thread.
        eventJobs.add(scope.launch(callMediaDispatcher) {
            pc.onTrack.collect { event ->
                // The event's own track, not a snapshot of event.streams — see the
                // [remoteVideoStreamTrack] doc for why the stream wrapper is unreliable here.
                val track = event.track
                if (track is VideoStreamTrack) {
                    FlashLog.i("CALL", "remote video track id=${track.id} call=$callId")
                    _remoteVideoStreamTrack.value = track
                }
                if (track is AudioStreamTrack) {
                    FlashLog.i("CALL", "remote audio track id=${track.id} call=$callId")
                    remoteAudio = track
                }
            }
        })
        eventJobs.add(scope.launch(callMediaDispatcher) {
            pc.onConnectionStateChange.collect { cs ->
                FlashLog.i("CALL", "peer connection state=$cs call=$callId")
                when (cs) {
                    PeerConnectionState.Connected -> {
                        dialTimeoutJob?.cancel()
                        connectTimeoutJob?.cancel()
                        disconnectGraceJob?.cancel()
                        // Media is flowing again, so whatever the signaling watcher thought it saw
                        // is moot — a session that can carry ICE can carry a Hangup.
                        signalingGraceJob?.cancel()
                        _state.value = _state.value.copy(
                            state = FlashCallState.ACTIVE,
                            // First connect only. Now that a call can genuinely reconnect, stamping
                            // this again would restart the duration the call log reports and turn a
                            // ten-minute call that survived a roam into a ten-second one.
                            connectedAt = _state.value.connectedAt ?: SystemTimeSource.nowMs(),
                        )
                        armStatsPolling(pc)
                    }
                    PeerConnectionState.Disconnected -> {
                        armIceRecovery(pc, FlashCallEndReason.DISCONNECTED)
                    }
                    PeerConnectionState.Failed -> {
                        armIceRecovery(pc, FlashCallEndReason.ERROR)
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
        })
        eventJobs.add(scope.launch(callMediaDispatcher) {
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
        })
    }

    // --------------------------------------------------------------- ICE recovery

    /**
     * Opens the recovery window for a call whose transport has just gone away (ERROR-033).
     *
     * The window is [disconnectGraceMs] long and is spent *working* rather than waiting: see
     * [recoverIce]. If it expires with the connection still down the call ends with [reason]. A
     * return to `Connected` cancels this job, which is the success path.
     *
     * Replaces two branches that both gave up too early. `Disconnected` used to be a bare
     * `delay(5_000)`, which was a bet that ICE would repair itself — and ICE cannot repair a path
     * whose local candidates no longer exist, which is precisely what a Wi-Fi roam produces.
     * `Failed` used to end the call on the spot, even though an ICE restart is the documented
     * remedy for exactly that state.
     */
    private fun armIceRecovery(pc: PeerConnection, reason: FlashCallEndReason) {
        disconnectGraceJob?.cancel()
        disconnectGraceJob = scope.launch {
            recoverIce(pc)
            if (_state.value.state != FlashCallState.ENDED) {
                FlashLog.w("CALL", "ICE recovery window expired, ending call=$callId reason=$reason")
                end(reason, notifyPeer = false)
            }
        }
    }

    /**
     * Tries to rebuild the media path for up to [disconnectGraceMs], then returns.
     *
     * ## Why a loop and not one attempt
     *
     * The device that roams loses *signaling* at the same moment it loses media — same radio, same
     * association. So the first restart offer very often cannot be delivered at all: [sendFrame]
     * returns false because the WS session it needs is itself being redialled by
     * `WsFlashNetwork`. One attempt at the moment of failure is therefore an attempt made at the
     * worst possible instant. The loop keeps offering until the transport underneath it comes back,
     * which on a 2.4 GHz-only client with no fast-transition support is seconds away — the reason
     * [FlashTransportProfile.callDisconnectGraceMs] is 25 s at LOW and not 5 s.
     *
     * ## Why only the caller offers
     *
     * The same glare rule as call setup (ADR-025): both endpoints see this transition
     * simultaneously, and two simultaneous offers on one PeerConnection is a rollback mess. The
     * callee simply waits out the window and answers whatever arrives — [onOffer] and [onAnswer]
     * accept a renegotiation while `ACTIVE` for that reason.
     */
    private suspend fun recoverIce(pc: PeerConnection) {
        val minIntervalMs = performanceMode().transport.iceRestartMinIntervalMs
        val deadline = SystemTimeSource.nowMs() + disconnectGraceMs
        if (direction != FlashCallDirection.OUTGOING) {
            // Answerer: nothing to send, just hold the window open.
            delay(disconnectGraceMs)
            return
        }
        while (true) {
            val remaining = deadline - SystemTimeSource.nowMs()
            if (remaining <= 0L) return
            if (SystemTimeSource.nowMs() - lastIceRestartAtMs >= minIntervalMs) {
                lastIceRestartAtMs = SystemTimeSource.nowMs()
                attemptIceRestart(pc)
            }
            delay(minOf(minIntervalMs, remaining))
        }
    }

    /**
     * Sends one ICE restart offer: new ICE credentials, fresh candidate gathering, same media
     * sections. Returns whether the offer reached the peer at all.
     *
     * Goes through [setLocalDescriptionTuned] like every other description this device generates,
     * so a restart is also when a tier change since the call started takes effect.
     *
     * Takes [signalMutex] because it mutates the same signaling state an inbound frame would, and
     * an offer created while a remote description is half-applied is a rollback. Never throws: a
     * failed restart is one wasted attempt, and the window has more.
     */
    private suspend fun attemptIceRestart(pc: PeerConnection): Boolean = signalMutex.withLock {
        if (ended) return@withLock false
        // Pinned: createOffer + setLocalDescription are native.
        onMediaThread {
        try {
            val offer = pc.createOffer(
                OfferAnswerOptions(
                    iceRestart = true,
                    offerToReceiveAudio = true,
                    offerToReceiveVideo = video,
                ),
            )
            val applied = setLocalDescriptionTuned(pc, offer)
            val delivered = sendFrame(
                CallWireFrame.Offer(callId = callId, from = localDeviceId, sdp = applied.sdp),
            )
            FlashLog.i("CALL", "ICE restart offer delivered=$delivered call=$callId")
            delivered
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FlashLog.w("CALL", "ICE restart offer failed: ${t.message}")
            false
        }
        }
    }

    // ------------------------------------------------------------------ metrics

    /**
     * Starts the [stats] sampler. Armed on Connected rather than at media start because
     * `getStats()` has nothing to say about a transport that has not selected a candidate pair yet.
     *
     * The period comes from [FlashTransportProfile.callStatsIntervalMs], so it halves to 2 s on LOW:
     * a `getStats()` pass walks every report the native stack holds, which is a measurable cost on
     * the hardware least able to pay it. It is also the governor's tick — [applyVoicePriority]
     * rides this loop — so a slower sample is a proportionally slower governor, which is the
     * trade being made.
     */
    private fun armStatsPolling(pc: PeerConnection) {
        statsJob?.cancel()
        lastStatsAtUs = 0L
        lastBytesReceived = 0L
        lastBytesSent = 0L
        lastAudioEnergy = 0L
        lastAudioDurationS = 0.0
        lastPacketsLost = 0L
        lastPacketsReceived = 0L
        lastIntervalLoss = null
        loggedReportShape = false
        val intervalMs = performanceMode().transport.callStatsIntervalMs
        // S2e: was a dedicated single-thread executor (JVM-only API). The sampler now rides
        // the shared media thread ([callMediaDispatcher] — single on JVM, pool on Android),
        // which also puts getStats() on the pinned thread where WASAPI/COM wants it.
        val sampler = statsDispatcher
            ?: callMediaDispatcher.also { statsDispatcher = it }
        statsJob = scope.launch(sampler) {
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
                delay(intervalMs)
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
        // Scale the TIER's ceiling, not a constant: a concession is a fraction of what this device
        // was ever going to send, so on LOW the ladder walks down from 350 kbit/s, not 2.5 Mbit/s.
        val profile = performanceMode().video
        val ceiling = (profile.maxBitrateKbps * BPS_PER_KBPS * level.bitrateScale).toInt()
        val floor = profile.minBitrateKbps * BPS_PER_KBPS
        try {
            val applied = sender.applyVideoTuning(
                VideoSendTuning(
                    maxBitrateBps = ceiling,
                    minBitrateBps = if (level.holdsBitrateFloor) floor else null,
                    scaleResolutionDownBy = level.scaleResolutionDownBy,
                    active = level.videoActive,
                ),
            )
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
     * Stats type-dialect normalizer (measured 2026-09-15, desktop live run).
     *
     * Android's libwebrtc reports W3C-style lowercase-hyphen types (`candidate-pair`,
     * `inbound-rtp`); webrtc-java on the desktop reports the same taxonomy as UPPER_SNAKE
     * (`CANDIDATE_PAIR`, `OUTBOUND_RTP`). Member keys are camelCase on both. Matching on the
     * normalized form keeps one parser for both hosts — without it the desktop never
     * resolves RTT/jitter/kbps and the latency badge stays empty on connected calls.
     */
    private fun normStatType(type: String): String = type.lowercase().replace('_', '-')

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
        // Pinned: getStats() walks native reports.
        val report = onMediaThread { pc.getStats() } ?: return null
        val all = report.stats.values

        // RTT lives on the SELECTED candidate pair. The transport stat names it outright;
        // the nominated-and-succeeded pair is the fallback for older report shapes.
        val selectedId = all.firstOrNull { normStatType(it.type) == "transport" }
            ?.members?.get("selectedCandidatePairId") as? String
        val pairs = all.filter { normStatType(it.type) == "candidate-pair" }
        val pair = pairs.firstOrNull { it.id == selectedId }
            ?: pairs.firstOrNull {
                it.members.bool("nominated") == true && it.members.str("state") == "succeeded"
            }
            ?: pairs.firstOrNull { it.members.num("currentRoundTripTime") != null || it.members.num("roundTripTime") != null }

        val rttSec = pair?.members?.num("currentRoundTripTime")
            ?: pair?.members?.num("roundTripTime")
            ?: run {
                val totalRtt = pair?.members?.num("totalRoundTripTime")
                val resp = pair?.members?.num("responsesReceived")
                if (totalRtt != null && resp != null && resp > 0) totalRtt / resp else null
            }

        val inbound = all.filter { normStatType(it.type) == "inbound-rtp" }
        val outbound = all.filter { normStatType(it.type) == "outbound-rtp" }
        val audioIn = inbound.firstOrNull { it.members.str("kind") == "audio" }
        val videoIn = inbound.firstOrNull { it.members.str("kind") == "video" }
        val videoOut = outbound.firstOrNull { it.members.str("kind") == "video" }

        val bytesIn = inbound.sumOf { it.members.num("bytesReceived")?.toLong() ?: 0L }
        val bytesOut = outbound.sumOf { it.members.num("bytesSent")?.toLong() ?: 0L }
        // Flow movement, logged on change only: the one-shot shape dump below proves the
        // fields exist, but a single t=0 sample cannot say whether anything ever moves.
        // Compared BEFORE the lasts update — after it they are equal by construction.
        // `audioLevel` is the mic-liveness witness — nonzero means capture delivers frames
        // even when the network carries nothing. W3C reports it as a 0.0–1.0 double, so it
        // must stay a Double: truncating to Int reads 0 for anything below full scale and
        // the witness goes blind on quiet speech.
        val audioSource = all
            .filter { normStatType(it.type) == "media-source" }
            .firstOrNull { it.members.str("kind") == "audio" }
        val audioLevel = audioSource?.members?.num("audioLevel")
        // totalSamplesDuration grows iff the ADM pulls frames at all; totalAudioEnergy grows
        // iff those frames are non-silent. Frozen duration = capture not running (wrong ADM
        // state); growing duration with frozen energy = a dead/muted device delivering zeros
        // (wrong device selected — the native index-0 fallback — or OS-muted).
        val audioEnergy = audioSource?.members?.num("totalAudioEnergy")?.toLong() ?: 0L
        val audioDurationS = audioSource?.members?.num("totalSamplesDuration") ?: 0.0
        if (bytesIn != lastBytesReceived || bytesOut != lastBytesSent ||
            audioEnergy != lastAudioEnergy || audioDurationS != lastAudioDurationS
        ) {
            FlashLog.i(
                "CALL",
                "stats flow bytesIn=$bytesIn bytesOut=$bytesOut audioLevel=$audioLevel " +
                    "audioEnergy=$audioEnergy audioDurationS=$audioDurationS",
            )
        }
        val nowUs = report.timestampUs
        val elapsedUs = if (lastStatsAtUs > 0L) nowUs - lastStatsAtUs else 0L
        val inboundKbps = kbps(bytesIn - lastBytesReceived, elapsedUs)
        val outboundKbps = kbps(bytesOut - lastBytesSent, elapsedUs)
        lastStatsAtUs = nowUs
        lastBytesReceived = bytesIn
        lastBytesSent = bytesOut
        lastAudioEnergy = audioEnergy
        lastAudioDurationS = audioDurationS

        val lost = inbound.sumOf { it.members.num("packetsLost")?.toLong() ?: 0L }
        val received = inbound.sumOf { it.members.num("packetsReceived")?.toLong() ?: 0L }
        if (!loggedReportShape) {
            loggedReportShape = true
            val shape = all.groupBy { it.type }.mapValues { (_, reports) ->
                reports.firstOrNull()?.members?.keys?.sorted()
            }
            FlashLog.i(
                "CALL",
                "stats shape types=$shape bytesIn=$bytesIn bytesOut=$bytesOut " +
                    "packetsReceived=$received packetsLost=$lost",
            )
        }

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
            rttMs = rttSec?.let { (it * 1000).roundToInt() },
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

    private fun Map<String, Any>.num(key: String): Double? =
        (this[key] as? Number)?.toDouble() ?: (this[key] as? String)?.toDoubleOrNull()

    private fun Map<String, Any>.str(key: String): String? =
        (this[key] as? String) ?: this[key]?.toString()

    private fun Map<String, Any>.bool(key: String): Boolean? =
        (this[key] as? Boolean) ?: (this[key] as? String)?.toBooleanStrictOrNull()

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
        signalingGraceJob?.cancel()
        deferredFrames.clear()
        _state.value = _state.value.copy(
            state = FlashCallState.ENDED,
            endReason = reason,
        )
        // Native teardown hops to the media thread: end() is routinely called from UI
        // callbacks and pool timer jobs. Async is safe — ended=true already guards every
        // path, and releaseMedia is idempotent (a racing startMedia unwinds itself).
        scope.launch(callMediaDispatcher) { releaseMedia() }
        if (notifyPeer) {
            scope.launch {
                sendFrame(CallWireFrame.Hangup(callId = callId, from = localDeviceId))
            }
        }
        onEnded(this)
    }

    /**
     * Closes the PeerConnection, stops the event collectors and releases the microphone
     * and camera. Idempotent. Lifecycle-locked: runs to completion before any concurrent
     * [startMedia] may enter, so the next acquisition never observes mid-teardown native
     * state. Runs on the media thread ([onMediaThread]): `close()` and stream/track release
     * are native calls. Callers: [end] (via a pinned launch) and [startMedia]'s unwind.
     */
    private suspend fun releaseMedia() {
        mediaLifecycleMutex.withLock { releaseMediaLocked() }
    }

    /** [releaseMedia] with the lifecycle lock already held (startMedia's ended-path). */
    private suspend fun releaseMediaLocked(): Unit = onMediaThread {
        statsJob?.cancel()
        statsJob = null
        // The sampler thread belongs to the call, not the process: a finished call must not
        // hold it. `close()` is idempotent and releaseMedia is too, so a second pass is a no-op.
        statsDispatcher = null
        _stats.value = null
        eventJobs.toList().forEach { it.cancel() }
        eventJobs.clear()
        // Unpublish first: the UI unbinds its renderer sinks off the back of these flows,
        // and every track they hold is about to be stopped.
        _localVideoStreamTrack.value = null
        _remoteVideoStreamTrack.value = null
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

    /**
     * Host calls this when the WS signaling session to the peer died.
     *
     * Opens a window instead of ending the call (ERROR-033). This used to be an immediate
     * `end(DISCONNECTED)` on the reasoning that "a call cannot survive its signaling session" —
     * true in the long run, false over the two to five seconds a mesh roam actually takes, and it
     * pre-empted every recovery mechanism in this file: the ICE restart in [recoverIce] can only be
     * delivered over signaling, so killing the call the moment signaling drops guaranteed the
     * restart never happened on the one failure it exists for.
     *
     * The window is sized by [disconnectGraceMs], the same budget the media path gets, because the
     * two outages are the same outage. [onSignalingRestored] closes it; expiry ends the call.
     */
    public fun onSignalingLost() {
        if (ended) return
        if (signalingGraceJob?.isActive == true) return // already counting
        FlashLog.i("CALL", "signaling lost, holding call for ${disconnectGraceMs}ms call=$callId")
        signalingGraceJob = scope.launch {
            delay(disconnectGraceMs)
            if (!ended) {
                FlashLog.w("CALL", "signaling did not return, ending call=$callId")
                end(FlashCallEndReason.DISCONNECTED, notifyPeer = false)
            }
        }
    }

    /**
     * Host calls this when a signaling session to the peer is live again.
     *
     * Cancels the [onSignalingLost] window. It does **not** revive the media path — that is
     * [recoverIce]'s job, and its own window is still running underneath this one. What it does is
     * make the restart offer deliverable, which is the only reason the call was kept alive.
     */
    public fun onSignalingRestored() {
        if (ended) return
        if (signalingGraceJob?.isActive != true) return
        FlashLog.i("CALL", "signaling restored, call held call=$callId")
        signalingGraceJob?.cancel()
        signalingGraceJob = null
    }

    private companion object {
        /**
         * Cap on [deferredFrames]. A peer trickles a handful of host candidates on a LAN;
         * anything past this is a flood, not a race, and must not grow without bound.
         */
        const val MAX_DEFERRED_FRAMES = 64

        /**
         * Allocator weights, and the reason a video call can be understood on a bad link.
         *
         * `bitratePriority` is relative and defaults to 1.0 for every stream, so the pair below
         * asks the allocator to serve voice roughly eight times more eagerly than video when the
         * estimate is too small for both. Voice needs ~32 kbit/s of a link that must be at least
         * a few hundred; the weighting only ever matters in the region where video was going to
         * be ugly regardless.
         *
         * Not tiered: these are ratios, and the ratio voice needs does not depend on the handset.
         * (AUDIO_BITRATE_PRIORITY / VIDEO_BITRATE_PRIORITY moved to the sender-tuning seam
         * (RtpSenderTuning.kt) with the rest of the native-knob plumbing — S2e.)
         */
        const val BPS_PER_KBPS = 1_000
    }
}
