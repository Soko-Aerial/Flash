package com.transfer.flash.core.common.perf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anchors the tier rules to the three devices in the ERROR-033 field report plus the two device
 * classes the report told us to anticipate (a wearable, and hardware below the BelFone).
 *
 * These are regression tests for a *policy*, which is a slightly unusual thing to pin. The reason
 * they are worth pinning: the classifier's thresholds are the only place a retune can silently
 * turn a working device into a minimalist one, and that regression is invisible in a build log.
 */
class FlashPerformanceClassifierTest {

    // ----------------------------------------------------------------- field-report devices

    /**
     * BelFone SCP810: 2 GB (reports ~1800 MB), Android 8.1, 480x640, 2.4 GHz-only b/g/n.
     * Caught by the RAM gate; the display and radio would each catch it independently.
     */
    private val belfone = FlashDeviceProfile(
        totalRamMb = 1_800,
        isLowRamDevice = false,
        apiLevel = 27,
        cpuCores = 4,
        screenPixels = 480 * 640,
        isWatch = false,
        supports5GHz = false,
        hasHardwareVideoEncoder = false,
    )

    private val pixel7 = FlashDeviceProfile(
        totalRamMb = 7_400,
        apiLevel = 34,
        cpuCores = 8,
        screenPixels = 1080 * 2400,
        supports5GHz = true,
        hasHardwareVideoEncoder = true,
    )

    /** An Infinix in the 4 GB / API 31 / 8-core shape: one concern (RAM), so it stays HIGH. */
    private val infinix = FlashDeviceProfile(
        totalRamMb = 3_700,
        apiLevel = 31,
        cpuCores = 8,
        screenPixels = 720 * 1600,
        supports5GHz = true,
        hasHardwareVideoEncoder = true,
    )

    @Test
    fun belfone_is_low() {
        val verdict = FlashPerformanceClassifier.classify(belfone)
        assertEquals(FlashPerformanceMode.LOW, verdict.mode)
        assertTrue("reason should name RAM: ${verdict.reason}", verdict.reason.contains("RAM"))
    }

    @Test
    fun pixel7_is_high_with_no_concerns() {
        val verdict = FlashPerformanceClassifier.classify(pixel7)
        assertEquals(FlashPerformanceMode.HIGH, verdict.mode)
        assertEquals("no concerns", verdict.reason)
    }

    @Test
    fun infinix_stays_high_on_a_single_concern() {
        assertEquals(FlashPerformanceMode.HIGH, FlashPerformanceClassifier.classify(infinix).mode)
    }

    // ----------------------------------------------------------------- gates

    @Test
    fun wearable_is_low_regardless_of_everything_else() {
        val verdict = FlashPerformanceClassifier.classify(pixel7.copy(isWatch = true))
        assertEquals(FlashPerformanceMode.LOW, verdict.mode)
        assertEquals("wearable", verdict.reason)
    }

    @Test
    fun platform_low_ram_flag_is_conclusive() {
        val verdict = FlashPerformanceClassifier.classify(pixel7.copy(isLowRamDevice = true))
        assertEquals(FlashPerformanceMode.LOW, verdict.mode)
    }

    @Test
    fun tiny_display_is_low_even_with_plenty_of_ram() {
        // The class of device below the BelFone: adequate RAM, a postage-stamp panel.
        val verdict = FlashPerformanceClassifier.classify(
            pixel7.copy(screenPixels = 320 * 320),
        )
        assertEquals(FlashPerformanceMode.LOW, verdict.mode)
        assertTrue(verdict.reason.contains("display"))
    }

    @Test
    fun pre_oreo_is_low() {
        assertEquals(
            FlashPerformanceMode.LOW,
            FlashPerformanceClassifier.classify(pixel7.copy(apiLevel = 24)).mode,
        )
    }

    @Test
    fun dual_core_is_low() {
        assertEquals(
            FlashPerformanceMode.LOW,
            FlashPerformanceClassifier.classify(pixel7.copy(cpuCores = 2)).mode,
        )
    }

    // ----------------------------------------------------------------- concern counting

