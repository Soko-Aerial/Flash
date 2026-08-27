package com.transfer.flash.core.network.resilience

/**
 * Liveness state of the remote peer as judged by [HeartbeatTracker].
 */
internal enum class HeartbeatState {
    /** Recent pong(s) within threshold — peer presumed alive. */
    Alive,

    /** One or more pings missed, below threshold — degraded but not declared. */
    Suspect,

    /**
     * [HeartbeatPolicy.missedThreshold] consecutive misses — session must be
     * torn down cleanly instead of hanging half-open (plan upgrade 3).
     */
    Dead,
}

/**
 * What the owning connection loop should do at the current tick.
 */
internal sealed interface HeartbeatAction {
    /** No ping is outstanding and one is due — send a ping now. */
    data object PingNow : HeartbeatAction

    /** A ping is outstanding and has not yet timed out — keep waiting. */
    data object AwaitPong : HeartbeatAction

    /**
     * Threshold reached — tear down the session and report the peer offline.
     * Also returned on every tick while already [HeartbeatState.Dead] so a
     * late caller still observes the terminal instruction.
     */
    data object DeclareDead : HeartbeatAction
}

/**
 * Pure dead-peer detection state machine (plan C4.3).
 *
 * All time is explicit: callers feed [onTick]/[onPingSent]/[onPongReceived]
 * with `nowMs` values from their own clock (injected time source or fake),
 * so tests are fully deterministic with no coroutine-test machinery and no
 * real sleeping.
 *
 * Model:
 * - A ping sent via [onPingSent] becomes "outstanding".
 * - An outstanding ping that survives an entire [HeartbeatPolicy.intervalMs]
 *   without a pong counts as exactly one miss.
 * - Reaching [HeartbeatPolicy.missedThreshold] misses transitions to
 *   [HeartbeatState.Dead] and every subsequent [onTick] returns
 *   [HeartbeatAction.DeclareDead]. The boundary is inclusive: the tick that
 *   records the threshold-th miss IS the declaring tick.
 * - Any pong resets the miss counter to zero ([HeartbeatState.Alive]).
 * - When no ping is outstanding, a tick that is [intervalMs] past the last
 *   sign of life requests a fresh ping.
 *
 * Thread-safety: NOT thread-safe by design — the owning connection loop
 * serializes calls (mirrors LanSession's single read-loop model).
 */
internal class HeartbeatTracker(private val policy: HeartbeatPolicy = HeartbeatPolicy()) {

    var state: HeartbeatState = HeartbeatState.Alive
        private set

    private var pendingPingSentAtMs: Long? = null
    private var missedCount: Int = 0
    private var lastSignOfLifeMs: Long = 0L

    val missedCountValue: Int get() = missedCount

    /** Records an outgoing ping at [nowMs]; it is now awaiting a pong. */
    fun onPingSent(nowMs: Long) {
        if (state == HeartbeatState.Dead) return
        pendingPingSentAtMs = nowMs
        if (missedCount == 0) lastSignOfLifeMs = nowMs
    }

    /** Records a received pong at [nowMs]; peer is alive again. */
    fun onPongReceived(nowMs: Long) {
        pendingPingSentAtMs = null
        missedCount = 0
        lastSignOfLifeMs = nowMs
        if (state != HeartbeatState.Dead) state = HeartbeatState.Alive
    }

    /**
     * Advances the clock to [nowMs] and returns the action the owner should
     * perform.
     */
    fun onTick(nowMs: Long): HeartbeatAction {
        if (state == HeartbeatState.Dead) return HeartbeatAction.DeclareDead

        val pendingAt = pendingPingSentAtMs
        return if (pendingAt != null) {
            if (nowMs - pendingAt >= policy.intervalMs) {
                // Ping timed out: count exactly one miss for it.
                pendingPingSentAtMs = null
                missedCount++
                if (missedCount >= policy.missedThreshold) {
                    state = HeartbeatState.Dead
                    HeartbeatAction.DeclareDead
                } else {
                    state = HeartbeatState.Suspect
                    // Immediately probe again to keep detection cadence.
                    HeartbeatAction.PingNow
                }
            } else {
                HeartbeatAction.AwaitPong
            }
        } else {
            if (nowMs - lastSignOfLifeMs >= policy.intervalMs) {
                HeartbeatAction.PingNow
            } else {
                // Idle inside the interval; nothing due yet.
                HeartbeatAction.AwaitPong
            }
        }
    }

    /** Manual reset after a fresh (re)connect: back to a clean Alive slate. */
    fun reset(nowMs: Long = 0L) {
        state = HeartbeatState.Alive
        pendingPingSentAtMs = null
        missedCount = 0
        lastSignOfLifeMs = nowMs
    }
}
