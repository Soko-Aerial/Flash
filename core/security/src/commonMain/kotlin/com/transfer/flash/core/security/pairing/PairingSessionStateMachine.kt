package com.transfer.flash.core.security.pairing

/**
 * Lifecycle phases of one pairing session (C2.6; protocol v2 per ADR-042).
 *
 * | [PairingPhase]             | Side      | Meaning |
 * |----------------------------|-----------|---------|
 * | [Idle]                     | both      | No active session. |
 * | [AwaitingPeerNonce]        | initiator | PAIR_REQUEST (with commitment) sent; waiting for R's nonce. No code yet. |
 * | [AwaitingPeerReveal]       | responder | Valid v2 request; PAIR_NONCE sent; waiting for I to open its commitment. No code yet. |
 * | [RequestReceived]          | responder | Commitment verified; code derived; consent card ready. |
 * | [AwaitingLocalDecision]    | responder | Consent card shown; decision window running. |
 * | [AwaitingPeerConfirmation] | both      | Code derived (I) / local accept given (R); waiting for ACCEPT/CONFIRM/PAIRED. |
 * | [Confirmed]                | both      | Handshake completed; pin material available. |
 * | [DeclinedByPeer]           | initiator | Peer explicitly declined. |
 * | [Expired]                  | both      | Timeout. **Neutral**, deliberately distinct from [Failed]. |
 * | [Failed]                   | both      | Protocol or security violation (commitment mismatch, identity mismatch, v1 peer, …). |
 */
public enum class PairingPhase {
    Idle,
    AwaitingPeerNonce,
    AwaitingPeerReveal,
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
    /** How long a pairing request stays valid after creation (UI-032's 30 s countdown). */
    val requestExpiryMs: Long = DEFAULT_REQUEST_EXPIRY_MS,
    /** Per-side decision window once the consent card is shown. Defaults to the request expiry. */
    val decisionWindowMs: Long = DEFAULT_REQUEST_EXPIRY_MS,
) {
    public companion object {
        public const val DEFAULT_REQUEST_EXPIRY_MS: Long = 30_000L
    }
}

/**
 * Immutable snapshot of one pairing session. The reducer ([PairingSessionStateMachine.reduce]) is a
 * pure function of `(state, event)`: nonces and pinned identities arrive INSIDE events, so every
 * transition is unit-testable without Android, coroutines, randomness or real time.
 */
public data class PairingSessionState(
    val phase: PairingPhase = PairingPhase.Idle,
    val requestId: String? = null,
    val peerDeviceId: String? = null,
    val peerName: String? = null,
    val peerFingerprintHex: String? = null,
    val peerEphemeralPublicKey: ByteArray? = null,
    /** 6-digit code both sides display. Null until BOTH nonces are known (v2). */
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
    /** v2: true on the side that sent PAIR_REQUEST. Fixes the order of the code inputs. */
    val isInitiator: Boolean = false,
    /** v2: this side's ephemeral public key, as sent on the wire. */
    val localEphemeralPublicKey: ByteArray? = null,
    /** v2: this side's nonce (the initiator's stays secret until PAIR_REVEAL). */
    val localNonce: ByteArray? = null,
    /** v2: the peer's nonce once revealed. */
    val peerNonce: ByteArray? = null,
    /** v2 responder: the initiator's commitment from PAIR_REQUEST. */
    val peerCommitHex: String? = null,
) {
    public companion object {
        public val IDLE: PairingSessionState = PairingSessionState()
    }
}

/** Events driving the pairing state machine. Pure data; no coroutines required. */
internal sealed interface PairingSessionEvent {

    /** A PAIR_REQUEST arrived (responder side). [pinnedPeerFingerprintHex] is the key TLS pinned for the sender. */
    data class RequestReceived(
        val requestId: String,
        val peerDeviceId: String,
        val peerName: String,
        val peerFingerprintHex: String,
        val peerEphemeralPublicKey: ByteArray,
        val receivedAtMs: Long,
        val protocolVersion: Int = 1,
        val peerCommitHex: String? = null,
        val pinnedPeerFingerprintHex: String? = null,
        val localEphemeralPublicKey: ByteArray = ByteArray(0),
        val localNonce: ByteArray = ByteArray(0),
    ) : PairingSessionEvent

