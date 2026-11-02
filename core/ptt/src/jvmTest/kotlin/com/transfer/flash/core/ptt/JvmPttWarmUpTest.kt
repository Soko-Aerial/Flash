package com.transfer.flash.core.ptt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * OPT-O12-03 / PTTD-04: the first `TargetDataLine` open on this host costs about a second (EXP-018), and that second
 * used to be spent inside the first PTT press. `JvmPttAudio.warmUpInBackground()` pays it at engine construction
 * instead: both lines are opened once and closed immediately, on a daemon thread.
 *
 * What is proven here: both kinds of line are opened exactly once, they are closed again (the microphone is not left
 * held), a second call does nothing, and a host with no audio device does not throw.
 */
class JvmPttWarmUpTest {

    private fun awaitUntil(what: String, timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for: $what")
            Thread.sleep(2)
        }
    }

    @Test
    fun `warm-up opens both lines once and closes them`() {
        val lines = FakePcmLines()
        val audio = JvmPttAudio(lines)

        audio.warmUpInBackground()

        awaitUntil("both lines opened") { lines.inputs.size == 1 && lines.outputs.size == 1 }
        awaitUntil("both lines closed") {
            lines.inputs.snapshot().single().closed && lines.outputs.snapshot().single().closed
        }
        val input = lines.inputs.snapshot().single()
        assertTrue(input.stopped, "the microphone is stopped, not just closed")
        assertEquals(listOf(16_000), lines.inputRatesTried.snapshot(), "warmed at the engine's usual rate")
    }

    @Test
    fun `a second warm-up does nothing`() {
        val lines = FakePcmLines()
        val audio = JvmPttAudio(lines)

        audio.warmUpInBackground()
        audio.warmUpInBackground()
        awaitUntil("both lines opened") { lines.inputs.size == 1 && lines.outputs.size == 1 }
        Thread.sleep(100)

        assertEquals(1, lines.inputs.size, "the warm-up is idempotent")
        assertEquals(1, lines.outputs.size)
    }

    @Test
    fun `a host with no audio lines does not throw`() {
        val lines = FakePcmLines(supportedRates = emptySet())
        val audio = JvmPttAudio(lines)

        audio.warmUpInBackground()

        awaitUntil("both opens were attempted") {
            lines.inputRatesTried.size == 1 && lines.outputs.size == 0
        }
    }
}
