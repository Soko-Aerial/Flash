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
import com.shepeliev.webrtckmp.SignalingState
import com.shepeliev.webrtckmp.VideoStreamTrack
import com.shepeliev.webrtckmp.audioTracks
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onIceConnectionStateChange
import com.shepeliev.webrtckmp.onTrack
import com.shepeliev.webrtckmp.videoTracks
import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallParticipantUi
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallStats
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.FlashGroupCallLimits
import com.transfer.flash.core.calling.model.FlashParticipantVideo
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.concurrent.SyncMap
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.perf.FlashNetworkBand
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.perf.FlashVideoProfile
import com.transfer.flash.core.common.perf.ThermalGovernor
import com.transfer.flash.core.common.time.SystemTimeSource
import kotlin.concurrent.Volatile
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
import kotlinx.coroutines.withContext

/**
 * Multi-peer WebRTC mesh session for Group Calls (Phase 2, docs/group/phase-2-group-voice.md).
 *
 * Architecture:
 * - Decentralized full-mesh topology: Each participant maintains an independent 1:1 [PeerConnection]
 *   leg with every other participant in the group call.
 * - Single local capture: [MediaDevices.getUserMedia] runs once; the local audio (and optional video)
 *   track is shared across all active [PeerConnection] legs.
 * - Resilient per-leg lifecycle:
 *   - When a peer leaves or drops, only that peer's leg is closed and disposed.
 *   - The remaining participants continue streaming audio and video without any interruption or glitch.
 *   - When only 1 participant remains, a grace timeout starts ("Waiting for others to join...")
 *     allowing late-joiners or returning peers to reconnect without dropping the room.
 *   - Glare prevention: Deterministic tie-breaker (`localDeviceId > remotePeerId`) assigns the
 *     Offerer role per leg, preventing simultaneous offer collisions.
 */
