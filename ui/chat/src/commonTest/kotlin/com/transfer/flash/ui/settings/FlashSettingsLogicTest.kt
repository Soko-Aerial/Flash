package com.transfer.flash.ui.settings

import com.transfer.flash.core.common.perf.FlashPerformanceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** JVM tests for UI-049 settings-page pure helpers. */
class FlashSettingsLogicTest {

    @Test
    fun `theme mode labels are user facing`() {
        assertEquals("System", FlashSettingsMath.themeModeLabel(FlashThemeMode.System))
        assertEquals("Light", FlashSettingsMath.themeModeLabel(FlashThemeMode.Light))
        assertEquals("Dark", FlashSettingsMath.themeModeLabel(FlashThemeMode.Dark))
    }

    @Test
    fun `trusted peers subtitle pluralizes honestly`() {
        assertEquals(
            "No verified devices yet",
            FlashSettingsMath.trustedPeersSubtitle(0),
        )
        assertEquals("1 device verified", FlashSettingsMath.trustedPeersSubtitle(1))
        assertEquals("4 devices verified", FlashSettingsMath.trustedPeersSubtitle(4))
    }

    @Test
    fun `system theme mode follows the OS`() {
        assertTrue(FlashSettingsMath.resolveDarkTheme(FlashThemeMode.System, systemDark = true))
        assertFalse(FlashSettingsMath.resolveDarkTheme(FlashThemeMode.System, systemDark = false))
    }

    @Test
    fun `explicit theme modes override the OS`() {
        assertFalse(FlashSettingsMath.resolveDarkTheme(FlashThemeMode.Light, systemDark = true))
        assertTrue(FlashSettingsMath.resolveDarkTheme(FlashThemeMode.Dark, systemDark = false))
    }

    /**
     * ERROR-031 / D7. The restricted copy has to name the *consequence* — a user who reads
     * "battery optimisation" has no way to connect it to messages not arriving overnight — and it
     * has to be the one that asks for a tap, because the exempt state needs no action.
     */
    @Test
    fun `the battery row explains the screen-off consequence and only asks for a tap when restricted`() {
        val restricted = FlashSettingsMath.batteryExemptionSubtitle(exempt = false)
        val exempt = FlashSettingsMath.batteryExemptionSubtitle(exempt = true)

        assertNotEquals(exempt, restricted)
        assertTrue(restricted.contains("screen"), "restricted copy must mention the screen: $restricted")
        assertTrue(exempt.contains("screen"), "exempt copy must mention the screen: $exempt")
        assertTrue(restricted.contains("tap"), "restricted copy must ask for a tap: $restricted")
        assertFalse(exempt.contains("tap"), "exempt copy must not ask for a tap: $exempt")
    }

    @Test
    fun `the battery row value states the exemption at a glance`() {
        assertEquals("Allowed", FlashSettingsMath.batteryExemptionValue(exempt = true))
        assertEquals("Restricted", FlashSettingsMath.batteryExemptionValue(exempt = false))
    }

    /**
     * ERROR-031 / D8. Both halves have to say what the switch trades, because the honest question
     * a user is asking here is "what do I lose" — and the answer differs by state, not just in
     * tone: on, the picture degrades first; off, both streams compete.
     */
    @Test
    fun `the voice priority row says which stream pays`() {
        val on = FlashSettingsMath.prioritiseVoiceSubtitle(enabled = true)
        val off = FlashSettingsMath.prioritiseVoiceSubtitle(enabled = false)

        assertNotEquals(off, on)
        assertTrue(on.contains("Video", ignoreCase = true), "on copy must name video: $on")
        assertTrue(off.contains("video", ignoreCase = true), "off copy must name video: $off")
    }

    /** ERROR-033. Auto is the absence of a pin, so it needs a label of its own alongside the tiers. */
    @Test
    fun `the performance picker labels auto separately from the three tiers`() {
        assertEquals("Auto", FlashSettingsMath.performanceModeLabel(null))
        assertEquals("Low", FlashSettingsMath.performanceModeLabel(FlashPerformanceMode.LOW))
        assertEquals("Medium", FlashSettingsMath.performanceModeLabel(FlashPerformanceMode.MEDIUM))
        assertEquals("High", FlashSettingsMath.performanceModeLabel(FlashPerformanceMode.HIGH))

        val labels = (listOf(null) + FlashPerformanceMode.entries)
            .map { FlashSettingsMath.performanceModeLabel(it) }
        assertEquals(labels.size, labels.toSet().size, "labels must be distinct: $labels")
    }

