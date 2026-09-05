package com.transfer.flash.core.network.ws

import kotlin.concurrent.Volatile

/**
 * Liveness bookkeeping for one [WsConnection]'s keepalive loop, extracted so the decision is a
 * pure function of (clock, inbound traffic, tick arrivals) and therefore JVM-testable without
 * sockets or coroutine test infrastructure.
 *
 * ## The problem this encodes (ERROR-025)
 *
 * The naive check — "close if `now - lastInbound > livenessTimeout`" — is correct only while the
 * loop it runs in is actually being scheduled. On Android it is not: with the screen off, the app
 * backgrounded, or the process Doze-frozen, a `delay(10_000)` can return a minute later. The first
 * resumed tick then measures how long the *process* slept, blames the *peer*, and closes a session
 * that is perfectly alive. Both peers do it at the same moment, so a phone that was merely
 * backgrounded appears to go offline and come back, and everything sent in that window — chat
 * messages, `FLASH_CALL` signaling — fails for want of a session.
 *
 * ## The rule
 *
 * A tick also reports how long it has been since the *previous* tick, measured with the same
 * clock. When that gap is far larger than the interval the loop asked for, the scheduler did not
 * run, so nothing about the peer can be inferred: the silence window is forgiven (rebased to now),
 * a PING is sent, and the verdict is deferred to a tick that ran on time.
 *
 * ## Why forgiveness is bounded (ERROR-031)
 *
 * Forgiveness used to be unbounded: *every* stalled tick rebased the window. On a device that
 * throttles background coroutines hard enough that ticks are chronically late, the watchdog then
 * never renders a verdict at all, and a session whose socket is long dead stays in
 * `activeSessions` forever — a zombie. That is worse than a false close, because presence is
 * computed from that map and every reconnect path in the app skips peers that already have a
 * session, so nothing else reaps it either.
 *
 * So a stall episode is forgiven **once**. The first stalled tick rebases and arms a probe; while
 * that probe is outstanding — no inbound frame of any kind since it was armed — a further stalled
 * tick may re-PING but may **not** rebase, which lets the ordinary silence window keep growing
 * until it renders a verdict.
 *
 * A verdict rendered by a stalled tick carries [Verdict.Close.needsConfirmation], because that
 * tick had itself just resumed and the read loop resumes on its own dispatcher: a PONG already
 * sitting in the socket buffer may not have been stamped yet. [confirmClose] re-reads the
 * timestamp after a short *awake* delay and withdraws the verdict if the peer proved itself.
 *
 * Not thread-safe by itself; [WsConnection] confines mutation to one keepalive coroutine and marks
 * the cross-thread field volatile.
 */
internal class WsKeepalive(
    private val pingIntervalMs: Long,
    private val livenessTimeoutMs: Long,
    startedAtMs: Long,
) {
    /** What the keepalive loop should do after a tick. */
    internal sealed interface Verdict {
        /** Peer is within its liveness window (or unjudgeable): probe and keep going. */
        object Ping : Verdict

        /**
         * Peer has been silent for longer than the liveness window.
         *
         * @param reason which rule fired, so the two reap causes are distinguishable in logs.
         * @param needsConfirmation the verdict came from a tick whose own scheduling had slipped,
         *   so an already-received PONG may not have been stamped yet — re-check with
         *   [confirmClose] after an awake delay before acting on it.
         */
        data class Close(
            val silentForMs: Long,
            val reason: String,
            val needsConfirmation: Boolean = false,
        ) : Verdict
    }

    /** Wall clock of the last inbound frame of ANY kind. Written by the read loop. */
    @Volatile
    internal var lastInboundAtMs: Long = startedAtMs
        private set

    /** Wall clock of the previous keepalive tick, used to detect a stalled scheduler. */
    private var lastTickAtMs: Long = startedAtMs

    /**
     * When the current stall episode forgave its silence window, in the same clock, or [NO_PROBE]
     * while no probe is outstanding. Armed by the first stalled tick and cleared only by an inbound
     * frame that arrived after it — never moved by a later stalled tick, which is the bound.
     */
    private var probeArmedAtMs: Long = NO_PROBE

    /** Any inbound frame — text, binary, ping or pong — proves the peer is alive. */
    fun onInbound(nowMs: Long) {
        lastInboundAtMs = nowMs
    }

    fun onTick(nowMs: Long): Verdict {
        val tickGapMs = nowMs - lastTickAtMs
        lastTickAtMs = nowMs
        val inboundAtMs = lastInboundAtMs
        // The peer answered a probe armed by an earlier stalled tick: the episode is over and the
        // next stall is entitled to its own forgiveness.
        if (probeArmedAtMs != NO_PROBE && inboundAtMs > probeArmedAtMs) {
            probeArmedAtMs = NO_PROBE
        }
        // A gap this large cannot have been produced by a healthy scheduler, so the silence it
        // "measured" is the process's, not the peer's. A negative gap (NTP correction, manual time
        // change) is equally unjudgeable.
        val stalled = tickGapMs >= pingIntervalMs * STALL_FACTOR || tickGapMs < 0L
        if (stalled && probeArmedAtMs == NO_PROBE) {
            lastInboundAtMs = nowMs
            probeArmedAtMs = nowMs
            return Verdict.Ping
        }
        val silentForMs = nowMs - inboundAtMs
        return when {
            // Inbound timestamp ahead of the tick's reading: distrust the window rather than the
            // peer. Does not consume the episode's forgiveness.
            silentForMs < 0L -> {
                lastInboundAtMs = nowMs
                Verdict.Ping
            }
            silentForMs <= livenessTimeoutMs -> Verdict.Ping
            stalled -> Verdict.Close(silentForMs, REASON_STALL_PROBE, needsConfirmation = true)
            else -> Verdict.Close(silentForMs, REASON_SILENT)
        }
    }

    /**
     * Second look at a [Verdict.Close] that carried [Verdict.Close.needsConfirmation], taken after
     * the caller has stayed awake for a moment. Withdraws the verdict — and ends the stall episode
     * — if the read loop stamped a frame in the meantime.
     */
    fun confirmClose(nowMs: Long): Verdict {
        val silentForMs = nowMs - lastInboundAtMs
        if (silentForMs > livenessTimeoutMs) {
            return Verdict.Close(silentForMs, REASON_STALL_PROBE)
        }
        probeArmedAtMs = NO_PROBE
        return Verdict.Ping
    }

    internal companion object {
        /**
         * How many ping intervals a tick may slip before the loop stops trusting its own clock
         * reading. Two intervals is comfortably above ordinary dispatcher jitter (milliseconds)
         * and far below an Android app-standby freeze (tens of seconds to minutes).
         */
        internal const val STALL_FACTOR: Long = 2L

        /** No stall probe is outstanding. Not a valid clock reading. */
        private const val NO_PROBE: Long = Long.MIN_VALUE

        /** Silence measured across ticks that ran on schedule. */
        internal const val REASON_SILENT: String = "No inbound traffic"

        /** Silence that outlived the one forgiveness a stall episode gets. */
        internal const val REASON_STALL_PROBE: String = "No inbound frame after stall probe"
    }
}
