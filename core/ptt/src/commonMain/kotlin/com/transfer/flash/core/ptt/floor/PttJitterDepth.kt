package com.transfer.flash.core.ptt.floor

import com.transfer.flash.core.ptt.PttLock
import kotlin.concurrent.Volatile

/**
 * How deep the playout jitter buffer waits before it starts playing — learnt from the last burst
 * (OPT-O12-04).
 *
 * The buffer's target depth is the **start gate** only: once playout has begun, the tick rate paces it
 * and the depth no longer matters. That makes it safe to adapt across bursts and impossible to adapt
 * inside one, which is what the finding asked for without the risk it carries: lowering the gate on a
 * link that then jitters costs one concealed packet at the start of *that* burst, never a permanently
 * under-buffered session. And a wrong guess corrects itself on the next burst, in either direction.
 *
 * Rules, in the order they apply:
 * - A burst shorter than [MIN_JUDGED_PACKETS] says nothing (a two-second "roger" is not evidence).
 * - Concealment above [RAISE_RATIO] of the burst raises the gate by [STEP_MS] (ceiling [MAX_MS]):
 *   the link is losing packets and the next start should ride it out.
 * - A long, completely clean burst lowers it by [STEP_MS] (floor [MIN_MS]): on a LAN the 120 ms start
 *   gate is pure turn-taking latency that the measurement does not support.
 *
 * The starting value is the shipped 120 ms, so the first burst of a process is never worse than
 * before; on a clean link it decays to 60 ms over three bursts, i.e. 3–4 minutes of ordinary talking.
 * Device confirmation of the resulting talk-start latency is `PERF-C01` in the backlog.
 */
internal object PttJitterDepth {

    /** Today's constant, and the value every process starts from. */
    const val DEFAULT_MS: Long = 120L

    /** Lowest gate: three packets at 20 ms. Below that a single reorder is an audible gap. */
    const val MIN_MS: Long = 60L

    /** Highest gate: eight packets at 20 ms, still inside a walkie-talkie turn. */
    const val MAX_MS: Long = 160L

    const val STEP_MS: Long = 20L

    /** A burst shorter than this is not evidence about the link. */
    const val MIN_JUDGED_PACKETS: Long = 50L

    /** A burst at least this long, with no concealment at all, earns a step down. */
    const val CLEAN_PACKETS: Long = 200L

    /** Concealment above this fraction of a judged burst raises the gate. */
    const val RAISE_RATIO: Double = 0.02

    private val lock = PttLock()

    @Volatile
    private var depthMs: Long = DEFAULT_MS

    /** The gate the next playout device should use. */
    fun targetDepthMs(): Long = depthMs

    /**
     * Folds one finished burst's counters in. [readyTotal] and [concealedTotal] are the playout
     * device's own totals (`PttPlayoutSnapshot`), so a caller needs no extra state.
     */
    fun observe(readyTotal: Long, concealedTotal: Long) {
        val total = readyTotal + concealedTotal
        if (total < MIN_JUDGED_PACKETS) return
        lock.withLock {
            depthMs = when {
                concealedTotal.toDouble() / total.toDouble() > RAISE_RATIO ->
                    (depthMs + STEP_MS).coerceAtMost(MAX_MS)
                concealedTotal == 0L && total >= CLEAN_PACKETS ->
                    (depthMs - STEP_MS).coerceAtLeast(MIN_MS)
                else -> depthMs
            }
        }
    }

    /** Test seam: forget what previous bursts taught. */
    fun reset() {
        lock.withLock { depthMs = DEFAULT_MS }
    }
}
