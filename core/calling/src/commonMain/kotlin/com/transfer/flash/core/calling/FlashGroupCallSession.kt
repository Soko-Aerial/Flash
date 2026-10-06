@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.calling

import com.shepeliev.webrtckmp.AudioStreamTrack
import com.shepeliev.webrtckmp.BundlePolicy
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.onEnded
import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.MediaStreamTrackKind
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnection
import com.shepeliev.webrtckmp.PeerConnectionState
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
import com.transfer.flash.core.calling.model.FlashCallNotice
import com.transfer.flash.core.calling.model.FlashCameraProblem
import com.transfer.flash.core.calling.model.FlashCallParticipantState
import com.transfer.flash.core.calling.model.FlashCallParticipantUi
import com.transfer.flash.core.calling.model.FlashCallReactionKind
import com.transfer.flash.core.calling.model.FlashCallState
import com.transfer.flash.core.calling.model.FlashCallStats
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.FlashGroupCallLimits
import com.transfer.flash.core.calling.model.FlashParticipantVideo
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.concurrent.SyncMap
import com.transfer.flash.core.common.concurrent.SyncSet
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    /**
     * Whether a device is an active member of this call's group (ERROR-103). A frame of a live call names devices
     * (an invite's member list, a relayed join); none of them gets a leg or a tile unless the roster says it is a
     * member, so a stale or forged list can no longer put a stranger's id on the participant list.
     */
    private val isGroupMember: suspend (peerId: String) -> Boolean = { true },
    /** This device's network band (G2), read for every announcement; cheap and non-blocking. */
    private val networkBand: () -> FlashNetworkBand = { FlashNetworkBand.UNKNOWN },
    /**
     * The user's "Send smaller video in groups" setting (ADR-053, default off), read on every
     * stats tick so turning it on mid-call takes effect within a second or two.
     */
    private val smallerVideoForMany: () -> Boolean = { false },
    /**
     * The user's "Prioritise voice quality" setting (D8, default on), read on every stats tick: when on, a leg whose link
     * is bad gives up video before audio suffers ([CallQualityGovernor]).
     */
    private val prioritiseVoice: () -> Boolean = { true },
    /** Wall clock for the leg-liveness bookkeeping; injectable so tests can run on virtual time. */
    private val nowMs: () -> Long = { SystemTimeSource.nowMs() },
) : FlashCallMedia {

    private fun resolveName(peerId: String, fallback: String? = null): String {
        val resolved = peerNameResolver(peerId)?.ifBlank { null }
            ?: fallback?.takeIf { it.isNotBlank() && it != peerId }
        return resolved ?: if (peerId.length > 8) "Member (${peerId.take(4)})" else peerId
    }

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
     * ERROR-088: the members the call was offered to (the invite's member list), whether or not a leg exists for them. The
     * presence tick tells every one of them that the call is running, so a member the caller could not reach still sees the
     * ongoing call, from any participant that can, and can join. Announce-only: a connection is built for a leg, never for
     * an entry here.
     */
    @Volatile
    private var announceMembers: List<String> = emptyList()

    /** Until when the presence tick re-offers an invite that has not been delivered (the ring window of the invitee). */
    @Volatile
    private var inviteRetryUntilMs: Long = 0L

    /** The peers a presence announcement is in flight to, so a slow dial never stacks announcements. */
    private val announcing = SyncSet<String>()

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

    /** What the other participants' controls say (ADR-067): mic, camera, hand, reactions. */
    private val statusBook = CallStatusBook()

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

    /**
     * Claimed exactly once by [endSession] (an atomic compare-and-set: the end can be asked for
     * from the UI, the timers and the leg collectors on different threads), after which
     * [isEnded] reads true everywhere.
     */
    private val endClaim = MutableStateFlow(false)

    @Volatile
    private var isEnded = false
    public val isSessionEnded: Boolean get() = isEnded
    public fun countConnectedParticipants(): Int = countConnectedLegs()
    private var soloWaitingJob: Job? = null

    /** Members the caller could not call, by device id, with the reason shown on their tile (see [startOutgoing]). */
    @Volatile
    private var unavailableNotes: Map<String, String> = emptyMap()
    private var dialTimeoutJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var connectDeadlineJob: Job? = null
    private var statsJob: Job? = null
    private var presenceJob: Job? = null

    /** ERROR-105: reports the local camera's track ending; replaced on a camera restart, cancelled with the media. */
    private var cameraWatchJob: Job? = null
    private var lastStatsAtMs = 0L
    private var lastBytesReceived = 0L
    private var lastBytesSent = 0L

    /** Representation of a single peer leg in the mesh. */
    private inner class GroupLeg(
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
        /**
         * When this peer itself last sent us a frame of this call (ERROR-086 (d)). A leg built for a
         * member who is not in the call never hears anything back; that silence is what [pruneUnansweredLegs] acts on.
         */
        var heardAtMs: Long = 0L,
        /** Rebuilds after a failed connection since it last connected; bounded by [MAX_LEG_RECOVERIES]. */
        var recoveryAttempts: Int = 0,
        /** When the leg last entered CONNECTING (0 = never); a rejoin is only waited for while it is young. */
        var connectingSinceMs: Long = 0L,
        /** This device invited the peer to a call it started (ERROR-088); false for a leg it did not invite. */
        var inviteExpected: Boolean = false,
        /** The invite went out on a live session. Until it has, the presence tick offers it again while the call is set up. */
        var inviteDelivered: Boolean = false,
        /** Times this device re-sent its join to a peer that never offered (ERROR-096); bounded by [MAX_OFFER_NUDGES]. */
        var offerNudges: Int = 0,
        /** When the last such re-send went out. */
        var lastNudgeAtMs: Long = 0L,
        /** D8 for groups: decides how much of this leg's video is given up to keep voice intelligible. One per leg, one link each. */
        val governor: CallQualityGovernor = CallQualityGovernor(
            recoveryCooldownMs = VOICE_PRIORITY_RECOVERY_COOLDOWN_MS,
            nowMs = nowMs,
        ),
        /** The rung [governor] last chose; [tuneVideoSender] applies it, including to a rebuilt connection's new sender. */
        var concession: VideoConcession = VideoConcession.FULL,
        /** Inbound packet counters at the previous stats sample, for the per-interval loss the governor needs. */
        var lastLost: Long = 0L,
        var lastReceived: Long = 0L,
        /** The signaling session to this peer is down; [onSignalingRestored] owns that recovery, not the connection watchdog. */
        var signalingLost: Boolean = false,
    )

    internal fun getLegStateForTesting(peerId: String): FlashCallParticipantState? = legs[peerId]?.state

    internal fun setLegStateForTesting(peerId: String, state: FlashCallParticipantState) {
        legs[peerId]?.state = state
        refreshUiState()
    }

    /** Test hook: puts the call itself in [state] without media (the status rules run on a JVM unit test). */
    internal fun setCallStateForTesting(state: FlashCallState) {
        _state.update { it.copy(state = state) }
    }

    /** Test hook: add a leg in [state] and treat this device as in the call (media acquired). */
    internal fun addJoinedLegForTesting(peerId: String, state: FlashCallParticipantState) {
        legs[peerId] = GroupLeg(peerId = peerId, peerName = peerId, state = state)
        isMediaAcquired = true
        refreshUiState()
    }

    /** Test hook: one quality sample for [peerId]'s leg goes to its voice-priority governor and the result is applied. */
    internal suspend fun applyVoicePriorityForTesting(peerId: String, sample: CallQualitySample) {
        val leg = legs[peerId] ?: return
        applyVoicePriority(listOf(leg to sample))
    }

    internal fun concessionForTesting(peerId: String): VideoConcession? = legs[peerId]?.concession

    /** Test hook: the call is connected (what the first leg reaching CONNECTED does). */
    internal fun markActiveForTesting() {
        updateUi { it.copy(state = FlashCallState.ACTIVE) }
    }

    /** Test hook: the local media is already open (what a successful [acquireMedia] leaves), so [startOutgoing] runs without a microphone. */
    internal fun markMediaAcquiredForTesting() {
        isMediaAcquired = true
    }

    /** Test hook: this device accepted or joined and is now connecting (what [accept] does before it acquires media). */
    internal fun markConnectingForTesting() {
        updateUi { it.copy(state = FlashCallState.CONNECTING) }
    }

    /**
     * Test hook: a leg whose connection was built at [pcCreatedAtMs] and whose peer last sent a
     * frame at [heardAtMs] (0 = never). No native connection exists; the bookkeeping is what is tested.
     */
    internal fun addSettingUpLegForTesting(peerId: String, pcCreatedAtMs: Long, heardAtMs: Long = 0L) {
        legs[peerId] = GroupLeg(
            peerId = peerId,
            peerName = peerId,
            state = FlashCallParticipantState.CONNECTING,
            pcGeneration = 1,
            pcCreatedAtMs = pcCreatedAtMs,
            heardAtMs = heardAtMs,
            connectingSinceMs = pcCreatedAtMs,
        )
        isMediaAcquired = true
        refreshUiState()
    }

    /** Sets up an incoming ringing group call leg from the inviting caller. */
    public fun startIncomingRinging(peerId: String, callerName: String, members: List<String> = emptyList()) {
        // ERROR-088: remembered for the presence tick only (see [announceMembers]); no leg, so accepting builds no
        // connection to a member who is not in the call.
        announceMembers = members.filter { it != localDeviceId && it != peerId }.distinct()
        val resolvedName = resolveName(peerId, callerName)
        legs[peerId] = GroupLeg(
            peerId = peerId,
            peerName = resolvedName,
            state = FlashCallParticipantState.INVITED,
        )
        refreshUiState()
        // ERROR-086 (c): a caller that crashed or lost its link never sends the hangup, and the
        // invitee used to ring until someone touched the screen.
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (!isEnded && _state.value.state == FlashCallState.RINGING) {
                FlashLog.i("GROUP_CALL", "Incoming group call $callId was not answered in ${RING_TIMEOUT_MS / 1000}s; stopping the ring")
                endSession(FlashCallEndReason.NO_ANSWER)
            }
        }
    }

    /**
     * Starts an outgoing group call, sending invites to all initial members.
     *
     * [unavailable] names the members of the group this device could not build a leg for, with the reason (ERROR-095:
     * not paired, so nothing lets it trust them). They are not legs and never get a frame; they only show on the
     * caller's participant list with the reason, so a missing member is visible instead of silently left out.
     */
    public suspend fun startOutgoing(
        initialMemberIds: List<String>,
        unavailable: Map<String, String> = emptyMap(),
    ): Boolean {
        val invitees = initialMemberIds.filter { it != localDeviceId }.distinct()
        sessionMutex.withLock {
            if (isEnded) return false
            unavailableNotes = unavailable.filterKeys { it != localDeviceId && it !in invitees }
            announceMembers = invitees
            inviteRetryUntilMs = nowMs() + RING_TIMEOUT_MS
            invitees.forEach { memberId ->
                legs[memberId] = GroupLeg(
                    peerId = memberId,
                    peerName = resolveName(memberId),
                    state = FlashCallParticipantState.INVITED,
                    inviteExpected = true,
                )
            }
            refreshUiState()
        }

        val mediaFailure = acquireMedia()
        if (mediaFailure != null) {
            endSession(mediaFailure)
            return false
        }

        routeVideo { startReceiving() }

        // Fan out GroupInvite to all initial members, in parallel: a member that is not connected is dialed first, and
        // one slow dial must not hold up the rest. A member that could not be reached is offered the invite again by
        // the presence tick while the call is being set up.
        coroutineScope {
            invitees.forEach { memberId -> launch { deliverInvite(memberId) } }
        }

        armDialTimeout()
        armPresenceAnnouncement()
        return true
    }

    /**
     * Hands this call's invite to [memberId] (ERROR-088). True once it went out on a live session; false when the
     * member could not be reached (no session and none could be dialed, or the session does not present the key the
     * member's certificate names), and the presence tick offers it again until the invitee's ring window is over.
     */
    private suspend fun deliverInvite(memberId: String): Boolean {
        // Never invite to a call that is over: the invite would ring a phone for a call nobody is in.
        if (isEnded) return false
        val sent = sendFrame(
            CallWireFrame.GroupInvite(
                callId = callId,
                from = localDeviceId,
                groupId = groupId,
                callerName = localName,
                video = video,
                members = announceMembers,
                band = networkBand(),
                videoRequests = true,
            ),
            memberId,
        )
        val leg = legs[memberId]
        if (sent) {
            if (leg != null && !leg.inviteDelivered) {
                leg.inviteDelivered = true
                refreshUiState()
            }
        } else {
            FlashLog.i("GROUP_CALL", "Invite for call $callId to $memberId not delivered (no usable session yet); will offer it again while the call is set up")
        }
        return sent
    }

    /**
     * If no one joins within [DIAL_TIMEOUT_MS], end with NO_ANSWER and tell the invitees, who would
     * otherwise keep ringing (ERROR-086 (c)).
     */
    internal fun armDialTimeout() {
        dialTimeoutJob?.cancel()
        dialTimeoutJob = scope.launch {
            delay(DIAL_TIMEOUT_MS)
            val giveUp = sessionMutex.withLock {
                !isEnded && _state.value.state == FlashCallState.DIALING && countConnectedLegs() == 0
            }
            if (giveUp) {
                FlashLog.i("GROUP_CALL", "No members answered outgoing group call $callId in ${DIAL_TIMEOUT_MS / 1000}s")
                endSession(FlashCallEndReason.NO_ANSWER, notifyPeers = true)
            }
        }
    }

    /**
     * Accepts an incoming ringing group call. [audioOnly] joins a video call without opening the camera (the host
     * passes it when the camera permission was denied).
     */
    public suspend fun accept(audioOnly: Boolean = false): Boolean {
        sessionMutex.withLock {
            if (isEnded || _state.value.state != FlashCallState.RINGING) return false
            updateUi { it.copy(state = FlashCallState.CONNECTING) }
        }
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null

        val mediaFailure = acquireMedia(audioOnly)
        if (mediaFailure != null) {
            endSession(mediaFailure)
            return false
        }

        armPresenceAnnouncement()
        armConnectDeadline()
        routeVideo { startReceiving() }

        // Broadcast GroupAccept / GroupJoin to known participants (in parallel: a peer that is not connected is dialed first)
        val currentPeers = legs.keysSnapshot()
        coroutineScope {
            currentPeers.forEach { peerId ->
                launch {
                    sendFrame(acceptFrame(), peerId)
                }
            }
        }
        announceAcceptToOtherMembers(skip = currentPeers.toSet())

        // Initiate legs with peers where localDeviceId > peerId
        currentPeers.forEach { peerId ->
            scope.launch {
                ensureLegConnected(peerId)
            }
        }
        return true
    }

    private fun acceptFrame() = CallWireFrame.GroupAccept(
        callId = callId,
        from = localDeviceId,
        groupId = groupId,
        band = networkBand(),
        videoRequests = true,
    )

    /**
     * ERROR-096: tells the other members this call was offered to that this device joined, directly. Accepting used to tell
     * only the inviter, who relayed it as a [CallWireFrame.GroupJoin]; when that one frame did not reach a member (it
     * was still ringing, or the inviter was busy), the member never learned this device was in the call, built no leg to it,
     * and, being the device that must offer, never offered. This device then waited for an offer that could not come.
     *
     * A member that is not in the call and is not ringing drops the frame (it has no such call), a member that is
     * ringing records the join like a relayed one, and a member in the call connects. Each send is its own coroutine:
     * a member that cannot be reached is dialed for seconds and must not hold up this device's own connections.
     */
    internal fun announceAcceptToOtherMembers(skip: Set<String>) {
        announceMembers.filter { it != localDeviceId && it !in skip }.forEach { memberId ->
            scope.launch { sendFrame(acceptFrame(), memberId) }
        }
    }

    /** Joins an ongoing group call announced by peers. */
    public suspend fun joinExisting(memberIds: List<String>): Boolean {
        sessionMutex.withLock {
            if (isEnded) return false
            updateUi {
                it.copy(
                    state = FlashCallState.CONNECTING,
                    connectedAt = SystemTimeSource.nowMs(),
                )
            }
            memberIds.filter { it != localDeviceId }.forEach { memberId ->
                legs[memberId] = GroupLeg(
                    peerId = memberId,
                    peerName = resolveName(memberId),
                    state = FlashCallParticipantState.CONNECTING,
                    connectingSinceMs = nowMs(),
                )
            }
            refreshUiState()
        }

        val mediaFailure = acquireMedia()
        if (mediaFailure != null) {
            endSession(mediaFailure)
            return false
        }

        armPresenceAnnouncement()
        armConnectDeadline()
        routeVideo { startReceiving() }

        // Announce join to all known members (in parallel: a member that is not connected is dialed first)
        val currentPeers = legs.keysSnapshot()
        coroutineScope {
            currentPeers.forEach { peerId ->
                launch {
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
            }
        }

        currentPeers.forEach { peerId ->
            scope.launch {
                ensureLegConnected(peerId)
            }
        }
        return true
    }

    internal fun armPresenceAnnouncement() {
        if (presenceJob != null) return
        presenceJob = scope.launch {
            while (!isEnded) {
                val callState = _state.value.state
                if (callState == FlashCallState.ACTIVE || callState == FlashCallState.CONNECTING || callState == FlashCallState.DIALING) {
                    val frame = presenceFrame()
                    // ERROR-088: every leg AND every member the call was offered to, so a member nobody has a leg for still
                    // sees the ongoing call. Each announcement runs on its own (a dial may take seconds) and never stacks.
                    (legs.keysSnapshot() + announceMembers).filter { it != localDeviceId }.distinct().forEach { peerId ->
                        announceTo(peerId, frame)
                    }
                    try {
                        pruneUnansweredLegs()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        FlashLog.w("GROUP_CALL", "pruneUnansweredLegs error: ${t.message}")
                    }
                    try {
                        nudgeSilentOfferers()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        FlashLog.w("GROUP_CALL", "nudgeSilentOfferers error: ${t.message}")
                    }
                }
                delay(4_000L)
            }
        }
    }

    /**
     * One presence announcement to [peerId] (ERROR-088), preceded by the invite when it has not been delivered yet and the
     * invitee would still be ringing. Skipped when the previous one to the same peer is still in flight.
     */
    private fun announceTo(peerId: String, presence: CallWireFrame.GroupPresence) {
        if (isEnded || !announcing.add(peerId)) return
        scope.launch {
            try {
                if (isEnded) return@launch
                val leg = legs[peerId]
                if (leg != null && leg.inviteExpected && !leg.inviteDelivered &&
                    leg.state == FlashCallParticipantState.INVITED && nowMs() < inviteRetryUntilMs
                ) {
                    deliverInvite(peerId)
                }
                // The dial above can take seconds; a presence sent after the call ended would bring its banner back.
                if (isEnded) return@launch
                sendFrame(presence, peerId)
            } finally {
                announcing.remove(peerId)
            }
        }
    }

    /**
     * A joiner has no list of who is in the call, so [joinExisting] builds a connection to every
     * group member; the ones who are not in the call never answer. Such a leg used to stay
     * CONNECTING for the whole call (ERROR-086 (d)): a connection and its ICE work for nothing, a
     * phantom tile, and a participant counted against the call's size cap and the video budget.
     *
     * A leg is given up on when its connection is [UNANSWERED_LEG_MS] old and its peer has sent
     * nothing since it was built (an answer, an offer, a candidate, a presence all count). The
     * connection is closed and the leg goes back to INVITED; a later frame from that peer (its
     * [CallWireFrame.GroupPresence], a join) brings it back.
     */
    internal suspend fun pruneUnansweredLegs() {
        if (isEnded) return
        val now = nowMs()
        val silent = legs.valuesSnapshot().filter { leg ->
            leg.state == FlashCallParticipantState.CONNECTING &&
                leg.pcCreatedAtMs > 0L &&
                leg.heardAtMs < leg.pcCreatedAtMs &&
                now - leg.pcCreatedAtMs >= UNANSWERED_LEG_MS
        }
        if (silent.isEmpty()) return
        for (leg in silent) {
            val pruned = leg.legMutex.withLock {
                // Re-check under the leg lock: the peer may have answered while we waited for it.
                if (leg.state != FlashCallParticipantState.CONNECTING || leg.heardAtMs >= leg.pcCreatedAtMs) {
                    false
                } else {
                    closeLeg(leg)
                    leg.state = FlashCallParticipantState.INVITED
                    true
                }
            }
            if (pruned) {
                FlashLog.i(
                    "GROUP_CALL",
                    "Leg ${leg.peerId} pc#${leg.pcGeneration} gave no sign of life in ${UNANSWERED_LEG_MS / 1000}s; not in the call, back to invited",
                )
                routeVideo { onPeerLeft(leg.peerId) }
            }
        }
        refreshUiState()
    }

    /**
     * ERROR-096: this device is the answerer of a leg (the lower device id; the other side offers), its connection has been
     * built for [OFFER_NUDGE_AFTER_MS] and no offer came. The peer is alive (presence keeps arriving, which is why
     * [pruneUnansweredLegs] leaves the leg alone), so the likeliest cause is that it never learned this device is in the call and
     * has no leg to offer on. This device tells it directly, again, every [OFFER_NUDGE_AFTER_MS], at most [MAX_OFFER_NUDGES]
     * times. A peer with no leg for this device builds one and offers; a peer whose own connection to this device is under
     * [LEG_SETUP_GRACE_MS] old keeps it and re-sends its pending offer, an older one is rebuilt and offers again
     * ([keepSettingUpLeg]), so the repeats escalate rather than just repeat.
     */
    internal suspend fun nudgeSilentOfferers() {
        if (isEnded) return
        val now = nowMs()
        legs.valuesSnapshot().filter { leg ->
            leg.state == FlashCallParticipantState.CONNECTING &&
                localDeviceId < leg.peerId &&
                leg.pcCreatedAtMs > 0L &&
                !leg.remoteDescriptionSet &&
                leg.offerNudges < MAX_OFFER_NUDGES &&
                now - maxOf(leg.pcCreatedAtMs, leg.lastNudgeAtMs) >= OFFER_NUDGE_AFTER_MS
        }.forEach { leg ->
            leg.offerNudges++
            leg.lastNudgeAtMs = now
            FlashLog.i(
                "GROUP_CALL",
                "Leg ${leg.peerId} pc#${leg.pcGeneration} has waited ${(now - leg.pcCreatedAtMs) / 1000}s for an offer; " +
                    "telling it directly that this device is in call $callId (${leg.offerNudges}/$MAX_OFFER_NUDGES)",
            )
            scope.launch { sendFrame(acceptFrame(), leg.peerId) }
        }
    }

    /**
     * ERROR-096: a presence from a member this device has no leg for, while this device is in the call. The coordinator only
     * hands over presences of roster members it trusts, for this call, so the member is in the call. Such a member used to be
     * ignored: the presence handler needs a leg, and the only things that made one were a relayed join and an offer. When
     * the join was not relayed, both devices were in the same call and never connected. The member gets a leg
     * and [reviveIfInCall] connects it; whichever side has the higher id offers, as always.
     */
    private fun dropNonMember(peerId: String, what: String) {
        FlashLog.w("GROUP_CALL", "Ignoring $what for call $callId from/about $peerId: not an active member of group=$groupId")
    }

    private suspend fun adoptMemberInCall(peerId: String) {
        if (!isMediaAcquired || peerId == localDeviceId) return
        if (legs[peerId] == null && !isGroupMember(peerId)) {
            dropNonMember(peerId, "presence")
            return
        }
        val callState = _state.value.state
        if (callState != FlashCallState.ACTIVE && callState != FlashCallState.CONNECTING && callState != FlashCallState.DIALING) return
        // A full call turns the member away before it is given a tile (it would otherwise sit there as "invited").
        if (legs[peerId] == null && turnAwayIfFull(peerId)) return
        sessionMutex.withLock {
            if (isEnded) return@withLock
            if (legs[peerId] == null) {
                legs[peerId] = GroupLeg(peerId = peerId, peerName = resolveName(peerId), state = FlashCallParticipantState.INVITED)
                FlashLog.i("GROUP_CALL", "Member $peerId is in call $callId (presence) and had no leg here; adding it")
            }
        }
        reviveIfInCall(peerId)
    }

    /**
     * Ends an accepted or joined call that never connects to anyone (ERROR-086 (f)): the call may
     * have ended while this device was joining, or every leg may be unreachable. Without this the
     * screen stayed on "Connecting..." until the user found a way out.
     */
    internal fun armConnectDeadline() {
        connectDeadlineJob?.cancel()
        connectDeadlineJob = scope.launch {
            delay(CONNECT_DEADLINE_MS)
            while (true) {
                val verdict = sessionMutex.withLock {
                    when {
                        isEnded || _state.value.state != FlashCallState.CONNECTING || countConnectedLegs() > 0 -> Verdict.STOP
                        hasRejoiningLeg() -> Verdict.WAIT
                        else -> Verdict.END
                    }
                }
                when (verdict) {
                    Verdict.STOP -> return@launch
                    Verdict.WAIT -> delay(REJOIN_EXTENSION_MS)
                    Verdict.END -> {
                        FlashLog.i("GROUP_CALL", "Group call $callId did not connect to anyone in ${CONNECT_DEADLINE_MS / 1000}s; ending")
                        endSession(FlashCallEndReason.ERROR, notifyPeers = true)
                        return@launch
                    }
                }
            }
        }
    }

    /**
     * Whether some leg is on its way to connecting with a peer that is answering: CONNECTING, its
     * peer has sent a frame since the leg started connecting, and the attempt is still young
     * ([REJOIN_MAX_MS]). Such a leg is about to be a participant, so the solo grace and the
     * connect deadline wait for it. A leg whose peer never said anything (a member who is not in
     * the call) does not count, and neither does one that has been trying for too long.
     */
    private fun hasRejoiningLeg(): Boolean {
        val now = nowMs()
        return legs.valuesSnapshot().any { leg ->
            leg.state == FlashCallParticipantState.CONNECTING &&
                leg.connectingSinceMs > 0L &&
                leg.heardAtMs >= leg.connectingSinceMs &&
                now - leg.connectingSinceMs < REJOIN_MAX_MS
        }
    }

    /** What a timer that holds [sessionMutex] decided; acted on after the lock is released. */
    private enum class Verdict { STOP, WAIT, END }

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
        // Sent in the background, like the hangup frames in endSession: a socket that never drains
        // must not keep the Decline button from ending the call on this device.
        legs.keysSnapshot().forEach { peerId ->
            scope.launch {
                sendFrame(
                    CallWireFrame.GroupDecline(callId = callId, from = localDeviceId, groupId = groupId),
                    peerId,
                )
            }
        }
        endSession(FlashCallEndReason.DECLINED)
    }

    /** Hangs up / leaves the group call. */
    public suspend fun hangUp() {
        leave(FlashCallEndReason.NORMAL)
    }

    /** Tells every participant this device left, then ends the session with [reason]. */
    private suspend fun leave(reason: FlashCallEndReason) {
        endSession(reason, notifyPeers = true)
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
        // The transport peer is demonstrably alive and in this call (ERROR-086 (d)).
        legs[peerId]?.heardAtMs = nowMs()

        when (frame) {
            is CallWireFrame.GroupInvite -> {
                // Peer invited us to this group call (inbound ringing)
                if (!isGroupMember(effectivePeerId)) {
                    dropNonMember(effectivePeerId, "invite")
                    return
                }
                val knownMembers = frame.members.filter { it != localDeviceId && isGroupMember(it) }
                if (knownMembers.size != frame.members.count { it != localDeviceId }) {
                    FlashLog.w("GROUP_CALL", "Invite from $effectivePeerId for call $callId listed ${frame.members.count { it != localDeviceId } - knownMembers.size} device(s) that are not members of group=$groupId; ignoring them")
                }
                sessionMutex.withLock {
                    legs.getOrPut(effectivePeerId) {
                        GroupLeg(peerId = effectivePeerId, peerName = resolveName(effectivePeerId, frame.callerName), state = FlashCallParticipantState.INVITED)
                    }.recordBand(frame.band)
                    knownMembers.forEach { memberId ->
                        legs.getOrPut(memberId) {
                            GroupLeg(peerId = memberId, peerName = resolveName(memberId), state = FlashCallParticipantState.INVITED)
                        }
                    }
                    refreshUiState()
                }
                if (frame.from == peerId) routeVideo { onAnnouncement(effectivePeerId, frame.videoRequests) }
            }

            is CallWireFrame.GroupAccept, is CallWireFrame.GroupJoin -> {
                if (legs[effectivePeerId] == null && !isGroupMember(effectivePeerId)) {
                    dropNonMember(effectivePeerId, if (frame is CallWireFrame.GroupAccept) "accept" else "join")
                    return
                }
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
                        leg.beginConnecting()
                    }
                    // The join/accept itself is the peer announcing it is in the call.
                    if (frame.from == peerId) leg.heardAtMs = nowMs()
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
                    reviveIfInCall(effectivePeerId)
                } else if (frame.from == peerId) {
                    adoptMemberInCall(effectivePeerId)
                    legs[effectivePeerId]?.let {
                        it.recordBand(frame.band)
                        routeVideo { onAnnouncement(effectivePeerId, frame.videoRequests, frame.videoFree) }
                    }
                }
            }

            // G3 video requests: only between participants of this call (the coordinator has
            // already checked that `from` is the transport peer).
            // A request that arrives after its sender left (a frame in flight) must not add a watcher: nothing would
            // ever remove it, and it would hold an encoder slot for the rest of the call.
            is CallWireFrame.VideoRequest -> if (legs[effectivePeerId]?.let { it.state != FlashCallParticipantState.LEFT } == true) {
                routeVideo { onRequest(effectivePeerId, frame) }
            }
            is CallWireFrame.VideoRelease -> if (legs[effectivePeerId] != null) routeVideo { onRelease(effectivePeerId, frame) }
            is CallWireFrame.VideoGrant -> if (legs[effectivePeerId] != null) routeVideo { onGrant(effectivePeerId, frame) }
            is CallWireFrame.VideoDeny -> if (legs[effectivePeerId] != null) routeVideo { onDeny(effectivePeerId, frame) }

            // ADR-067: a participant's controls. Only its own frame, and only from someone in the call.
            is CallWireFrame.Status -> if (frame.from == peerId && legs[effectivePeerId]?.state.let { it != null && it != FlashCallParticipantState.LEFT }) {
                onStatus(effectivePeerId, frame)
            }

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
            // Once the session has ended nothing may build a connection: endSession closes each
            // leg under this lock, so a leg operation that starts after it must do nothing.
            if (isEnded) return@onMediaThread
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
        // A new connection counts its packets from zero: a stale baseline would hide the first seconds of loss from the governor.
        leg.lastLost = 0L
        leg.lastReceived = 0L
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
                // An event from a connection this leg has since replaced, or from an ended call, says nothing.
                if (isEnded || generation != leg.pcGeneration) return@collect
                when (connState) {
                    PeerConnectionState.Connected -> {
                        leg.state = FlashCallParticipantState.CONNECTED
                        leg.recoveryAttempts = 0
                        armStatsPolling()
                        sessionMutex.withLock {
                            cancelSoloWaiting()
                            updateUi {
                                if (it.state != FlashCallState.ACTIVE) {
                                    it.copy(
                                        state = FlashCallState.ACTIVE,
                                        connectedAt = it.connectedAt ?: SystemTimeSource.nowMs(),
                                    )
                                } else {
                                    it
                                }
                            }
                            refreshUiState()
                        }
                        // ADR-067: tell the newly connected participant where our controls stand.
                        sendStatusTo(leg.peerId)
                        // G3: the encodings may not have existed when the sender was first
                        // tuned, so set this leg's on/off again now that it is connected.
                        routeVideo {
                            leg.videoSender?.let {
                                tuneVideoSender(
                                    it,
                                    active = isSending(leg.peerId),
                                    height = sendHeight(leg.peerId)?.takeIf { h -> h > 0 },
                                    concession = leg.concession,
                                )
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
                        // Failed never heals (ERROR-086 (e)). Disconnected usually does, but not after a Wi-Fi roam: the
                        // local candidates are gone and ICE cannot repair that (ERROR-033, the 1:1 call restarts ICE for
                        // it), and a leg left here holds a slot for the whole call. So it is rebuilt if it is still down
                        // after a longer wait.
                        if (connState == PeerConnectionState.Failed) {
                            scheduleLegRecovery(leg, generation)
                        } else {
                            scheduleLegRecovery(leg, generation, firstDelayMs = DISCONNECTED_REBUILD_AFTER_MS)
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
            if (isEnded) return@onMediaThread
            val stream = localStream ?: return@onMediaThread
            var existing = leg.peerConnection
            // ERROR-086 (e): the peer rebuilt a connection that failed on both sides. A new offer
            // cannot be applied to a failed connection, so that one is replaced.
            if (existing != null && existing.connectionState.let { it == PeerConnectionState.Failed || it == PeerConnectionState.Closed }) {
                FlashLog.i("GROUP_CALL", "Offer from $peerId replaces failed pc#${leg.pcGeneration} (connection=${existing.connectionState})")
                closeLeg(leg)
                leg.beginConnecting()
                existing = null
            }
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
        val endReason = sessionMutex.withLock {
            val leg = legs[peerId] ?: return
            leg.state = FlashCallParticipantState.LEFT
            scope.launch {
                leg.legMutex.withLock {
                    closeLeg(leg)
                }
            }
            refreshUiState()
            checkSoloState()
            endReasonAfterDeparture()
        }
        statusBook.forget(peerId)
        routeVideo { onPeerLeft(peerId) }
        if (endReason != null) {
            FlashLog.i("GROUP_CALL", "Group call $callId has nobody left to wait for; ending ($endReason)")
            endSession(endReason)
        }
    }

    /**
     * ERROR-086 (c): whether a departure ([handlePeerLeft]) ends a call that has not started.
     * A ringing invitee stops ringing when the caller hangs up and nobody else has joined; a
     * caller whose every invitee has left stops dialing at once instead of waiting out the timeout.
     * Members who were only listed (INVITED) do not count as being in the call.
     */
    private fun endReasonAfterDeparture(): FlashCallEndReason? {
        val all = legs.valuesSnapshot()
        return when (_state.value.state) {
            FlashCallState.RINGING ->
                FlashCallEndReason.NO_ANSWER.takeIf {
                    all.none { it.state == FlashCallParticipantState.CONNECTING || it.state == FlashCallParticipantState.CONNECTED }
                }
            FlashCallState.DIALING ->
                FlashCallEndReason.DECLINED.takeIf { all.all { it.state == FlashCallParticipantState.LEFT } }
            else -> null
        }
    }

    /**
     * If no remote member is connected in an active call, enter a grace window rather than abruptly
     * failing ([SOLO_GRACE_MS]). The window ends the call only when nobody is connected *and* nobody
     * is on the way back ([hasRejoiningLeg]): the desktop log of 2026-09-30 shows a peer rejoining
     * 3 s before the timer fired, and the call was ended under it (ERROR-086 (b)).
     *
     * Runs in a job of its own, and [endSession] never cancels the job it runs in: that self-cancel
     * was the bug that left the call un-closable (ERROR-086).
     */
    private fun checkSoloState() {
        if (isEnded || _state.value.state != FlashCallState.ACTIVE) return
        if (soloWaitingJob != null || countConnectedLegs() > 0) return
        FlashLog.i("GROUP_CALL", "All remote members left group call $callId; waiting ${SOLO_GRACE_MS / 1000}s for peers...")
        soloWaitingJob = scope.launch {
            delay(SOLO_GRACE_MS)
            while (true) {
                val verdict = sessionMutex.withLock {
                    when {
                        isEnded -> Verdict.STOP
                        countConnectedLegs() > 0 -> {
                            soloWaitingJob = null
                            Verdict.STOP
                        }
                        hasRejoiningLeg() -> Verdict.WAIT
                        else -> Verdict.END
                    }
                }
                when (verdict) {
                    Verdict.STOP -> return@launch
                    Verdict.WAIT -> delay(REJOIN_EXTENSION_MS)
                    Verdict.END -> {
                        // A peer that connected while we left the lock cancelled this job; honour it.
                        currentCoroutineContext().ensureActive()
                        FlashLog.i("GROUP_CALL", "Grace timeout expired with no peers; ending group call $callId")
                        endSession(FlashCallEndReason.NORMAL, notifyPeers = true)
                        return@launch
                    }
                }
            }
        }
    }

    private fun cancelSoloWaiting() {
        soloWaitingJob?.cancel()
        soloWaitingJob = null
    }

    /**
     * Brings a leg back from INVITED when its peer proves it is in the call (a presence frame)
     * after [pruneUnansweredLegs] gave up on it, or when the original announcement was lost.
     * A LEFT leg is never revived this way: a stale presence frame must not undo a hangup.
     */
    private suspend fun reviveIfInCall(peerId: String) {
        val leg = legs[peerId] ?: return
        if (leg.state != FlashCallParticipantState.INVITED || !isMediaAcquired) return
        val callState = _state.value.state
        if (callState != FlashCallState.ACTIVE && callState != FlashCallState.CONNECTING && callState != FlashCallState.DIALING) return
        if (turnAwayIfFull(peerId)) return
        val revived = sessionMutex.withLock {
            if (isEnded || leg.state != FlashCallParticipantState.INVITED) {
                false
            } else {
                leg.beginConnecting()
                refreshUiState()
                true
            }
        }
        if (!revived) return
        FlashLog.i("GROUP_CALL", "Leg $peerId is in call $callId (presence); connecting")
        scope.launch { ensureLegConnected(peerId) }
    }

    /** Moves the leg to CONNECTING, recording when it started and that its peer is talking to us. */
    private fun GroupLeg.beginConnecting() {
        if (state != FlashCallParticipantState.CONNECTING) connectingSinceMs = nowMs()
        state = FlashCallParticipantState.CONNECTING
    }

    /**
     * ERROR-086 (e): a connection that reports Failed never recovers, and a leg whose connection
     * failed used to stay "Reconnecting…" until the peer left and rejoined. The offerer (the higher
     * device id, the same tie-break as the first offer) waits a moment, closes the failed
     * connection and offers again; the other side replaces its own failed connection on the new
     * offer ([handleInboundOffer]). Bounded: [MAX_LEG_RECOVERIES] rebuilds until the leg connects
     * again. A [onSignalingRestored] rebuild that got there first wins, and this one stands down.
     */
    private fun scheduleLegRecovery(leg: GroupLeg, generation: Int, firstDelayMs: Long? = null) {
        if (isEnded || localDeviceId <= leg.peerId) return
        if (leg.recoveryAttempts >= MAX_LEG_RECOVERIES) {
            FlashLog.w("GROUP_CALL", "Leg ${leg.peerId} pc#$generation failed; giving up after $MAX_LEG_RECOVERIES rebuilds")
            return
        }
        val attempt = ++leg.recoveryAttempts
        scope.launch {
            delay(firstDelayMs ?: (LEG_RECOVERY_DELAY_MS * attempt))
            if (isEnded || leg.pcGeneration != generation || leg.state != FlashCallParticipantState.DISCONNECTED) return@launch
            // Signaling to the peer is down: an offer could not be delivered, and onSignalingRestored rebuilds this leg itself.
            if (leg.signalingLost) return@launch
            FlashLog.i("GROUP_CALL", "Leg ${leg.peerId} pc#$generation failed; rebuilding ($attempt/$MAX_LEG_RECOVERIES)")
            val rebuild = leg.legMutex.withLock {
                if (isEnded || leg.pcGeneration != generation || leg.state != FlashCallParticipantState.DISCONNECTED) {
                    false
                } else {
                    closeLeg(leg)
                    leg.beginConnecting()
                    true
                }
            }
            if (rebuild) {
                sessionMutex.withLock { refreshUiState() }
                ensureLegConnected(leg.peerId)
            }
        }
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
                        val leg = legs[effect.peerId]
                        val sender = leg?.videoSender
                        if (sender != null) {
                            val concession = leg.concession
                            onMediaThread { tuneVideoSender(sender, active = effect.on, height = effect.height, concession = concession) }
                        }
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
            smallerForMany = smallerVideoForMany(),
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
        val qualitySamples = mutableListOf<Pair<GroupLeg, CallQualitySample>>()
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
            val legLost = inbound.sumOf { it.members.num("packetsLost")?.toLong() ?: 0L }
            val legReceived = inbound.sumOf { it.members.num("packetsReceived")?.toLong() ?: 0L }
            totalLost += legLost
            totalReceived += legReceived
            // D8 for groups: this leg's own link quality over the last interval only (a cumulative loss could never recover).
            val lostDelta = (legLost - leg.lastLost).coerceAtLeast(0L)
            val receivedDelta = (legReceived - leg.lastReceived).coerceAtLeast(0L)
            leg.lastLost = legLost
            leg.lastReceived = legReceived
            qualitySamples += leg to CallQualitySample(
                rttMs = rttSec?.let { (it * 1000).roundToInt() },
                audioJitterMs = inbound.firstOrNull { it.members.str("kind") == "audio" }?.members?.num("jitter")
                    ?.let { (it * 1000).roundToInt() },
                lossFraction = (lostDelta + receivedDelta).takeIf { it > 0L }?.let { lostDelta.toDouble() / it },
            )

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
        applyVoicePriority(qualitySamples)
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
            setLocalSpeaking(isLocalTalker(_state.value.micMuted, localAudioLevel))
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
        leg.signalingLost = true
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
        leg.signalingLost = false
        leg.signalingGraceJob?.cancel()
        leg.signalingGraceJob = null
        if (leg.state == FlashCallParticipantState.DISCONNECTED) {
            leg.beginConnecting()
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
        updateUi { it.copy(micMuted = next) }
        val tracks = localStream?.audioTracks.orEmpty()
        if (tracks.isNotEmpty()) {
            scope.launch(callMediaDispatcher) {
                tracks.forEach { runCatching { it.enabled = !next } }
            }
        }
        broadcastStatus()
        return next
    }

    public fun toggleCamera(): Boolean {
        val current = _state.value
        // ERROR-105: the camera stopped, so the button opens it again (a new capture) instead of unmuting a dead track.
        if (current.cameraNeedsRestart()) {
            scope.launch { restartCamera() }
            return true
        }
        // Joined without a camera, so there is nothing to switch on; the button must not claim otherwise.
        if (current.cameraOff && localStream?.videoTracks.orEmpty().isEmpty()) return true
        val next = !current.cameraOff
        updateUi { it.copy(cameraOff = next, cameraProblem = null) }
        val tracks = localStream?.videoTracks.orEmpty()
        if (tracks.isNotEmpty()) {
            scope.launch(callMediaDispatcher) {
                try {
                    tracks.forEach { it.enabled = !next }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    FlashLog.e("GROUP_CALL", "toggleCamera: could not set camera enabled=${!next} call=$callId", t)
                    reportCameraProblem(FlashCameraProblem.FAILED)
                }
            }
        }
        // G3: a camera that is off turns new requests down; turning it on tells those peers.
        scope.launch { routeVideo { setCameraOff(next) } }
        broadcastStatus()
        return next
    }

    /**
     * ERROR-105: shows [problem] on the call screen. A stopped camera ([FlashCameraProblem.FAILED]) also turns the
     * camera off and tells everyone (their tiles stop waiting for a picture, and new requests for it are turned down);
     * a failed switch clears itself.
     */
    internal fun reportCameraProblem(problem: FlashCameraProblem) {
        if (isEnded) return
        FlashLog.w("GROUP_CALL", "camera problem=$problem call=$callId")
        updateUi { it.withCameraProblem(problem) }
        if (problem == FlashCameraProblem.FAILED) {
            scope.launch { routeVideo { setCameraOff(true) } }
            broadcastStatus()
        } else {
            scope.launch {
                delay(SWITCH_PROBLEM_CLEAR_MS)
                updateUi { if (it.cameraProblem == FlashCameraProblem.SWITCH_FAILED) it.copy(cameraProblem = null) else it }
            }
        }
    }

    /**
     * ERROR-105: the local camera track ended while the camera was meant to be on (another app took it, it was
     * unplugged, the driver failed). webrtc-kmp turns the capturer's error into `stop()` and drops the message, so the
     * track's end is the only signal there is. Cancelled with the call's media.
     */
    private fun watchLocalCamera(track: VideoStreamTrack) {
        cameraWatchJob?.cancel()
        cameraWatchJob = scope.launch(callMediaDispatcher) {
            track.onEnded.first()
            if (_localVideoStreamTrack.value === track) reportCameraProblem(FlashCameraProblem.FAILED)
        }
    }

    /**
     * ERROR-105: opens the camera again after it stopped and hands the new track to every leg's sender
     * (`replaceTrack`: no renegotiation). Legs built later take it from the call's stream. Any failure leaves the
     * problem on screen.
     */
    private suspend fun restartCamera() {
        val opened = onMediaThread {
            mediaLifecycleMutex.withLock {
                if (isEnded) return@withLock false
                val stream = localStream ?: return@withLock false
                val capture = captureProfile ?: groupCaptureProfile()
                try {
                    val temporary = MediaDevices.getUserMedia {
                        video {
                            width(capture.captureWidth)
                            height(capture.captureHeight)
                            frameRate(capture.captureFps.toDouble())
                        }
                    }
                    val fresh = temporary.videoTracks.firstOrNull()
                    if (fresh == null) {
                        temporary.release()
                        return@withLock false
                    }
                    // The track moves to the call's own stream; the temporary one is only a container.
                    temporary.removeTrack(fresh)
                    temporary.release()
                    for (leg in legs.valuesSnapshot()) {
                        leg.videoSender?.let { sender -> runCatching { sender.replaceTrack(fresh) } }
                    }
                    val stale = _localVideoStreamTrack.value
                    stale?.let { runCatching { stream.removeTrack(it) } }
                    stream.addTrack(fresh)
                    // Publish the new track before stopping the old one: the old track's watcher only reports
                    // when its track is still the published one.
                    _localVideoStreamTrack.value = fresh
                    runCatching { stale?.stop() }
                    watchLocalCamera(fresh)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    FlashLog.e("GROUP_CALL", "restartCamera failed call=$callId", t)
                    false
                }
            }
        }
        if (opened) {
            updateUi { it.copy(cameraOff = false, cameraProblem = null) }
            scope.launch { routeVideo { setCameraOff(false) } }
            broadcastStatus()
        } else {
            reportCameraProblem(FlashCameraProblem.FAILED)
        }
    }

    /** ADR-067: raises or lowers this device's hand and tells everyone in the call. */
    public fun setHandRaised(raised: Boolean) {
        if (_state.value.handRaised == raised) return
        updateUi { it.copy(handRaised = raised) }
        broadcastStatus()
    }

    /** ADR-067: shows [kind] on every screen. False when not in a live call or inside the sender's own gap. */
    public fun sendReaction(kind: FlashCallReactionKind): Boolean {
        if (_state.value.state != FlashCallState.ACTIVE && _state.value.state != FlashCallState.CONNECTING) return false
        val seq = statusBook.nextLocalSeq() ?: return false
        statusBook.addLocal(localDeviceId, kind)
        updateUi { it.copy(reactions = statusBook.activeReactions()) }
        scheduleReactionExpiry()
        broadcastStatus(reaction = kind, reactionSeq = seq)
        return true
    }

    /**
     * ADR-067 data saver: this device asks nobody for video until turned off. Everyone else keeps sending to whoever still
     * watches; audio is untouched. A voice call has no video, so it is a no-op there.
     */
    public fun setDataSaver(on: Boolean) {
        if (!video) return
        updateUi { it.copy(dataSaver = on) }
        scope.launch {
            routeVideo {
                health.dataSaver = on
                FlashLog.i("GROUP_CALL", "data saver=$on")
                tick()
            }
        }
    }

    // ------------------------------------------------------------------ status (ADR-067)

    /** Participants a status can be sent to: everyone who accepted (they all have a signaling session to us). */
    private fun statusAudience(): List<String> =
        legs.valuesSnapshot().filter {
            it.state == FlashCallParticipantState.CONNECTING ||
                it.state == FlashCallParticipantState.CONNECTED ||
                it.state == FlashCallParticipantState.DISCONNECTED
        }.map { it.peerId }

    private fun statusFrame(reaction: FlashCallReactionKind? = null, reactionSeq: Long = 0L): CallWireFrame.Status {
        val st = _state.value
        return CallWireFrame.Status(
            callId = callId,
            from = localDeviceId,
            micOn = !st.micMuted,
            cameraOn = if (video) !st.cameraOff else null,
            handRaised = st.handRaised,
            reaction = reaction,
            reactionSeq = reactionSeq,
        )
    }

    /** Tells everyone in the call what this device's controls say. Fire and forget; the next change or connect repairs a loss. */
    private fun broadcastStatus(reaction: FlashCallReactionKind? = null, reactionSeq: Long = 0L) {
        if (isEnded || !isMediaAcquired) return
        val frame = statusFrame(reaction, reactionSeq)
        statusAudience().forEach { peerId -> scope.launch { sendFrame(frame, peerId) } }
    }

    private fun sendStatusTo(peerId: String) {
        if (isEnded || !isMediaAcquired) return
        val frame = statusFrame()
        scope.launch { sendFrame(frame, peerId) }
    }

    private fun onStatus(peerId: String, frame: CallWireFrame.Status) {
        val shown = statusBook.apply(peerId, frame)
        updateUi { it.copy(reactions = statusBook.activeReactions()) }
        refreshUiState()
        if (shown != null) scheduleReactionExpiry()
    }

    private fun scheduleReactionExpiry() {
        scope.launch {
            delay(CallStatusBook.REACTION_LIFETIME_MS + 100L)
            updateUi { it.copy(reactions = statusBook.activeReactions()) }
        }
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
        try {
            onMediaThread { _localVideoStreamTrack.value?.switchCamera() }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // ERROR-105: "No other camera available" / "Switch camera failed" used to vanish into the host's launch.
            FlashLog.e("GROUP_CALL", "switchCamera failed call=$callId", t)
            reportCameraProblem(FlashCameraProblem.SWITCH_FAILED)
        }
    }

    public fun setSpeaker(on: Boolean) {
        updateUi { it.copy(speakerOn = on) }
    }

    private fun refreshUiState() {
        val participants = legs.valuesSnapshot().map { leg ->
            FlashCallParticipantUi(
                peerId = leg.peerId,
                name = leg.peerName,
                isSpeaking = leg.isSpeaking,
                isMuted = leg.isMuted || !statusBook.peer(leg.peerId).micOn,
                cameraOff = video && !statusBook.peer(leg.peerId).cameraOn,
                handRaised = statusBook.peer(leg.peerId).handRaised,
                state = leg.state,
                video = videoStates[leg.peerId] ?: FlashParticipantVideo.OFF,
                reachable = !(leg.inviteExpected && !leg.inviteDelivered && leg.state == FlashCallParticipantState.INVITED),
            )
        } + unavailableNotes.mapNotNull { (peerId, note) ->
            // A member that has since joined through someone else has a leg and shows as itself.
            if (legs[peerId] != null) {
                null
            } else {
                FlashCallParticipantUi(
                    peerId = peerId,
                    name = resolveName(peerId),
                    state = FlashCallParticipantState.INVITED,
                    reachable = false,
                    note = note,
                )
            }
        }
        updateUi {
            it.copy(
                participants = participants,
                compactVideo = video && videoCompactNow,
                videoFocusPeerId = videoFocusNow,
                videoMainPeerId = videoMainNow,
                healthWarning = if (video) healthNow.warning else null,
                showingFewerVideos = video && healthNow.showingFewer,
                smallerVideoForMany = video && smallerVideoForMany(),
            )
        }
    }

    /**
     * Every write to the UI state except the final one goes through here. ENDED is terminal: a
     * late leg event, a stats tick or a toggle must never turn an ended call back into a live one,
     * and the compare-and-set keeps two writers from losing each other's update (a concurrent
     * refresh used to be able to overwrite ENDED with a copy of the state it had read before).
     */
    private fun updateUi(block: (FlashCallUiState) -> FlashCallUiState) {
        _state.update { if (it.state == FlashCallState.ENDED) it else block(it) }
    }

    /**
     * Opens the microphone (and camera for a video call). Null on success; otherwise the reason the call cannot
     * start (ERROR-105). A video call whose camera cannot open is joined audio only, with a [FlashCallNotice].
     */
    private suspend fun acquireMedia(audioOnly: Boolean = false): FlashCallEndReason? {
        if (isMediaAcquired) return null
        // Pinned: getUserMedia drives ADM device select + factory init. Lifecycle-locked
        // against endSession's teardown (see mediaLifecycleMutex).
        return onMediaThread {
            mediaLifecycleMutex.withLock {
        // The call may have ended while this acquire waited for the lifecycle lock; opening the
        // camera now would leak it, because the teardown has already run (or is waiting behind us).
        if (isEnded) return@withLock FlashCallEndReason.ERROR
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
            val acquired = acquireWithCameraFallback(video && !audioOnly) { withVideo ->
                MediaDevices.getUserMedia {
                    audio {
                        echoCancellation(true)
                        noiseSuppression(true)
                        autoGainControl(true)
                    }
                    if (withVideo) {
                        video {
                            width(capture.captureWidth)
                            height(capture.captureHeight)
                            frameRate(capture.captureFps.toDouble())
                        }
                    }
                }
            }
            val stream = when (acquired) {
                is MediaAcquire.Failed -> {
                    FlashLog.e("GROUP_CALL", "media not available for call $callId reason=${acquired.reason}")
                    return@withLock acquired.reason
                }
                is MediaAcquire.Ready -> acquired.stream
            }
            stream.videoTracks.firstOrNull()?.settings?.let { s ->
                FlashLog.i("GROUP_CALL", "camera opened ${s.width ?: "?"}x${s.height ?: "?"}@${s.frameRate ?: "?"}")
            }
            localStream = stream
            _localVideoStreamTrack.value = stream.videoTracks.firstOrNull()
            stream.videoTracks.firstOrNull()?.let { watchLocalCamera(it) }
            isMediaAcquired = true
            if (video && stream.videoTracks.isEmpty()) {
                // Joined audio only (the host asked for it after a denial, the camera would not open, or the desktop
                // has none): the camera is off for everyone's tiles and the user is told why.
                val notice = when {
                    audioOnly -> FlashCallNotice.CAMERA_DENIED_AUDIO_ONLY
                    else -> (acquired as MediaAcquire.Ready).notice ?: FlashCallNotice.CAMERA_UNAVAILABLE_AUDIO_ONLY
                }
                FlashLog.i("GROUP_CALL", "joining call $callId without camera notice=$notice")
                updateUi { it.copy(cameraOff = true, notice = notice) }
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FlashLog.e("GROUP_CALL", "Failed to acquire media for group call", t)
            FlashCallEndReason.ERROR
        }
            }
        }
    }

    /**
     * Ends the call. Idempotent (an atomic claim), and ordered like the 1:1 session's `end()`:
     * the UI and the coordinator learn the call is over **first**, the native teardown follows.
     *
     * ERROR-086: this used to tear the media down first and publish ENDED last, and the solo
     * grace timer that called it cancelled its own job on the way, so the teardown threw at its
     * first suspension point and ENDED/`onEnded` never happened: the call could not be closed and
     * no other call could start. Now (1) nothing here cancels the coroutine it runs in, (2) ENDED
     * and `onEnded` come before any native work, so a hung `close()` cannot keep the call on
     * screen, and (3) the teardown runs non-cancellably, so it finishes even if the caller is
     * cancelled. [notifyPeers] sends GroupHangup to every known member (a hangup, a timeout).
     */
    private suspend fun endSession(reason: FlashCallEndReason, notifyPeers: Boolean = false) {
        if (!endClaim.compareAndSet(false, true)) return
        isEnded = true
        FlashLog.i("GROUP_CALL", "Group call $callId ending reason=$reason notifyPeers=$notifyPeers")
        if (notifyPeers) {
            // Every leg, and every member the call was only announced to: a device that never joined shows a "join" banner
            // from this call's presence, and this goodbye is what takes that banner down.
            (legs.keysSnapshot() + announceMembers).filter { it != localDeviceId }.distinct().forEach { peerId ->
                scope.launch {
                    sendFrame(
                        CallWireFrame.GroupHangup(callId = callId, from = localDeviceId, groupId = groupId),
                        peerId,
                    )
                }
            }
        }
        cancelTimers()
        _stats.value = null
        _state.update { it.copy(state = FlashCallState.ENDED, endReason = reason) }
        try {
            onEnded(this)
        } finally {
            withContext(NonCancellable) { teardownMedia() }
        }
    }

    /** Cancels every timer and loop of this session except the coroutine that is running [endSession]. */
    private suspend fun cancelTimers() {
        val self = currentCoroutineContext()[Job]
        listOf(soloWaitingJob, dialTimeoutJob, ringTimeoutJob, connectDeadlineJob, presenceJob, statsJob).forEach { job ->
            if (job !== self) job?.cancel()
        }
        soloWaitingJob = null
        dialTimeoutJob = null
        ringTimeoutJob = null
        connectDeadlineJob = null
        presenceJob = null
        statsJob = null
    }

    /**
     * Releases the connections and the camera and microphone. Runs after ENDED is published.
     *
     * Order matches the 1:1 session: unpublish the tracks first (the UI unbinds its renderers off
     * those flows, and every track is about to be stopped), then close the connections, then
     * release the local stream. Each leg is closed under its own lock so an operation in flight
     * (an offer being created) finishes before its connection is closed and one that starts later
     * sees [isEnded] and does nothing (ERROR-086 (h)); a leg whose lock cannot be had within
     * [LEG_TEARDOWN_WAIT_MS] is closed anyway, because a stuck operation must not keep the
     * camera on. Lock order is leg lock, then [mediaLifecycleMutex], as everywhere else.
     */
    private suspend fun teardownMedia() {
        onMediaThread {
            cameraWatchJob?.cancel()
            cameraWatchJob = null
            _localVideoStreamTrack.value = null
            _remoteVideoStreamTrack.value = null
            remoteVideo.clear()
            publishRemoteVideo()
        }
        for (leg in legs.valuesSnapshot()) {
            var locked = leg.legMutex.tryLock()
            var waitedMs = 0L
            while (!locked && waitedMs < LEG_TEARDOWN_WAIT_MS) {
                delay(TEARDOWN_POLL_MS)
                waitedMs += TEARDOWN_POLL_MS
                locked = leg.legMutex.tryLock()
            }
            try {
                closeLeg(leg)
            } finally {
                if (locked) leg.legMutex.unlock()
            }
        }
        // Pinned: stream release is native. Lifecycle-locked: an acquire that is still running
        // finishes first and its stream is released here.
        onMediaThread {
            mediaLifecycleMutex.withLock {
                legs.clear()
                try {
                    localStream?.release()
                } catch (_: Throwable) {}
                localStream = null
            }
        }
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
     * the offerer and its offer is still unanswered after [OFFER_RESEND_MIN_AGE_MS], the same
     * offer is sent again, in case the peer lost it (sooner, the peer is still answering it: the
     * 2026-09-29 test re-sent one 8 ms after the first and got two answers). An older or failed connection is rebuilt as before (the peer may have
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
        if (localDeviceId > leg.peerId && signaling == SignalingState.HaveLocalOffer && offer != null &&
            ageMs >= OFFER_RESEND_MIN_AGE_MS
        ) {
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
     * full camera profile (an old client, or a leg not yet decided). [concession] is the leg's own
     * voice-priority rung (D8); the numbers are worked out in [groupVideoTuning].
     */
    private fun tuneVideoSender(
        sender: RtpSender,
        active: Boolean,
        height: Int? = null,
        concession: VideoConcession = VideoConcession.FULL,
    ) {
        val profile = captureProfile ?: groupCaptureProfile()
        val tuning = groupVideoTuning(profile, height, concession, active)
        try {
            val applied = sender.applyVideoTuning(tuning)
            if (!applied) {
                FlashLog.w("GROUP_CALL", "video sender has no encodings to tune")
                return
            }
            FlashLog.i(
                "GROUP_CALL",
                "video sender tuned applied=$applied active=${tuning.active} height=${height ?: "full"} " +
                    "max=${tuning.maxBitrateBps / BPS_PER_KBPS}kbps min=${tuning.minBitrateBps?.div(BPS_PER_KBPS) ?: "none"}kbps " +
                    "fps=${profile.captureFps} concession=$concession degradation=MAINTAIN_FRAMERATE",
            )
        } catch (t: Throwable) {
            FlashLog.w("GROUP_CALL", "video sender tuning failed: ${t.message}")
        }
    }

    /**
     * D8 for groups: feeds each connected leg's quality sample to its own [CallQualityGovernor] and applies a new rung to
     * that leg's sender only, so one bad link costs that leg its video and not everybody's. With the user's "Prioritise voice
     * quality" off, every leg goes back to full. No video, no concession. The decision is the governor's; this applies it.
     */
    private suspend fun applyVoicePriority(samples: List<Pair<GroupLeg, CallQualitySample>>) {
        if (!video || isEnded) return
        val enabled = prioritiseVoice()
        val changed = mutableListOf<GroupLeg>()
        for ((leg, sample) in samples) {
            val next = if (enabled) {
                leg.governor.onSample(sample)
            } else if (leg.concession != VideoConcession.FULL) {
                leg.governor.reset()
                VideoConcession.FULL
            } else {
                null
            }
            if (next != null && next != leg.concession) {
                FlashLog.i("GROUP_CALL", "Leg ${leg.peerId} voice priority: video ${leg.concession} -> $next")
                leg.concession = next
                changed += leg
            }
        }
        if (changed.isEmpty()) return
        routeVideo {
            changed.forEach { leg ->
                val sender = leg.videoSender ?: return@forEach
                tuneVideoSender(
                    sender,
                    active = isSending(leg.peerId),
                    height = sendHeight(leg.peerId)?.takeIf { h -> h > 0 },
                    concession = leg.concession,
                )
            }
            emptyList()
        }
        val worst = legs.valuesSnapshot().map { it.concession }.maxByOrNull { it.ordinal } ?: VideoConcession.FULL
        updateUi { it.copy(videoLimitReason = worst.reason) }
    }

    private companion object {
        /** Minimum cooldown after a concession step before a gentler recovery rung can be applied to prevent MediaCodec thrashing. */
        const val VOICE_PRIORITY_RECOVERY_COOLDOWN_MS = 8_000L

        /** How long a leg may stay Disconnected before the offerer rebuilds its connection (ERROR-033 for groups). */
        const val DISCONNECTED_REBUILD_AFTER_MS = 10_000L

        /** How long a group call with nobody connected waits for someone to come back before it ends (ERROR-086). */
        const val SOLO_GRACE_MS = 30_000L

        /** While a peer is on its way back, how long past the grace the end is put off, each time. */
        const val REJOIN_EXTENSION_MS = 10_000L

        /** The longest a leg's connecting attempt is waited for past the grace (ERROR-086 (b)). */
        const val REJOIN_MAX_MS = 40_000L

        /** How long an outgoing group call rings with nobody joining. */
        const val DIAL_TIMEOUT_MS = 30_000L

        /** How long an incoming group call rings when the caller never says it stopped (ERROR-086 (c)). */
        const val RING_TIMEOUT_MS = 45_000L

        /** How long an accepted or joined call may go without connecting to anyone (ERROR-086 (f)). */
        const val CONNECT_DEADLINE_MS = 45_000L

        /** How long a built connection may get no frame from its peer before the leg is given up on (ERROR-086 (d)). */
        const val UNANSWERED_LEG_MS = 15_000L

        /** How long an answerer leg waits for an offer before this device tells the peer it is in the call (ERROR-096), and the repeat interval. */
        const val OFFER_NUDGE_AFTER_MS = 8_000L

        /** How many times that is repeated for one leg. */
        const val MAX_OFFER_NUDGES = 3

        /** Rebuilds of a failed leg before giving up, and the delay unit between them (ERROR-086 (e)). */
        const val MAX_LEG_RECOVERIES = 3
        const val LEG_RECOVERY_DELAY_MS = 1_500L

        /** How long the teardown waits for a leg operation in flight before closing the leg regardless. */
        const val LEG_TEARDOWN_WAIT_MS = 2_000L
        const val TEARDOWN_POLL_MS = 50L

        // AUDIO_BITRATE_PRIORITY / VIDEO_BITRATE_PRIORITY moved to the sender-tuning seam
        // (RtpSenderTuning.kt) with the rest of the native-knob plumbing (S2e).
        const val BPS_PER_KBPS = 1000

        /** How long a connection being set up is kept through repeated accepts/joins (ERROR-076). */
        const val LEG_SETUP_GRACE_MS = 10_000L

        /** A pending offer younger than this is not re-sent on a repeated accept/join: the peer is still answering it. */
        const val OFFER_RESEND_MIN_AGE_MS = 3_000L

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
