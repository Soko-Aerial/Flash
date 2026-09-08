package com.transfer.flash.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for UI-040 pure logic: PCM synthesis math + playback policy. Runs on both the Android
 * host JVM and desktop JVM — the synthesis half of the sound stack is `commonMain`, so desktop
 * gets the same coverage even though its `rememberFlashSounds` actual plays nothing.
 * The AudioTrack backend (`FlashSoundPlayer`) is Android-dependent and covered by device QA.
 */
class FlashSoundsTest {

    private val sampleRate = FlashSoundSynth.SAMPLE_RATE_HZ

    // ---- Synthesis: length & bounds -------------------------------------------------

    @Test
    fun render_lengthMatchesSumOfSegmentDurations() {
        for (sound in FlashSound.entries) {
            val expected = (FlashSoundSynth.totalDurationMs(sound) * sampleRate / 1000L).toInt()
            assertEquals(expected, FlashSoundSynth.render(sound).size, "sound=$sound")
        }
    }

    @Test
    fun render_lengthScalesWithSampleRate() {
        val expected = (FlashSoundSynth.totalDurationMs(FlashSound.MessageDelivered) * 8000 / 1000L).toInt()
        assertEquals(expected, FlashSoundSynth.render(FlashSound.MessageDelivered, 8000).size)
    }

    @Test
    fun render_amplitudeNeverExceeds16BitRange() {
        for (sound in FlashSound.entries) {
            val maxAbs = FlashSoundSynth.render(sound).maxOf { kotlin.math.abs(it.toInt()) }
            assertTrue(maxAbs <= Short.MAX_VALUE.toInt(), "sound=$sound max=$maxAbs")
        }
    }

    @Test
    fun render_respectsPeakAmplitudeCeiling() {
        val ceiling = (FlashSound.Error.peakAmplitude * Short.MAX_VALUE * 1.05).toInt()
        val maxAbs = FlashSoundSynth.render(FlashSound.Error).maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(maxAbs in 1..ceiling)
    }

    // ---- Synthesis: envelope ---------------------------------------------------------

    @Test
    fun envelope_decaysMonotonicallyAfterAttack_singleToneEvents() {
        val singleTone = listOf(
            FlashSound.MessageDelivered,
            FlashSound.MessageReceived,
            FlashSound.RecordingStart,
            FlashSound.RecordingStop,
        )
        val attackFrames = 5.0 * sampleRate / 1000.0
        for (sound in singleTone) {
            val samples = FlashSoundSynth.render(sound)
            val peaks = collectPositivePeaks(samples, startAt = attackFrames.toInt() + 1)
            assertTrue(peaks.size >= 5, "sound=$sound too few peaks: ${peaks.size}")
            peaks.zipWithNext().forEach { (prev, next) ->
                assertTrue(
                    next <= prev * 1.02,
                    "sound=$sound non-monotonic decay: $prev -> $next",
                )
            }
        }
    }

    @Test
    fun render_silenceGapInError_isAllZeros() {
        val samples = FlashSoundSynth.render(FlashSound.Error)
        val seg1Frames = 130 * sampleRate / 1000
        val gapFrames = 60 * sampleRate / 1000
        for (i in seg1Frames until seg1Frames + gapFrames) {
            assertEquals(0, samples[i].toInt(), "index=$i")
        }
    }

    @Test
    fun error_hasTwoAudiblePulses() {
        val samples = FlashSoundSynth.render(FlashSound.Error)
        val seg1Frames = 130 * sampleRate / 1000
        val pulse2Start = seg1Frames + 60 * sampleRate / 1000
        val threshold = (FlashSound.Error.peakAmplitude * Short.MAX_VALUE * 0.3).toInt()
        val pulse1Max = samples.copyOfRange(0, seg1Frames).maxOf { kotlin.math.abs(it.toInt()) }
        val pulse2Max = samples.copyOfRange(pulse2Start, samples.size).maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue(pulse1Max >= threshold)
        assertTrue(pulse2Max >= threshold)
    }

    // ---- Frequencies ------------------------------------------------------------------

    @Test
    fun zeroCrossingEstimate_matchesSpecifiedFrequencyWithinTolerance() {
        val sound = FlashSound.MessageDelivered
        val specFreq = FlashSoundSynth.dominantFrequencyHz(sound)
        val samples = FlashSoundSynth.render(sound)
        val skip = (6.0 * sampleRate / 1000.0).toInt()
        val end = samples.size - (4.0 * sampleRate / 1000.0).toInt()
        var crossings = 0
        for (i in skip until end) {
            if ((samples[i] >= 0) != (samples[i - 1] >= 0)) crossings++
        }
        val n = end - skip
        val estimated = crossings.toDouble() * sampleRate / (2.0 * n)
        assertEquals(specFreq, estimated, specFreq * 0.05)
    }

