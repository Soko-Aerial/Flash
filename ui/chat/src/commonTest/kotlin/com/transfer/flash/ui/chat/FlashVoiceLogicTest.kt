package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashVoiceAttachmentUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashVoiceLogicTest {

    @Test
    fun `duration formats as m-ss`() {
        assertEquals("0:00", FlashVoiceMath.formatDuration(0L))
        assertEquals("0:05", FlashVoiceMath.formatDuration(5_000L))
        assertEquals("0:59", FlashVoiceMath.formatDuration(59_999L))
        assertEquals("1:00", FlashVoiceMath.formatDuration(60_000L))
        assertEquals("23:04", FlashVoiceMath.formatDuration(1_384_000L))
        assertEquals("0:00", FlashVoiceMath.formatDuration(-3_000L))
    }

    @Test
    fun `amplitudes resample to exactly the requested bar count`() {
        val amplitudes = List(120) { it % 100 }
        val bars = FlashVoiceMath.bucketAmplitudes(amplitudes, 40)

        assertEquals(40, bars.size)
        assertTrue(bars.all { it in FlashVoiceMath.MIN_BAR_HEIGHT..1f })
    }

    @Test
    fun `bucketing preserves peaks within each bucket`() {
        // 4 samples -> 2 buckets; bucket 0 covers samples 0-1 (peak 80), bucket 1 covers 2-3 (peak 100)
        val bars = FlashVoiceMath.bucketAmplitudes(listOf(10, 80, 5, 100), 2)

        assertEquals(0.8f, bars[0])
        assertEquals(1.0f, bars[1])
    }

    @Test
    fun `empty amplitudes produce minimal visible bars`() {
        val bars = FlashVoiceMath.bucketAmplitudes(emptyList(), 12)

        assertEquals(12, bars.size)
        assertTrue(bars.all { it == FlashVoiceMath.MIN_BAR_HEIGHT })
    }

    @Test
    fun `tap fraction clamps into zero one range`() {
        assertEquals(0f, FlashVoiceMath.fractionForTap(tapX = -20f, widthPx = 400f))
        assertEquals(0.25f, FlashVoiceMath.fractionForTap(tapX = 100f, widthPx = 400f))
        assertEquals(1f, FlashVoiceMath.fractionForTap(tapX = 500f, widthPx = 400f))
        assertEquals(0f, FlashVoiceMath.fractionForTap(tapX = 50f, widthPx = 0f))
    }

    @Test
    fun `tap maps to snapped bar index`() {
        assertEquals(0, FlashVoiceMath.barIndexForTap(tapX = 5f, widthPx = 400f, barCount = 40))
        assertEquals(10, FlashVoiceMath.barIndexForTap(tapX = 100f, widthPx = 400f, barCount = 40))
        assertEquals(39, FlashVoiceMath.barIndexForTap(tapX = 399f, widthPx = 400f, barCount = 40))
        assertEquals(0, FlashVoiceMath.barIndexForTap(tapX = 100f, widthPx = 0f, barCount = 40))
    }

    @Test
    fun `elapsed derives from seek fraction`() {
        assertEquals(0L, FlashVoiceMath.elapsedForFraction(0f, 23_000L))
        assertEquals(11_500L, FlashVoiceMath.elapsedForFraction(0.5f, 23_000L))
        assertEquals(23_000L, FlashVoiceMath.elapsedForFraction(1.5f, 23_000L))
        assertEquals(0L, FlashVoiceMath.elapsedForFraction(0.5f, 0L))
    }

    @Test
    fun `speed cycles one x one point five two back`() {
        assertEquals(1.5f, FlashVoiceMath.nextPlaybackSpeed(1.0f))
        assertEquals(2.0f, FlashVoiceMath.nextPlaybackSpeed(1.5f))
        assertEquals(1.0f, FlashVoiceMath.nextPlaybackSpeed(2.0f))
    }

    @Test
    fun `speed labels match cycle values`() {
        assertEquals("1×", FlashVoiceMath.speedLabel(1.0f))
        assertEquals("1.5×", FlashVoiceMath.speedLabel(1.5f))
        assertEquals("2×", FlashVoiceMath.speedLabel(2.0f))
    }

    @Test
    fun `trailing label shows total when untouched or finished`() {
        assertEquals("0:23", FlashVoiceMath.trailingLabel(elapsedMs = 0L, durationMs = 23_000L, hasStarted = false))
        assertEquals("0:23", FlashVoiceMath.trailingLabel(elapsedMs = 23_000L, durationMs = 23_000L, hasStarted = true))
    }

    @Test
    fun `trailing label shows remaining countdown while mid-playback`() {
        assertEquals("-0:12", FlashVoiceMath.trailingLabel(elapsedMs = 11_000L, durationMs = 23_000L, hasStarted = true))
        assertEquals("-0:23", FlashVoiceMath.trailingLabel(elapsedMs = 0L, durationMs = 23_000L, hasStarted = true))
    }

    @Test
    fun `played bar count tracks progress and clamps`() {
        assertEquals(0, FlashVoiceMath.playedBarCount(elapsedMs = 0L, durationMs = 23_000L, barCount = 40))
        assertEquals(20, FlashVoiceMath.playedBarCount(elapsedMs = 11_500L, durationMs = 23_000L, barCount = 40))
        assertEquals(40, FlashVoiceMath.playedBarCount(elapsedMs = 30_000L, durationMs = 23_000L, barCount = 40))
        assertEquals(0, FlashVoiceMath.playedBarCount(elapsedMs = 5_000L, durationMs = 0L, barCount = 40))
    }

    @Test
    fun `voice attachment model carries waveform metadata`() {
        val voice = FlashVoiceAttachmentUi(
            id = "voice-9",
            uri = "file://voice/clip.aac",
            durationMs = 41_500L,
            amplitudes = listOf(10, 90, 50),
        )

        assertEquals("voice-9", voice.id)
        assertEquals(41_500L, voice.durationMs)
        assertEquals(3, voice.amplitudes.size)
        assertEquals("audio/aac", voice.mimeType)
    }
}
