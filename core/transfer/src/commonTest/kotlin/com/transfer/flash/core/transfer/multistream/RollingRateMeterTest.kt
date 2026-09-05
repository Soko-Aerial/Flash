package com.transfer.flash.core.transfer.multistream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Executes [RollingRateMeter] on **each** target (Phase 13B-1).
 *
 * Two reasons this suite exists rather than an `androidHostTest` one:
 *
 *  * Moving `MultiStreamProgress.kt` into `commonMain` cost a content edit — three
 *    `@Synchronized` annotations became [com.transfer.flash.core.transfer.concurrent.PlatformLock]
 *    blocks, and all three early exits in `instantBytesPerSec` became `return@withLock`. Being in
 *    `commonTest` means `jvmTest` runs it too, which is what separates "the desktop lock compiles"
 *    from "the desktop lock locks" (CONVENTIONS.md R3.1).
 *  * The class had **no test at all** before this. Its own KDoc records a rate regression
 *    field-reported on device on 2026-08-24 — reported speed climbing toward `totalBytes/window`
 *    regardless of real throughput — and nothing in the repo guarded against it recurring.
 *    [rate_usesOldestSampleInWindow_notFirstEver] is that guard.
 */
class RollingRateMeterTest {

    @Test
    fun rate_isNegativeOne_untilTwoSamples() {
        var now = 0L
        val meter = RollingRateMeter(nowMs = { now }, windowMs = 1_000L)
        assertEquals(-1.0, meter.instantBytesPerSec(0L), EPS, "no samples at all")
        meter.record(0L)
        assertEquals(-1.0, meter.instantBytesPerSec(0L), EPS, "one sample spans no interval")
        now = 500L
        meter.record(500L)
        assertEquals(1_000.0, meter.instantBytesPerSec(now), EPS, "two samples: 500 B / 500 ms")
    }

    @Test
    fun rate_isBytesPerSecondAcrossWindow() {
        var now = 0L
        val meter = RollingRateMeter(nowMs = { now }, windowMs = 2_000L)
        meter.record(0L)
        now = 1_000L
        meter.record(1L shl 20)
        assertEquals(1_048_576.0, meter.instantBytesPerSec(now), EPS, "1 MiB in 1 s")
    }

    @Test
    fun rate_usesOldestSampleInWindow_notFirstEver() {
        var now = 0L
        val meter = RollingRateMeter(nowMs = { now }, windowMs = 1_000L)
        meter.record(0L)
        // 250 B every 250 ms is exactly 1 000 B/s, held for three full windows. Cumulative bytes
        // are numerically equal to elapsed ms, so the true rate is 1 000 B/s at every instant.
        //
        // The 2026-08-24 regression kept the FIRST sample ever as `oldest` while the time span
        // stayed window-sized, so Δbytes grew without bound: it would report 2 000 B/s at t=2 s
        // and 3 000 B/s at t=3 s. A constant-rate feed is therefore enough to catch it, provided
        // the assertion runs after more than one window has elapsed — which is why the loop
        // asserts at every window boundary rather than only at the end.
        while (now < 3_000L) {
            now += 250L
            meter.record(now)
            if (now % 1_000L == 0L) {
                assertEquals(1_000.0, meter.instantBytesPerSec(now), EPS, "at t=$now ms")
            }
        }
    }

    @Test
    fun rate_resetsOnBackwardsClock() {
        var now = 1_000L
        val meter = RollingRateMeter(nowMs = { now }, windowMs = 1_000L)
        meter.record(1_000L)
        now = 500L // NTP correction or host resume moved the wall clock backwards.
        meter.record(1_500L)
        assertEquals(-1.0, meter.instantBytesPerSec(now), EPS, "window was cleared, not inverted")
        now = 1_500L
        meter.record(2_500L)
        // Only the two post-reset samples count: 1 000 B over 1 000 ms. Without the clear, the
        // straddling pair would read 1 500 B over 500 ms and report 3 000 B/s.
        assertEquals(1_000.0, meter.instantBytesPerSec(now), EPS, "pre-jump samples discarded")
    }

    @Test
    fun rate_isNegativeOne_whenStalled() {
        var now = 0L
        val meter = RollingRateMeter(nowMs = { now }, windowMs = 2_000L)
        meter.record(1_000L)
        now = 1_000L
        meter.record(1_000L)
        // No byte delta inside the window: the ETA is hidden rather than faked as 0 B/s.
        assertEquals(-1.0, meter.instantBytesPerSec(now), EPS)
    }

    @Test
    fun reset_clearsWindow() {
        var now = 0L
        val meter = RollingRateMeter(nowMs = { now }, windowMs = 2_000L)
        meter.record(0L)
        now = 1_000L
        meter.record(1_000L)
        assertEquals(1_000.0, meter.instantBytesPerSec(now), EPS)
        // The pause/resume case the method's KDoc describes: a window straddling the paused gap
        // divides real bytes by pause wall-clock and reports a bogus near-zero rate.
        meter.reset()
        assertEquals(-1.0, meter.instantBytesPerSec(now), EPS, "no samples survive a reset")
    }

    @Test
    fun contention_recordAndReadDoNotCorrupt() = runTest {
        // A real monotonic clock rather than a shared `var`: the test needs the timestamps to race
        // as hard as the deque does, and a plain Long read from several dispatcher threads is
        // itself unsynchronised.
        val start = TimeSource.Monotonic.markNow()
        val meter = RollingRateMeter(
            nowMs = { start.elapsedNow().inWholeMilliseconds },
            windowMs = 50L,
        )
        val readings = DoubleArray(WORKERS * ROUNDS)
        withContext(Dispatchers.Default) {
            List(WORKERS) { w ->
                launch {
                    repeat(ROUNDS) { r ->
                        meter.record((r.toLong() + 1L) * 4_096L)
                        // Each coroutine writes its OWN slots, so a lost write cannot mask a bad
                        // reading — the same reason AutoConnectGateTest uses a per-worker array.
                        readings[w * ROUNDS + r] =
                            meter.instantBytesPerSec(start.elapsedNow().inWholeMilliseconds)
                    }
                }
            }.joinAll()
        }
        // An unguarded ArrayDeque under this load does not merely lose an update on the JVM: it
        // can throw from removeFirst() or read a half-written slot and yield NaN. Every legal
        // reading is either the stall sentinel or a finite positive rate — this is what makes the
        // swapped-in lock's exclusion observable rather than assumed.
        readings.forEachIndexed { i, r ->
            assertTrue(r == -1.0 || (r > 0.0 && r.isFinite()), "reading $i was $r")
        }
        meter.reset()
        assertEquals(-1.0, meter.instantBytesPerSec(0L), EPS, "still usable after the storm")
    }

    private companion object {
        const val EPS = 1e-9
        const val WORKERS = 8
        const val ROUNDS = 2_000
    }
}