    @Test
    fun everyEvent_hasDistinctDominantFrequency() {
        val freqs = FlashSound.entries.map { FlashSoundSynth.dominantFrequencyHz(it) }
        assertEquals(FlashSound.entries.size, freqs.toSet().size)
        assertTrue(freqs.all { it > 0.0 })
    }

    @Test
    fun messageSent_isRisingPair() {
        val segs = FlashSound.MessageSent.segments.filter { !it.isSilence }
        assertEquals(2, segs.size)
        assertTrue(segs[1].freqHz > segs[0].freqHz)
    }

    @Test
    fun received_isSofterAndLowerThanSent() {
        assertTrue(
            FlashSound.MessageReceived.segments[0].freqHz <
                FlashSound.MessageSent.segments[0].freqHz,
        )
        assertTrue(
            FlashSound.MessageReceived.peakAmplitude < FlashSound.MessageSent.peakAmplitude,
        )
    }

    // ---- Vocabulary freeze ------------------------------------------------------------

    @Test
    fun soundVocabulary_hasExactlyTheDocumentedEvents() {
        assertEquals(
            listOf(
                "MessageSent", "MessageDelivered", "MessageReceived", "RecordingStart",
                "RecordingStop", "TransferComplete", "PairingSuccess", "Error",
            ),
            FlashSound.entries.map { it.name },
        )
    }

    // ---- Policy truth table -------------------------------------------------------------
    // Ringer/interruption values mirror AudioManager.RINGER_MODE_* and
    // NotificationManager.INTERRUPTION_FILTER_*. Since Phase 18 these are also mirrored as
    // private literals inside `FlashSounds.kt` (commonMain cannot import `android.media`), so
    // this block is now the independent second witness for those five values: if the platform
    // ever renumbered them, production and test would have to be changed together, on purpose.

    private val ringerNormal = 2   // AudioManager.RINGER_MODE_NORMAL
    private val ringerSilent = 0   // AudioManager.RINGER_MODE_SILENT
    private val ringerVibrate = 1  // AudioManager.RINGER_MODE_VIBRATE
    private val filterAll = 1      // INTERRUPTION_FILTER_ALL
    private val filterPriority = 2
    private val filterNone = 3
    private val filterAlarms = 4
    private val filterUnknown = 0

    @Test
    fun policy_disabledMeansNeverPlay_regardlessOfSystemState() {
        assertFalse(FlashSoundPolicy.shouldPlay(soundsEnabled = false))
        assertFalse(
            FlashSoundPolicy.shouldPlay(soundsEnabled = false, ringerMode = ringerSilent),
        )
        assertFalse(
            FlashSoundPolicy.shouldPlay(
                soundsEnabled = false,
                interruptionFilter = filterNone,
            ),
        )
    }

    @Test
    fun policy_enabledAndQuietSystem_playsOnlyInFullyPermissiveState() {
        assertTrue(
            FlashSoundPolicy.shouldPlay(
                soundsEnabled = true,
                ringerMode = ringerNormal,
                interruptionFilter = filterAll,
            ),
        )
        for (ringer in intArrayOf(ringerSilent, ringerVibrate)) {
            assertFalse(
                FlashSoundPolicy.shouldPlay(
                    soundsEnabled = true,
                    ringerMode = ringer,
                    interruptionFilter = filterAll,
                ),
            )
        }
        for (filter in intArrayOf(filterPriority, filterAlarms, filterNone)) {
            assertFalse(
                FlashSoundPolicy.shouldPlay(
                    soundsEnabled = true,
                    ringerMode = ringerNormal,
                    interruptionFilter = filter,
                ),
            )
        }
    }

    @Test
    fun policy_unknownInterruptionFilter_isAllowed() {
        assertTrue(
            FlashSoundPolicy.shouldPlay(
                soundsEnabled = true,
                ringerMode = ringerNormal,
                interruptionFilter = filterUnknown,
            ),
        )
    }

    @Test
    fun settings_defaultIsOff() {
        assertFalse(FlashSoundSettings.soundsEnabled)
    }
}

private fun collectPositivePeaks(samples: ShortArray, startAt: Int): List<Double> {
    val peaks = mutableListOf<Double>()
    var i = startAt.coerceAtLeast(1)
    while (i < samples.size - 1) {
        val v = samples[i].toInt()
        if (v > samples[i - 1].toInt() && v >= samples[i + 1].toInt() && v > 2000) {
            peaks.add(v.toDouble())
            i += 2
        } else {
            i++
        }
    }
    return peaks
}