    @Test
    fun two_concerns_demote_to_medium() {
        // 4 GB-class RAM (one concern) + four cores (a second) and nothing gate-worthy.
        val verdict = FlashPerformanceClassifier.classify(
            infinix.copy(cpuCores = 4),
        )
        assertEquals(FlashPerformanceMode.MEDIUM, verdict.mode)
    }

    @Test
    fun a_missing_hardware_encoder_alone_does_not_demote() {
        assertEquals(
            FlashPerformanceMode.HIGH,
            FlashPerformanceClassifier.classify(
                pixel7.copy(hasHardwareVideoEncoder = false),
            ).mode,
        )
    }

    @Test
    fun unknown_facts_never_count_against_a_device() {
        val allUnknown = FlashDeviceProfile()
        val verdict = FlashPerformanceClassifier.classify(allUnknown)
        assertEquals(FlashPerformanceMode.HIGH, verdict.mode)
        assertEquals("no concerns", verdict.reason)
    }

    // ----------------------------------------------------------------- persistence tokens

    @Test
    fun keys_round_trip() {
        FlashPerformanceMode.entries.forEach { mode ->
            assertEquals(mode, FlashPerformanceMode.fromKey(FlashPerformanceMode.toKey(mode)))
        }
    }

    @Test
    fun auto_and_unknown_tokens_both_mean_detect() {
        assertNull(FlashPerformanceMode.fromKey(FlashPerformanceMode.Keys.AUTO))
        assertNull(FlashPerformanceMode.fromKey(null))
        assertNull(FlashPerformanceMode.fromKey("ludicrous"))
        assertEquals(FlashPerformanceMode.Keys.AUTO, FlashPerformanceMode.toKey(null))
    }

    @Test
    fun keys_are_case_insensitive_on_read() {
        assertEquals(FlashPerformanceMode.LOW, FlashPerformanceMode.fromKey("LOW"))
    }

    // ----------------------------------------------------------------- profile invariants

    @Test
    fun only_high_animates() {
        assertTrue(FlashPerformanceMode.LOW.reduceMotion)
        assertTrue(FlashPerformanceMode.MEDIUM.reduceMotion)
        assertFalse(FlashPerformanceMode.HIGH.reduceMotion)
    }

    /** The field report's headline requirement: 540p at MEDIUM and below it at LOW. */
    @Test
    fun video_is_capped_at_540p_and_below() {
        assertEquals(540, FlashPerformanceMode.MEDIUM.video.captureHeight)
        assertTrue(FlashPerformanceMode.LOW.video.captureHeight < 540)
        assertEquals(1080, FlashPerformanceMode.HIGH.video.captureHeight)
    }

    @Test
    fun video_bitrate_window_is_ordered_at_every_tier() {
        FlashPerformanceMode.entries.forEach { mode ->
            val v = mode.video
            assertTrue(
                "$mode: min ${v.minBitrateKbps} < start ${v.startBitrateKbps} < max ${v.maxBitrateKbps}",
                v.minBitrateKbps < v.startBitrateKbps && v.startBitrateKbps < v.maxBitrateKbps,
            )
        }
    }

    @Test
    fun tiers_are_monotone_in_cost() {
        val low = FlashPerformanceMode.LOW
        val medium = FlashPerformanceMode.MEDIUM
        val high = FlashPerformanceMode.HIGH
        assertTrue(low.video.maxBitrateKbps < medium.video.maxBitrateKbps)
        assertTrue(medium.video.maxBitrateKbps < high.video.maxBitrateKbps)
        assertTrue(low.video.captureFps <= medium.video.captureFps)
        assertTrue(medium.video.captureFps <= high.video.captureFps)
        // Cheaper tier = never MORE packets: a longer-or-equal ptime and a lower-or-equal
        // packet rate. Only LOW is strictly cheaper than HIGH today: the be57111 packetization
        // pass moved HIGH onto MEDIUM's 20 ms (the WebRTC global standard), so 20 ms equality
        // between those two tiers is intended, not drift.
        assertTrue(low.voice.ptimeMs >= medium.voice.ptimeMs)
        assertTrue(medium.voice.ptimeMs >= high.voice.ptimeMs)
        assertTrue(low.voice.packetsPerSecond <= medium.voice.packetsPerSecond)
        assertTrue(medium.voice.packetsPerSecond <= high.voice.packetsPerSecond)
        // And the endpoints stay an honest distance apart: LOW is a genuine cut, not a rounding.
        assertTrue(low.voice.packetsPerSecond < high.voice.packetsPerSecond)
    }

