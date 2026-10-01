package com.transfer.flash.core.calling

import com.transfer.flash.core.common.perf.FlashVideoProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The encoder numbers one leg's copy of this device's video gets in a group call. Pure: the floor
 * and the voice-priority rungs are decided here and only applied by the session, so they are
 * checked without a PeerConnection.
 */
class GroupVideoTuningTest {

    private val high = FlashVideoProfile.HIGH.copy(captureHeight = 720)

    @Test
    fun `the floor never reaches the copy's own ceiling, so congestion control can still move the encoder`() {
        // Audit finding: the HIGH profile's 600 kbps floor on a 360p copy (ceiling 450) pinned the encoder at a constant 450.
        val tuning = groupVideoTuning(high, height = 360, concession = VideoConcession.FULL, active = true)
        assertEquals(450_000, tuning.maxBitrateBps)
        val floor = assertNotNull(tuning.minBitrateBps)
        assertTrue(floor < tuning.maxBitrateBps, "floor $floor must sit below the ceiling ${tuning.maxBitrateBps}")
        assertEquals(225_000, floor)
    }

    @Test
    fun `a copy that has room for the profile floor keeps it`() {
        val tuning = groupVideoTuning(high, height = 720, concession = VideoConcession.FULL, active = true)
        assertEquals(1_800_000, tuning.maxBitrateBps)
        assertEquals(600_000, tuning.minBitrateBps)
        val medium = groupVideoTuning(FlashVideoProfile.MEDIUM, height = 540, concession = VideoConcession.FULL, active = true)
        assertEquals(900_000, medium.maxBitrateBps)
        assertEquals(250_000, medium.minBitrateBps)
    }

    @Test
    fun `an old client or an undecided leg gets the full camera profile`() {
        val tuning = groupVideoTuning(FlashVideoProfile.MEDIUM, height = null, concession = VideoConcession.FULL, active = true)
        assertEquals(900_000, tuning.maxBitrateBps)
        assertEquals(1.0, tuning.scaleResolutionDownBy)
        assertEquals(24.0, tuning.maxFramerate)
    }

    @Test
    fun `the copy is scaled down from the capture height`() {
        val tuning = groupVideoTuning(high, height = 360, concession = VideoConcession.FULL, active = true)
        assertEquals(2.0, tuning.scaleResolutionDownBy)
        assertTrue(tuning.maintainFramerate)
        assertTrue(tuning.demoteForVoice)
    }

    @Test
    fun `every concession rung drops the floor, halves or quarters the ceiling and the last stops the video`() {
        val full = groupVideoTuning(high, 720, VideoConcession.FULL, active = true)
        val reducedBitrate = groupVideoTuning(high, 720, VideoConcession.REDUCED_BITRATE, active = true)
        val reducedResolution = groupVideoTuning(high, 720, VideoConcession.REDUCED_RESOLUTION, active = true)
        val paused = groupVideoTuning(high, 720, VideoConcession.PAUSED, active = true)

        assertNotNull(full.minBitrateBps)
        for (rung in listOf(reducedBitrate, reducedResolution, paused)) assertNull(rung.minBitrateBps)

        assertEquals(full.maxBitrateBps / 2, reducedBitrate.maxBitrateBps)
        assertEquals(1.0, full.scaleResolutionDownBy)
        assertEquals(1.0, reducedBitrate.scaleResolutionDownBy)
        assertEquals(full.maxBitrateBps / 4, reducedResolution.maxBitrateBps)
        assertEquals(2.0, reducedResolution.scaleResolutionDownBy)

        assertTrue(full.active && reducedBitrate.active && reducedResolution.active)
        assertFalse(paused.active)
    }

    @Test
    fun `a muted microphone is never a talker, however loud its capture reads`() {
        // Audit finding: the capture level of a muted microphone could still evict a camera watcher.
        assertFalse(isLocalTalker(micMuted = true, audioLevel = 0.9))
        assertTrue(isLocalTalker(micMuted = false, audioLevel = 0.9))
        assertFalse(isLocalTalker(micMuted = false, audioLevel = 0.005))
        assertFalse(isLocalTalker(micMuted = false, audioLevel = null))
    }

    @Test
    fun `a leg that is not a watcher stays off whatever its rung`() {
        assertFalse(groupVideoTuning(high, 540, VideoConcession.FULL, active = false).active)
        assertFalse(groupVideoTuning(high, 540, VideoConcession.REDUCED_BITRATE, active = false).active)
    }
}
