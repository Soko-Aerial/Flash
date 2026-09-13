package com.transfer.flash.core.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM tests for the audio-protective governor (ERROR-031 / D8).
 *
 * The whole point of extracting the policy from [FlashCallSession] is that these cases are
 * reachable here at all: "does video step down when audio degrades, and does it come back" is
 * otherwise a two-phone experiment over a congested hotspot.
 */
class CallQualityGovernorTest {

    private fun clean() = CallQualitySample(rttMs = 20, audioJitterMs = 3, lossFraction = 0.0)

    private fun jittery() = CallQualitySample(rttMs = 20, audioJitterMs = 60, lossFraction = 0.0)

    private fun lossy() = CallQualitySample(rttMs = 20, audioJitterMs = 3, lossFraction = 0.10)

    private fun laggy() = CallQualitySample(rttMs = 400, audioJitterMs = 3, lossFraction = 0.0)

    /** Between the two thresholds: bad enough not to earn recovery, not bad enough to degrade. */
    private fun greyZone() = CallQualitySample(rttMs = 180, audioJitterMs = 20, lossFraction = 0.02)

    @Test
    fun `a healthy call gives up no video at all`() {
        val governor = CallQualityGovernor()
        repeat(30) { assertNull(governor.onSample(clean())) }
        assertEquals(VideoConcession.FULL, governor.level)
    }

    @Test
    fun `each audio symptom on its own is enough to degrade`() {
        listOf(jittery(), lossy(), laggy()).forEach { bad ->
            val governor = CallQualityGovernor()
            assertNull(governor.onSample(bad), "one bad sample must not be enough: $bad")
            assertEquals(VideoConcession.REDUCED_BITRATE, governor.onSample(bad))
        }
    }

    /**
     * The ladder is walked one rung per sustained-bad window, never several at once: a two-second
     * burst must cost bitrate, not the camera.
     */
    @Test
    fun `sustained trouble walks the ladder one rung at a time and stops at the bottom`() {
        val governor = CallQualityGovernor()
        val seen = mutableListOf<VideoConcession>()
        repeat(20) { governor.onSample(jittery())?.let(seen::add) }

        assertEquals(
            listOf(
                VideoConcession.REDUCED_BITRATE,
                VideoConcession.REDUCED_RESOLUTION,
                VideoConcession.PAUSED,
            ),
            seen,
        )
        assertEquals(VideoConcession.PAUSED, governor.level)
    }

    @Test
    fun `video comes back rung by rung once the link is clean again`() {
        val governor = CallQualityGovernor()
        repeat(20) { governor.onSample(jittery()) }
        assertEquals(VideoConcession.PAUSED, governor.level)

        val seen = mutableListOf<VideoConcession>()
        repeat(30) { governor.onSample(clean())?.let(seen::add) }

        assertEquals(
            listOf(
                VideoConcession.REDUCED_RESOLUTION,
                VideoConcession.REDUCED_BITRATE,
                VideoConcession.FULL,
            ),
            seen,
        )
    }

    /**
     * Hysteresis, the property that keeps the picture from strobing: recovery costs more clean
     * samples than degradation costs bad ones, so a link that alternates good/bad cannot pump the
     * ladder up and down.
     */
    @Test
    fun `recovery is strictly slower than degradation`() {
        val degradeGovernor = CallQualityGovernor()
        var degradeSamples = 0
        while (degradeGovernor.level == VideoConcession.FULL) {
            degradeGovernor.onSample(jittery())
            degradeSamples++
        }

        val recoverGovernor = CallQualityGovernor()
        repeat(2) { recoverGovernor.onSample(jittery()) }
        assertEquals(VideoConcession.REDUCED_BITRATE, recoverGovernor.level)
        var recoverSamples = 0
        while (recoverGovernor.level != VideoConcession.FULL) {
            recoverGovernor.onSample(clean())
            recoverSamples++
        }

        assertTrue(recoverSamples > degradeSamples, "recovery ($recoverSamples) must take longer than degradation ($degradeSamples)")
    }

