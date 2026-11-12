@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

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
        val networkOnly = FlashSettingsMath.Section.entries.filter {
            FlashSettingsMath.sectionVisible("Unrestricted battery", it)
        }
        assertEquals(listOf(FlashSettingsMath.Section.NETWORK), networkOnly)

        // "battery" intentionally matches both Network ("Unrestricted battery") and Advanced ("Battery saver" for Eco discovery).
        val batteryMatches = FlashSettingsMath.Section.entries.filter {
            FlashSettingsMath.sectionVisible("battery", it)
        }
        assertEquals(listOf(FlashSettingsMath.Section.NETWORK, FlashSettingsMath.Section.ADVANCED), batteryMatches)
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
        assertFalse(FlashSettingsMath.isAtDefaults(d.copy(swarmEnabled = false)))
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

    @Test
    fun `conditional rows do not match when hidden by the host`() {
        // Radio gateway lives under Advanced and must stay hidden when disabled by the host
        val advanced = FlashSettingsMath.Section.ADVANCED
        assertFalse(FlashSettingsMath.sectionVisible("radio", advanced, showRadioGateway = false, showWindowsContextMenu = true))
        assertFalse(FlashSettingsMath.sectionVisible("gateway", advanced, showRadioGateway = false, showWindowsContextMenu = true))
        assertTrue(FlashSettingsMath.sectionVisible("radio", advanced, showRadioGateway = true, showWindowsContextMenu = true))

        // File Explorer context menu lives under Network
        val network = FlashSettingsMath.Section.NETWORK
        assertFalse(FlashSettingsMath.sectionVisible("context menu", network, showRadioGateway = true, showWindowsContextMenu = false))
        assertTrue(FlashSettingsMath.sectionVisible("context menu", network, showRadioGateway = true, showWindowsContextMenu = true))
    }

    @Test
    fun `search matches sub-labels and option chips without showing empty sections`() {
        val visibleForDark = FlashSettingsMath.Section.entries.filter { FlashSettingsMath.sectionVisible("Dark", it) }
        assertEquals(listOf(FlashSettingsMath.Section.APPEARANCE), visibleForDark)
        assertTrue(FlashSettingsMath.rowVisible("Dark", FlashSettingsMath.Section.APPEARANCE, *FlashSettingsMath.themeRowTerms.toTypedArray()))

        listOf("Light", "System").forEach { mode ->
            assertTrue(FlashSettingsMath.sectionVisible(mode, FlashSettingsMath.Section.APPEARANCE))
            assertTrue(FlashSettingsMath.rowVisible(mode, FlashSettingsMath.Section.APPEARANCE, *FlashSettingsMath.themeRowTerms.toTypedArray()))
        }

        listOf("Ghost", "Eco", "Boost").forEach { mode ->
            val sections = FlashSettingsMath.Section.entries.filter { FlashSettingsMath.sectionVisible(mode, it) }
            assertEquals(listOf(FlashSettingsMath.Section.ADVANCED), sections)
            assertTrue(FlashSettingsMath.rowVisible(mode, FlashSettingsMath.Section.ADVANCED, *FlashSettingsMath.discoveryRowTerms.toTypedArray()))
        }

        listOf("Ultra Low", "Low", "Medium", "High").forEach { tier ->
            assertTrue(FlashSettingsMath.sectionVisible(tier, FlashSettingsMath.Section.ADVANCED))
            assertTrue(FlashSettingsMath.rowVisible(tier, FlashSettingsMath.Section.ADVANCED, *FlashSettingsMath.performanceRowTerms.toTypedArray()))
        }

        assertEquals(
            listOf(FlashSettingsMath.Section.STORAGE),
            FlashSettingsMath.Section.entries.filter { FlashSettingsMath.sectionVisible("Clear received files", it) },
        )
        assertTrue(FlashSettingsMath.rowVisible("Clear received files", FlashSettingsMath.Section.STORAGE, *FlashSettingsMath.receivedFilesRowTerms.toTypedArray()))

        // AboutCard search term parity (including app version)
        assertTrue(FlashSettingsMath.matches("2.2.0", "About", "Version", "2.2.0", "Protocol", "FLASH_XFER/1", "Device id", "00000000"))
    }

    @Test
    fun `enterprise and security rows gate search visibility by enrolled and host capability flags`() {
        assertTrue(FlashSettingsMath.sectionVisible("Enrolment", FlashSettingsMath.Section.ENTERPRISE, isEnterpriseEnrolled = false))
        assertFalse(FlashSettingsMath.sectionVisible("Department", FlashSettingsMath.Section.ENTERPRISE, isEnterpriseEnrolled = false))
        assertFalse(FlashSettingsMath.sectionVisible("Directory", FlashSettingsMath.Section.ENTERPRISE, isEnterpriseEnrolled = false))
        assertFalse(FlashSettingsMath.sectionVisible("Root fingerprint", FlashSettingsMath.Section.ENTERPRISE, isEnterpriseEnrolled = false))

        assertFalse(FlashSettingsMath.sectionVisible("Enrolment", FlashSettingsMath.Section.ENTERPRISE, isEnterpriseEnrolled = true))
        assertTrue(FlashSettingsMath.sectionVisible("Department", FlashSettingsMath.Section.ENTERPRISE, isEnterpriseEnrolled = true))
        assertTrue(FlashSettingsMath.sectionVisible("Directory", FlashSettingsMath.Section.ENTERPRISE, isEnterpriseEnrolled = true))
        assertTrue(FlashSettingsMath.sectionVisible("Root fingerprint", FlashSettingsMath.Section.ENTERPRISE, isEnterpriseEnrolled = true))

        // Hidden/nullable host rows must neither match nor leave an empty expanded section behind
        assertFalse(FlashSettingsMath.sectionVisible("haptics", FlashSettingsMath.Section.APPEARANCE, showHaptics = false))
        assertFalse(FlashSettingsMath.sectionVisible("battery", FlashSettingsMath.Section.NETWORK, showBattery = false))
        assertFalse(FlashSettingsMath.sectionVisible("save location", FlashSettingsMath.Section.STORAGE, showSaveLocation = false))
        assertFalse(FlashSettingsMath.sectionVisible("export logs", FlashSettingsMath.Section.ADVANCED, showExportLogs = false))
        assertFalse(
            FlashSettingsMath.sectionVisible(
                "security",
                FlashSettingsMath.Section.SECURITY,
                showEncryption = false,
                showTrustedPeers = false,
            ),
        )
        assertFalse(
            FlashSettingsMath.sectionVisible(
                "",
                FlashSettingsMath.Section.SECURITY,
                showEncryption = false,
                showTrustedPeers = false,
            ),
        )

        val d = FlashSettingsModel()
        assertTrue(FlashSettingsMath.isAtDefaults(d.copy(hapticsEnabled = false), showHaptics = false))
        assertFalse(FlashSettingsMath.isAtDefaults(d.copy(hapticsEnabled = false), showHaptics = true))
    }

    @Test
    fun `background row search separates Android listening from Desktop tray and matches launch off copy`() {
        assertFalse(
            FlashSettingsMath.sectionVisible(
                query = "tray",
                section = FlashSettingsMath.Section.NETWORK,
                backgroundListenTitle = "Keep listening in the background",
                backgroundListenSubtitle = "Stay reachable with nobody connected; the system may still stop it",
            ),
        )
        assertTrue(
            FlashSettingsMath.sectionVisible(
                query = "listening",
                section = FlashSettingsMath.Section.NETWORK,
                backgroundListenTitle = "Keep listening in the background",
                backgroundListenSubtitle = "Stay reachable with nobody connected; the system may still stop it",
            ),
        )

        assertFalse(
            FlashSettingsMath.sectionVisible(
                query = "listening",
                section = FlashSettingsMath.Section.NETWORK,
                showBattery = false,
                backgroundListenTitle = "Close to tray",
                backgroundListenSubtitle = "Closing the window keeps Flash running in the notification area",
            ),
        )
        assertTrue(
            FlashSettingsMath.sectionVisible(
                query = "tray",
                section = FlashSettingsMath.Section.NETWORK,
                showBattery = false,
                backgroundListenTitle = "Close to tray",
                backgroundListenSubtitle = "Closing the window keeps Flash running in the notification area",
            ),
        )

        assertTrue(
            FlashSettingsMath.sectionVisible(
                query = "Flash opens straight away",
                section = FlashSettingsMath.Section.APPEARANCE,
            ),
        )
        assertTrue(
            FlashSettingsMath.rowVisible(
                query = "Flash opens straight away",
                section = FlashSettingsMath.Section.APPEARANCE,
                *FlashSettingsMath.launchAnimationRowTerms.toTypedArray(),
            ),
        )
    }
}

