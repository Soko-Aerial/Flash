package com.transfer.flash.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Structural host-wiring contracts for Desktop Settings and Hub bottomInset integration. */
class DesktopSettingsWiringTest {

    private fun desktopShellSource(): String {
        val rel = "desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopShell.kt"
        val base = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, rel).isFile }
        return File(base, rel).readText()
    }

    @Test
    fun `desktop settings hides Android-only battery and haptics rows and wires Security and Save rows`() {
        val shell = desktopShellSource()
        val settingsBlock = shell.substringAfter("private fun DesktopSettingsDestination(")
        assertTrue(settingsBlock.contains("onHapticsChanged = null"))
        assertTrue(settingsBlock.contains("showHapticsToggle = false"))
        assertTrue(settingsBlock.contains("onOpenBatterySettings = null"))
        assertTrue(settingsBlock.contains("showBatteryOptimization = false"))
        assertTrue(settingsBlock.contains("onOpenEncryption = onOpenEncryption"))
        assertTrue(settingsBlock.contains("onOpenTrustedPeers = onOpenTrustedPeers"))
        assertTrue(settingsBlock.contains("onPickSaveLocation = {"))
        assertTrue(shell.contains("FlashEncryptionSheet("))
        assertTrue(shell.contains("onOpenEncryption = { showEncryptionSheet = true }"))
        assertTrue(shell.contains("onOpenTrustedPeers = { nav.selectTab(FlashDestination.NearbyDevices) }"))
    }

    @Test
    fun `desktop Hub route receives bottomInset directly instead of outer padding clip`() {
        val shell = desktopShellSource()
        val hubCall = shell.substringAfter("com.transfer.flash.ui.hub.FlashHubRoute(")
            .substringBefore("FlashDestination.Conversation ->")
        assertTrue(hubCall.contains("bottomInset = tabBottomInset"))
        assertFalse(hubCall.contains("Modifier.padding(bottom = tabBottomInset)"))
    }
}
