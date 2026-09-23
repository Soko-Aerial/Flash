@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.common.id.UuidIdGenerator
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.security.crypto.secureRandomBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI-facing pairing lifecycle events consumed by the Nearby tab and the
 * `FlashPairingDialog` (UI-032). Mirrors the plan's target abstraction:
 * `sealed interface FlashPairingEvent { RequestReceived(peer, transport, code6);
 * PeerAccepted; PeerDeclined; Expired; Confirmed }` (+ [Failed] for protocol
 * errors, which have no demo-card representation).
 */
public sealed interface FlashPairingEvent {

    /** A peer wants to pair. Carries everything the consent card renders. */
    public data class RequestReceived(
        val requestId: String,
        val peerDeviceId: String,
        val peerName: String,
        val code6: String,
        val expiresAtMs: Long,
    ) : FlashPairingEvent

    /** Peer agreed (PAIR_ACCEPT received on the initiator side). */
    public data class PeerAccepted(val requestId: String) : FlashPairingEvent

    /** Peer explicitly declined. */
    public data class PeerDeclined(val requestId: String?) : FlashPairingEvent

    /** Neutral timeout — nobody misbehaved; retry is safe. */
    public data class Expired(val requestId: String?) : FlashPairingEvent

    /**
     * Handshake completed. Carries the material to pin via TOFU
     * ([com.transfer.flash.core.security.trust.pinned.TofuPolicy]).
     */
    public data class Confirmed(
        val requestId: String,
        val fingerprintHex: String,
        val ephemeralPubKey: ByteArray,
    ) : FlashPairingEvent

    /** Protocol violation (e.g., numeric-comparison hash mismatch). */
    public data class Failed(val requestId: String?, val reason: String) : FlashPairingEvent
}

/**
 * Thin pairing orchestrator (C2.6) over the pure state machine
 * ([PairingSessionStateMachine]), the shared display-code derivation
 * ([NumericComparisonCode]) and the wire frames ([FlashPairingFrame]).
 *
 * This matches the plan's target abstraction for C2:
 * ```text
 * interface FlashPairingProtocol { beginRequest(peer); respondAccept(); respondDecline(); events: Flow<FlashPairingEvent> }
 * ```
 * with two additive integration points kept explicit because transport wiring lands
 * in C4/C6:
 * - [onFrame]: the connection layer feeds decoded inbound frames here.
 * - [onTick]: the owning engine drives expiry from its own scheduler/time source;
 *   this class owns NO coroutines, NO scope and NO clock of its own.
 *
 * Outbound frames are handed to the injected [sendFrame] sink (fake-friendly; no
 * Android types). The sink must be non-blocking/enqueue-only — it is invoked from
 * non-suspend contexts; actual socket I/O belongs to the transport's own queue
 * (C4/C6). Crypto material arrives via injected providers so this class can
 * be unit-tested while `...core.security.crypto` (concurrent workstream) supplies
 * the real key sources later.
 *
 * Expiry semantics follow [PairingSessionStateMachine]: Expired is neutral, distinct
 * from Failed. The engine decides whether to auto-retry or resurface UI.
 */
public interface FlashPairingProtocol {
    /** Hot stream of pairing events; never completes. */
    public val events: Flow<FlashPairingEvent>

    /** Current session snapshot (for UI state restoration / tests). */
    public val session: StateFlow<PairingSessionState>

    /** Initiates pairing with [peer]; sends PAIR_REQUEST. */
    public fun beginRequest(
        peerDeviceId: String,
        peerName: String?,
        peerFingerprintHex: String,
    ): FlashResult<Unit>

    /** Local user accepted the displayed code match; sends PAIR_ACCEPT. */
    public suspend fun respondAccept(): FlashResult<Unit>

    /** Local user declined/dismissed; resets locally (decline frame lands C4/C6). */
    public suspend fun respondDecline(): FlashResult<Unit>

    /** Transport layer entry point for decoded inbound frames. */
    public suspend fun onFrame(frame: FlashPairingFrame)

    /** Engine clock tick; drives request/decision-window expiry. */
    public fun onTick(nowMs: Long)
}

