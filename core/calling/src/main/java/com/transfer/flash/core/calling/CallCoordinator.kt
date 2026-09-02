package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
import com.transfer.flash.core.calling.model.FlashCallEndReason
import com.transfer.flash.core.calling.model.FlashCallLogEntry
import com.transfer.flash.core.calling.model.FlashCallUiState
import com.transfer.flash.core.calling.protocol.CallFrameCodec
import com.transfer.flash.core.calling.protocol.CallWireFrame
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
) : FlashCalling {
    private val _activeCall = MutableStateFlow<FlashCallUiState?>(null)
    override val activeCall: StateFlow<FlashCallUiState?> = _activeCall.asStateFlow()

    /**
     * Renderable media for the live call, if any. Read by the UI layer to bind renderers and the
     * quality badge; the concrete session type stays inside this module.
     */
    override val media: FlashCallMedia?
        get() = currentSession

    @Volatile
    private var currentSession: FlashCallSession? = null

    private var stateCollector: Job? = null

    /** Outgoing call entry point (chat header call / video-call buttons). */
    override suspend fun startCall(peerId: String, peerName: String, video: Boolean): Boolean {
        if (currentSession != null) return false // one call at a time
        val session = newSession(
            callId = UUID.randomUUID().toString(),
            peerId = peerId,
            peerName = peerName,
            direction = FlashCallDirection.OUTGOING,
            video = video,
        )
        currentSession = session
        observeSession(session)
        return session.startOutgoing()
    }

    /**
     * Host calls this with every inbound `FLASH_CALL` text frame. Returns true when
     * the frame was consumed (a live call exists or an invite was handled), false when
     * the text is not a call frame at all.
     *
     * - Invite with no live call → creates the incoming session (RINGING).
     * - Invite while a call is live → auto-declined "busy" so the caller's UI does not hang.
     * - Frame matching the live call id → routed to the session.
     * - Frame for any other call id → dropped (stale frame for a dead call).
     */
    override suspend fun onInboundText(peerId: String, text: String): Boolean {
        val frame = CallFrameCodec.decode(text) ?: return false
        val session = currentSession
        if (session == null) {
            if (frame is CallWireFrame.Invite) {
                startIncoming(peerId = peerId, frame = frame)
                return true
            }
            return false
        }
        if (frame.callId == session.callId) {
            if (frame is CallWireFrame.Invite) {
                return true // duplicate invite — session keys by callId, ignore
            }
            session.onInboundFrame(frame)
            return true
        }
        if (frame is CallWireFrame.Invite) {
            // Busy: auto-decline so the caller's UI does not hang on DIALING. The decline
            // goes to the NEW inviter (peerId), not the live session's peer.
            sendFrame(CallWireFrame.Decline(callId = frame.callId, from = localDeviceId), peerId)
        }
        return false
    }

    /** Local user accepted the incoming call. */
    override suspend fun accept(): Boolean = currentSession?.accept() ?: false

    /** Local user declined the incoming call. */
    override suspend fun decline(): Boolean {
        val session = currentSession ?: return false
        session.decline()
        return true
    }

    /** Local user hung up / ended the call. */
    override suspend fun hangUp(): Boolean {
        val session = currentSession ?: return false
        session.hangUp()
        return true
    }

    override fun toggleMute(): Boolean = currentSession?.toggleMute() ?: false

    override fun toggleCamera(): Boolean = currentSession?.toggleCamera() ?: false

    override suspend fun switchCamera() {
        currentSession?.switchCamera()
    }

    override fun setSpeaker(on: Boolean) {
        currentSession?.setSpeaker(on)
    }

    /** Host calls this when the WS signaling session to the call peer died. */
    override fun onSignalingLost(peerId: String) {
        if (currentSession?.peerId == peerId) {
            currentSession?.onSignalingLost()
        }
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
        val endedAt = System.currentTimeMillis()
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

    private fun startIncoming(peerId: String, frame: CallWireFrame.Invite) {
        val session = newSession(
            callId = frame.callId,
            peerId = peerId,
            peerName = frame.callerName,
            direction = FlashCallDirection.INCOMING,
            video = frame.video,
        )
        currentSession = session
        observeSession(session)
    }
}
