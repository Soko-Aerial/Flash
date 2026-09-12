package com.transfer.flash.core.messaging.ptt

/**
 * Pure floor-control state machine for PTT voice sessions (ADR-032, Phase 0).
 *
 * Strict half-duplex: one floor holder transmits, everyone else only receives. The machine
 * is fully deterministic and side-effect free — it maps `(state, event)` to
 * `(state, effects)` and the Android host executes the returned [PttFloorEffect] list
 * (capture/playout control, wire sends, user notices). `nowMs` is always caller-supplied,
 * so the machine is unit-testable with no clock and stays out of `commonMain` time APIs.
 *
 * Key rules (see ADR-032):
 * - A local press toggles: Idle → TALKING, TALKING → idle (+ wire `stop`).
 * - Only the holder's own stop ends a talk; anyone else's `stop` is ignored (a forged or
 *   stale stop cannot tear another device's transmission down).
 * - A busy floor denies: pressing while TALKING (elsewhere) or LISTENING notifies busy.
 * - Receiver cancel (`LocalStopPress` while LISTENING) tears down locally + sends `leave`.
 * - `Tick` drives the 60 s burst cap (45 s warning) and the 5 s orphan timeout; user-
 *   initiated ends emit no [PttFloorEffect.NotifyEnded] (the user already knows).
 */
public data class PttFloorConfig(
    val maxBurstMs: Long = 60_000L,
    val warnAtMs: Long = 45_000L,
    val heartbeatTimeoutMs: Long = 5_000L,
    val collisionWindowMs: Long = 1_500L,
)

public sealed interface PttFloorState {
    public data object Idle : PttFloorState

    public data class Talking(
        val sessionId: String,
        val startedAtMs: Long,
        /** Local device id; blank only for compatibility with pure-state callers. */
        val holderId: String = "",
        val warned: Boolean = false,
        val announced: Boolean = false,
    ) : PttFloorState

    public data class Listening(
        val sessionId: String,
        val holderId: String,
        val holderName: String,
        val startedAtMs: Long,
        /** Most recent authenticated heartbeat or accepted audio packet. */
        val lastActivityMs: Long,
        val sampleRateHz: Int = 16000,
        val packetMs: Int = 20,
    ) : PttFloorState
}

public sealed interface PttFloorEvent {
    /** Local PTT press. [sessionId] is host-generated (keeps UUIDs out of common). */
    public data class LocalPress(
        val nowMs: Long,
        val sessionId: String,
        val holderId: String = "",
    ) : PttFloorEvent

    /** Explicit local stop (notification Stop action, UI button). Toggle twin of press. */
    public data object LocalStopPress : PttFloorEvent

    public data class RemoteStart(
        val sessionId: String,
        val holderId: String,
        val holderName: String,
        val nowMs: Long,
        val sampleRateHz: Int = 16000,
        val packetMs: Int = 20,
    ) : PttFloorEvent

    public data class RemoteStop(val sessionId: String, val peerId: String) : PttFloorEvent

    /** The holder sent Start on at least one live leg; capture may now publish audio. */
    public data class StartAnnounced(val sessionId: String) : PttFloorEvent

    /** Receiver cancel hint from a peer: informational, never changes state. */
    public data class RemoteLeave(val sessionId: String, val peerId: String) : PttFloorEvent

    /** Authenticated holder liveness (heartbeat or a valid audio packet). */
    public data class RemoteActivity(
        val sessionId: String,
        val peerId: String,
        val nowMs: Long,
    ) : PttFloorEvent

    public data class Tick(val nowMs: Long) : PttFloorEvent

    /** A phone call started: mic exclusivity + ADR-026 quiet tear any session down. */
    public data object CallStarted : PttFloorEvent

    /** Mic capture failed after TALKING began: release the floor we cannot use. */
    public data object MicDenied : PttFloorEvent
}

public enum class PttEndReason {
    LOCAL_STOP,
    REMOTE_STOP,
    TIMEOUT,
    BURST_CAP,
    CALL_STARTED,
    MIC_DENIED,
}

