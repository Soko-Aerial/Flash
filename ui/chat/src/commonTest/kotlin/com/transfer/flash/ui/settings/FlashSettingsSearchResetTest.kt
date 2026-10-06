package com.transfer.flash.ui.settings

import com.transfer.flash.core.common.perf.FlashPerformanceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashSettingsSearchResetTest {

    @Test
    fun `a blank query shows everything`() {
        FlashSettingsMath.Section.entries.forEach {
            assertTrue(FlashSettingsMath.sectionVisible("", it))
            assertTrue(FlashSettingsMath.sectionVisible("   ", it))
            assertTrue(FlashSettingsMath.rowVisible("", it, "anything"))
        }
    }

    @Test
    fun `a query finds the section that owns the row and hides the others`() {
        val visible = FlashSettingsMath.Section.entries.filter { FlashSettingsMath.sectionVisible("battery", it) }
        assertEquals(listOf(FlashSettingsMath.Section.NETWORK), visible)
    }

    @Test
    fun `search is case-insensitive and trims`() {
        assertTrue(FlashSettingsMath.sectionVisible("  HAPTICS ", FlashSettingsMath.Section.APPEARANCE))
        assertFalse(FlashSettingsMath.sectionVisible("haptics", FlashSettingsMath.Section.CALLING))
    }

    @Test
    fun `inside a visible section only the matching rows show`() {
        val s = FlashSettingsMath.Section.STORAGE
        assertTrue(FlashSettingsMath.rowVisible("videos", s, "Auto-download videos"))
        assertFalse(FlashSettingsMath.rowVisible("videos", s, "Auto-download voice"))
    }

    @Test
    fun `a query matching the section title keeps every row of that section`() {
        val s = FlashSettingsMath.Section.CALLING
        assertTrue(FlashSettingsMath.rowVisible("calling", s, "Prioritise voice quality"))
        assertTrue(FlashSettingsMath.rowVisible("calling", s, "Send smaller video in groups"))
    }

    @Test
    fun `a nonsense query matches nothing`() {
        assertTrue(FlashSettingsMath.Section.entries.none { FlashSettingsMath.sectionVisible("zzzqq", it) })
    }

    @Test
    fun `a fresh model is at defaults and each preference change leaves defaults`() {
        val d = FlashSettingsModel()
        assertTrue(FlashSettingsMath.isAtDefaults(d))
        assertFalse(FlashSettingsMath.isAtDefaults(d.copy(themeMode = FlashThemeMode.Dark)))
        assertFalse(FlashSettingsMath.isAtDefaults(d.copy(autoDownloadVideo = true)))
        assertFalse(FlashSettingsMath.isAtDefaults(d.copy(discoveryMode = "ECO")))
        assertFalse(FlashSettingsMath.isAtDefaults(d.copy(swarmEnabled = true)))
        assertFalse(FlashSettingsMath.isAtDefaults(d.copy(performanceMode = FlashPerformanceMode.LOW)))
    }

    @Test
    fun `identity and read-only values do not count as changed preferences`() {
        val d = FlashSettingsModel()
        assertTrue(FlashSettingsMath.isAtDefaults(d.copy(displayName = "Kali", trustedPeerCount = 4, saveLocationLabel = "x")))
    }

    @Test
    fun `the Windows menu only counts where the host shows that row`() {
        val d = FlashSettingsModel()
        assertTrue(FlashSettingsMath.isAtDefaults(d.copy(windowsContextMenu = false, showWindowsContextMenu = false)))
        assertFalse(FlashSettingsMath.isAtDefaults(d.copy(windowsContextMenu = false, showWindowsContextMenu = true)))
    }
}
