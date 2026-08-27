package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.common.time.FlashTimeSource

/**
 * Lifecycle phases of one pairing session (C2.6). Replaces the UI demo state
 * machine in `docs/ui/profile-ui.md` (`FlashPairingPhase`) with an engine-owned,
 * timeout-aware machine.
 *
 * Mapping to the demo enum in docs/ui/profile-ui.md:
 *
 * | [PairingPhase]            | Demo `FlashPairingPhase`      | Notes |
 * |---------------------------|-------------------------------|-------|
 * | [Idle]                    | `Idle`                        | No active session. |
 * | [RequestReceived]         | `RequestReceived`             | Valid PAIR_REQUEST arrived; consent card content ready. |
 * | [AwaitingLocalDecision]   | `RequestReceived` (sub-state) | Demo collapsed this into `RequestReceived`; engine needs the distinction to enforce the per-side decision window once the dialog is actually shown. |
 * | [AwaitingPeerConfirmation]| `AwaitingPeerConfirmation`    | Local decision made; waiting for peer ACCEPT/CONFIRM/PAIRED. |
 * | [Confirmed]               | `Paired`                      | Handshake completed; pin material available. |
 * | [DeclinedByPeer]          | `Declined`                    | Peer explicitly declined (engine-reported, per profile-ui.md). |
 * | [Expired]                 | `Expired`                     | Timeout — **neutral** outcome, deliberately distinct from [Failed]. |
 * | [Failed]                  | *(no demo equivalent)*        | Protocol error (e.g., confirmation-hash mismatch); surfaces via error states, not the pairing card. |
 */
public enum class PairingPhase {
    Idle,
    RequestReceived,
    AwaitingLocalDecision,
    AwaitingPeerConfirmation,
    Confirmed,
    DeclinedByPeer,
    Expired,
    Failed,
}

/** Engine-owned timeout configuration for a pairing session. */
public data class PairingTimeouts(
    /**
     * How long a pairing request stays valid after creation. Mirrors the 30 s
     * countdown shown by UI-032 (`expiresInSeconds = 30` in profile-ui.md).
     */
    val requestExpiryMs: Long = DEFAULT_REQUEST_EXPIRY_MS,
    /**
     * Per-side decision window: how long the local user may deliberate after the
     * dialog is shown before the session expires. Configurable engine policy;
     * defaults to the request expiry so both clocks agree initially.
     */
    val decisionWindowMs: Long = DEFAULT_REQUEST_EXPIRY_MS,
) {
    public companion object {
        public const val DEFAULT_REQUEST_EXPIRY_MS: Long = 30_000L
    }
}

/**
 * Immutable snapshot of one pairing session. All fields are plain JVM types; the
 * reducer ([PairingSessionStateMachine.reduce]) is a pure function of
 * `(state, event)` so every transition path can be unit-tested without Android,
 * coroutines, or real time.
 */
public data class PairingSessionState(
    val phase: PairingPhase = PairingPhase.Idle,
    val requestId: String? = null,
    val peerDeviceId: String? = null,
    val peerName: String? = null,
    val peerFingerprintHex: String? = null,
    val peerEphemeralPublicKey: ByteArray? = null,
    /** 6-digit code both sides display while deciding (null when no active request). */
    val code6: String? = null,
    /** SHA-256 hex of [code6]; incoming PAIR_CONFIRM must match this constant-time. */
    val expectedCodeHashHex: String? = null,
    /** Absolute deadline for the whole request ([PairingTimeouts.requestExpiryMs]). */
    val expiresAtMs: Long? = null,
    /** Absolute deadline for the local user's decision (may be earlier than [expiresAtMs]). */
    val decisionDeadlineMs: Long? = null,
    /** True once the peer sent PAIR_ACCEPT (initiator-side progress marker). */
    val peerAccepted: Boolean = false,
    /** Human-readable reason when [phase] is [PairingPhase.Failed]. */
    val failureReason: String? = null,
) {
    public companion object {
        public val IDLE: PairingSessionState = PairingSessionState()
    }
}

/** Events driving the pairing state machine. Pure data; no coroutines required. */
internal sealed interface PairingSessionEvent {

