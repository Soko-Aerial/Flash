package com.transfer.flash.core.transfer.multistream

import com.transfer.flash.core.transfer.concurrent.PlatformLock

/**
 * Aggregate transfer telemetry for one multi-stream session (C5.7), shaped for the UI-016 file
 * card: confirmed byte progress, live speed over a rolling window, ETA.
 *
 * @param bytesDone receiver-confirmed bytes (resume-seeded chunks included); monotonically
 *   non-decreasing because it is derived from a union-only bit-vector.
 * @param totalBytes logical file size; `bytesDone == totalBytes` at completion.
 * @param instantBytesPerSec rolling-window throughput aggregated across ALL streams
 *   (`bytesDone` deltas divided by wall-clock deltas — never summed per-stream rates, which
 *   double-counts overlapping windows). `-1.0` until two samples exist.
 * @param etaMs `(totalBytes - bytesDone) / rate`; `-1` when unknown/stalled/completed.
 */
internal data class MultiStreamProgress(
    val bytesDone: Long,
    val totalBytes: Long,
    val instantBytesPerSec: Double = -1.0,
    val etaMs: Long = -1L,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesDone.toDouble() / totalBytes).toFloat()

    companion object {

        /** Default telemetry window (plan C5.7: aggregate throughput accounting, 2 s rolling). */
        const val DEFAULT_WINDOW_MS: Long = 2_000L
    }
}

/**
 * Rolling-window byte-delta rate estimator shared by all streams of one session. Deterministic:
 * the clock is injected, so JVM tests drive it with a fake timeline.
 *
 * Keeps (timestamp, cumulativeBytes) samples inside [windowMs] and reports
 * `(newest.bytes - oldest.bytes) / (newest.t - oldest.t)` over that span. Earlier revision kept
 * only the FIRST sample ever while letting the time window slide — Δbytes grew unbounded while
 * Δtime stayed window-sized, so reported speed climbed continuously toward totalBytes/window
 * regardless of actual throughput (field-reported on device, 2026-08-24).
 *
 * Phase 13B-1 moved this class from `androidMain` to `commonMain`. The only edit it needed was
 * mutual exclusion: three `@Synchronized` annotations became [PlatformLock] blocks, because
 * `@Synchronized` resolves to `kotlin.jvm.Synchronized` and does not exist in common code. The
 * monitor changed from `this` to a private object, which nothing can observe — the class is
 * `internal` and no code anywhere synchronises on an instance of it.
 */
internal class RollingRateMeter(
    private val nowMs: () -> Long,
    private val windowMs: Long = MultiStreamProgress.DEFAULT_WINDOW_MS,
) {
    init {
        require(windowMs > 0) { "windowMs must be > 0" }
    }

    private class Sample(val atMs: Long, val cumulativeBytes: Long)

    private val samples = ArrayDeque<Sample>()

    private val lock = PlatformLock()

    /** Records that cumulative progress reached [cumulativeBytes] at the injected "now". */
    fun record(cumulativeBytes: Long): Unit = lock.withLock {
        val t = nowMs()
        val last = samples.lastOrNull()
        if (last != null && t < last.atMs) {
            // Clock went backwards (host suspend/NTP): reset instead of producing negatives.
            samples.clear()
        }
        samples.addLast(Sample(t, cumulativeBytes))
        prune(t)
    }

    /**
     * Discards every sample. Used when transmission pauses and resumes: a window that straddles
     * the paused gap divides real bytes by pause wall-clock and reports a bogus near-zero rate.
     */
    fun reset(): Unit = lock.withLock {
        samples.clear()
    }

    /**
     * Bytes/sec across the sliding window ending at [atMs]; `-1.0` when no forward progress is
     * visible inside the window (stall ⇒ ETA hidden, not faked).
     */
    fun instantBytesPerSec(atMs: Long): Double = lock.withLock {
        prune(atMs)
        // `withLock` cannot be `inline` on an `expect class`, so every early exit below is a
        // `return@withLock`. A plain `return` does not compile here.
        if (samples.size < 2) return@withLock -1.0
        val newest = samples.last()
        val oldest = samples.first()
        val dtMs = (newest.atMs - oldest.atMs).toDouble()
        if (dtMs <= 0.0) return@withLock -1.0
        val db = (newest.cumulativeBytes - oldest.cumulativeBytes).toDouble()
        if (db <= 0.0) return@withLock -1.0
        db * 1000.0 / dtMs
    }

    /**
     * Drops samples older than the window. Deliberately **unguarded**: it is only ever called
     * from inside a [lock] block. Both `actual`s use `synchronized`, which is reentrant on the
     * JVM, so taking the lock here would work today — but it would deadlock on any future target
     * whose `actual` is not reentrant, so the invariant is kept here instead of the lock.
     */
    private fun prune(now: Long) {
        val cutoff = now - windowMs
        while (samples.size > 1 && samples.first().atMs < cutoff) {
            samples.removeFirst()
        }
    }
}

/** Terminal outcome of [MultiStreamDispatcher.send]. */
internal sealed interface MultiStreamResult {

    /**
     * All chunks confirmed by the receiver (bit-vector complete).
     *
     * @property verified receiver COMPLETE flag (whole-file digest recheck result or trust-per-
     *   chunk when the engine disabled recheck); null when completion was detected via full ACK
     *   coverage before the COMPLETE frame was ingested.
     */
    data class Completed(
        val totalChunks: Int,
        val chunksSent: Int,
        val chunksSkippedResume: Int,
        val bytesSent: Long,
        val bytesSkippedResume: Long,
        val fileSha256Hex: String,
        val verified: Boolean?,
        val deadChannelIds: List<Int>,
        /** Serialized terminal COMPLETE frame emitted exactly once (see dispatcher KDoc). */
        val completeFrameBytes: ByteArray?,
    ) : MultiStreamResult

    data class Failed(
        val reason: String,
        val deadChannelIds: List<Int>,
        /** Un-ACKed chunk indexes — feed into a fresh dispatcher's doneIndexes? No: these are
         *  the NOT-done set; resume uses the receiver's persisted done-set (C5.6). Kept for
         *  diagnostics and targeted repair. */
        val unconfirmedIndexes: List<Int>,
    ) : MultiStreamResult
}
