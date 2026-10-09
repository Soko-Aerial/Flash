package com.transfer.flash.core.network.radio

/**
 * Transmit pacing for a half-duplex radio channel behind a KISS TNC.
 *
 * The TNC does its own carrier sense (persistence P and slot time, KISS commands 2 and 3), and the host cannot hear the
 * channel through a KISS port. What the host *can* do is not queue frames faster than the channel can carry them and add random
 * spacing so two stations that reacted to the same event do not key up in lock step. [TxPacingPolicy] is that rule as data;
 * [TxPacer] applies it with an injected clock and random source so it is unit-testable.
 *
 * All durations are milliseconds. Every number here is an unmeasured default; BT-00 measures the real ones.
 */
public class TxPacingPolicy(
    /** On-air bit rate of the radio's modem (1200 for Bell 202 AFSK; 9600 only if BT-00 proves it). */
    public val airBaud: Int = 1200,
    /** Estimated keyup delay the TNC adds per transmission (its TXDELAY setting). */
    public val txDelayMs: Int = 300,
    /** Estimated tail the TNC adds per transmission. */
    public val txTailMs: Int = 20,
    /** Bit-stuffing and flag overhead factor applied to the frame bits (HDLC stuffing adds about 3 percent on average, 20 percent worst case). */
    public val stuffingFactor: Double = 1.1,
    /** Guaranteed quiet time after the estimated end of our own transmission before the next one may start. */
    public val minGapMs: Long = 500,
    /** Extra random wait in [0, jitterMs) added to the gap (radio plan 5.1: jitter budget of 500 to 2000 ms in total). */
    public val jitterMs: Long = 1500,
    /** Frames waiting for the channel; more are refused with [TxResult.QueueFull]. */
    public val maxQueue: Int = 16,
) {
    init {
        require(airBaud > 0 && stuffingFactor >= 1.0 && minGapMs >= 0 && jitterMs >= 0 && maxQueue >= 1)
    }

    /** Estimated time the radio is transmitting an AX.25 frame of [ax25Bytes] (address field through info; FCS and flags are added here). */
    public fun airtimeMs(ax25Bytes: Int): Long {
        val bits = (ax25Bytes + FCS_BYTES + FLAG_BYTES) * 8 * stuffingFactor
        return (bits * 1000.0 / airBaud).toLong() + txDelayMs + txTailMs
    }

    /** Pacing for tests and loopback: no waiting. */
    public companion object {
        private const val FCS_BYTES = 2
        private const val FLAG_BYTES = 2

        /** A policy that never waits. */
        public val NONE: TxPacingPolicy = TxPacingPolicy(minGapMs = 0, jitterMs = 0, txDelayMs = 0, txTailMs = 0)
    }
}

/** Decides how long the next transmission must wait. Pure: state is the time the channel is next free. */
public class TxPacer(private val policy: TxPacingPolicy, private val random01: () -> Double) {
    private var freeAtMs: Long = Long.MIN_VALUE

    /** Milliseconds to wait before transmitting at [nowMs] (0 when the channel is free). */
    public fun waitMs(nowMs: Long): Long = if (freeAtMs == Long.MIN_VALUE) 0 else maxOf(0L, freeAtMs - nowMs)

    /** Records that a frame of [ax25Bytes] was handed to the TNC at [nowMs] and schedules the next free time. */
    public fun onTransmitted(nowMs: Long, ax25Bytes: Int) {
        val jitter = if (policy.jitterMs == 0L) 0L else (random01().coerceIn(0.0, 0.999999) * policy.jitterMs).toLong()
        freeAtMs = nowMs + policy.airtimeMs(ax25Bytes) + policy.minGapMs + jitter
    }

    /** Forgets history (after a reconnect). */
    public fun reset() {
        freeAtMs = Long.MIN_VALUE
    }
}