    /**
     * The mechanism behind "even with only voice at 25 kbps it still lags": ptime is the knob that
     * moves header cost. HIGH was retuned from 10 ms to 20 ms (the WebRTC global standard) in the
     * be57111 packetization pass, halving it to 50 pps — ~20 kbit/s of header against 32 kbit/s
     * of speech. LOW (60 ms) cuts the rate and the header bill by roughly three.
     */
    @Test
    fun ptime_choice_is_what_moves_header_overhead() {
        assertEquals(50, FlashPerformanceMode.HIGH.voice.packetsPerSecond)
        assertEquals(16, FlashPerformanceMode.LOW.voice.packetsPerSecond)
        assertTrue(FlashPerformanceMode.HIGH.voice.headerOverheadKbps >= 20)
        assertTrue(FlashPerformanceMode.LOW.voice.headerOverheadKbps <= 8)
    }

    @Test
    fun opus_ptime_values_are_codec_legal() {
        val legal = setOf(10, 20, 40, 60)
        FlashPerformanceMode.entries.forEach { mode ->
            assertTrue(
                "$mode ptime=${mode.voice.ptimeMs}",
                mode.voice.ptimeMs in legal,
            )
        }
    }

    /**
     * `WsKeepalive` forgives a tick that slipped by `STALL_FACTOR` intervals; a liveness window
     * inside that slack would turn ordinary dispatcher lateness into a verdict. The factor is 2,
     * duplicated here because `:core:common` cannot depend on `:core:network`.
     */
    @Test
    fun liveness_window_clears_the_stall_forgiveness_band() {
        FlashPerformanceMode.entries.forEach { mode ->
            val t = mode.transport
            assertTrue(
                "$mode: liveness ${t.livenessTimeoutMs} vs ping ${t.pingIntervalMs}",
                t.livenessTimeoutMs > t.pingIntervalMs * 2,
            )
        }
    }

    /** A grace window shorter than the restart it is meant to cover makes the restart pointless. */
    @Test
    fun disconnect_grace_covers_an_ice_restart_attempt() {
        FlashPerformanceMode.entries.forEach { mode ->
            val t = mode.transport
            assertTrue(
                "$mode: grace ${t.callDisconnectGraceMs} vs restart floor ${t.iceRestartMinIntervalMs}",
                t.callDisconnectGraceMs > t.iceRestartMinIntervalMs * 2,
            )
        }
    }

    @Test
    fun cheaper_tiers_come_back_faster() {
        assertTrue(
            FlashPerformanceMode.LOW.transport.reconnectCapMs <
                FlashPerformanceMode.MEDIUM.transport.reconnectCapMs,
        )
        assertTrue(
            FlashPerformanceMode.MEDIUM.transport.reconnectCapMs <
                FlashPerformanceMode.HIGH.transport.reconnectCapMs,
        )
    }

    @Test
    fun transfer_profile_bounds_scale_with_performance_tier() {
        val low = FlashPerformanceMode.LOW.transfer
        val med = FlashPerformanceMode.MEDIUM.transfer
        val high = FlashPerformanceMode.HIGH.transfer

        assertEquals(1, low.streamCount)
        assertEquals(2, med.streamCount)
        assertEquals(4, high.streamCount)

        assertTrue(low.chunkSizeBytes <= med.chunkSizeBytes)
        assertTrue(med.chunkSizeBytes <= high.chunkSizeBytes)

        assertTrue(low.feedBufferFrames < med.feedBufferFrames)
        assertTrue(med.feedBufferFrames < high.feedBufferFrames)

        assertTrue(low.sharedBufferFrames < med.sharedBufferFrames)
        assertTrue(med.sharedBufferFrames < high.sharedBufferFrames)

        assertFalse("LOW mode disables video thumbnail extraction", low.allowVideoThumbnails)
        assertTrue("HIGH mode enables video thumbnail extraction", high.allowVideoThumbnails)

        assertTrue(low.sqliteCacheSizeKb < med.sqliteCacheSizeKb)
        assertTrue(med.sqliteCacheSizeKb < high.sqliteCacheSizeKb)
    }
}
