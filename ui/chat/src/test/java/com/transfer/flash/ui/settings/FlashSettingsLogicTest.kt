package com.transfer.flash.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertTrue("restricted copy must mention the screen: $restricted", restricted.contains("screen"))
        assertTrue("exempt copy must mention the screen: $exempt", exempt.contains("screen"))
        assertTrue("restricted copy must ask for a tap: $restricted", restricted.contains("tap"))
        assertFalse("exempt copy must not ask for a tap: $exempt", exempt.contains("tap"))
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
        assertTrue("on copy must name video: $on", on.contains("Video", ignoreCase = true))
        assertTrue("off copy must name video: $off", off.contains("video", ignoreCase = true))
    }
}