    /**
     * ERROR-033. On Auto the row has to name the tier that was chosen for the user: a device
     * misclassified LOW and a device on a bad link look identical from the outside otherwise, and
     * the pin is the only lever for the second case.
     */
    @Test
    fun `auto names the detected tier and a pin does not`() {
        val auto = FlashSettingsMath.performanceModeSubtitle(
            pinned = null,
            detected = FlashPerformanceMode.LOW,
        )
        assertTrue(auto.contains("Low"), "auto copy must name the detected tier: $auto")
        assertTrue(auto.contains("Matched to this device"), "auto copy must say it was matched: $auto")

        val pinned = FlashSettingsMath.performanceModeSubtitle(
            pinned = FlashPerformanceMode.LOW,
            detected = FlashPerformanceMode.HIGH,
        )
        assertFalse(pinned.contains("Matched"), "a pin is not 'matched to this device': $pinned")
        assertFalse(pinned.contains("1080p"), "a pin must not describe the detected tier: $pinned")
    }

    /**
     * The subtitle is the only place the tier's cost is visible, so it has to move with the tier —
     * all three of the things field testing changed (capture size, packet rate, animations).
     */
    @Test
    fun `the subtitle reports the tier actually in force`() {
        val low = FlashSettingsMath.performanceModeSubtitle(
            pinned = FlashPerformanceMode.LOW,
            detected = FlashPerformanceMode.HIGH,
        )
        val high = FlashSettingsMath.performanceModeSubtitle(
            pinned = FlashPerformanceMode.HIGH,
            detected = FlashPerformanceMode.LOW,
        )

        assertNotEquals(high, low)
        assertTrue(low.contains("360p15"), "LOW must show its capture size: $low")
        assertTrue(high.contains("1080p30"), "HIGH must show its capture size: $high")
        // 60 ms frames vs 20 ms: the packet rate is what the Belfone was actually choking on.
        assertTrue(low.contains("16 voice packets/s"), "LOW must show its packet rate: $low")
        assertTrue(high.contains("50 voice packets/s"), "HIGH must show its packet rate: $high")
        assertTrue(low.contains("animations off"), "LOW must disclose that it stops animating: $low")
        assertFalse(high.contains("animations off"), "HIGH animates, so it must not claim otherwise: $high")
    }

    /** A pinned tier reads the same whatever auto-detect would have said — the pin wins outright. */
    @Test
    fun `a pin ignores the detected tier entirely`() {
        FlashPerformanceMode.entries.forEach { pin ->
            val subtitles = FlashPerformanceMode.entries.map {
                FlashSettingsMath.performanceModeSubtitle(pinned = pin, detected = it)
            }
            assertEquals(1, subtitles.toSet().size, "$pin must not vary with detection: $subtitles")
        }
    }

    @Test
    fun `discovery mode labels and subtitles match known modes`() {
        assertEquals("Standard", FlashSettingsMath.discoveryModeShortLabel("STANDARD"))
        assertEquals("Ghost", FlashSettingsMath.discoveryModeShortLabel("GHOST"))
        assertEquals("Eco", FlashSettingsMath.discoveryModeShortLabel("ECO"))
        assertEquals("Boost", FlashSettingsMath.discoveryModeShortLabel("BOOST"))

        assertTrue(FlashSettingsMath.discoveryModeSubtitle("STANDARD").contains("Discoverable"))
        assertTrue(FlashSettingsMath.discoveryModeSubtitle("GHOST").contains("Browse only"))
        assertTrue(FlashSettingsMath.discoveryModeSubtitle("ECO").contains("Battery saver"))
        assertTrue(FlashSettingsMath.discoveryModeSubtitle("BOOST").contains("responsiveness"))
    }
}
