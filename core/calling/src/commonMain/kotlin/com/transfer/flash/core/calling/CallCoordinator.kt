@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallLogEntry
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.model.OngoingGroupCallUi
import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import com.transfer.flash.core.common.perf.FlashPerformanceMode
import com.transfer.flash.core.common.time.SystemTimeSource
import kotlin.concurrent.Volatile
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
) : FlashCalling {
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
        if (currentSession != null || currentGroupSession != null) return false // one call at a time
        val trustedMembers = memberIds.filter { isTrustedPeer(it) && it != localDeviceId }
        if (trustedMembers.isEmpty()) return false

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
            sendFrame = sendFrame,
            onEnded = { ended ->
                if (currentGroupSession === ended) {
                    currentGroupSession = null
                    stateCollector?.cancel()
                    stateCollector = null
                    _activeCall.value = ended.state.value
                    scope.launch {
                        kotlinx.coroutines.delay(2_000L)
                        if (currentSession == null && currentGroupSession == null) {
                            _activeCall.value = null
                        }
                    }
                }
            },
            performanceMode = performanceMode,
            peerNameResolver = peerNameResolver,
        )
        currentGroupSession = session
        observeGroupSession(session)
        return session.startOutgoing(trustedMembers)
    }

    /** Joins an ongoing group call announced by peers. */
    override suspend fun joinGroupCall(
        groupId: String,
        callId: String,
        memberIds: List<String>,
        video: Boolean,
    ): Boolean {
        if (currentSession != null || currentGroupSession != null) return false
        val trustedMembers = memberIds.filter { isTrustedPeer(it) && it != localDeviceId }
        if (trustedMembers.isEmpty()) return false

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
            sendFrame = sendFrame,
            onEnded = { ended ->
                if (currentGroupSession === ended) {
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
            },
            performanceMode = performanceMode,
            peerNameResolver = peerNameResolver,
        )
        currentGroupSession = session
        observeGroupSession(session)

        val currentMap = _ongoingGroupCalls.value.toMutableMap()
        currentMap.remove(groupId)
        _ongoingGroupCalls.value = currentMap

        return session.joinExisting(trustedMembers)
    }

    /** Queries online members of a group to discover if an active call is ongoing. */
    override suspend fun queryGroupCall(groupId: String, memberIds: List<String>) {
        val trustedMembers = memberIds.filter { isTrustedPeer(it) && it != localDeviceId }
        val frame = CallWireFrame.GroupQuery(from = localDeviceId, groupId = groupId)
        trustedMembers.forEach { peerId ->
            sendFrame(frame, peerId)
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

        // SENTINEL: Fail closed on claimed-author vs transport-peer mismatch for 1:1 call frames
        if (frame is CallWireFrame.Invite ||
            frame is CallWireFrame.Accept ||
            frame is CallWireFrame.Decline ||
            frame is CallWireFrame.Hangup ||
            frame is CallWireFrame.Offer ||
            frame is CallWireFrame.Answer ||
            frame is CallWireFrame.IceCandidate) {
            if (frame.from != peerId) return false
        }

        if (frame is CallWireFrame.GroupPresence) {
            if (currentGroupSession?.callId == frame.callId) return true
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
            val liveSession = currentGroupSession
            if (liveSession != null && liveSession.groupId == frame.groupId && !liveSession.isSessionEnded) {
                val count = liveSession.countConnectedParticipants() + 1
                sendFrame(
                    CallWireFrame.GroupPresence(
                        callId = liveSession.callId,
                        from = localDeviceId,
                        groupId = liveSession.groupId,
                        callerName = liveSession.groupName,
                        video = liveSession.video,
                        participantCount = count,
                    ),
                    peerId,
                )
                return true
            }
            return false
        }

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
            if (!isTrustedPeer(peerId)) {
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
        currentGroupSession?.let {
            it.hangUp()
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
            sendFrame = sendFrame,
            onEnded = { ended ->
                if (currentGroupSession === ended) {
                    currentGroupSession = null
                    stateCollector?.cancel()
                    stateCollector = null
                    _activeCall.value = ended.state.value
                    scope.launch {
                        kotlinx.coroutines.delay(2_000L)
                        if (currentSession == null && currentGroupSession == null) {
                            _activeCall.value = null
                        }
                    }
                }
            },
            performanceMode = performanceMode,
            peerNameResolver = peerNameResolver,
        )
        currentGroupSession = session
        observeGroupSession(session)
        session.startIncomingRinging(peerId = peerId, callerName = frame.callerName)
    }
}