public sealed interface PttFloorEffect {
    public data class StartCapture(val sessionId: String) : PttFloorEffect
    public data class EnableCapturePackets(val sessionId: String) : PttFloorEffect
    public data class StopCapture(val sessionId: String) : PttFloorEffect
    public data class StartPlayout(
        val sessionId: String,
        val holderName: String,
        val sampleRateHz: Int,
        val packetMs: Int,
    ) : PttFloorEffect
    public data class StopPlayout(val sessionId: String, val reason: PttEndReason) : PttFloorEffect
    public data class SendStop(val sessionId: String) : PttFloorEffect
    public data class SendLeave(val sessionId: String) : PttFloorEffect
    public data class RemoveMember(val sessionId: String, val peerId: String) : PttFloorEffect
    public data class NotifyBusy(val holderName: String?) : PttFloorEffect
    public data class NotifyBurstWarning(val remainingMs: Long) : PttFloorEffect
    public data class NotifyEnded(val reason: PttEndReason, val holderName: String?) : PttFloorEffect
}

public data class PttTransition(
    val state: PttFloorState,
    val effects: List<PttFloorEffect>,
)

public object PttFloorMachine {
    public fun reduce(
        state: PttFloorState,
        event: PttFloorEvent,
        config: PttFloorConfig = PttFloorConfig(),
    ): PttTransition {
        if (event is PttFloorEvent.RemoteLeave) {
            val effects = if (
                state is PttFloorState.Talking && event.sessionId == state.sessionId
            ) {
                listOf(PttFloorEffect.RemoveMember(event.sessionId, event.peerId))
            } else {
                emptyList()
            }
            return PttTransition(state, effects)
        }
        return when (state) {
            is PttFloorState.Idle -> reduceIdle(event)
            is PttFloorState.Talking -> reduceTalking(state, event, config)
            is PttFloorState.Listening -> reduceListening(state, event, config)
        }
    }

    private fun reduceIdle(event: PttFloorEvent): PttTransition {
        return when (event) {
            is PttFloorEvent.LocalPress ->
                if (event.sessionId.isBlank()) {
                    PttTransition(PttFloorState.Idle, emptyList())
                } else {
                    PttTransition(
                        PttFloorState.Talking(
                            sessionId = event.sessionId,
                            startedAtMs = event.nowMs,
                            holderId = event.holderId,
                        ),
                        listOf(PttFloorEffect.StartCapture(event.sessionId)),
                    )
                }
            is PttFloorEvent.RemoteStart -> PttTransition(
                PttFloorState.Listening(
                    sessionId = event.sessionId,
                    holderId = event.holderId,
                    holderName = event.holderName,
                    startedAtMs = event.nowMs,
                    lastActivityMs = event.nowMs,
                    sampleRateHz = event.sampleRateHz,
                    packetMs = event.packetMs,
                ),
                listOf(
                    PttFloorEffect.StartPlayout(
                        sessionId = event.sessionId,
                        holderName = event.holderName,
                        sampleRateHz = event.sampleRateHz,
                        packetMs = event.packetMs,
                    ),
                ),
            )
            else -> PttTransition(PttFloorState.Idle, emptyList())
        }
    }