    /** A validated PAIR_REQUEST arrived (responder side). */
    data class RequestReceived(
        val requestId: String,
        val peerDeviceId: String,
        val peerName: String,
        val peerFingerprintHex: String,
        val peerEphemeralPublicKey: ByteArray,
        val receivedAtMs: Long,
    ) : PairingSessionEvent

    /** The local user initiated pairing with a peer (initiator side). */
    data class BeginRequested(
        val requestId: String,
        val peerDeviceId: String,
        val peerName: String?,
        val peerFingerprintHex: String,
        val startedAtMs: Long,
    ) : PairingSessionEvent

    /** The responder UI surfaced the consent dialog; starts the decision window. */
    data class PromptShown(val nowMs: Long) : PairingSessionEvent

    /** Local user accepted (implies the displayed codes matched on their screen). */
    object LocalAccept : PairingSessionEvent

    /** Local user declined/dismissed; returns to Idle without error. */
    object LocalDecline : PairingSessionEvent

    /** PAIR_ACCEPT frame received from the peer (initiator side). */
    data class PeerAccepted(val requestId: String) : PairingSessionEvent

    /** Peer declined (frame-level decline lands with C4/C6 wire encoding). */
    object PeerDeclined : PairingSessionEvent

    /**
     * PAIR_CONFIRM received: carries the hash of the code as seen by the sender;
     * verified constant-time against [PairingSessionState.expectedCodeHashHex].
     */
    data class PeerConfirmed(val codeHashHex: String) : PairingSessionEvent

    /** PAIRED frame received: completion material from the peer (initiator side). */
    data class Paired(
        val peerFingerprintHex: String,
        val peerEphemeralPublicKey: ByteArray,
    ) : PairingSessionEvent

    /** Engine clock tick; drives all expiry transitions. */
    data class Tick(val nowMs: Long) : PairingSessionEvent

    /** Unrecoverable protocol problem during an ACTIVE session (bad frame order, …). */
    data class ProtocolError(val reason: String) : PairingSessionEvent
}

/**
 * Pure reducer for the pairing lifecycle. Time never enters implicitly: callers
 * supply timestamps inside events, and deadlines are computed here from the
 * injected [PairingTimeouts] — expiry stays deterministic under test and the owning
 * engine ([FlashPairingProtocol] / C7) drives ticks from its [FlashTimeSource].
 *
 * Design notes:
 * - Expiry is **neutral**: `Expired ≠ Failed`. Expired means "nobody answered in
 *   time" (retryable, silent); Failed means "protocol violated" (surfaces an error).
 * - Terminal phases ([Confirmed], [DeclinedByPeer], [Expired], [Failed]) absorb all
 *   further events until the engine resets the session.
 * - Inapplicable events (wrong phase, stale/wrong `requestId`, ticks while idle or
 *   terminal) leave the state UNCHANGED — the reducer is total and never throws.
 * - Local decline is NOT an error: it resets to Idle (the demo dismissed the card).
 */
internal object PairingSessionStateMachine {

    fun initial(): PairingSessionState = PairingSessionState.IDLE

