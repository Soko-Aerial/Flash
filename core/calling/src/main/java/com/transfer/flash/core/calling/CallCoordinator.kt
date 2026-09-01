package com.transfer.flash.core.calling

import com.transfer.flash.core.calling.model.FlashCallDirection
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
 * Bridges the pure call session to the host app: it keys sessions by call id, routes
 * inbound `FLASH_CALL` frames, enforces the one-call-at-a-time rule (a second invite
 * while a call is live is auto-declined "busy"), and exposes the active call's UI
 * state as a single [StateFlow] the navigation layer can push/pop a route on.
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
) {
    private val _activeCall = MutableStateFlow<FlashCallUiState?>(null)
    public val activeCall: StateFlow<FlashCallUiState?> = _activeCall.asStateFlow()

    /** The live session, if any. Read by the UI layer for track access (renderers). */
    public val session: FlashCallSession?
        get() = currentSession

    @Volatile
    private var currentSession: FlashCallSession? = null

    private var stateCollector: Job? = null

    /** Outgoing call entry point (chat header call / video-call buttons). */
    public suspend fun startCall(peerId: String, peerName: String, video: Boolean): Boolean {
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
    public suspend fun onInboundText(peerId: String, text: String): Boolean {
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
    public suspend fun accept(): Boolean = currentSession?.accept() ?: false

    /** Local user declined the incoming call. */
    public suspend fun decline(): Boolean {
        val session = currentSession ?: return false
        session.decline()
        return true
    }

    /** Local user hung up / ended the call. */
    public suspend fun hangUp(): Boolean {
        val session = currentSession ?: return false
        session.hangUp()
        return true
    }

    /** Host calls this when the WS signaling session to the call peer died. */
    public fun onSignalingLost(peerId: String) {
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
                onEnded = { ended ->
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