    private fun reduceTalking(
        state: PttFloorState.Talking,
        event: PttFloorEvent,
        config: PttFloorConfig,
    ): PttTransition {
        return when (event) {
            // Toggle: a second press (or explicit stop) releases our own floor.
            is PttFloorEvent.LocalPress,
            is PttFloorEvent.LocalStopPress -> PttTransition(
                PttFloorState.Idle,
                listOf(
                    PttFloorEffect.StopCapture(state.sessionId),
                    PttFloorEffect.SendStop(state.sessionId),
                ),
            )
            is PttFloorEvent.StartAnnounced ->
                if (event.sessionId == state.sessionId && !state.announced) {
                    PttTransition(
                        state.copy(announced = true),
                        listOf(PttFloorEffect.EnableCapturePackets(state.sessionId)),
                    )
                } else {
                    PttTransition(state, emptyList())
                }
            is PttFloorEvent.Tick -> {
                val elapsed = (event.nowMs - state.startedAtMs).coerceAtLeast(0L)
                when {
                    elapsed >= config.maxBurstMs -> PttTransition(
                        PttFloorState.Idle,
                        listOf(
                            PttFloorEffect.StopCapture(state.sessionId),
                            PttFloorEffect.SendStop(state.sessionId),
                            PttFloorEffect.NotifyEnded(PttEndReason.BURST_CAP, null),
                        ),
                    )
                    elapsed >= config.warnAtMs && !state.warned -> PttTransition(
                        state.copy(warned = true),
                        listOf(PttFloorEffect.NotifyBurstWarning(config.maxBurstMs - elapsed)),
                    )
                    else -> PttTransition(state, emptyList())
                }
            }
            is PttFloorEvent.CallStarted -> PttTransition(
                PttFloorState.Idle,
                listOf(
                    PttFloorEffect.StopCapture(state.sessionId),
                    PttFloorEffect.SendStop(state.sessionId),
                    PttFloorEffect.NotifyEnded(PttEndReason.CALL_STARTED, null),
                ),
            )
            is PttFloorEvent.MicDenied -> PttTransition(
                PttFloorState.Idle,
                listOf(
                    PttFloorEffect.StopCapture(state.sessionId),
                    PttFloorEffect.SendStop(state.sessionId),
                    PttFloorEffect.NotifyEnded(PttEndReason.MIC_DENIED, null),
                ),
            )
            is PttFloorEvent.RemoteStart ->
                if (
                    state.holderId.isNotBlank() && event.holderId < state.holderId &&
                    (event.nowMs - state.startedAtMs).coerceAtLeast(0L) <= config.collisionWindowMs
                ) {
                    PttTransition(
                        PttFloorState.Listening(
                            sessionId = event.sessionId,
                            holderId = event.holderId,
                            holderName = event.holderName,
                            startedAtMs = event.nowMs,
                            lastActivityMs = event.nowMs,
                            sampleRateHz = event.sampleRateHz,
                            packetMs = event.packetMs,
                        ),
                        listOf(
                            PttFloorEffect.StopCapture(state.sessionId),
                            PttFloorEffect.SendStop(state.sessionId),
                            PttFloorEffect.StartPlayout(
                                event.sessionId,
                                event.holderName,
                                event.sampleRateHz,
                                event.packetMs,
                            ),
                        ),
                    )
                } else {
                    PttTransition(state, emptyList())
                }
            // Only the holder's own stop ends a talk; foreign stops/heartbeats are ignored.
            else -> PttTransition(state, emptyList())
        }
    }

    private fun reduceListening(
        state: PttFloorState.Listening,
        event: PttFloorEvent,
        config: PttFloorConfig,
    ): PttTransition {
        return when (event) {
            is PttFloorEvent.RemoteStop ->
                if (event.sessionId != state.sessionId || event.peerId != state.holderId) {
                    PttTransition(state, emptyList())
                } else {
                    PttTransition(
                        PttFloorState.Idle,
                        listOf(
                            PttFloorEffect.StopPlayout(state.sessionId, PttEndReason.REMOTE_STOP),
                            PttFloorEffect.NotifyEnded(PttEndReason.REMOTE_STOP, state.holderName),
                        ),
                    )
                }
            // Receiver cancel: local teardown + leave hint; no ended notice (user-initiated).
            is PttFloorEvent.LocalStopPress -> PttTransition(
                PttFloorState.Idle,
                listOf(
                    PttFloorEffect.StopPlayout(state.sessionId, PttEndReason.LOCAL_STOP),
                    PttFloorEffect.SendLeave(state.sessionId),
                ),
            )
            // Busy floor denies: stay on the current session, tell the user who holds it.
            is PttFloorEvent.LocalPress -> PttTransition(
                state,
                listOf(PttFloorEffect.NotifyBusy(state.holderName)),
            )
            is PttFloorEvent.RemoteActivity ->
                if (
                    event.sessionId != state.sessionId || event.peerId != state.holderId ||
                    event.nowMs < state.lastActivityMs
                ) {
                    PttTransition(state, emptyList())
                } else {
                    PttTransition(state.copy(lastActivityMs = event.nowMs), emptyList())
                }
            is PttFloorEvent.Tick ->
                if ((event.nowMs - state.lastActivityMs).coerceAtLeast(0L) >= config.heartbeatTimeoutMs) {
                    PttTransition(
                        PttFloorState.Idle,
                        listOf(
                            PttFloorEffect.StopPlayout(state.sessionId, PttEndReason.TIMEOUT),
                            PttFloorEffect.NotifyEnded(PttEndReason.TIMEOUT, state.holderName),
                        ),
                    )
                } else {
                    PttTransition(state, emptyList())
                }
            is PttFloorEvent.CallStarted -> PttTransition(
                PttFloorState.Idle,
                listOf(
                    PttFloorEffect.StopPlayout(state.sessionId, PttEndReason.CALL_STARTED),
                    PttFloorEffect.SendLeave(state.sessionId),
                    PttFloorEffect.NotifyEnded(PttEndReason.CALL_STARTED, state.holderName),
                ),
            )
            // A second floor claim while busy is ignored (no preemption in v1).
            else -> PttTransition(state, emptyList())
        }
    }
}