@OptIn(FlashInternalApi::class)
public class FlashGroupCallSession(
    public val callId: String,
    public val groupId: String,
    public val groupName: String,
    public val direction: FlashCallDirection,
    public val video: Boolean,
    private val localDeviceId: String,
    private val localName: String,
    private val scope: CoroutineScope,
    /** Outbound frame dispatcher addressing frames to specific peers. */
    private val sendFrame: suspend (CallWireFrame, peerId: String) -> Boolean,
    private val onEnded: (FlashGroupCallSession) -> Unit = {},
    private val performanceMode: () -> FlashPerformanceMode = { FlashPerformanceMode.HIGH },
    private val peerNameResolver: (String) -> String? = { null },
    /** This device's network band (G2), read for every announcement; cheap and non-blocking. */
    private val networkBand: () -> FlashNetworkBand = { FlashNetworkBand.UNKNOWN },
) : FlashCallMedia {

    private fun resolveName(peerId: String, fallback: String? = null): String =
        peerNameResolver(peerId)?.ifBlank { null }
            ?: fallback?.takeIf { it.isNotBlank() && it != peerId }
            ?: peerId

    private val _state = MutableStateFlow(
        FlashCallUiState(
            callId = callId,
            peerId = groupId,
            peerName = groupName,
            direction = direction,
            video = video,
            state = if (direction == FlashCallDirection.OUTGOING) FlashCallState.DIALING else FlashCallState.RINGING,
            isGroup = true,
            groupId = groupId,
            participants = emptyList(),
        ),
    )
    public val state: StateFlow<FlashCallUiState> = _state.asStateFlow()

    private val _stats = MutableStateFlow<FlashCallStats?>(null)
    override val stats: StateFlow<FlashCallStats?> = _stats.asStateFlow()

    private val _localVideoStreamTrack = MutableStateFlow<VideoStreamTrack?>(null)
    override val localVideoStreamTrack: StateFlow<VideoStreamTrack?> = _localVideoStreamTrack.asStateFlow()

    private val _remoteVideoStreamTrack = MutableStateFlow<VideoStreamTrack?>(null)
    override val remoteVideoStreamTrack: StateFlow<VideoStreamTrack?> = _remoteVideoStreamTrack.asStateFlow()

    /** Every participant's video (G1). Touched only on the media thread; see [publishRemoteVideo]. */
    private val remoteVideo = PeerTrackTable<VideoStreamTrack>()
    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoStreamTrack>>(emptyMap())
    override val remoteVideoTracks: StateFlow<Map<String, VideoStreamTrack>> = _remoteVideoTracks.asStateFlow()

    private val legs = SyncMap<String, GroupLeg>()
    private val sessionMutex = Mutex()

    /**
     * Request-based video (G3). Every router call and the effects it returns run under
     * [videoMutex] (see [routeVideo]), so the encodings are switched in the order the router
     * decided. The two snapshots let non-suspending readers (the UI state, a new leg's sender
     * tuning, the presence frame) see its state without the lock.
     */
    private val videoMutex = Mutex()
    private val videoRouter = GroupVideoRouter(
        callId = callId,
        localId = localDeviceId,
        limits = { videoLimits() },
        participants = {
            legs.valuesSnapshot().filter { it.state in VIDEO_PRESENT }.map { it.peerId }.sorted()
        },
        clock = { SystemTimeSource.nowMs() },
    )

    @Volatile
    private var videoStates: Map<String, FlashParticipantVideo> = emptyMap()

    /** Legs carrying this device's video, with the copy's height (null: full profile, an old client). */
    @Volatile
    private var sendingTo: Map<String, Int?> = emptyMap()

    @Volatile
    private var videoFreeNow: Int = 0

    /** G5 snapshots for the UI state: the pin, the main tile's participant, and the compact layout. */
    @Volatile
    private var videoFocusNow: String? = null

    @Volatile
    private var videoMainNow: String? = null

    @Volatile
    private var videoCompactNow: Boolean = false

    /** G6: heat, CPU and decoder health; used only under [videoMutex]. */
    private val health = CallHealthMonitor()

    @Volatile
    private var healthNow: CallHealthMonitor.Verdict = CallHealthMonitor.Verdict()
    private var lastCpuNanos: Long? = null
    private var lastCpuAtMs: Long = 0L

    /** Device-test logging (`CALL_DIAG`), fed by the stats loop. */
    private val diagnostics = CallDiagnostics()

    /** The last `video route` line, so a change is logged once. */
    private var lastRouteLine: String? = null

    /** The camera size this call asked for ([groupCaptureProfile] at acquire time). */
    @Volatile
    private var captureProfile: FlashVideoProfile? = null

    /**
     * Serializes media acquisition against native teardown (same contract as the 1:1
     * session's lock): [acquireMedia] and [endSession]'s native section never interleave, so a
     * rejoin acquire cannot observe a previous leg's mid-teardown `close()`/release.
     * Non-reentrant by construction: holders call only the Locked variants ([closeLegLocked]).
     */
    private val mediaLifecycleMutex = Mutex()

    /**
     * Confines [block] to [callMediaDispatcher]: every native WebRTC touch in this session
     * goes through here. Legs are driven from IO-pool collectors, timer jobs and UI-thread
     * toggles; without this each drives WASAPI/COM from a different OS thread (silent buzz +
     * dead capture on Windows). Nesting is safe — `withContext` on the media thread from the
     * media thread suspends and re-queues.
     */
    private suspend fun <T> onMediaThread(block: suspend CoroutineScope.() -> T): T =
        withContext(callMediaDispatcher, block)

    private var localStream: MediaStream? = null
    private var isMediaAcquired = false
    private var isEnded = false
    public val isSessionEnded: Boolean get() = isEnded
    public fun countConnectedParticipants(): Int = countConnectedLegs()
    private var soloWaitingJob: Job? = null
    private var statsJob: Job? = null
    private var presenceJob: Job? = null
    private var lastStatsAtMs = 0L
    private var lastBytesReceived = 0L
    private var lastBytesSent = 0L

    /** Representation of a single peer leg in the mesh. */
    private class GroupLeg(
        val peerId: String,
        var peerName: String,
        var state: FlashCallParticipantState = FlashCallParticipantState.INVITED,
        var peerConnection: PeerConnection? = null,
        var isSpeaking: Boolean = false,
        var isMuted: Boolean = false,
        val legMutex: Mutex = Mutex(),
        var iceJob: Job? = null,
        var trackJob: Job? = null,
        var connJob: Job? = null,
        var signalingGraceJob: Job? = null,
        val pendingIce: ArrayDeque<IceCandidate> = ArrayDeque(),
        var remoteDescriptionSet: Boolean = false,
        var audioSender: RtpSender? = null,
        var videoSender: RtpSender? = null,
        /** The video this leg's connection delivered, so closing the leg removes exactly that. */
        var remoteVideoTrack: VideoStreamTrack? = null,
        /** The band this peer last announced (G2); null until it does, or from an old client. */
        var band: FlashNetworkBand? = null,
        /** How many connections this leg has built (logs name them `pc#n`). */
        var pcGeneration: Int = 0,
        /** When the current connection was built, for the setup grace (ERROR-076). */
        var pcCreatedAtMs: Long = 0L,
    )

    internal fun getLegStateForTesting(peerId: String): FlashCallParticipantState? = legs[peerId]?.state

    internal fun setLegStateForTesting(peerId: String, state: FlashCallParticipantState) {
        legs[peerId]?.state = state
        refreshUiState()
    }

    /** Test hook: add a leg in [state] and treat this device as in the call (media acquired). */
    internal fun addJoinedLegForTesting(peerId: String, state: FlashCallParticipantState) {
        legs[peerId] = GroupLeg(peerId = peerId, peerName = peerId, state = state)
        isMediaAcquired = true
        refreshUiState()
    }

    /** Sets up an incoming ringing group call leg from the inviting caller. */
    public fun startIncomingRinging(peerId: String, callerName: String) {
        val resolvedName = resolveName(peerId, callerName)
        legs[peerId] = GroupLeg(
            peerId = peerId,
            peerName = resolvedName,
            state = FlashCallParticipantState.INVITED,
        )
        refreshUiState()
    }

    /** Starts an outgoing group call, sending invites to all initial members. */
    public suspend fun startOutgoing(initialMemberIds: List<String>): Boolean {
        sessionMutex.withLock {
            if (isEnded) return false
            initialMemberIds.filter { it != localDeviceId }.forEach { memberId ->
                legs[memberId] = GroupLeg(
                    peerId = memberId,
                    peerName = resolveName(memberId),
                    state = FlashCallParticipantState.INVITED,
                )
            }
            refreshUiState()
        }

        val acquired = acquireMedia()
        if (!acquired) {
            endSession(FlashCallEndReason.ERROR)
            return false
        }

        routeVideo { startReceiving() }

        // Fan out GroupInvite to all initial members
        initialMemberIds.filter { it != localDeviceId }.forEach { memberId ->
            sendFrame(
                CallWireFrame.GroupInvite(
                    callId = callId,
                    from = localDeviceId,
                    groupId = groupId,
                    callerName = localName,
                    video = video,
                    members = initialMemberIds,
                    band = networkBand(),
                    videoRequests = true,
                ),
                memberId,
            )
        }

        // Arm dial timeout (30s): if no one joins, end with NO_ANSWER
        scope.launch {
            delay(30_000L)
            sessionMutex.withLock {
                if (_state.value.state == FlashCallState.DIALING && countConnectedLegs() == 0) {
                    FlashLog.i("GROUP_CALL", "No members answered outgoing group call $callId in 30s")
                    endSession(FlashCallEndReason.NO_ANSWER)
                }
            }
        }
        armPresenceAnnouncement()
        return true
    }

    /** Accepts an incoming ringing group call. */
    public suspend fun accept(): Boolean {
        sessionMutex.withLock {
            if (isEnded || _state.value.state != FlashCallState.RINGING) return false
            _state.value = _state.value.copy(state = FlashCallState.CONNECTING)
        }

        val acquired = acquireMedia()
        if (!acquired) {
            endSession(FlashCallEndReason.ERROR)
            return false
        }

        armPresenceAnnouncement()
        routeVideo { startReceiving() }

        // Broadcast GroupAccept / GroupJoin to known participants
        val currentPeers = legs.keysSnapshot()
        currentPeers.forEach { peerId ->
            sendFrame(
                CallWireFrame.GroupAccept(
                    callId = callId,
                    from = localDeviceId,
                    groupId = groupId,
                    band = networkBand(),
                    videoRequests = true,
                ),
                peerId,
            )
        }

        // Initiate legs with peers where localDeviceId > peerId
        currentPeers.forEach { peerId ->
            scope.launch {
                ensureLegConnected(peerId)
            }
        }
        return true
    }

    /** Joins an ongoing group call announced by peers. */
    public suspend fun joinExisting(memberIds: List<String>): Boolean {
        sessionMutex.withLock {
            if (isEnded) return false
            _state.value = _state.value.copy(
                state = FlashCallState.CONNECTING,
                connectedAt = SystemTimeSource.nowMs(),
            )
            memberIds.filter { it != localDeviceId }.forEach { memberId ->
                legs[memberId] = GroupLeg(
                    peerId = memberId,
                    peerName = resolveName(memberId),
                    state = FlashCallParticipantState.CONNECTING,
                )
            }
            refreshUiState()
        }

        val acquired = acquireMedia()
        if (!acquired) {
            endSession(FlashCallEndReason.ERROR)
            return false
        }

        armPresenceAnnouncement()
        routeVideo { startReceiving() }

        // Announce join to all known members
        val currentPeers = legs.keysSnapshot()
        currentPeers.forEach { peerId ->
            sendFrame(
                CallWireFrame.GroupJoin(
                    callId = callId,
                    from = localDeviceId,
                    groupId = groupId,
                    participantName = localName,
                    band = networkBand(),
                    videoRequests = true,
                ),
                peerId,
            )
        }

        currentPeers.forEach { peerId ->
            scope.launch {
                ensureLegConnected(peerId)
            }
        }
        return true
    }

    private fun armPresenceAnnouncement() {
        if (presenceJob != null) return
        presenceJob = scope.launch {
            while (!isEnded) {
                val callState = _state.value.state
                if (callState == FlashCallState.ACTIVE || callState == FlashCallState.CONNECTING || callState == FlashCallState.DIALING) {
                    val frame = presenceFrame()
                    legs.keysSnapshot().forEach { peerId ->
                        sendFrame(frame, peerId)
                    }
                }
                delay(4_000L)
            }
        }
    }

    /** This call's presence announcement: the group, the head count, the band and (G3) video room. */
    internal fun presenceFrame(): CallWireFrame.GroupPresence = CallWireFrame.GroupPresence(
        callId = callId,
        from = localDeviceId,
        groupId = groupId,
        callerName = groupName,
        video = video,
        participantCount = countConnectedLegs() + 1,
        band = networkBand(),
        videoRequests = true,
        videoFree = if (video) videoFreeNow else null,
    )

    /** Declines an incoming ringing group call. */
    public suspend fun decline() {
        legs.keysSnapshot().forEach { peerId ->
            sendFrame(
                CallWireFrame.GroupDecline(callId = callId, from = localDeviceId, groupId = groupId),
                peerId,
            )
        }
        endSession(FlashCallEndReason.DECLINED)
    }

    /** Hangs up / leaves the group call. */
    public suspend fun hangUp() {
        leave(FlashCallEndReason.NORMAL)
    }

    /** Tells every participant this device left, then ends the session with [reason]. */
    private suspend fun leave(reason: FlashCallEndReason) {
        sessionMutex.withLock {
            if (isEnded) return
            legs.keysSnapshot().forEach { peerId ->
                scope.launch {
                    sendFrame(
                        CallWireFrame.GroupHangup(callId = callId, from = localDeviceId, groupId = groupId),
                        peerId,
                    )
                }
            }
            endSession(reason)
        }
    }

    /**
     * G7: a device this call doesn't count yet ([peerId]) accepted or joined. When the call
     * already holds [FlashGroupCallLimits.maxParticipants] people (this device included), it is
     * told so (`gfull`) and no leg is built; it leaves on its own. Only a device that is in the
     * call decides; a ringing one has not joined anything yet.
     */
    private suspend fun turnAwayIfFull(peerId: String): Boolean {
        if (!isMediaAcquired) return false
        val max = FlashGroupCallLimits.maxParticipants(video)
        val full = sessionMutex.withLock {
            val known = legs[peerId]?.state in VIDEO_PRESENT
            val present = 1 + legs.valuesSnapshot().count { it.peerId != peerId && it.state in VIDEO_PRESENT }
            !known && present >= max
        }
        if (!full) return false
        FlashLog.i("GROUP_CALL", "Call $callId is full ($max); turning $peerId away")
        sendFrame(CallWireFrame.GroupFull(callId = callId, from = localDeviceId, groupId = groupId, max = max), peerId)
        return true
    }

    /** Handles inbound call frames addressed to this group call. */
    public suspend fun onInboundFrame(frame: CallWireFrame, peerId: String) {
        if (isEnded) return

        val effectivePeerId = if (frame.from.isNotBlank()) frame.from else peerId
        if (effectivePeerId == localDeviceId) return
        logInbound(frame, effectivePeerId, peerId)

        when (frame) {
            is CallWireFrame.GroupInvite -> {
                // Peer invited us to this group call (inbound ringing)
                sessionMutex.withLock {
                    legs.getOrPut(effectivePeerId) {
                        GroupLeg(peerId = effectivePeerId, peerName = resolveName(effectivePeerId, frame.callerName), state = FlashCallParticipantState.INVITED)
                    }.recordBand(frame.band)
                    frame.members.filter { it != localDeviceId }.forEach { memberId ->
                        legs.getOrPut(memberId) {
                            GroupLeg(peerId = memberId, peerName = resolveName(memberId), state = FlashCallParticipantState.INVITED)
                        }
                    }
                    refreshUiState()
                }
                if (frame.from == peerId) routeVideo { onAnnouncement(effectivePeerId, frame.videoRequests) }
            }

            is CallWireFrame.GroupAccept, is CallWireFrame.GroupJoin -> {
                val peerName = resolveName(effectivePeerId, if (frame is CallWireFrame.GroupJoin) frame.participantName else null)
                if (turnAwayIfFull(effectivePeerId)) return
                sessionMutex.withLock {
                    val leg = legs.getOrPut(effectivePeerId) {
                        GroupLeg(peerId = effectivePeerId, peerName = peerName)
                    }
                    leg.peerName = peerName
                    // A relayed GroupJoin names the joiner but was sent by someone else; only a
                    // frame from the peer itself says what its band is.
                    if (frame.from == peerId) {
                        leg.recordBand(if (frame is CallWireFrame.GroupAccept) frame.band else (frame as CallWireFrame.GroupJoin).band)
                    }
                    if (leg.state != FlashCallParticipantState.CONNECTED) {
                        leg.state = FlashCallParticipantState.CONNECTING
                    }
                    refreshUiState()
                }
                // Only the peer's own frame says whether it speaks the video protocol (G3).
                if (frame.from == peerId) {
                    val requests = if (frame is CallWireFrame.GroupAccept) frame.videoRequests else (frame as CallWireFrame.GroupJoin).videoRequests
                    routeVideo { onAnnouncement(effectivePeerId, requests) }
                }

                if (isMediaAcquired) {
                    val cause = "${if (frame is CallWireFrame.GroupAccept) "gaccept" else "gjoin"} via $peerId"
                    scope.launch {
                        val existingLeg = legs[effectivePeerId]
                        if (existingLeg != null && existingLeg.state != FlashCallParticipantState.CONNECTED) {
                            val kept = existingLeg.legMutex.withLock {
                                if (keepSettingUpLeg(existingLeg, cause)) {
                                    true
                                } else {
                                    closeLeg(existingLeg)
                                    false
                                }
                            }
                            if (kept) return@launch
                        }
                        ensureLegConnected(effectivePeerId)
                    }
                    // Mesh propagation: only the direct recipient of a GroupAccept fans out GroupJoin to other known legs.
                    // GroupJoin frames must NOT be re-fanned out to avoid broadcast loops/echo storms.
                    if (frame is CallWireFrame.GroupAccept) {
                        val otherPeers = legs.keysSnapshot().filter { it != localDeviceId && it != effectivePeerId }
                        otherPeers.forEach { otherPeerId ->
                            scope.launch {
                                sendFrame(
                                    CallWireFrame.GroupJoin(
                                        callId = callId,
                                        from = effectivePeerId,
                                        groupId = groupId,
                                        participantName = peerName,
                                    ),
                                    otherPeerId,
                                )
                            }
                        }
                    }
                }
            }

            is CallWireFrame.GroupPresence -> {
                // The coordinator hands over this call's own announcements: the band (G2) and the
                // video protocol and room (G3) are what is new.
                if (frame.from == peerId && legs[effectivePeerId] != null) {
                    legs[effectivePeerId]?.recordBand(frame.band)
                    routeVideo { onAnnouncement(effectivePeerId, frame.videoRequests, frame.videoFree) }
                }
            }

            // G3 video requests: only between participants of this call (the coordinator has
            // already checked that `from` is the transport peer).
            is CallWireFrame.VideoRequest -> if (legs[effectivePeerId] != null) routeVideo { onRequest(effectivePeerId, frame) }
            is CallWireFrame.VideoRelease -> if (legs[effectivePeerId] != null) routeVideo { onRelease(effectivePeerId, frame) }
            is CallWireFrame.VideoGrant -> if (legs[effectivePeerId] != null) routeVideo { onGrant(effectivePeerId, frame) }
            is CallWireFrame.VideoDeny -> if (legs[effectivePeerId] != null) routeVideo { onDeny(effectivePeerId, frame) }

            is CallWireFrame.GroupDecline -> {
                handlePeerLeft(effectivePeerId, reason = "declined")
            }

            // G7: a participant says the call was already full when this device joined.
            is CallWireFrame.GroupFull -> if (frame.from == peerId && legs[effectivePeerId] != null) {
                FlashLog.i("GROUP_CALL", "Call $callId is full (max ${frame.max}) per $effectivePeerId; leaving")
                leave(FlashCallEndReason.FULL)
            }

            is CallWireFrame.GroupHangup -> {
                handlePeerLeft(effectivePeerId, reason = "left")
            }

            is CallWireFrame.Offer -> {
                handleInboundOffer(effectivePeerId, frame.sdp)
            }

            is CallWireFrame.Answer -> {
                handleInboundAnswer(effectivePeerId, frame.sdp)
            }

            is CallWireFrame.IceCandidate -> {
                handleInboundIce(effectivePeerId, frame)
            }

            else -> {
                // Ignore 1:1 legacy frames
            }
        }
    }

    /** Ensures a WebRTC leg is established with [peerId]. Pinned: PC create + offer are native. */
    private suspend fun ensureLegConnected(peerId: String) {
        val leg = legs[peerId] ?: return
        leg.legMutex.withLock {
            onMediaThread {
            if (leg.peerConnection != null) {
                val isDead = leg.state == FlashCallParticipantState.DISCONNECTED ||
                    leg.state == FlashCallParticipantState.LEFT
                if (!isDead) return@onMediaThread
                closeLeg(leg)
            }

            val stream = localStream ?: return@onMediaThread
            val pc = createPeerConnectionForLeg(leg, stream)
            leg.peerConnection = pc

            // Glare prevention tie-breaker:
            // The peer with the higher lexicographical deviceId creates the offer.
            if (localDeviceId > peerId) {
                try {
                    val offer = pc.createOffer(OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = video))
                    val applied = setLocalDescriptionTuned(pc, offer)
                    sendFrame(
                        CallWireFrame.Offer(callId = callId, from = localDeviceId, sdp = applied.sdp),
                        peerId,
                    )
                    FlashLog.i("GROUP_CALL", "Sent offer to peer $peerId pc#${leg.pcGeneration} for group call $callId")
                } catch (t: Throwable) {
                    FlashLog.e("GROUP_CALL", "Failed to create offer for leg $peerId", t)
                }
            }
            }
        }
    }

    private fun createPeerConnectionForLeg(leg: GroupLeg, stream: MediaStream): PeerConnection {
        // Callers (ensureLegConnected / handleInboundOffer) already run pinned; the collectors
        // below are pinned at launch so no leg continuation escapes the media thread.
        val pc = PeerConnection(
            RtcConfiguration(
                iceServers = emptyList(),
                bundlePolicy = BundlePolicy.MaxBundle,
                rtcpMuxPolicy = RtcpMuxPolicy.Require,
                iceCandidatePoolSize = 1,
            ),
        )
        leg.pcGeneration++
        leg.pcCreatedAtMs = SystemTimeSource.nowMs()
        FlashLog.i(
            "GROUP_CALL",
            "Leg ${leg.peerId} pc#${leg.pcGeneration} created role=${if (localDeviceId > leg.peerId) "offerer" else "answerer"}",
        )

        // Add shared local tracks to this leg
        val audioTrack = stream.audioTracks.firstOrNull()
        if (audioTrack != null) {
            val audioSender = pc.addTrack(audioTrack, stream)
            leg.audioSender = audioSender
            tuneAudioSender(audioSender)
        }
        if (video) {
            val videoTrack = stream.videoTracks.firstOrNull()
            if (videoTrack != null) {
                val videoSender = pc.addTrack(videoTrack, stream)
                leg.videoSender = videoSender
                // G3: the encoding starts off unless this peer asked (or is an old client).
                tuneVideoSender(videoSender, active = leg.peerId in sendingTo, height = sendingTo[leg.peerId])
            }
        }

        // Listen for remote tracks
        leg.trackJob = scope.launch(callMediaDispatcher) {
            pc.onTrack.collect { trackEvent ->
                val track = trackEvent.track
                FlashLog.i("GROUP_CALL", "Received track on leg ${leg.peerId} pc#${leg.pcGeneration}: ${track?.kind} id=${track?.id?.take(8)}")
                if (track is VideoStreamTrack) {
                    leg.remoteVideoTrack = track
                    if (remoteVideo.put(leg.peerId, track)) publishRemoteVideo()
                }
            }
        }

        // Trickle local ICE candidates to this specific peer
        leg.iceJob = scope.launch(callMediaDispatcher) {
            pc.onIceCandidate.collect { candidate ->
                sendFrame(
                    CallWireFrame.IceCandidate(
                        callId = callId,
                        from = localDeviceId,
                        sdpMid = candidate.sdpMid,
                        sdpMLineIndex = candidate.sdpMLineIndex,
                        candidate = candidate.candidate,
                    ),
                    leg.peerId,
                )
            }
        }

        // Monitor connection state
        leg.connJob = scope.launch(callMediaDispatcher) {
            val generation = leg.pcGeneration
            // Log only: ICE is the half of the connection that finds a network path; DTLS the other.
            launch {
                pc.onIceConnectionStateChange.collect { ice ->
                    FlashLog.i("GROUP_CALL", "Leg ${leg.peerId} pc#$generation ice=$ice")
                }
            }
            pc.onConnectionStateChange.collect { connState ->
                FlashLog.i("GROUP_CALL", "Leg ${leg.peerId} pc#$generation state changed to $connState")
                when (connState) {
                    PeerConnectionState.Connected -> {
                        leg.state = FlashCallParticipantState.CONNECTED
                        armStatsPolling()
                        sessionMutex.withLock {
                            cancelSoloWaiting()
                            if (_state.value.state != FlashCallState.ACTIVE) {
                                _state.value = _state.value.copy(
                                    state = FlashCallState.ACTIVE,
                                    connectedAt = _state.value.connectedAt ?: SystemTimeSource.nowMs(),
                                )
                            }
                            refreshUiState()
                        }
                        // G3: the encodings may not have existed when the sender was first
                        // tuned, so set this leg's on/off again now that it is connected.
                        routeVideo {
                            leg.videoSender?.let {
                                tuneVideoSender(it, active = isSending(leg.peerId), height = sendHeight(leg.peerId)?.takeIf { h -> h > 0 })
                            }
                            reconcile()
                        }
                    }
                    PeerConnectionState.Disconnected, PeerConnectionState.Failed -> {
                        leg.state = FlashCallParticipantState.DISCONNECTED
                        sessionMutex.withLock {
                            refreshUiState()
                            checkSoloState()
                        }
                    }
                    PeerConnectionState.Closed -> {
                        leg.state = FlashCallParticipantState.LEFT
                        sessionMutex.withLock {
                            refreshUiState()
                            checkSoloState()
                        }
                    }
                    else -> {}
                }
            }
        }

        return pc
    }

    private suspend fun handleInboundOffer(peerId: String, sdp: String) {
        val leg = legs.getOrPut(peerId) { GroupLeg(peerId = peerId, peerName = resolveName(peerId)) }
        leg.legMutex.withLock {
            // Pinned: PC create + setRemote/createAnswer/setLocal are native.
            onMediaThread {
            val stream = localStream ?: return@onMediaThread
            val existing = leg.peerConnection
            if (existing != null) {
                FlashLog.i(
                    "GROUP_CALL",
                    "Offer from $peerId on existing pc#${leg.pcGeneration} signaling=${existing.signalingState} " +
                        "connection=${existing.connectionState} remoteSet=${leg.remoteDescriptionSet}",
                )
            }
            val pc = existing ?: createPeerConnectionForLeg(leg, stream).also { leg.peerConnection = it }

            try {
                setRemoteDescriptionTuned(pc, SessionDescriptionType.Offer, sdp)
                leg.remoteDescriptionSet = true
                flushPendingIce(leg, pc)
                val answer = pc.createAnswer(OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = video))
                val applied = setLocalDescriptionTuned(pc, answer)
                sendFrame(
                    CallWireFrame.Answer(callId = callId, from = localDeviceId, sdp = applied.sdp),
                    peerId,
                )
                FlashLog.i("GROUP_CALL", "Answered offer from peer $peerId pc#${leg.pcGeneration} for group call $callId")
            } catch (t: Throwable) {
                FlashLog.e("GROUP_CALL", "Failed to answer offer from $peerId", t)
            }
            }
        }
    }

    private suspend fun handleInboundAnswer(peerId: String, sdp: String) {
        val leg = legs[peerId] ?: return
        leg.legMutex.withLock {
            // Pinned: setRemoteDescription is native.
            onMediaThread {
            val pc = leg.peerConnection ?: run {
                FlashLog.w("GROUP_CALL", "Answer from $peerId dropped: no connection")
                return@onMediaThread
            }
            // ERROR-076: an answer means something only to a connection waiting for one. A
            // second answer in `stable` is rejected by WebRTC anyway; skip it without the retry.
            val signaling = pc.signalingState
            if (signaling != SignalingState.HaveLocalOffer) {
                FlashLog.w("GROUP_CALL", "Stale answer from $peerId ignored: pc#${leg.pcGeneration} signaling=$signaling")
                return@onMediaThread
            }
            try {
                setRemoteDescriptionTuned(pc, SessionDescriptionType.Answer, sdp)
                leg.remoteDescriptionSet = true
                flushPendingIce(leg, pc)
                FlashLog.i("GROUP_CALL", "Applied answer from peer $peerId pc#${leg.pcGeneration} for group call $callId")
            } catch (t: Throwable) {
                FlashLog.e("GROUP_CALL", "Failed to apply answer from $peerId", t)
            }
            }
        }
    }

    private suspend fun handleInboundIce(peerId: String, frame: CallWireFrame.IceCandidate) {
        val leg = legs.getOrPut(peerId) { GroupLeg(peerId = peerId, peerName = resolveName(peerId)) }
        val candidate = IceCandidate(
            sdpMid = frame.sdpMid ?: "",
            sdpMLineIndex = frame.sdpMLineIndex,
            candidate = frame.candidate,
        )
        leg.legMutex.withLock {
            // Pinned: addIceCandidate is native.
            onMediaThread {
            val pc = leg.peerConnection
            if (pc != null && leg.remoteDescriptionSet) {
                try {
                    pc.addIceCandidate(candidate)
                } catch (t: Throwable) {
                    FlashLog.w("GROUP_CALL", "Failed to add ICE candidate on leg $peerId: ${t.message}")
                }
            } else {
                leg.pendingIce.add(candidate)
            }
            }
        }
    }

    private suspend fun flushPendingIce(leg: GroupLeg, pc: PeerConnection) {
        // Pinned: addIceCandidate is native. Callers already run pinned; the wrap keeps the
        // guarantee local so a future caller cannot escape the media thread.
        onMediaThread {
        while (leg.pendingIce.isNotEmpty()) {
            val candidate = leg.pendingIce.removeFirst()
            try {
                pc.addIceCandidate(candidate)
            } catch (t: Throwable) {
                FlashLog.w("GROUP_CALL", "Failed to flush pending ICE candidate on leg ${leg.peerId}: ${t.message}")
            }
        }
        }
    }

    /**
     * Handles peer departure cleanly.
     * Closes only that peer's WebRTC leg; all other participants remain connected without any glitch.
     */
    private suspend fun handlePeerLeft(peerId: String, reason: String) {
        FlashLog.i("GROUP_CALL", "Peer $peerId left group call $callId ($reason)")
        sessionMutex.withLock {
            val leg = legs[peerId] ?: return
            leg.state = FlashCallParticipantState.LEFT
            scope.launch {
                leg.legMutex.withLock {
                    closeLeg(leg)
                }
            }
            refreshUiState()
            checkSoloState()
        }
        routeVideo { onPeerLeft(peerId) }
    }

    /** If only 1 member remains in an active call, enter a grace window rather than abruptly failing. */
    private suspend fun checkSoloState() {
        if (_state.value.state != FlashCallState.ACTIVE) return
        val connectedCount = countConnectedLegs()
        if (connectedCount == 0 && soloWaitingJob == null) {
            FlashLog.i("GROUP_CALL", "All remote members left group call $callId; waiting 30s for peers...")
            soloWaitingJob = scope.launch {
                delay(30_000L)
                sessionMutex.withLock {
                    if (countConnectedLegs() == 0 && !isEnded) {
                        FlashLog.i("GROUP_CALL", "Grace timeout expired with no peers; ending group call $callId")
                        endSession(FlashCallEndReason.NORMAL)
                    }
                }
            }
        }
    }

    private fun cancelSoloWaiting() {
        soloWaitingJob?.cancel()
        soloWaitingJob = null
    }

    private fun countConnectedLegs(): Int =
        legs.valuesSnapshot().count { it.state == FlashCallParticipantState.CONNECTED }

    private suspend fun closeLeg(leg: GroupLeg) {
        mediaLifecycleMutex.withLock { closeLegLocked(leg) }
    }

    /** [closeLeg] with the lifecycle lock already held (endSession's teardown section). */
    private suspend fun closeLegLocked(leg: GroupLeg) {
        // Pinned: peerConnection.close() is native. Callers run pinned already; the wrap keeps
        // the guarantee local.
        onMediaThread {
        if (leg.peerConnection != null) {
            FlashLog.i("GROUP_CALL", "Leg ${leg.peerId} pc#${leg.pcGeneration} closed (state=${leg.state})")
        }
        leg.iceJob?.cancel()
        leg.trackJob?.cancel()
        leg.connJob?.cancel()
        leg.signalingGraceJob?.cancel()
        leg.pendingIce.clear()
        leg.remoteDescriptionSet = false
        try {
            leg.peerConnection?.close()
        } catch (_: Throwable) {}
        leg.peerConnection = null
        leg.audioSender = null
        leg.videoSender = null
        leg.remoteVideoTrack?.let { track ->
            if (remoteVideo.remove(leg.peerId, expected = track)) publishRemoteVideo()
        }
        leg.remoteVideoTrack = null
        }
    }

    /**
     * Runs one [GroupVideoRouter] step and carries out its effects, all under [videoMutex], so
     * two steps can never switch an encoding in the wrong order. Audio calls have no video to
     * route. Effects are frames (sent on the signaling path) and encoding switches.
     */
    private suspend fun routeVideo(step: GroupVideoRouter.() -> List<GroupVideoRouter.Effect>) {
        if (!video || isEnded) return
        videoMutex.withLock {
            val effects = videoRouter.step()
            sendingTo = legs.keysSnapshot().filter { videoRouter.isSending(it) }
                .associateWith { peer -> videoRouter.sendHeight(peer)?.takeIf { it > 0 } }
            videoFreeNow = videoRouter.freeSlots()
            videoFocusNow = videoRouter.pinnedPeer
            videoMainNow = videoRouter.pinnedPeer ?: videoRouter.followedPeer
            healthNow = health.verdict()
            videoCompactNow = videoLimits().receive <= 1
            videoStates = legs.keysSnapshot().associateWith { videoRouter.receiveState(it) }
            logRoute()
            effects.forEach { effect ->
                when (effect) {
                    is GroupVideoRouter.Effect.Send -> sendFrame(effect.frame, effect.peerId)
                    is GroupVideoRouter.Effect.Announce -> sendFrame(presenceFrame(), effect.peerId)
                    is GroupVideoRouter.Effect.Sending -> {
                        FlashLog.i(
                            "GROUP_CALL",
                            "Leg ${effect.peerId} video send=${if (effect.on) "on" else "off"} height=${effect.height ?: "full"}",
                        )
                        val sender = legs[effect.peerId]?.videoSender
                        if (sender != null) onMediaThread { tuneVideoSender(sender, active = effect.on, height = effect.height) }
                    }
                }
            }
        }
        refreshUiState()
    }

    /** This device's video limits now: tier and band (G4), then heat, CPU and "Show fewer" (G6). */
    private fun videoLimits(): GroupVideoLimits {
        val verdict = health.verdict()
        return GroupVideoLimits.of(
            performanceMode(),
            effectiveBand(),
            struggling = verdict.struggling,
            receiveCap = verdict.receiveCap,
            acceptNew = verdict.acceptNew,
        )
    }

    /**
     * This process's share of all cores since the previous sample, in percent (G6), or null on
     * the first sample or when the platform can't tell.
     */
    private fun sampleCpuPercent(nowMs: Long): Double? {
        val cpu = processCpuTimeNanos() ?: return null
        val previous = lastCpuNanos
        val elapsedMs = nowMs - lastCpuAtMs
        lastCpuNanos = cpu
        lastCpuAtMs = nowMs
        if (previous == null || elapsedMs <= 0L) return null
        val cpuMs = (cpu - previous).coerceAtLeast(0L) / 1_000_000.0
        return cpuMs * 100.0 / (elapsedMs * availableCores())
    }

    /**
     * The band that sets this device's video limits (G3/G4): its own, or, when it cannot tell
     * (a phone hosting the hotspot), the slowest band its peers report.
     */
    private fun effectiveBand(): FlashNetworkBand? =
        networkBand().takeIf { it != FlashNetworkBand.UNKNOWN } ?: FlashNetworkBand.slowest(linkBands().values)

    /** Stores a band the peer announced (G2) and logs a change. A null (old client) keeps what is known. */
    private fun GroupLeg.recordBand(announced: FlashNetworkBand?) {
        if (announced == null || announced == band) return
        band = announced
        FlashLog.i(
            "GROUP_CALL",
            "Leg $peerId band=${announced.label} local=${networkBand().label} link=${FlashNetworkBand.link(networkBand(), announced).label}",
        )
    }

    /**
     * Each participant's link band (G2): the slower end of this device's band and the one that
     * participant announced. Read by the stats sampler; G4 will size video budgets from it.
     */
    internal fun linkBands(): Map<String, FlashNetworkBand> {
        val local = networkBand()
        return legs.valuesSnapshot().associate { it.peerId to FlashNetworkBand.link(local, it.band) }
    }

    /** Publishes [remoteVideo] after a change. Media thread only, like every [remoteVideo] access. */
    private fun publishRemoteVideo() {
        _remoteVideoTracks.value = remoteVideo.snapshot()
        _remoteVideoStreamTrack.value = remoteVideo.newest
    }

    private fun armStatsPolling() {
        if (statsJob != null) return
        val intervalMs = performanceMode().transport.callStatsIntervalMs
        // Pinned: getStats() is native.
        statsJob = scope.launch(callMediaDispatcher) {
            while (!isEnded) {
                try {
                    sampleMeshStats()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    FlashLog.w("GROUP_CALL", "sampleMeshStats error: ${t.message}")
                }
                delay(intervalMs)
            }
        }
    }

    private suspend fun sampleMeshStats() {
        val diagNow = SystemTimeSource.nowMs()
        val diagDue = diagnostics.due(diagNow)
        val activeLegs = legs.valuesSnapshot().filter { it.state == FlashCallParticipantState.CONNECTED && it.peerConnection != null }
        if (activeLegs.isEmpty()) {
            if (diagDue) FlashLog.i(CallDiagnostics.TAG, diagnostics.processLine(diagNow, diagExtra(null)))
            _stats.value = null
            return
        }

        var totalBytesIn = 0L
        var totalBytesOut = 0L
        val rtts = mutableListOf<Int>()
        var totalLost = 0L
        var totalReceived = 0L

        var uiNeedsRefresh = false
        var localAudioLevel: Double? = null
        // G6: legs whose arriving video is decoded in software.
        var softwareDecodedLegs = 0
        for (leg in activeLegs) {
            val pc = leg.peerConnection ?: continue
            // Pinned: getStats() is native.
            val report = try {
                onMediaThread { pc.getStats() }
            } catch (_: Throwable) {
                null
            } ?: continue
            if (diagDue) {
                FlashLog.i(
                    CallDiagnostics.TAG,
                    diagnostics.legLine("${leg.peerId} pc#${leg.pcGeneration}", leg.state.name, report, diagNow),
                )
            }

            val all = report.stats.values
            val selectedId = all.firstOrNull { it.type == "transport" }
                ?.members?.get("selectedCandidatePairId") as? String
            val pairs = all.filter { it.type == "candidate-pair" }
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
            if (rttSec != null) {
                rtts.add((rttSec * 1000).roundToInt())
            }

            val inbound = all.filter { it.type == "inbound-rtp" }
            val outbound = all.filter { it.type == "outbound-rtp" }
            totalBytesIn += inbound.sumOf { it.members.num("bytesReceived")?.toLong() ?: 0L }
            totalBytesOut += outbound.sumOf { it.members.num("bytesSent")?.toLong() ?: 0L }
            totalLost += inbound.sumOf { it.members.num("packetsLost")?.toLong() ?: 0L }
            totalReceived += inbound.sumOf { it.members.num("packetsReceived")?.toLong() ?: 0L }

            val decoder = inbound.firstOrNull { it.members.str("kind") == "video" }?.members?.str("decoderImplementation")
            if (videoStates[leg.peerId]?.hasPicture() == true && CallHealthMonitor.isSoftwareDecoder(decoder)) {
                softwareDecodedLegs++
            }

            val audioLevel = inbound.firstOrNull { it.members.str("kind") == "audio" || it.members.num("audioLevel") != null }
                ?.members?.num("audioLevel")
                ?: all.firstOrNull { it.type == "track" && it.members.str("kind") == "audio" }
                    ?.members?.num("audioLevel")
            // This device's own microphone level (the send side's media-source), for G3's
            // talker-first rule. Best effort: a backend that does not report it reads as silent.
            all.firstOrNull { it.type == "media-source" && it.members.str("kind") == "audio" }
                ?.members?.num("audioLevel")
                ?.let { level -> localAudioLevel = maxOf(localAudioLevel ?: 0.0, level) }

            if (audioLevel != null) {
                val speaking = audioLevel > 0.01
                if (leg.isSpeaking != speaking) {
                    leg.isSpeaking = speaking
                    uiNeedsRefresh = true
                }
            }
        }

        if (uiNeedsRefresh) {
            refreshUiState()
        }
        val speakers = activeLegs.filter { it.isSpeaking }.map { it.peerId }.toSet()
        val sampledAt = SystemTimeSource.nowMs()
        val cpuPercent = sampleCpuPercent(sampledAt)
        if (diagDue) FlashLog.i(CallDiagnostics.TAG, diagnostics.processLine(diagNow, diagExtra(cpuPercent)))
        val thermal = ThermalGovernor.get().status
        val receiving = videoStates.values.count { it.hasPicture() }
        // A HIGH device (and the desktop) decodes several VP8 streams in software as a matter of
        // course; its load shows in the CPU signal instead (plan §8 G6).
        val softwareDecode = performanceMode() != FlashPerformanceMode.HIGH && receiving >= 2 && softwareDecodedLegs > 0
        routeVideo {
            val before = healthNow
            val after = health.update(sampledAt, thermal, cpuPercent, softwareDecode)
            if (after.warning != before.warning) {
                FlashLog.i(
                    "GROUP_CALL",
                    "health warning=${after.warning} thermal=$thermal cpu=${cpuPercent?.roundToInt()}% " +
                        "softwareDecode=$softwareDecode",
                )
            }
            setLocalSpeaking((localAudioLevel ?: 0.0) > 0.01)
            // G4: tick lets a split budget step back up and picks up changed limits.
            onSpeakers(speakers) + tick()
        }

        val nowMs = SystemTimeSource.nowMs()
        val elapsedMs = if (lastStatsAtMs > 0L) nowMs - lastStatsAtMs else 0L
        val inKbps = if (elapsedMs > 0L) {
            val deltaBytes = (totalBytesIn - lastBytesReceived).coerceAtLeast(0L)
            (deltaBytes * 8 / elapsedMs).toInt()
        } else null
        val outKbps = if (elapsedMs > 0L) {
            val deltaBytes = (totalBytesOut - lastBytesSent).coerceAtLeast(0L)
            (deltaBytes * 8 / elapsedMs).toInt()
        } else null

        lastStatsAtMs = nowMs
        lastBytesReceived = totalBytesIn
        lastBytesSent = totalBytesOut

        val avgRtt = if (rtts.isNotEmpty()) rtts.average().roundToInt() else null
        val lossFraction = if (totalReceived + totalLost > 0L) {
            totalLost.toDouble() / (totalReceived + totalLost).toDouble()
        } else null

        _stats.value = FlashCallStats(
            rttMs = avgRtt,
            inboundKbps = inKbps,
            outboundKbps = outKbps,
            packetLoss = lossFraction,
            networkBand = FlashNetworkBand.slowest(linkBands().values),
        )
    }

    private fun Map<String, Any>.num(key: String): Double? =
        (this[key] as? Number)?.toDouble() ?: (this[key] as? String)?.toDoubleOrNull()

    private fun Map<String, Any>.str(key: String): String? =
        (this[key] as? String) ?: this[key]?.toString()

    private fun Map<String, Any>.bool(key: String): Boolean? =
        (this[key] as? Boolean) ?: (this[key] as? String)?.toBooleanStrictOrNull()

    public fun onSignalingLost(peerId: String) {
        val leg = legs[peerId] ?: return
        leg.state = FlashCallParticipantState.DISCONNECTED
        refreshUiState()
        leg.signalingGraceJob?.cancel()
        leg.signalingGraceJob = scope.launch {
            delay(15_000L) // 15-second grace window for Wi-Fi roam
            if (leg.state == FlashCallParticipantState.DISCONNECTED) {
                handlePeerLeft(peerId, reason = "signaling timeout")
            }
        }
    }

    public fun onSignalingRestored(peerId: String) {
        val leg = legs[peerId] ?: return
        leg.signalingGraceJob?.cancel()
        leg.signalingGraceJob = null
        if (leg.state == FlashCallParticipantState.DISCONNECTED) {
            leg.state = FlashCallParticipantState.CONNECTING
            refreshUiState()
            scope.launch {
                leg.legMutex.withLock {
                    closeLeg(leg)
                }
                ensureLegConnected(peerId)
            }
            scope.launch {
                sendFrame(presenceFrame(), peerId)
            }
        }
    }

    /**
     * Group mute/camera toggles use the same optimistic-state + pinned-native shape as the 1:1
     * session: the button state flips synchronously, the track `enabled` flip hops to the media
     * thread (these are called straight from UI callbacks).
     */
    public fun toggleMute(): Boolean {
        val next = !_state.value.micMuted
        _state.value = _state.value.copy(micMuted = next)
        val tracks = localStream?.audioTracks.orEmpty()
        if (tracks.isNotEmpty()) {
            scope.launch(callMediaDispatcher) {
                tracks.forEach { runCatching { it.enabled = !next } }
            }
        }
        return next
    }

    public fun toggleCamera(): Boolean {
        val next = !_state.value.cameraOff
        _state.value = _state.value.copy(cameraOff = next)
        val tracks = localStream?.videoTracks.orEmpty()
        if (tracks.isNotEmpty()) {
            scope.launch(callMediaDispatcher) {
                tracks.forEach { runCatching { it.enabled = !next } }
            }
        }
        // G3: a camera that is off turns new requests down; turning it on tells those peers.
        scope.launch { routeVideo { setCameraOff(next) } }
        return next
    }

    /** See [FlashCalling.setVideoFocus]. */
    public fun setVideoFocus(peerId: String?) {
        scope.launch { routeVideo { setFocus(peerId) } }
    }

    /** G6 "Show fewer": cap receiving at one video until turned off. */
    public fun setShowFewerVideos(on: Boolean) {
        scope.launch {
            routeVideo {
                health.showFewer = on
                FlashLog.i("GROUP_CALL", "show fewer videos=$on")
                tick()
            }
        }
    }

    public suspend fun switchCamera() {
        onMediaThread { _localVideoStreamTrack.value?.switchCamera() }
    }

    public fun setSpeaker(on: Boolean) {
        _state.value = _state.value.copy(speakerOn = on)
    }

    private fun refreshUiState() {
        val participants = legs.valuesSnapshot().map { leg ->
            FlashCallParticipantUi(
                peerId = leg.peerId,
                name = leg.peerName,
                isSpeaking = leg.isSpeaking,
                isMuted = leg.isMuted,
                state = leg.state,
                video = videoStates[leg.peerId] ?: FlashParticipantVideo.OFF,
            )
        }
        _state.value = _state.value.copy(
            participants = participants,
            compactVideo = video && videoCompactNow,
            videoFocusPeerId = videoFocusNow,
            videoMainPeerId = videoMainNow,
            healthWarning = if (video) healthNow.warning else null,
            showingFewerVideos = video && healthNow.showingFewer,
        )
    }

    private suspend fun acquireMedia(): Boolean {
        if (isMediaAcquired) return true
        // Pinned: getUserMedia drives ADM device select + factory init. Lifecycle-locked
        // against endSession's teardown (see mediaLifecycleMutex).
        return onMediaThread {
            mediaLifecycleMutex.withLock {
        try {
            // Same explicit voice processing as 1:1 startMedia: bare audio(true) leaves
            // AEC/NS/AGC null, which the JVM backend maps to off (desktop howls).
            // ERROR-075: a bare video() let the camera open at whatever mode it listed first
            // (a webcam's 1080p or 4K) while a group call never sends more than 720p.
            val capture = groupCaptureProfile()
            captureProfile = capture
            FlashLog.i(
                "GROUP_CALL",
                "acquire media video=$video tier=${performanceMode().key}" +
                    (if (video) " camera asked ${capture.captureWidth}x${capture.captureHeight}@${capture.captureFps}" else ""),
            )
            val stream = MediaDevices.getUserMedia {
                audio {
                    echoCancellation(true)
                    noiseSuppression(true)
                    autoGainControl(true)
                }
                if (video) {
                    video {
                        width(capture.captureWidth)
                        height(capture.captureHeight)
                        frameRate(capture.captureFps.toDouble())
                    }
                }
            }
            stream.videoTracks.firstOrNull()?.settings?.let { s ->
                FlashLog.i("GROUP_CALL", "camera opened ${s.width ?: "?"}x${s.height ?: "?"}@${s.frameRate ?: "?"}")
            }
            localStream = stream
            _localVideoStreamTrack.value = stream.videoTracks.firstOrNull()
            isMediaAcquired = true
            true
        } catch (e: CameraPermissionException) {
            FlashLog.e("GROUP_CALL", "CAMERA permission not granted", e)
            false
        } catch (e: RecordAudioPermissionException) {
            FlashLog.e("GROUP_CALL", "RECORD_AUDIO permission not granted", e)
            false
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FlashLog.e("GROUP_CALL", "Failed to acquire media for group call", t)
            false
        }
            }
        }
    }

    private suspend fun endSession(reason: FlashCallEndReason) {
        if (isEnded) return
        isEnded = true
        cancelSoloWaiting()
        presenceJob?.cancel()
        presenceJob = null
        statsJob?.cancel()
        statsJob = null
        _stats.value = null

        // Pinned: leg close + stream release are native. All callers are suspend (public
        // hangUp/decline, session/leg mutex paths, timer launches), so awaiting here is safe.
        // Lifecycle-locked with the Locked leg variant: a rejoin acquire cannot interleave.
        onMediaThread {
            mediaLifecycleMutex.withLock {
        legs.valuesSnapshot().forEach { closeLegLocked(it) }
        legs.clear()
        remoteVideo.clear()
        publishRemoteVideo()

        try {
            localStream?.release()
        } catch (_: Throwable) {}
        localStream = null
            }
        }
        _localVideoStreamTrack.value = null
        _remoteVideoStreamTrack.value = null

        _state.value = _state.value.copy(
            state = FlashCallState.ENDED,
            endReason = reason,
        )
        onEnded(this)
    }

    private suspend fun setLocalDescriptionTuned(
        pc: PeerConnection,
        desc: SessionDescription,
    ): SessionDescription {
        val tunedSdp = runCatching { CallSdp.tuneLocal(desc.sdp, performanceMode()) }.getOrNull()
        val sdpWithCodecs = CallSdp.enforceVp8Only(tunedSdp ?: desc.sdp)
        val finalDesc = if (sdpWithCodecs != desc.sdp) SessionDescription(desc.type, sdpWithCodecs) else desc
        try {
            pc.setLocalDescription(finalDesc)
            FlashLog.i("GROUP_CALL", "local ${desc.type} (tuned) sdp len=${finalDesc.sdp.length}")
            return finalDesc
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FlashLog.w("GROUP_CALL", "tuned local SDP rejected, using original: ${t.message}")
        }
        val fallbackDesc = SessionDescription(desc.type, CallSdp.enforceVp8Only(desc.sdp))
        FlashLog.i("GROUP_CALL", "local ${desc.type} sdp len=${fallbackDesc.sdp.length}")
        pc.setLocalDescription(fallbackDesc)
        return fallbackDesc
    }

    private suspend fun setRemoteDescriptionTuned(
        pc: PeerConnection,
        type: SessionDescriptionType,
        sdp: String,
    ) {
        val tuned = runCatching { CallSdp.tuneRemote(sdp, performanceMode()) }.getOrNull()
        val sdpWithCodecs = CallSdp.enforceVp8Only(tuned ?: sdp)
        try {
            pc.setRemoteDescription(SessionDescription(type, sdpWithCodecs))
            FlashLog.i("GROUP_CALL", "remote $type (tuned) sdp len=${sdpWithCodecs.length}")
            return
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FlashLog.w("GROUP_CALL", "tuned remote SDP rejected, using original: ${t.message}")
        }
        val fallbackSdp = CallSdp.enforceVp8Only(sdp)
        FlashLog.i("GROUP_CALL", "remote $type sdp len=${fallbackSdp.length}")
        pc.setRemoteDescription(SessionDescription(type, fallbackSdp))
    }

    /**
     * The camera size for this call: the tier's profile, but never taller than the tallest copy
     * a group call sends (720p, G4), with the width scaled to match.
     */
    private fun groupCaptureProfile(): FlashVideoProfile {
        val tier = performanceMode().video
        if (tier.captureHeight <= GroupVideoLimits.HEIGHT_720) return tier
        return tier.copy(
            captureWidth = tier.captureWidth * GroupVideoLimits.HEIGHT_720 / tier.captureHeight,
            captureHeight = GroupVideoLimits.HEIGHT_720,
        )
    }

    /**
     * ERROR-076: whether a repeated accept/join ([cause]) should leave [leg]'s connection alone.
     * A connection still being set up (under [LEG_SETUP_GRACE_MS] old, not failed) is kept:
     * rebuilding it made a second offer while the peer was answering the first, and that late
     * answer then failed DTLS (CERTIFICATE_UNKNOWN) on the new connection. When this device is
     * the offerer and its offer is still unanswered, the same offer is sent again, in case the
     * peer lost it. An older or failed connection is rebuilt as before (the peer may have
     * restarted). Caller holds the leg mutex.
     */
    private suspend fun keepSettingUpLeg(leg: GroupLeg, cause: String): Boolean = onMediaThread {
        val pc = leg.peerConnection ?: return@onMediaThread false
        val ageMs = SystemTimeSource.nowMs() - leg.pcCreatedAtMs
        val connection = pc.connectionState
        val signaling = pc.signalingState
        val settingUp = connection != PeerConnectionState.Failed &&
            connection != PeerConnectionState.Closed &&
            connection != PeerConnectionState.Disconnected
        if (!settingUp || ageMs >= LEG_SETUP_GRACE_MS) {
            FlashLog.i(
                "GROUP_CALL",
                "Leg ${leg.peerId} pc#${leg.pcGeneration} rebuilt on $cause (connection=$connection signaling=$signaling age=${ageMs}ms)",
            )
            return@onMediaThread false
        }
        FlashLog.i(
            "GROUP_CALL",
            "Leg ${leg.peerId} pc#${leg.pcGeneration} kept on $cause (connection=$connection signaling=$signaling age=${ageMs}ms)",
        )
        val offer = pc.localDescription
        if (localDeviceId > leg.peerId && signaling == SignalingState.HaveLocalOffer && offer != null) {
            sendFrame(CallWireFrame.Offer(callId = callId, from = localDeviceId, sdp = offer.sdp), leg.peerId)
            FlashLog.i("GROUP_CALL", "Re-sent pending offer to ${leg.peerId} pc#${leg.pcGeneration}")
        }
        true
    }

    /** One line per inbound call frame (device-test logging); ICE candidates are too many to log. */
    private fun logInbound(frame: CallWireFrame, from: String, via: String) {
        if (frame is CallWireFrame.IceCandidate) return
        val leg = legs[from]
        val text = when (frame) {
            is CallWireFrame.Offer -> "Offer sdp=${frame.sdp.length}"
            is CallWireFrame.Answer -> "Answer sdp=${frame.sdp.length}"
            else -> frame.toString().replace("callId=$callId, ", "")
        }
        val route = if (via != from) " via=$via" else ""
        val legText = leg?.let { "${it.state} pc#${it.pcGeneration}" } ?: "none"
        FlashLog.i("GROUP_CALL", "rx from=$from$route leg=$legText $text")
    }

    /** Logs the video routing state when it changed (G3–G6 device checks). Under [videoMutex]. */
    private fun logRoute() {
        val line = "video route limits=${videoLimits()} sending=$sendingTo free=$videoFreeNow " +
            "receive=${videoStates.filterValues { it != FlashParticipantVideo.OFF }} focus=$videoFocusNow " +
            "main=$videoMainNow compact=$videoCompactNow health=${healthNow.warning}"
        if (line == lastRouteLine) return
        lastRouteLine = line
        FlashLog.i("GROUP_CALL", line)
    }

    /** The session's fields on the `CALL_DIAG` process line. */
    private fun diagExtra(cpuPercent: Double?): String {
        val legStates = legs.valuesSnapshot().joinToString(",") { "${it.peerId.take(8)}:${it.state}#${it.pcGeneration}" }
        return "call=${callId.take(8)} video=$video tier=${performanceMode().key} band=${effectiveBand()?.label} " +
            "thermal=${ThermalGovernor.get().status} g6cpu=${cpuPercent?.roundToInt()}% " +
            "camera=${captureProfile?.label} legs=[$legStates]"
    }

    private fun tuneAudioSender(sender: RtpSender) {
        val maxBitrateBps = performanceMode().voice.maxBitrateBps
        try {
            val applied = sender.applyAudioTuning(AudioSendTuning(maxBitrateBps = maxBitrateBps))
            if (!applied) {
                FlashLog.w("GROUP_CALL", "audio sender has no encodings to tune")
                return
            }
            FlashLog.i(
                "GROUP_CALL",
                "audio sender tuned applied=$applied max=${maxBitrateBps / 1000}kbps " +
                    "networkPriority=HIGH bitratePriority=$AUDIO_BITRATE_PRIORITY",
            )
        } catch (t: Throwable) {
            FlashLog.w("GROUP_CALL", "audio sender tuning failed: ${t.message}")
        }
    }

    /**
     * Tunes the video encoding of one leg. [height] is the copy's height from the G4 budget: the
     * encoder scales the camera down to it and caps the bitrate for that height. Null keeps the
     * full camera profile (an old client, or a leg not yet decided).
     */
    private fun tuneVideoSender(sender: RtpSender, active: Boolean, height: Int? = null) {
        val profile = captureProfile ?: groupCaptureProfile()
        val sent = height?.coerceIn(1, profile.captureHeight) ?: profile.captureHeight
        val scaleDown = profile.captureHeight.toDouble() / sent
        val maxKbps = if (height == null) {
            profile.maxBitrateKbps
        } else {
            minOf(profile.maxBitrateKbps, GroupVideoLimits.maxBitrateKbps(sent))
        }
        val minKbps = minOf(profile.minBitrateKbps, maxKbps)
        try {
            val applied = sender.applyVideoTuning(
                VideoSendTuning(
                    maxBitrateBps = maxKbps * BPS_PER_KBPS,
                    minBitrateBps = minKbps * BPS_PER_KBPS,
                    maxFramerate = profile.captureFps.toDouble(),
                    scaleResolutionDownBy = scaleDown,
                    active = active,
                    demoteForVoice = true,
                    maintainFramerate = true,
                ),
            )
            if (!applied) {
                FlashLog.w("GROUP_CALL", "video sender has no encodings to tune")
                return
            }
            FlashLog.i(
                "GROUP_CALL",
                "video sender tuned applied=$applied active=$active height=${sent}p max=${maxKbps}kbps " +
                    "fps=${profile.captureFps} degradation=MAINTAIN_FRAMERATE",
            )
        } catch (t: Throwable) {
            FlashLog.w("GROUP_CALL", "video sender tuning failed: ${t.message}")
        }
    }

    private companion object {
        // AUDIO_BITRATE_PRIORITY / VIDEO_BITRATE_PRIORITY moved to the sender-tuning seam
        // (RtpSenderTuning.kt) with the rest of the native-knob plumbing (S2e).
        const val BPS_PER_KBPS = 1000

        /** How long a connection being set up is kept through repeated accepts/joins (ERROR-076). */
        const val LEG_SETUP_GRACE_MS = 10_000L

        /** Participants whose video can be asked for (G3): in the call, or briefly unreachable. */
        val VIDEO_PRESENT = setOf(
            FlashCallParticipantState.CONNECTING,
            FlashCallParticipantState.CONNECTED,
            FlashCallParticipantState.DISCONNECTED,
        )
    }
}

/** Whether this participant's video is arriving (granted, or an older client that always sends). */
private fun FlashParticipantVideo.hasPicture(): Boolean =
    this == FlashParticipantVideo.RECEIVING || this == FlashParticipantVideo.UNMANAGED
