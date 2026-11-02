package com.transfer.flash.core.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * OPT-O28-03: the three call-quality numbers `CALL_DIAG` now prints — jitter-buffer delay, concealment share and
 * audio/video playout skew. Pure arithmetic, so it is testable without a native stack; the awkward cases are the ones
 * that matter, because a number that reads "0 ms, 0 %, in sync" when the backend reported nothing is worse than no number.
 */
class CallQualityMathTest {

    @Test
    fun `jitter buffer delay is the average wait per played sample`() {
        // 1.5 s of total buffering across 900 played samples = 1.67 ms each.
        assertEquals(2, CallQualityMath.jitterBufferDelayMs(delaySecondsDelta = 1.5, emittedDelta = 900.0))
        assertEquals(0, CallQualityMath.jitterBufferDelayMs(delaySecondsDelta = 0.0, emittedDelta = 480.0))
        // 120 ms of buffering per 20 ms packet is what a deep buffer would look like.
        assertEquals(120, CallQualityMath.jitterBufferDelayMs(delaySecondsDelta = 0.12, emittedDelta = 1.0))
    }

    @Test
    fun `jitter buffer delay is unknown rather than zero when the backend says nothing`() {
        assertNull(CallQualityMath.jitterBufferDelayMs(null, 900.0))
        assertNull(CallQualityMath.jitterBufferDelayMs(1.5, null))
        assertNull(
            CallQualityMath.jitterBufferDelayMs(1.5, 0.0),
            "no samples played: an average would divide by zero",
        )
        assertNull(
            CallQualityMath.jitterBufferDelayMs(-1.0, 10.0),
            "a counter that went backwards is not a delay",
        )
    }

    @Test
    fun `concealment is a share of what was received, to one decimal`() {
        assertEquals(5.0, CallQualityMath.concealmentPercent(50.0, 1_000.0))
        assertEquals(0.4, CallQualityMath.concealmentPercent(4.0, 1_000.0))
        assertEquals(0.0, CallQualityMath.concealmentPercent(0.0, 1_000.0))
        assertEquals(100.0, CallQualityMath.concealmentPercent(10.0, 10.0))
    }

    @Test
    fun `concealment is unknown when there is nothing to divide by`() {
        assertNull(CallQualityMath.concealmentPercent(null, 1_000.0))
        assertNull(CallQualityMath.concealmentPercent(5.0, null))
        assertNull(CallQualityMath.concealmentPercent(5.0, 0.0))
    }

    @Test
    fun `audio video skew is the difference between the two playout clocks`() {
        assertEquals(0, CallQualityMath.avDriftMs(123.5, 123.5))
        assertEquals(40, CallQualityMath.avDriftMs(123.54, 123.5))
        assertEquals(-40, CallQualityMath.avDriftMs(123.46, 123.5))
        assertEquals(1_500, CallQualityMath.avDriftMs(101.5, 100.0))
    }

    @Test
    fun `skew is unknown in an audio-only call or a one-way stream`() {
        assertNull(CallQualityMath.avDriftMs(123.5, null))
        assertNull(CallQualityMath.avDriftMs(null, 123.5))
        assertNull(CallQualityMath.avDriftMs(null, null))
    }
}