    fun reduce(
        state: PairingSessionState,
        event: PairingSessionEvent,
        timeouts: PairingTimeouts,
        localFingerprintHex: String,
    ): PairingSessionState {
        if (state.phase.isTerminal()) return state

        return when (event) {
            is PairingSessionEvent.RequestReceived -> {
                if (state.phase != PairingPhase.Idle) return state
                val code = NumericComparisonCode.derive(localFingerprintHex, event.peerFingerprintHex)
                state.copy(
                    phase = PairingPhase.RequestReceived,
                    requestId = event.requestId,
                    peerDeviceId = event.peerDeviceId,
                    peerName = event.peerName,
                    peerFingerprintHex = event.peerFingerprintHex,
                    peerEphemeralPublicKey = event.peerEphemeralPublicKey,
                    code6 = code,
                    expectedCodeHashHex = NumericComparisonCode.confirmationHashHex(code),
                    expiresAtMs = event.receivedAtMs + timeouts.requestExpiryMs,
                    decisionDeadlineMs = null,
                    peerAccepted = false,
                )
            }

            is PairingSessionEvent.BeginRequested -> {
                if (state.phase != PairingPhase.Idle) return state
                val code = NumericComparisonCode.derive(localFingerprintHex, event.peerFingerprintHex)
                state.copy(
                    phase = PairingPhase.AwaitingPeerConfirmation,
                    requestId = event.requestId,
                    peerDeviceId = event.peerDeviceId,
                    peerName = event.peerName,
                    peerFingerprintHex = event.peerFingerprintHex,
                    code6 = code,
                    expectedCodeHashHex = NumericComparisonCode.confirmationHashHex(code),
                    expiresAtMs = event.startedAtMs + timeouts.requestExpiryMs,
                    decisionDeadlineMs = null,
                    peerAccepted = false,
                )
            }

            is PairingSessionEvent.PromptShown ->
                if (state.phase == PairingPhase.RequestReceived) {
                    state.copy(
                        phase = PairingPhase.AwaitingLocalDecision,
                        decisionDeadlineMs = event.nowMs + timeouts.decisionWindowMs,
                    )
                } else {
                    state
                }

            PairingSessionEvent.LocalAccept ->
                if (state.phase == PairingPhase.RequestReceived ||
                    state.phase == PairingPhase.AwaitingLocalDecision
                ) {
                    state.copy(phase = PairingPhase.AwaitingPeerConfirmation, decisionDeadlineMs = null)
                } else {
                    state
                }

            PairingSessionEvent.LocalDecline ->
                if (state.isActive()) PairingSessionState.IDLE else state

            is PairingSessionEvent.PeerAccepted ->
                if (state.phase == PairingPhase.AwaitingPeerConfirmation &&
                    event.requestId == state.requestId
                ) {
                    state.copy(peerAccepted = true)
                } else {
                    state
                }

            PairingSessionEvent.PeerDeclined ->
                // Only meaningful on the initiator side (we sent PAIR_REQUEST, peer
                // answered no). In responder-side phases this frame is protocol noise:
                // per the total-reducer principle above, leave the state UNCHANGED.
                if (state.phase == PairingPhase.AwaitingPeerConfirmation) {
                    state.copy(phase = PairingPhase.DeclinedByPeer)
                } else {
                    state
                }

            is PairingSessionEvent.PeerConfirmed -> {
                if (state.phase != PairingPhase.AwaitingPeerConfirmation) return state
                val expected = state.expectedCodeHashHex
                if (expected != null && NumericComparisonCode.hashesEqual(expected, event.codeHashHex)) {
                    state.copy(phase = PairingPhase.Confirmed, decisionDeadlineMs = null)
                } else {
                    // Constant-time comparison failed: someone derived a different code.
                    state.copy(phase = PairingPhase.Failed, failureReason = "code-hash-mismatch")
                }
            }

            is PairingSessionEvent.Paired -> {
                if (state.phase != PairingPhase.AwaitingPeerConfirmation) return state
                state.copy(
                    phase = PairingPhase.Confirmed,
                    peerFingerprintHex = event.peerFingerprintHex,
                    peerEphemeralPublicKey = event.peerEphemeralPublicKey,
                    decisionDeadlineMs = null,
                )
            }

            is PairingSessionEvent.Tick -> {
                if (!state.isActive()) return state
                val pastRequestExpiry = state.expiresAtMs?.let { event.nowMs >= it } == true
                val pastDecisionWindow =
                    state.phase == PairingPhase.AwaitingLocalDecision &&
                        state.decisionDeadlineMs?.let { event.nowMs >= it } == true
                if (pastRequestExpiry || pastDecisionWindow) {
                    state.copy(phase = PairingPhase.Expired)
                } else {
                    state
                }
            }

            is PairingSessionEvent.ProtocolError ->
                if (state.isActive()) {
                    state.copy(phase = PairingPhase.Failed, failureReason = event.reason)
                } else {
                    state
                }
        }
    }

    private fun PairingPhase.isTerminal(): Boolean =
        this == PairingPhase.Confirmed ||
            this == PairingPhase.DeclinedByPeer ||
            this == PairingPhase.Expired ||
            this == PairingPhase.Failed

    private fun PairingSessionState.isActive(): Boolean =
        phase == PairingPhase.RequestReceived ||
            phase == PairingPhase.AwaitingLocalDecision ||
            phase == PairingPhase.AwaitingPeerConfirmation
}