    /** The local user initiated pairing (initiator side). */
    data class BeginRequested(
        val requestId: String,
        val peerDeviceId: String,
        val peerName: String?,
        val peerFingerprintHex: String,
        val startedAtMs: Long,
        val localEphemeralPublicKey: ByteArray = ByteArray(0),
        val localNonce: ByteArray = ByteArray(0),
    ) : PairingSessionEvent

    /** v2, initiator: PAIR_NONCE arrived. [pinnedPeerFingerprintHex] is the key TLS pinned for the responder. */
    data class PeerNonce(
        val requestId: String,
        val responderFingerprintHex: String,
        val responderEphemeralPublicKey: ByteArray,
        val nonce: ByteArray,
        val pinnedPeerFingerprintHex: String?,
    ) : PairingSessionEvent

    /** v2, responder: PAIR_REVEAL arrived; opens the initiator's commitment. */
    data class PeerReveal(val requestId: String, val nonce: ByteArray) : PairingSessionEvent

    /** The responder UI surfaced the consent dialog; starts the decision window. */
    data class PromptShown(val nowMs: Long) : PairingSessionEvent

    /** Local user accepted (implies the displayed codes matched on their screen). */
    object LocalAccept : PairingSessionEvent

    /** Local user declined/dismissed; returns to Idle without error. */
    object LocalDecline : PairingSessionEvent

    /** PAIR_ACCEPT frame received from the peer (initiator side). */
    data class PeerAccepted(val requestId: String) : PairingSessionEvent

    /** Peer declined. */
    object PeerDeclined : PairingSessionEvent

    /** PAIR_CONFIRM received: hash of the code as seen by the sender, verified constant-time. */
    data class PeerConfirmed(val codeHashHex: String) : PairingSessionEvent

    /** PAIRED frame received: completion material from the peer (initiator side). */
    data class Paired(
        val peerFingerprintHex: String,
        val peerEphemeralPublicKey: ByteArray,
    ) : PairingSessionEvent

    /** Engine clock tick; drives all expiry transitions. */
    data class Tick(val nowMs: Long) : PairingSessionEvent

    /** Unrecoverable protocol problem during an ACTIVE session. */
    data class ProtocolError(val reason: String) : PairingSessionEvent
}

/**
 * Pure reducer for the pairing lifecycle.
 *
 * - Expiry is **neutral**: `Expired ≠ Failed`.
 * - Terminal phases ([Confirmed], [DeclinedByPeer], [Expired], [Failed]) absorb all further events.
 * - Inapplicable events (wrong phase, stale `requestId`, ticks while idle) leave the state UNCHANGED;
 *   the reducer is total and never throws.
 * - Security checks fail CLOSED into [PairingPhase.Failed] with a reason:
 *   `peer-update-required` (a v1 request), `invalid-fingerprint`, `identity-mismatch` (the claimed
 *   fingerprint is not the key TLS pinned for that peer), `commit-mismatch` (the revealed nonce does not
 *   open the commitment), `paired-material-mismatch` (PAIRED disagrees with what the code covered),
 *   `code-hash-mismatch`.
 */
internal object PairingSessionStateMachine {

