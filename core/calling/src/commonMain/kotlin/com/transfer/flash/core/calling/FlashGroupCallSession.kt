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
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallParticipantUi
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallStats
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.concurrent.SyncMap
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.time.SystemTimeSource
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

    private val legs = SyncMap<String, GroupLeg>()
    private val sessionMutex = Mutex()

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
    )

    internal fun getLegStateForTesting(peerId: String): FlashCallParticipantState? = legs[peerId]?.state

    internal fun setLegStateForTesting(peerId: String, state: FlashCallParticipantState) {
        legs[peerId]?.state = state
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

        // Broadcast GroupAccept / GroupJoin to known participants
        val currentPeers = legs.keysSnapshot()
        currentPeers.forEach { peerId ->
            sendFrame(
                CallWireFrame.GroupAccept(callId = callId, from = localDeviceId, groupId = groupId),
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

        // Announce join to all known members
        val currentPeers = legs.keysSnapshot()
        currentPeers.forEach { peerId ->
            sendFrame(
                CallWireFrame.GroupJoin(
                    callId = callId,
                    from = localDeviceId,
                    groupId = groupId,
                    participantName = localName,
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
                    val count = countConnectedLegs() + 1
                    val frame = CallWireFrame.GroupPresence(
                        callId = callId,
                        from = localDeviceId,
                        groupId = groupId,
                        callerName = groupName,
                        video = video,
                        participantCount = count,
                    )
                    legs.keysSnapshot().forEach { peerId ->
                        sendFrame(frame, peerId)
                    }
                }
                delay(4_000L)
            }
        }
    }

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
            endSession(FlashCallEndReason.NORMAL)
        }
    }

    /** Handles inbound call frames addressed to this group call. */
    public suspend fun onInboundFrame(frame: CallWireFrame, peerId: String) {
        if (isEnded) return

        val effectivePeerId = if (frame.from.isNotBlank()) frame.from else peerId
        if (effectivePeerId == localDeviceId) return

        when (frame) {
            is CallWireFrame.GroupInvite -> {
                // Peer invited us to this group call (inbound ringing)
                sessionMutex.withLock {
                    legs.getOrPut(effectivePeerId) {
                        GroupLeg(peerId = effectivePeerId, peerName = resolveName(effectivePeerId, frame.callerName), state = FlashCallParticipantState.INVITED)
                    }
                    frame.members.filter { it != localDeviceId }.forEach { memberId ->
                        legs.getOrPut(memberId) {
                            GroupLeg(peerId = memberId, peerName = resolveName(memberId), state = FlashCallParticipantState.INVITED)
                        }
                    }
                    refreshUiState()
                }
            }

            is CallWireFrame.GroupAccept, is CallWireFrame.GroupJoin -> {
                val peerName = resolveName(effectivePeerId, if (frame is CallWireFrame.GroupJoin) frame.participantName else null)
                sessionMutex.withLock {
                    val leg = legs.getOrPut(effectivePeerId) {
                        GroupLeg(peerId = effectivePeerId, peerName = peerName)
                    }
                    leg.peerName = peerName
                    if (leg.state != FlashCallParticipantState.CONNECTED) {
                        leg.state = FlashCallParticipantState.CONNECTING
                    }
                    refreshUiState()
                }

                if (isMediaAcquired) {
                    scope.launch {
                        val existingLeg = legs[effectivePeerId]
                        if (existingLeg != null && existingLeg.state != FlashCallParticipantState.CONNECTED) {
                            existingLeg.legMutex.withLock {
                                closeLeg(existingLeg)
                            }
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

            is CallWireFrame.GroupDecline -> {
                handlePeerLeft(effectivePeerId, reason = "declined")
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
                    FlashLog.i("GROUP_CALL", "Sent offer to peer $peerId for group call $callId")
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
                tuneVideoSender(videoSender)
            }
        }

        // Listen for remote tracks
        leg.trackJob = scope.launch(callMediaDispatcher) {
            pc.onTrack.collect { trackEvent ->
                val track = trackEvent.track
                FlashLog.i("GROUP_CALL", "Received track on leg ${leg.peerId}: ${track?.kind}")
                if (track is VideoStreamTrack) {
                    _remoteVideoStreamTrack.value = track
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
            pc.onConnectionStateChange.collect { connState ->
                FlashLog.i("GROUP_CALL", "Leg ${leg.peerId} state changed to $connState")
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
            val pc = leg.peerConnection ?: createPeerConnectionForLeg(leg, stream).also { leg.peerConnection = it }

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
                FlashLog.i("GROUP_CALL", "Answered offer from peer $peerId for group call $callId")
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
            val pc = leg.peerConnection ?: return@onMediaThread
            try {
                setRemoteDescriptionTuned(pc, SessionDescriptionType.Answer, sdp)
                leg.remoteDescriptionSet = true
                flushPendingIce(leg, pc)
                FlashLog.i("GROUP_CALL", "Applied answer from peer $peerId for group call $callId")
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
        }
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
        val activeLegs = legs.valuesSnapshot().filter { it.state == FlashCallParticipantState.CONNECTED && it.peerConnection != null }
        if (activeLegs.isEmpty()) {
            _stats.value = null
            return
        }

        var totalBytesIn = 0L
        var totalBytesOut = 0L
        val rtts = mutableListOf<Int>()
        var totalLost = 0L
        var totalReceived = 0L

        var uiNeedsRefresh = false
        for (leg in activeLegs) {
            val pc = leg.peerConnection ?: continue
            // Pinned: getStats() is native.
            val report = try {
                onMediaThread { pc.getStats() }
            } catch (_: Throwable) {
                null
            } ?: continue

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

            val audioLevel = inbound.firstOrNull { it.members.str("kind") == "audio" || it.members.num("audioLevel") != null }
                ?.members?.num("audioLevel")
                ?: all.firstOrNull { it.type == "track" && it.members.str("kind") == "audio" }
                    ?.members?.num("audioLevel")
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
                sendFrame(
                    CallWireFrame.GroupPresence(
                        callId = callId,
                        from = localDeviceId,
                        groupId = groupId,
                        callerName = groupName,
                        video = video,
                        participantCount = countConnectedLegs() + 1,
                    ),
                    peerId,
                )
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
        return next
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
            )
        }
        _state.value = _state.value.copy(participants = participants)
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
            val stream = MediaDevices.getUserMedia {
                audio {
                    echoCancellation(true)
                    noiseSuppression(true)
                    autoGainControl(true)
                }
                if (video) video()
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

    private fun tuneVideoSender(sender: RtpSender) {
        val profile = performanceMode().video
        try {
            val applied = sender.applyVideoTuning(
                VideoSendTuning(
                    maxBitrateBps = profile.maxBitrateKbps * BPS_PER_KBPS,
                    minBitrateBps = profile.minBitrateKbps * BPS_PER_KBPS,
                    maxFramerate = profile.captureFps.toDouble(),
                    scaleResolutionDownBy = 1.0,
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
                "video sender tuned applied=$applied max=${profile.maxBitrateKbps}kbps " +
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
    }
}
