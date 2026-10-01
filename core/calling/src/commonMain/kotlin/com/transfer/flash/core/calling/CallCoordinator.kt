@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallLogEntry
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.OngoingGroupCallUi
import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.concurrent.SyncMap
import com.transfer.flash.core.common.logging.FlashLog
import com.transfer.flash.core.common.perf.FlashNetworkBand
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.time.SystemTimeSource
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-level owner of at most one live [FlashCallSession] (C7, ADR-025, UI-050).
 *
 * The standard [FlashCalling] implementation. Bridges the pure call session to the host app: it
 * keys sessions by call id, routes inbound `FLASH_CALL` frames, enforces the one-call-at-a-time
 * rule (a second invite while a call is live is auto-declined "busy"), and exposes the active
 * call's UI state as a single [StateFlow] the navigation layer can push/pop a route on.
 *
 * The host wires [sendFrame] to the WS mesh (non-blocking sendTextAsync pattern) and
 * delivers every decoded `FLASH_CALL` text frame to [onInboundText]. Frames for dead
 * or unknown call ids are dropped silently — the session that cared is gone.
 */
public class CallCoordinator(
    private val localDeviceId: String,
    private val localName: String,
    private val scope: CoroutineScope,
    /**
     * Sends an encoded signaling frame to a specific peer. The destination is explicit
     * because call frames carry only `callId` + `from` (our own id) on the wire — the
     * host resolves the WS session for `peerId`, which may differ from the live call's
     * peer (busy-decline to a new inviter, ADR-025 / protocol.md).
     */
    private val sendFrame: suspend (CallWireFrame, peerId: String) -> Boolean,
    /**
     * Closes the call trust gap (group Phase 0): when supplied, an outbound call to an untrusted
     * peer is refused and an inbound invite from one is auto-declined without ever ringing. The
     * default (everything trusted) preserves source compatibility for existing constructors.
     */
    private val isTrustedPeer: (peerId: String) -> Boolean = { true },
    /**
     * ADR-044 V2: whether [peerId] may take part in a call of group [groupId]. A member of a v2 group that the
     * group owner vouched for is trusted there without being paired; 1:1 calls keep using [isTrustedPeer]. The
     * default (paired only) is what a host without vouched groups wants. Suspend: the answer comes from the
     * group roster, which lives in the chat database.
     */
    private val isGroupTrustedPeer: suspend (peerId: String, groupId: String) -> Boolean =
        { peerId, _ -> isTrustedPeer(peerId) },
    /**
     * Called once per finished call, with everything needed to write a call row into the
     * chat thread. Fired for every session that terminates, including declined and missed
     * ones, so a call log has no holes in it.
     *
     * Deliberately a plain callback over a plain record: `core:calling` must not know that
     * `core:messaging` exists (port/adapter inversion, ADR-024). Runs on whichever thread
     * ended the call — possibly a WebRTC callback thread — so an implementation must hand
     * off rather than block.
     */
    private val onCallLog: (FlashCallLogEntry) -> Unit = {},
    /**
     * Reads the user's "Prioritise voice quality" setting for every session this coordinator
     * creates (default on).
     *
     * A lambda rather than a value so a mid-call flip is honoured, and so `core:calling` keeps
     * knowing nothing about DataStore — the host owns the preference and passes a reader in
     * (port/adapter inversion, ADR-024).
     */
    private val prioritiseVoice: () -> Boolean = { true },
    /**
     * Reads the device's performance tier for every session this coordinator creates (ERROR-033).
     *
     * Capture resolution, Opus packetization and the call-recovery windows all come from here, so a
     * 2 GB API-27 handset stops trying to capture 1080p30 and stops paying 100 packets/second of
     * header overhead for 25 kbit/s of speech.
     *
     * A lambda for the same two reasons as [prioritiseVoice]: a tier change (auto-detect resolving,
     * or the user pinning a mode) must reach the next call without re-wiring anything, and
     * `core:calling` must keep knowing nothing about DataStore (ADR-024).
     *
     * Defaults to [FlashPerformanceMode.HIGH], whose profiles are the pre-tiering constants, so a
     * caller that does not tier behaves exactly as before.
     */
    private val performanceMode: () -> FlashPerformanceMode = { FlashPerformanceMode.HIGH },
    private val peerNameResolver: (peerId: String) -> String? = { null },
    /**
     * This device's network band (G2) for group calls. Must be cheap and must not block: it is read
     * for every group announcement (every 4 s). The default keeps callers that do not detect it
     * sending UNKNOWN, which the other end treats as "decide by my own band".
     */
    private val networkBand: () -> FlashNetworkBand = { FlashNetworkBand.UNKNOWN },
    /**
     * Reads the user's "Send smaller video in groups" setting (ADR-053, default off) for every
     * group call: a lambda for the same reasons as [prioritiseVoice].
     */
    private val smallerVideoForMany: () -> Boolean = { false },
    /**
     * ERROR-088: whether [peerId] belongs to group [groupId]'s call at all (an active roster member this device trusts or
     * was introduced to by the group owner), with or without a session to it right now. [isGroupTrustedPeer] is the
     * narrower "may a frame go to or come from this live connection" (a vouched member needs a session whose key is the
     * certified one). A call's members are chosen with this and the narrow gate is applied when an announcement is
     * sent or received, so a member that is not paired with this device and not connected yet is still invited.
     * The default is the narrow gate: what a host without vouched groups wants.
     */
    private val isGroupMember: suspend (peerId: String, groupId: String) -> Boolean =
        { peerId, groupId -> isGroupTrustedPeer(peerId, groupId) },
    /**
     * ERROR-088: makes sure a session to [peerId] exists, dialing it when it does not (`AutoConnector.ensureSession`), and
     * says whether one exists on return. Called when a group call's announcement (or a connection's offer) has no
     * session to go to, so a member that has not been connected yet can still be invited and can still join. Must be
     * cheap when a session is live. The default dials nothing.
     */
    private val reachPeer: suspend (peerId: String) -> Boolean = { false },
) : FlashCalling {
    /** When each refusal line (see [logRefusal]) was last written. */
    private val refusalLoggedAt = SyncMap<String, Long>()

    private val _activeCall = MutableStateFlow<FlashCallUiState?>(null)
    override val activeCall: StateFlow<FlashCallUiState?> = _activeCall.asStateFlow()

    private val _ongoingGroupCalls = MutableStateFlow<Map<String, OngoingGroupCallUi>>(emptyMap())
    override val ongoingGroupCalls: StateFlow<Map<String, OngoingGroupCallUi>> = _ongoingGroupCalls.asStateFlow()

    init {
        scope.launch {
            while (true) {
                delay(5_000L)
                val now = SystemTimeSource.nowMs()
                val current = _ongoingGroupCalls.value
                val filtered = current.filterValues { now - it.lastSeenTimestamp < 12_000L }
                if (filtered.size != current.size) {
                    _ongoingGroupCalls.value = filtered
                }
            }
        }
    }

    /**
     * Renderable media for the live call, if any. Read by the UI layer to bind renderers and the
     * quality badge; the concrete session type stays inside this module.
     */
    override val media: FlashCallMedia?
        get() = currentSession ?: currentGroupSession

    @Volatile
    private var currentSession: FlashCallSession? = null

    @Volatile
    private var currentGroupSession: FlashGroupCallSession? = null

    private var stateCollector: Job? = null

    /** Outgoing call entry point (chat header call / video-call buttons). */
    override suspend fun startCall(peerId: String, peerName: String, video: Boolean): Boolean {
        if (!isTrustedPeer(peerId)) return false // trust gate: only paired peers are callable
        clearEndedGroupSession()
        if (currentSession != null || currentGroupSession != null) return false // one call at a time
        val resolvedName = peerNameResolver(peerId)?.ifBlank { null }
            ?: peerName.takeIf { it.isNotBlank() && it != peerId }
            ?: peerId
        val session = newSession(
            callId = com.transfer.flash.core.common.id.UuidIdGenerator.newId(),
            peerId = peerId,
            peerName = resolvedName,
            direction = FlashCallDirection.OUTGOING,
            video = video,
        )
        currentSession = session
        observeSession(session)
        return session.startOutgoing()
    }

    /** Outgoing group call entry point (group chat header call buttons). */
    override suspend fun startGroupCall(
        groupId: String,
        groupName: String,
        memberIds: List<String>,
        video: Boolean,
    ): Boolean {
        clearEndedGroupSession()
        if (currentSession != null || currentGroupSession != null) return false // one call at a time
        val callable = callMembers(groupId, memberIds, "start")
        val members = callable.members
        if (members.isEmpty()) {
            FlashLog.w("GROUP_CALL", "Group call refused: group=$groupId has no member this device may call (${memberIds.size} listed)")
            return false
        }

        val currentMap = _ongoingGroupCalls.value.toMutableMap()
        currentMap.remove(groupId)
        _ongoingGroupCalls.value = currentMap

        val callId = com.transfer.flash.core.common.id.UuidIdGenerator.newId()
        val session = FlashGroupCallSession(
            callId = callId,
            groupId = groupId,
            groupName = groupName,
            direction = FlashCallDirection.OUTGOING,
            video = video,
            localDeviceId = localDeviceId,
            localName = localName,
            scope = scope,
            sendFrame = { frame, peerId -> sendGroupFrame(groupId, frame, peerId) },
            onEnded = ::onGroupSessionEnded,
            performanceMode = performanceMode,
            peerNameResolver = peerNameResolver,
            networkBand = networkBand,
            smallerVideoForMany = smallerVideoForMany,
            prioritiseVoice = prioritiseVoice,
        )
        currentGroupSession = session
        observeGroupSession(session)
        return session.startOutgoing(members, unavailable = callable.leftOut)
    }

    /** Joins an ongoing group call announced by peers. */
    override suspend fun joinGroupCall(
        groupId: String,
        callId: String,
        memberIds: List<String>,
        video: Boolean,
    ): Boolean {
        clearEndedGroupSession()
        if (currentSession != null || currentGroupSession != null) return false
        val members = callMembers(groupId, memberIds, "join").members
        if (members.isEmpty()) {
            FlashLog.w("GROUP_CALL", "Group call join refused: group=$groupId call=$callId has no member this device may call (${memberIds.size} listed)")
            return false
        }

        val groupCallUi = _ongoingGroupCalls.value[groupId]
        val groupName = groupCallUi?.groupName ?: "Group Call"

        val session = FlashGroupCallSession(
            callId = callId,
            groupId = groupId,
            groupName = groupName,
            direction = FlashCallDirection.OUTGOING,
            video = video,
            localDeviceId = localDeviceId,
            localName = localName,
            scope = scope,
            sendFrame = { frame, peerId -> sendGroupFrame(groupId, frame, peerId) },
            onEnded = ::onGroupSessionEnded,
            performanceMode = performanceMode,
            peerNameResolver = peerNameResolver,
            networkBand = networkBand,
            smallerVideoForMany = smallerVideoForMany,
            prioritiseVoice = prioritiseVoice,
        )
        currentGroupSession = session
        observeGroupSession(session)

        val currentMap = _ongoingGroupCalls.value.toMutableMap()
        currentMap.remove(groupId)
        _ongoingGroupCalls.value = currentMap

        return session.joinExisting(members)
    }

    /** Queries online members of a group to discover if an active call is ongoing. */
    override suspend fun queryGroupCall(groupId: String, memberIds: List<String>) {
        val members = callMembers(groupId, memberIds, "query").members
        val frame = CallWireFrame.GroupQuery(from = localDeviceId, groupId = groupId)
        members.forEach { peerId ->
            sendGroupFrame(groupId, frame, peerId)
        }
    }

    /**
     * Host calls this with every inbound `FLASH_CALL` text frame. Returns true when
     * the frame was consumed (a live call exists or an invite was handled), false when
     * the text is not a call frame at all.
     *
     * - Invite with no live call → creates the incoming session (RINGING).
     * - GroupInvite with no live call → creates the incoming group session (RINGING).
     * - Invite while a call is live → auto-declined "busy" so the caller's UI does not hang.
     * - Frame matching the live call id → routed to the session.
     * - Frame for any other call id → dropped (stale frame for a dead call).
     */
    override suspend fun onInboundText(peerId: String, text: String): Boolean {
        val frame = CallFrameCodec.decode(text) ?: return false

        // SENTINEL: Fail closed on claimed-author vs transport-peer mismatch for all non-relayed call frames,
        // and enforce trust check on group presence/query frames to prevent unauthenticated info leaks/UI spoofing.
        if (frame !is CallWireFrame.GroupJoin && frame.from != peerId) return false

        if (frame is CallWireFrame.GroupPresence) {
            if (!isGroupTrustedPeer(peerId, frame.groupId)) {
                FlashLog.w("GROUP_CALL", "Presence from $peerId for group=${frame.groupId} call=${frame.callId} dropped: ${refusalReason(peerId, frame.groupId)}")
                return false
            }
            currentGroupSession?.takeIf { it.callId == frame.callId }?.let { live ->
                live.onInboundFrame(frame, peerId)
                return true
            }
            val currentMap = _ongoingGroupCalls.value.toMutableMap()
            currentMap[frame.groupId] = OngoingGroupCallUi(
                callId = frame.callId,
                groupId = frame.groupId,
                groupName = frame.callerName,
                initiatorId = frame.from,
                video = frame.video,
                participantCount = frame.participantCount,
                lastSeenTimestamp = SystemTimeSource.nowMs(),
            )
            _ongoingGroupCalls.value = currentMap
            return true
        }

        if (frame is CallWireFrame.GroupQuery) {
            if (!isGroupTrustedPeer(peerId, frame.groupId)) {
                FlashLog.w("GROUP_CALL", "Query from $peerId for group=${frame.groupId} dropped: ${refusalReason(peerId, frame.groupId)}")
                return false
            }
            val liveSession = currentGroupSession
            if (liveSession != null && liveSession.groupId == frame.groupId && !liveSession.isSessionEnded) {
                sendFrame(liveSession.presenceFrame(), peerId)
                return true
            }
            return false
        }

        // ERROR-095: an ended group session that was never cleared would answer every invite "busy". The start
        // paths already drop it; an inbound invite is the other way a call begins.
        clearEndedGroupSession()
        val p2pSession = currentSession
        val groupSession = currentGroupSession

        // 1. If currently in a 1:1 call
        if (p2pSession != null) {
            if (frame.callId == p2pSession.callId) {
                if (peerId != p2pSession.peerId || frame.from != p2pSession.peerId) return false
                if (frame is CallWireFrame.Invite) return true // duplicate invite
                p2pSession.onInboundFrame(frame)
                return true
            }
            if (frame is CallWireFrame.Invite) {
                sendFrame(CallWireFrame.Decline(callId = frame.callId, from = localDeviceId), peerId)
                return true
            }
            if (frame is CallWireFrame.GroupInvite) {
                FlashLog.i("GROUP_CALL", "Invite from $peerId for group=${frame.groupId} call=${frame.callId} declined: busy in a 1:1 call ${p2pSession.callId}")
                sendFrame(CallWireFrame.GroupDecline(callId = frame.callId, from = localDeviceId, groupId = frame.groupId), peerId)
                return true
            }
            return false
        }

        // 2. If currently in a group call
        if (groupSession != null) {
            if (frame.callId == groupSession.callId) {
                groupSession.onInboundFrame(frame, peerId)
                return true
            }
            if (frame is CallWireFrame.Invite) {
                sendFrame(CallWireFrame.Decline(callId = frame.callId, from = localDeviceId), peerId)
                return true
            }
            if (frame is CallWireFrame.GroupInvite) {
                FlashLog.i("GROUP_CALL", "Invite from $peerId for group=${frame.groupId} call=${frame.callId} declined: busy in group call ${groupSession.callId} of group=${groupSession.groupId}")
                sendFrame(CallWireFrame.GroupDecline(callId = frame.callId, from = localDeviceId, groupId = frame.groupId), peerId)
                return true
            }
            return false
        }

        // 3. Neither 1:1 nor group call is active
        if (frame is CallWireFrame.Invite) {
            if (!isTrustedPeer(peerId)) {
                sendFrame(CallWireFrame.Decline(callId = frame.callId, from = localDeviceId), peerId)
                return true
            }
            startIncoming(peerId = peerId, frame = frame)
            return true
        }

        if (frame is CallWireFrame.GroupInvite) {
            if (!isGroupTrustedPeer(peerId, frame.groupId)) {
                FlashLog.w("GROUP_CALL", "Invite from $peerId for group=${frame.groupId} call=${frame.callId} declined silently: ${refusalReason(peerId, frame.groupId)}")
                sendFrame(CallWireFrame.GroupDecline(callId = frame.callId, from = localDeviceId, groupId = frame.groupId), peerId)
                return true
            }
            startIncomingGroup(peerId = peerId, frame = frame)
            return true
        }

        return false
    }

    /** Local user accepted the incoming call. */
    override suspend fun accept(): Boolean {
        currentGroupSession?.let { return it.accept() }
        return currentSession?.accept() ?: false
    }

    /** Local user declined the incoming call. */
    override suspend fun decline(): Boolean {
        currentGroupSession?.let {
            it.decline()
            return true
        }
        val session = currentSession ?: return false
        session.decline()
        return true
    }

    /** Local user hung up / ended the call. */
    override suspend fun hangUp(): Boolean {
        currentGroupSession?.let { session ->
            try {
                session.hangUp()
            } finally {
                // Defense in depth (ERROR-086): once a session is over it must not stay the live call,
                // whatever its own teardown did. The button the user pressed always has an effect.
                clearEndedGroupSession()
            }
            return true
        }
        val session = currentSession ?: return false
        session.hangUp()
        return true
    }

    override fun toggleMute(): Boolean {
        currentGroupSession?.let { return it.toggleMute() }
        return currentSession?.toggleMute() ?: false
    }

    override fun toggleCamera(): Boolean {
        currentGroupSession?.let { return it.toggleCamera() }
        return currentSession?.toggleCamera() ?: false
    }

    override suspend fun switchCamera() {
        currentGroupSession?.switchCamera()
        currentSession?.switchCamera()
    }

    override fun setSpeaker(on: Boolean) {
        currentGroupSession?.setSpeaker(on)
        currentSession?.setSpeaker(on)
    }

    override fun setVideoFocus(peerId: String?) {
        currentGroupSession?.setVideoFocus(peerId)
    }

    override fun setShowFewerVideos(on: Boolean) {
        currentGroupSession?.setShowFewerVideos(on)
    }

    /** Host calls this when the WS signaling session to the call peer died. */
    override fun onSignalingLost(peerId: String) {
        if (currentSession?.peerId == peerId) {
            currentSession?.onSignalingLost()
        }
        currentGroupSession?.onSignalingLost(peerId)
    }

    /**
     * Host calls this when a WS signaling session to the call peer came up — on every session
     * establishment, not only after an [onSignalingLost]. Harmless when no call is waiting on one.
     */
    override fun onSignalingRestored(peerId: String) {
        if (currentSession?.peerId == peerId) {
            currentSession?.onSignalingRestored()
        }
        currentGroupSession?.onSignalingRestored(peerId)
    }

    private fun newSession(
        callId: String,
        peerId: String,
        peerName: String,
        direction: FlashCallDirection,
        video: Boolean,
    ): FlashCallSession {
        return FlashCallSession(
            callId = callId,
            peerId = peerId,
            peerName = peerName,
            direction = direction,
            video = video,
            localDeviceId = localDeviceId,
            localName = localName,
            scope = scope,
            // Bind the session's single peer into the sendFrame call so the coordinator's
            // (CallWireFrame, peerId) signature is transparent to the session.
            sendFrame = { frame -> sendFrame(frame, peerId) },
            prioritiseVoice = prioritiseVoice,
            performanceMode = performanceMode,
            onEnded = { ended ->
                publishCallLog(ended)
                if (currentSession === ended) {
                    currentSession = null
                    stateCollector?.cancel()
                    stateCollector = null
                    // Show the final ENDED state briefly (e.g. "Call ended") before
                    // clearing the overlay. The state collector may not have had a
                    // chance to collect the ENDED emission before cancellation.
                    _activeCall.value = ended.state.value
                    scope.launch {
                        kotlinx.coroutines.delay(2_000L)
                        // A new call may have started within the window; only clear
                        // if no live session replaced the ended one.
                        if (currentSession == null) _activeCall.value = null
                    }
                }
            },
        )
    }

    /**
     * Hands a finished call to [onCallLog].
     *
     * Duration comes from `connectedAt`, which is only set once media actually flowed — so a
     * declined or unanswered call reports zero and reads as "missed"/"not answered" in the
     * thread rather than as a zero-second conversation. Both devices compute this
     * independently from state they already hold, which is why a call log needs no wire
     * frame of its own.
     */
    private fun publishCallLog(session: FlashCallSession) {
        val state = session.state.value
        val endedAt = SystemTimeSource.nowMs()
        val durationMs = state.connectedAt?.let { (endedAt - it).coerceAtLeast(0L) } ?: 0L
        onCallLog(
            FlashCallLogEntry(
                callId = session.callId,
                peerId = session.peerId,
                peerName = session.peerName,
                direction = session.direction,
                video = session.video,
                endReason = state.endReason ?: FlashCallEndReason.NORMAL,
                durationMs = durationMs,
                endedAt = endedAt,
            ),
        )
    }

    /**
     * A group session finished: hand its last state to the UI, free the coordinator for the next
     * call, and clear the overlay shortly after. The one place this is done (it was copied three
     * times). Idempotent, and a no-op for a session that is no longer the live one.
     */
    private fun onGroupSessionEnded(ended: FlashGroupCallSession) {
        if (currentGroupSession !== ended) return
        currentGroupSession = null
        stateCollector?.cancel()
        stateCollector = null
        _activeCall.value = ended.state.value
        scope.launch {
            delay(2_000L)
            if (currentSession == null && currentGroupSession == null) {
                _activeCall.value = null
            }
        }
    }

    /** Test seam: makes [session] the live group call without the media a real start needs. */
    internal fun installGroupSessionForTesting(session: FlashGroupCallSession) {
        currentGroupSession = session
        observeGroupSession(session)
    }

    /** Drops a live-call slot held by a group session that has already ended (ERROR-086's zombie). */
    private fun clearEndedGroupSession() {
        currentGroupSession?.takeIf { it.isSessionEnded }?.let(::onGroupSessionEnded)
    }

    private fun observeSession(session: FlashCallSession) {
        stateCollector?.cancel()
        stateCollector = scope.launch {
            session.state.collect { state ->
                _activeCall.value = state
            }
        }
    }

    private fun observeGroupSession(session: FlashGroupCallSession) {
        stateCollector?.cancel()
        stateCollector = scope.launch {
            session.state.collect { state ->
                _activeCall.value = state
            }
        }
    }

    private fun startIncoming(peerId: String, frame: CallWireFrame.Invite) {
        val resolvedName = peerNameResolver(peerId)?.ifBlank { null }
            ?: frame.callerName.takeIf { it.isNotBlank() && it != peerId }
            ?: peerId
        val session = newSession(
            callId = frame.callId,
            peerId = peerId,
            peerName = resolvedName,
            direction = FlashCallDirection.INCOMING,
            video = frame.video,
        )
        currentSession = session
        observeSession(session)
    }

    /**
     * The members a call of [groupId] is built for (ERROR-088): everyone listed that [isGroupMember] accepts. Not the
     * live-key gate: a member this device is not paired with and has no session to yet is still a member of the call,
     * dialed and checked when a frame is sent. Each member left out is logged with the reason.
     */
    internal suspend fun callMembers(groupId: String, memberIds: List<String>, purpose: String): CallMembers {
        val members = ArrayList<String>()
        val leftOut = LinkedHashMap<String, String>()
        for (id in memberIds.distinct()) {
            if (id == localDeviceId) continue
            if (isGroupMember(id, groupId)) {
                members += id
            } else {
                // ERROR-095: a legacy group has no way to trust a member this device is not paired with, and the call
                // used to leave that member out with nothing but this line. The caller's screen now names them.
                leftOut[id] = NOT_PAIRED_NOTE
                FlashLog.w("GROUP_CALL", "Group call $purpose group=$groupId: member $id left out (not an active roster member this device trusts or was introduced to)")
            }
        }
        if (leftOut.isNotEmpty()) {
            FlashLog.w("GROUP_CALL", "Group call $purpose group=$groupId: ${members.size} member(s) callable, ${leftOut.size} left out: ${leftOut.keys}")
        }
        return CallMembers(members, leftOut)
    }

    /** The members a call is built for, and the roster members this device had to leave out with the reason for the caller. */
    internal class CallMembers(val members: List<String>, val leftOut: Map<String, String>)

    /** Why [isGroupTrustedPeer] said no, for the log: by construction it implies [isGroupMember], so the two answers name the cause. */
    private suspend fun refusalReason(peerId: String, groupId: String): String =
        if (isGroupMember(peerId, groupId)) {
            "member of the group, but its live session does not present the key its certificate names (or there is no live session)"
        } else {
            "not an active roster member this device trusts or was introduced to"
        }

    /**
     * Every frame of a group call goes through here (ERROR-088). An announcement (invite, presence, accept, join) reveals
     * that the call exists, so it only goes to a roster member whose live session presents the key its certificate names;
     * the session is dialed first when there is none, which is what lets a member that is not paired with this device
     * and not connected yet be invited and join. Everything else (offers, candidates, farewells) is sent as before and
     * never dials: an announcement always precedes a connection, so its session exists by then, and a dial must not hold
     * a leg's lock or the media thread.
     */
    internal suspend fun sendGroupFrame(groupId: String, frame: CallWireFrame, peerId: String): Boolean {
        if (frame is CallWireFrame.GroupInvite || frame is CallWireFrame.GroupPresence ||
            frame is CallWireFrame.GroupAccept || frame is CallWireFrame.GroupJoin || frame is CallWireFrame.GroupQuery
        ) {
            if (!isGroupMember(peerId, groupId)) {
                logRefusal(peerId, groupId, frame, deferred = false)
                return false
            }
            dialIfNeeded(peerId)
            if (!isGroupTrustedPeer(peerId, groupId)) {
                logRefusal(peerId, groupId, frame, deferred = true)
                return false
            }
            return sendFrame(frame, peerId)
        }
        return sendFrame(frame, peerId)
    }

    /**
     * Writes why an announcement was not sent, at most once per [REFUSAL_LOG_INTERVAL_MS] for the same peer, group, frame and
     * cause: the presence tick offers the call to a member that cannot be reached every 4 s for as long as the call lasts.
     */
    private suspend fun logRefusal(peerId: String, groupId: String, frame: CallWireFrame, deferred: Boolean) {
        val key = "$peerId|$groupId|${frame.frameName()}|$deferred"
        val now = SystemTimeSource.nowMs()
        val last = refusalLoggedAt[key]
        if (last != null && now - last < REFUSAL_LOG_INTERVAL_MS) return
        refusalLoggedAt[key] = now
        val reason = refusalReason(peerId, groupId)
        if (deferred) {
            FlashLog.i("GROUP_CALL", "${frame.frameName()} to $peerId for group=$groupId not sent yet: $reason")
        } else {
            FlashLog.w("GROUP_CALL", "${frame.frameName()} to $peerId for group=$groupId not sent: $reason")
        }
    }

    private suspend fun dialIfNeeded(peerId: String): Boolean = try {
        reachPeer(peerId)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        FlashLog.w("GROUP_CALL", "Dial on demand to $peerId failed: ${t.message}")
        false
    }

    private fun CallWireFrame.frameName(): String = this::class.simpleName ?: "frame"

    private fun startIncomingGroup(peerId: String, frame: CallWireFrame.GroupInvite) {
        val session = FlashGroupCallSession(
            callId = frame.callId,
            groupId = frame.groupId,
            groupName = frame.callerName, // or groupId if name unavailable
            direction = FlashCallDirection.INCOMING,
            video = frame.video,
            localDeviceId = localDeviceId,
            localName = localName,
            scope = scope,
            sendFrame = { out, to -> sendGroupFrame(frame.groupId, out, to) },
            onEnded = ::onGroupSessionEnded,
            performanceMode = performanceMode,
            peerNameResolver = peerNameResolver,
            networkBand = networkBand,
            smallerVideoForMany = smallerVideoForMany,
            prioritiseVoice = prioritiseVoice,
        )
        currentGroupSession = session
        observeGroupSession(session)
        session.startIncomingRinging(peerId = peerId, callerName = frame.callerName, members = frame.members)
    }

    private companion object {
        /** The same refusal is written to the log once a minute (see [logRefusal]). */
        const val REFUSAL_LOG_INTERVAL_MS = 60_000L

        /** What a caller reads on the tile of a roster member it cannot call (ERROR-095). */
        const val NOT_PAIRED_NOTE = "Not paired with you"
    }
}