public class DefaultFlashPairingProtocol(
    private val localFingerprintHex: String,
    private val localDeviceId: String,
    private val localName: String,
    private val localModel: String,
    private val ephemeralPublicKeyProvider: () -> ByteArray,
    private val sendFrame: (FlashPairingFrame) -> Unit,
    /**
     * The fingerprint TLS pinned for a peer device (the trust store's pin), or null when none. v2 refuses
     * any pairing whose claimed fingerprint is not this key (ADR-042): the code must cover the identity
     * the transport actually authenticated.
     */
    private val peerIdentityPin: (peerDeviceId: String) -> String?,
    private val timeSource: FlashTimeSource,
    private val timeouts: PairingTimeouts = PairingTimeouts(),
) : FlashPairingProtocol {

    private val _events = MutableSharedFlow<FlashPairingEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<FlashPairingEvent> = _events.asSharedFlow()

    private val _session = MutableStateFlow(PairingSessionStateMachine.initial())
    override val session: StateFlow<PairingSessionState> = _session.asStateFlow()

    override fun beginRequest(
        peerDeviceId: String,
        peerName: String?,
        peerFingerprintHex: String,
    ): FlashResult<Unit> {
        if (_session.value.phase != PairingPhase.Idle) {
            return FlashResult.Failure(FlashError.Unknown("Pairing already in progress", null))
        }
        if (NumericComparisonCode.normalizeHex(peerFingerprintHex).isEmpty()) {
            return FlashResult.Failure(FlashError.Unknown("Peer fingerprint missing", null))
        }
        val requestId = newRequestId()
        val now = timeSource.nowMs()
        val localEpk = ephemeralPublicKeyProvider()
        val nonce = PairingV2.newNonce()
        reduceSession(
            PairingSessionEvent.BeginRequested(
                requestId = requestId,
                peerDeviceId = peerDeviceId,
                peerName = peerName,
                peerFingerprintHex = peerFingerprintHex,
                startedAtMs = now,
                localEphemeralPublicKey = localEpk,
                localNonce = nonce,
            ),
        )
        if (_session.value.phase == PairingPhase.Failed) {
            val reason = _session.value.failureReason ?: "request-failed"
            emit(FlashPairingEvent.Failed(requestId, reason))
            return FlashResult.Failure(FlashError.Unknown(reason, null))
        }
        return try {
            sendFrame(
                FlashPairingFrame.PairRequest(
                    requestId = requestId,
                    senderDeviceId = localDeviceId,
                    senderName = localName,
                    senderModel = localModel,
                    senderFingerprintHex = localFingerprintHex,
                    senderEphemeralPublicKey = localEpk,
                    createdAt = now,
                    protocolVersion = PairingV2.PROTOCOL_VERSION,
                    // The nonce stays secret until PAIR_REVEAL; only the commitment goes out now.
                    commitHex = PairingV2.commitHex(localFingerprintHex, localEpk, nonce),
                ),
            )
            FlashResult.Success(Unit)
        } catch (t: Throwable) {
            resetToIdle()
            FlashResult.Failure(FlashError.Unknown(t.message ?: "send failed", t))
        }
    }

    override suspend fun respondAccept(): FlashResult<Unit> {
        val state = _session.value
        val requestId = state.requestId
            ?: return FlashResult.Failure(FlashError.Unknown("No pairing request", null))
        if (!state.isActivePhase()) {
            return FlashResult.Failure(FlashError.Unknown("Not awaiting local decision", null))
        }
        reduceSession(PairingSessionEvent.LocalAccept)
        return try {
            sendFrame(FlashPairingFrame.PairAccept(requestId))
            FlashResult.Success(Unit)
        } catch (t: Throwable) {
            FlashResult.Failure(FlashError.Unknown(t.message ?: "send failed", t))
        }
    }

    override suspend fun respondDecline(): FlashResult<Unit> {
        // Wire-level decline notification is deferred to C4/C6 encoding; declining is
        // silent-by-omission until then (peer side expires instead of DeclinedByPeer).
        reduceSession(PairingSessionEvent.LocalDecline)
        return FlashResult.Success(Unit)
    }

    override suspend fun onFrame(frame: FlashPairingFrame) {
        when (frame) {
            is FlashPairingFrame.PairRequest -> {
                val before = _session.value
                val localEpk = ephemeralPublicKeyProvider()
                val nonce = PairingV2.newNonce()
                reduceSession(
                    PairingSessionEvent.RequestReceived(
                        requestId = frame.requestId,
                        peerDeviceId = frame.senderDeviceId,
                        peerName = frame.senderName,
                        peerFingerprintHex = frame.senderFingerprintHex,
                        peerEphemeralPublicKey = frame.senderEphemeralPublicKey,
                        receivedAtMs = timeSource.nowMs(),
                        protocolVersion = frame.protocolVersion,
                        peerCommitHex = frame.commitHex,
                        pinnedPeerFingerprintHex = peerIdentityPin(frame.senderDeviceId),
                        localEphemeralPublicKey = localEpk,
                        localNonce = nonce,
                    ),
                )
                val s = _session.value
                when {
                    before.phase != PairingPhase.Idle || s.requestId != frame.requestId ->
                        emit(FlashPairingEvent.Failed(frame.requestId, "request-rejected"))
                    s.phase == PairingPhase.AwaitingPeerReveal ->
                        // R reveals its nonce first; the code only exists once I opens its commitment.
                        sendOrFail(
                            FlashPairingFrame.PairNonce(
                                requestId = frame.requestId,
                                responderFingerprintHex = localFingerprintHex,
                                responderEphemeralPublicKey = localEpk,
                                nonce = nonce,
                            ),
                        )
                    s.phase == PairingPhase.Failed ->
                        emit(FlashPairingEvent.Failed(frame.requestId, s.failureReason ?: "request-rejected"))
                }
            }

            is FlashPairingFrame.PairNonce -> {
                val before = _session.value
                reduceSession(
                    PairingSessionEvent.PeerNonce(
                        requestId = frame.requestId,
                        responderFingerprintHex = frame.responderFingerprintHex,
                        responderEphemeralPublicKey = frame.responderEphemeralPublicKey,
                        nonce = frame.nonce,
                        pinnedPeerFingerprintHex = before.peerDeviceId?.let(peerIdentityPin),
                    ),
                )
                val after = _session.value
                if (after.phase == PairingPhase.AwaitingPeerConfirmation && before.phase == PairingPhase.AwaitingPeerNonce) {
                    after.localNonce?.let { sendOrFail(FlashPairingFrame.PairReveal(frame.requestId, it)) }
                } else {
                    emitIfNewlyFailed(before, after)
                }
            }

            is FlashPairingFrame.PairReveal -> {
                val before = _session.value
                reduceSession(PairingSessionEvent.PeerReveal(frame.requestId, frame.nonce))
                val s = _session.value
                if (s.phase == PairingPhase.RequestReceived && before.phase == PairingPhase.AwaitingPeerReveal) {
                    emit(
                        FlashPairingEvent.RequestReceived(
                            requestId = s.requestId!!,
                            peerDeviceId = s.peerDeviceId!!,
                            peerName = s.peerName ?: s.peerDeviceId!!,
                            code6 = s.code6!!,
                            expiresAtMs = s.expiresAtMs!!,
                        ),
                    )
                } else {
                    emitIfNewlyFailed(before, s)
                }
            }

            is FlashPairingFrame.PairAccept -> reduceSession(PairingSessionEvent.PeerAccepted(frame.requestId)).also {
                if (_session.value.peerAccepted) {
                    emit(FlashPairingEvent.PeerAccepted(frame.requestId))
                }
            }

            is FlashPairingFrame.PairConfirm -> {
                val before = _session.value
                reduceSession(PairingSessionEvent.PeerConfirmed(frame.codeHashHex))
                val after = _session.value
                if (after.phase == PairingPhase.Confirmed && before.phase != PairingPhase.Confirmed) {
                    after.requestId?.let { rid ->
                        sendPairedAndEmit(rid)
                    }
                } else if (after.phase == PairingPhase.Failed && before.phase != PairingPhase.Failed) {
                    emit(
                        FlashPairingEvent.Failed(
                            after.requestId,
                            after.failureReason ?: "code-hash-mismatch",
                        ),
                    )
                }
            }

            is FlashPairingFrame.Paired -> {
                val before = _session.value
                reduceSession(
                    PairingSessionEvent.Paired(
                        frame.peerFingerprintHex,
                        frame.peerEphemeralPublicKey,
                    ),
                )
                val after = _session.value
                if (after.phase == PairingPhase.Confirmed && before.phase != PairingPhase.Confirmed) {
                    after.requestId?.let { rid ->
                        emit(
                            FlashPairingEvent.Confirmed(
                                requestId = rid,
                                fingerprintHex = frame.peerFingerprintHex,
                                ephemeralPubKey = frame.peerEphemeralPublicKey,
                            ),
                        )
                    }
                } else {
                    emitIfNewlyFailed(before, after)
                }
            }
        }
    }

    override fun onTick(nowMs: Long) {
        val before = _session.value.phase
        reduceSession(PairingSessionEvent.Tick(nowMs))
        if (before != PairingPhase.Expired && _session.value.phase == PairingPhase.Expired) {
            emit(FlashPairingEvent.Expired(_session.value.requestId))
        }
    }

    private suspend fun sendPairedAndEmit(requestId: String) {
        val confirmed = _session.value
        runCatching {
            sendFrame(
                FlashPairingFrame.Paired(
                    requestId = requestId,
                    peerFingerprintHex = localFingerprintHex,
                    peerEphemeralPublicKey = ephemeralPublicKeyProvider(),
                ),
            )
        }.onFailure { reason ->
            // The reducer already marked the phase Confirmed (terminal, absorbs
            // events) — override with Failed so state and UI event stay consistent.
            _session.value = _session.value.copy(
                phase = PairingPhase.Failed,
                failureReason = "paired-send-failed",
            )
            emit(
                FlashPairingEvent.Failed(requestId, reason.message ?: "paired-send-failed"),
            )
            return
        }
        emit(
            FlashPairingEvent.Confirmed(
                requestId = requestId,
                fingerprintHex = confirmed.peerFingerprintHex ?: localFingerprintHex,
                ephemeralPubKey = confirmed.peerEphemeralPublicKey ?: ByteArray(0),
            ),
        )
    }

    /** Sends [frame]; a send failure fails the session rather than leaving it waiting forever. */
    private fun sendOrFail(frame: FlashPairingFrame) {
        runCatching { sendFrame(frame) }.onFailure { reason ->
            _session.value = _session.value.copy(phase = PairingPhase.Failed, failureReason = "send-failed")
            emit(FlashPairingEvent.Failed(frame.requestId, reason.message ?: "send-failed"))
        }
    }

    private fun emitIfNewlyFailed(before: PairingSessionState, after: PairingSessionState) {
        if (after.phase == PairingPhase.Failed && before.phase != PairingPhase.Failed) {
            emit(FlashPairingEvent.Failed(after.requestId, after.failureReason ?: "protocol-error"))
        }
    }

    private fun resetToIdle() {
        _session.value = PairingSessionStateMachine.initial()
    }

    /**
     * Reduces the current session by [event]. Side effect: emits a
     * [FlashPairingEvent.PeerDeclined] UI event when the reduction lands in
     * [PairingPhase.DeclinedByPeer] (the machine itself stays pure).
     */
    private fun reduceSession(event: PairingSessionEvent): PairingSessionState =
        PairingSessionStateMachine.reduce(_session.value, event, timeouts, localFingerprintHex)
            .also { _session.value = it }
            .also { updated ->
                if (updated.phase == PairingPhase.DeclinedByPeer) {
                    emit(FlashPairingEvent.PeerDeclined(updated.requestId))
                }
            }

    private fun emit(event: FlashPairingEvent) {
        _events.tryEmit(event)
    }

    private fun PairingSessionState.isActivePhase(): Boolean =
        phase == PairingPhase.RequestReceived || phase == PairingPhase.AwaitingLocalDecision

    /**
     * `<uuid>-<random uint32>`. Unchanged in shape from the pre-KMP version, which used
     * `UUID.randomUUID()` plus `SecureRandom.nextInt()`; both halves now come from the
     * multiplatform seams (`core:common`'s UUID seam and [secureRandomBytes]) and both are
     * still CSPRNG-backed on every target.
     */
    private fun newRequestId(): String =
        UuidIdGenerator.newId() + "-" + randomUInt32().toString()

    /** A uniformly random 32-bit value from the platform CSPRNG, big-endian over 4 bytes. */
    private fun randomUInt32(): UInt {
        val bytes = secureRandomBytes(UINT32_BYTES)
        var value = 0
        for (b in bytes) {
            value = (value shl 8) or (b.toInt() and 0xFF)
        }
        return value.toUInt()
    }

    private inline fun MutableStateFlow<PairingSessionState>.update(
        transform: (PairingSessionState) -> PairingSessionState,
    ) {
        value = transform(value)
    }

    private companion object {
        const val EVENT_BUFFER = 16
        const val UINT32_BYTES = 4
    }
}
