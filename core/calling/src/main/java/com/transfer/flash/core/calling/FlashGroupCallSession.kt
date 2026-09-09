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
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallParticipantUi
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallStats
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import java.util.concurrent.ConcurrentHashMap
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

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    override val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    override val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val legs = ConcurrentHashMap<String, GroupLeg>()
    private val sessionMutex = Mutex()

    private var localStream: MediaStream? = null
    private var isMediaAcquired = false
    private var isEnded = false
    private var soloWaitingJob: Job? = null
    private var statsJob: Job? = null
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
    )

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

        // Broadcast GroupAccept / GroupJoin to known participants
        val currentPeers = legs.keys.toList()
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

    /** Declines an incoming ringing group call. */
    public suspend fun decline() {
        legs.keys.forEach { peerId ->
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
            legs.keys.forEach { peerId ->
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

        when (frame) {
            is CallWireFrame.GroupInvite -> {
                // Peer invited us to this group call (inbound ringing)
                sessionMutex.withLock {
                    legs.getOrPut(peerId) {
                        GroupLeg(peerId = peerId, peerName = resolveName(peerId, frame.callerName), state = FlashCallParticipantState.INVITED)
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
                val peerName = resolveName(peerId, if (frame is CallWireFrame.GroupJoin) frame.participantName else null)
                sessionMutex.withLock {
                    val leg = legs.getOrPut(peerId) {
                        GroupLeg(peerId = peerId, peerName = peerName)
                    }
                    leg.peerName = peerName
                    leg.state = FlashCallParticipantState.CONNECTING
                    refreshUiState()
                }

                if (isMediaAcquired) {
                    scope.launch {
                        ensureLegConnected(peerId)
                    }
                    // Mesh propagation: announce this peer to other known legs so non-initiators connect with each other
                    val otherPeers = legs.keys.filter { it != localDeviceId && it != peerId }
                    otherPeers.forEach { otherPeerId ->
                        scope.launch {
                            sendFrame(
                                CallWireFrame.GroupJoin(
                                    callId = callId,
                                    from = peerId,
                                    groupId = groupId,
                                    participantName = peerName,
                                ),
                                otherPeerId,
                            )
                        }
                    }
                }
            }

            is CallWireFrame.GroupDecline -> {
                handlePeerLeft(peerId, reason = "declined")
            }

            is CallWireFrame.GroupHangup -> {
                handlePeerLeft(peerId, reason = "left")
            }

            is CallWireFrame.Offer -> {
                handleInboundOffer(peerId, frame.sdp)
            }

            is CallWireFrame.Answer -> {
                handleInboundAnswer(peerId, frame.sdp)
            }

            is CallWireFrame.IceCandidate -> {
                handleInboundIce(peerId, frame)
            }

            else -> {
                // Ignore 1:1 legacy frames
            }
        }
    }

    /** Ensures a WebRTC leg is established with [peerId]. */
    private suspend fun ensureLegConnected(peerId: String) {
        val leg = legs[peerId] ?: return
        leg.legMutex.withLock {
            if (leg.peerConnection != null) return

            val stream = localStream ?: return
            val pc = createPeerConnectionForLeg(leg, stream)
            leg.peerConnection = pc

            // Glare prevention tie-breaker:
            // The peer with the higher lexicographical deviceId creates the offer.
            if (localDeviceId > peerId) {
                try {
                    val offer = pc.createOffer(OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = video))
                    pc.setLocalDescription(offer)
                    sendFrame(
                        CallWireFrame.Offer(callId = callId, from = localDeviceId, sdp = offer.sdp),
                        peerId,
                    )
                    FlashLog.i("GROUP_CALL", "Sent offer to peer $peerId for group call $callId")
                } catch (t: Throwable) {
                    FlashLog.e("GROUP_CALL", "Failed to create offer for leg $peerId", t)
                }
            }
        }
    }

    private fun createPeerConnectionForLeg(leg: GroupLeg, stream: MediaStream): PeerConnection {
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
            pc.addTrack(audioTrack, stream)
        }
        if (video) {
            val videoTrack = stream.videoTracks.firstOrNull()
            if (videoTrack != null) {
                pc.addTrack(videoTrack, stream)
            }
        }

        // Listen for remote tracks
        leg.trackJob = scope.launch {
            pc.onTrack.collect { trackEvent ->
                val track = trackEvent.track
                FlashLog.i("GROUP_CALL", "Received track on leg ${leg.peerId}: ${track?.kind}")
                if (track is VideoTrack) {
                    _remoteVideoTrack.value = track
                }
            }
        }

        // Trickle local ICE candidates to this specific peer
        leg.iceJob = scope.launch {
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
        leg.connJob = scope.launch {
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
                                    connectedAt = _state.value.connectedAt ?: System.currentTimeMillis(),
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
            val stream = localStream ?: return
            val pc = leg.peerConnection ?: createPeerConnectionForLeg(leg, stream).also { leg.peerConnection = it }

            try {
                pc.setRemoteDescription(SessionDescription(SessionDescriptionType.Offer, sdp))
                leg.remoteDescriptionSet = true
                flushPendingIce(leg, pc)
                val answer = pc.createAnswer(OfferAnswerOptions(offerToReceiveAudio = true, offerToReceiveVideo = video))
                pc.setLocalDescription(answer)
                sendFrame(
                    CallWireFrame.Answer(callId = callId, from = localDeviceId, sdp = answer.sdp),
                    peerId,
                )
                FlashLog.i("GROUP_CALL", "Answered offer from peer $peerId for group call $callId")
            } catch (t: Throwable) {
                FlashLog.e("GROUP_CALL", "Failed to answer offer from $peerId", t)
            }
        }
    }

    private suspend fun handleInboundAnswer(peerId: String, sdp: String) {
        val leg = legs[peerId] ?: return
        leg.legMutex.withLock {
            val pc = leg.peerConnection ?: return
            try {
                pc.setRemoteDescription(SessionDescription(SessionDescriptionType.Answer, sdp))
                leg.remoteDescriptionSet = true
                flushPendingIce(leg, pc)
                FlashLog.i("GROUP_CALL", "Applied answer from peer $peerId for group call $callId")
            } catch (t: Throwable) {
                FlashLog.e("GROUP_CALL", "Failed to apply answer from $peerId", t)
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

    private suspend fun flushPendingIce(leg: GroupLeg, pc: PeerConnection) {
        while (leg.pendingIce.isNotEmpty()) {
            val candidate = leg.pendingIce.removeFirst()
            try {
                pc.addIceCandidate(candidate)
            } catch (t: Throwable) {
                FlashLog.w("GROUP_CALL", "Failed to flush pending ICE candidate on leg ${leg.peerId}: ${t.message}")
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
    private fun checkSoloState() {
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
        legs.values.count { it.state == FlashCallParticipantState.CONNECTED }

    private fun closeLeg(leg: GroupLeg) {
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
    }

    private fun armStatsPolling() {
        if (statsJob != null) return
        val intervalMs = performanceMode().transport.callStatsIntervalMs
        statsJob = scope.launch {
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
        val activeLegs = legs.values.filter { it.state == FlashCallParticipantState.CONNECTED && it.peerConnection != null }
        if (activeLegs.isEmpty()) {
            _stats.value = null
            return
        }

        var totalBytesIn = 0L
        var totalBytesOut = 0L
        val rtts = mutableListOf<Int>()
        var totalLost = 0L
        var totalReceived = 0L

        for (leg in activeLegs) {
            val pc = leg.peerConnection ?: continue
            val report = try {
                pc.getStats()
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
        }

        val nowMs = System.currentTimeMillis()
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
                ensureLegConnected(peerId)
            }
        }
    }

    public fun toggleMute(): Boolean {
        val next = !_state.value.micMuted
        localStream?.audioTracks?.forEach { it.enabled = !next }
        _state.value = _state.value.copy(micMuted = next)
        return next
    }

    public fun toggleCamera(): Boolean {
        val next = !_state.value.cameraOff
        localStream?.videoTracks?.forEach { it.enabled = !next }
        _state.value = _state.value.copy(cameraOff = next)
        return next
    }

    public suspend fun switchCamera() {
        _localVideoTrack.value?.switchCamera()
    }

    public fun setSpeaker(on: Boolean) {
        _state.value = _state.value.copy(speakerOn = on)
    }

    private fun refreshUiState() {
        val participants = legs.values.map { leg ->
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
        return try {
            val stream = MediaDevices.getUserMedia(audio = true, video = video)
            localStream = stream
            _localVideoTrack.value = stream.videoTracks.firstOrNull()
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

    private fun endSession(reason: FlashCallEndReason) {
        if (isEnded) return
        isEnded = true
        cancelSoloWaiting()
        statsJob?.cancel()
        statsJob = null
        _stats.value = null

        legs.values.forEach { closeLeg(it) }
        legs.clear()

        try {
            localStream?.release()
        } catch (_: Throwable) {}
        localStream = null
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null

        _state.value = _state.value.copy(
            state = FlashCallState.ENDED,
            endReason = reason,
        )
        onEnded(this)
    }
}