    @Test
    fun `alternating good and bad samples never reach a step change`() {
        val governor = CallQualityGovernor()
        repeat(40) { index ->
            val verdict = governor.onSample(if (index % 2 == 0) jittery() else clean())
            assertNull(verdict, "alternating link must hold its rung, changed at $index")
        }
        assertEquals(VideoConcession.FULL, governor.level)
    }

    /** The dead band holds position: no degradation, and no recovery either. */
    @Test
    fun `the grey zone neither degrades nor recovers`() {
        val governor = CallQualityGovernor()
        repeat(20) { assertNull(governor.onSample(greyZone())) }
        assertEquals(VideoConcession.FULL, governor.level)

        repeat(2) { governor.onSample(jittery()) }
        assertEquals(VideoConcession.REDUCED_BITRATE, governor.level)
        repeat(20) { assertNull(governor.onSample(greyZone())) }
        assertEquals(VideoConcession.REDUCED_BITRATE, governor.level)
    }

    /**
     * A grey-zone sample forgets one bad sample rather than all of them, so an every-other-second
     * problem still adds up — otherwise a link that is bad half the time reads as fine.
     */
    @Test
    fun `intermittent trouble still eventually costs video`() {
        val governor = CallQualityGovernor()
        var stepped = false
        repeat(30) { index ->
            val sample = if (index % 3 == 0) greyZone() else jittery()
            if (governor.onSample(sample) != null) stepped = true
        }
        assertTrue(stepped, "two-in-three bad seconds must eventually step video down")
    }

    @Test
    fun `a sample that measured nothing is not treated as clean`() {
        val governor = CallQualityGovernor()
        repeat(2) { governor.onSample(jittery()) }
        assertEquals(VideoConcession.REDUCED_BITRATE, governor.level)

        val blank = CallQualitySample(rttMs = null, audioJitterMs = null, lossFraction = null)
        repeat(20) { assertNull(governor.onSample(blank)) }
        assertEquals(VideoConcession.REDUCED_BITRATE, governor.level)
    }

    @Test
    fun `reset returns to full with no accumulated evidence`() {
        val governor = CallQualityGovernor()
        repeat(20) { governor.onSample(jittery()) }
        assertEquals(VideoConcession.PAUSED, governor.level)

        governor.reset()
        assertEquals(VideoConcession.FULL, governor.level)
        // Fresh evidence required: the pre-reset bad streak must not carry over.
        assertNull(governor.onSample(jittery()))
    }

    @Test
    fun `only the top rung keeps sending video and holds the bitrate floor`() {
        assertTrue(VideoConcession.FULL.videoActive)
        assertTrue(VideoConcession.REDUCED_BITRATE.videoActive)
        assertTrue(VideoConcession.REDUCED_RESOLUTION.videoActive)
        assertFalse(VideoConcession.PAUSED.videoActive)

        assertTrue(VideoConcession.FULL.holdsBitrateFloor)
        VideoConcession.entries.filter { it != VideoConcession.FULL }.forEach {
            assertFalse(it.holdsBitrateFloor, "$it must not pin a bitrate floor")
        }
    }

    /**
     * Every concession has to be explainable. A rung that silently makes the picture worse is
     * indistinguishable from a bug, which is the failure this string exists to prevent.
     */
    @Test
    fun `every concession explains itself and full explains nothing`() {
        assertNull(VideoConcession.FULL.reason)
        VideoConcession.entries.filter { it != VideoConcession.FULL }.forEach { level ->
            val reason = level.reason
            assertTrue(!reason.isNullOrBlank(), "$level must carry a reason")
            assertTrue(reason!!.contains("Video"), "$level must name video: $reason")
            assertTrue(reason.contains("audio"), "$level must name audio: $reason")
        }
    }

    @Test
    fun `the ladder descends monotonically in cost`() {
        val rungs = VideoConcession.entries
        rungs.zipWithNext { gentler, harsher ->
            assertTrue(harsher.bitrateScale <= gentler.bitrateScale, "$harsher must not spend more bitrate than $gentler")
            assertTrue(harsher.scaleResolutionDownBy >= gentler.scaleResolutionDownBy, "$harsher must not send more pixels than $gentler")
        }
        assertNull(VideoConcession.FULL.gentler)
        assertNull(VideoConcession.PAUSED.harsher)
    }
}