    const val REASON_UPDATE_REQUIRED: String = "peer-update-required"
    const val REASON_IDENTITY_MISMATCH: String = "identity-mismatch"
    const val REASON_COMMIT_MISMATCH: String = "commit-mismatch"
    const val REASON_PAIRED_MISMATCH: String = "paired-material-mismatch"
    const val REASON_INVALID_FINGERPRINT: String = "invalid-fingerprint"
    const val REASON_CODE_HASH_MISMATCH: String = "code-hash-mismatch"

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
                val base = state.copy(
                    requestId = event.requestId,
                    peerDeviceId = event.peerDeviceId,
                    peerName = event.peerName,
                    peerFingerprintHex = event.peerFingerprintHex,
                    isInitiator = false,
                )
                if (event.protocolVersion < PairingV2.PROTOCOL_VERSION || event.peerCommitHex.isNullOrBlank()) {
                    return base.failed(REASON_UPDATE_REQUIRED)
                }
                if (NumericComparisonCode.normalizeHex(localFingerprintHex).isEmpty() ||
                    NumericComparisonCode.normalizeHex(event.peerFingerprintHex).isEmpty()
                ) {
                    return base.failed(REASON_INVALID_FINGERPRINT)
                }
                if (!PairingV2.matchesPinnedIdentity(event.peerFingerprintHex, event.pinnedPeerFingerprintHex)) {
                    return base.failed(REASON_IDENTITY_MISMATCH)
                }
                base.copy(
                    phase = PairingPhase.AwaitingPeerReveal,
                    peerEphemeralPublicKey = event.peerEphemeralPublicKey,
                    peerCommitHex = event.peerCommitHex,
                    localEphemeralPublicKey = event.localEphemeralPublicKey,
                    localNonce = event.localNonce,
                    code6 = null,
                    expectedCodeHashHex = null,
                    expiresAtMs = event.receivedAtMs + timeouts.requestExpiryMs,
                    decisionDeadlineMs = null,
                    peerAccepted = false,
                )
            }

            is PairingSessionEvent.PeerReveal -> {
                if (state.phase != PairingPhase.AwaitingPeerReveal || event.requestId != state.requestId) return state
                val commit = state.peerCommitHex
                val peerFp = state.peerFingerprintHex
                val peerEpk = state.peerEphemeralPublicKey
                val localEpk = state.localEphemeralPublicKey
                val localNonce = state.localNonce
                if (commit == null || peerFp == null || peerEpk == null || localEpk == null || localNonce == null ||
                    !PairingV2.commitMatches(commit, peerFp, peerEpk, event.nonce)
                ) {
                    return state.failed(REASON_COMMIT_MISMATCH)
                }
                val code = PairingV2.deriveCode(
                    initiatorFingerprintHex = peerFp,
                    responderFingerprintHex = localFingerprintHex,
                    initiatorEphemeralPublicKey = peerEpk,
                    responderEphemeralPublicKey = localEpk,
                    initiatorNonce = event.nonce,
                    responderNonce = localNonce,
                )
                state.copy(
                    phase = PairingPhase.RequestReceived,
                    peerNonce = event.nonce,
                    code6 = code,
                    expectedCodeHashHex = NumericComparisonCode.confirmationHashHex(code),
                )
            }

            is PairingSessionEvent.BeginRequested -> {
                if (state.phase != PairingPhase.Idle) return state
                val base = state.copy(
                    requestId = event.requestId,
                    peerDeviceId = event.peerDeviceId,
                    peerName = event.peerName,
                    peerFingerprintHex = event.peerFingerprintHex,
                    isInitiator = true,
                )
                if (NumericComparisonCode.normalizeHex(localFingerprintHex).isEmpty() ||
                    NumericComparisonCode.normalizeHex(event.peerFingerprintHex).isEmpty()
                ) {
                    return base.failed(REASON_INVALID_FINGERPRINT)
                }
                base.copy(
                    phase = PairingPhase.AwaitingPeerNonce,
                    localEphemeralPublicKey = event.localEphemeralPublicKey,
                    localNonce = event.localNonce,
                    code6 = null,
                    expectedCodeHashHex = null,
                    expiresAtMs = event.startedAtMs + timeouts.requestExpiryMs,
                    decisionDeadlineMs = null,
                    peerAccepted = false,
                )
            }

            is PairingSessionEvent.PeerNonce -> {
                if (state.phase != PairingPhase.AwaitingPeerNonce || event.requestId != state.requestId) return state
                if (!PairingV2.matchesPinnedIdentity(event.responderFingerprintHex, event.pinnedPeerFingerprintHex)) {
                    return state.failed(REASON_IDENTITY_MISMATCH)
                }
                val localEpk = state.localEphemeralPublicKey
                val localNonce = state.localNonce
                if (localEpk == null || localNonce == null) return state.failed(REASON_COMMIT_MISMATCH)
                val code = PairingV2.deriveCode(
                    initiatorFingerprintHex = localFingerprintHex,
                    responderFingerprintHex = event.responderFingerprintHex,
                    initiatorEphemeralPublicKey = localEpk,
                    responderEphemeralPublicKey = event.responderEphemeralPublicKey,
                    initiatorNonce = localNonce,
                    responderNonce = event.nonce,
                )
                state.copy(
                    phase = PairingPhase.AwaitingPeerConfirmation,
                    peerFingerprintHex = event.responderFingerprintHex,
                    peerEphemeralPublicKey = event.responderEphemeralPublicKey,
                    peerNonce = event.nonce,
                    code6 = code,
                    expectedCodeHashHex = NumericComparisonCode.confirmationHashHex(code),
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
                    state.isInitiator &&
                    event.requestId == state.requestId
                ) {
                    state.copy(peerAccepted = true)
                } else {
                    state
                }

            PairingSessionEvent.PeerDeclined ->
                if (state.isInitiator &&
                    (state.phase == PairingPhase.AwaitingPeerConfirmation || state.phase == PairingPhase.AwaitingPeerNonce)
                ) {
                    state.copy(phase = PairingPhase.DeclinedByPeer)
                } else {
                    state
                }

            is PairingSessionEvent.PeerConfirmed -> {
                // Responder only: the initiator proves it derived the same code.
                if (state.phase != PairingPhase.AwaitingPeerConfirmation || state.isInitiator) return state
                val expected = state.expectedCodeHashHex
                if (expected != null && NumericComparisonCode.hashesEqual(expected, event.codeHashHex)) {
                    state.copy(phase = PairingPhase.Confirmed, decisionDeadlineMs = null)
                } else {
                    state.failed(REASON_CODE_HASH_MISMATCH)
                }
            }

            is PairingSessionEvent.Paired -> {
                // Initiator only, and only once the code exists: PAIRED must repeat exactly the identity and
                // ephemeral key that the code covered, or the session key would not be the verified one.
                if (state.phase != PairingPhase.AwaitingPeerConfirmation || !state.isInitiator || !state.peerAccepted) {
                    return state
                }
                val expectedFp = state.peerFingerprintHex
                val expectedEpk = state.peerEphemeralPublicKey
                if (NumericComparisonCode.normalizeHex(event.peerFingerprintHex).isEmpty()) {
                    return state.failed(REASON_INVALID_FINGERPRINT)
                }
                if (expectedFp == null || expectedEpk == null ||
                    !PairingV2.matchesPinnedIdentity(event.peerFingerprintHex, expectedFp) ||
                    !event.peerEphemeralPublicKey.contentEquals(expectedEpk)
                ) {
                    return state.failed(REASON_PAIRED_MISMATCH)
                }
                state.copy(phase = PairingPhase.Confirmed, decisionDeadlineMs = null)
            }

            is PairingSessionEvent.Tick -> {
                if (!state.isActive()) return state
                val pastRequestExpiry = state.expiresAtMs?.let { event.nowMs >= it } == true
                val pastDecisionWindow =
                    state.phase == PairingPhase.AwaitingLocalDecision &&
                        state.decisionDeadlineMs?.let { event.nowMs >= it } == true
                if (pastRequestExpiry || pastDecisionWindow) state.copy(phase = PairingPhase.Expired) else state
            }

            is PairingSessionEvent.ProtocolError ->
                if (state.isActive()) state.failed(event.reason) else state
        }
    }

    private fun PairingSessionState.failed(reason: String): PairingSessionState =
        copy(phase = PairingPhase.Failed, failureReason = reason)

    private fun PairingPhase.isTerminal(): Boolean =
        this == PairingPhase.Confirmed ||
            this == PairingPhase.DeclinedByPeer ||
            this == PairingPhase.Expired ||
            this == PairingPhase.Failed

    private fun PairingSessionState.isActive(): Boolean =
        phase == PairingPhase.AwaitingPeerNonce ||
            phase == PairingPhase.AwaitingPeerReveal ||
            phase == PairingPhase.RequestReceived ||
            phase == PairingPhase.AwaitingLocalDecision ||
            phase == PairingPhase.AwaitingPeerConfirmation
}
