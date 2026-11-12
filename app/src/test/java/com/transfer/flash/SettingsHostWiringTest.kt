package com.transfer.flash

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural host-wiring contracts for Android Settings, Hub, DisplayName, and TranssionGuideDialog. */
class SettingsHostWiringTest {

    private fun repoFile(relativePath: String): String {
        val base = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, relativePath).isFile }
        return File(base, relativePath).readText()
    }

    @Test
    fun `MainActivity wires Settings rows without dead taps or duplicated Material3 rename dialog`() {
        val main = repoFile("app/src/main/java/com/transfer/flash/MainActivity.kt")
        val settingsCall = main.substringAfter("FlashSettingsScreen(").substringBefore("val hubPendingJoins")
        assertTrue(settingsCall.contains("onOpenEncryption = { showEncryptionSheet = true }"))
        assertTrue(settingsCall.contains("onOpenTrustedPeers = { nav.selectTab(FlashDestination.NearbyDevices) }"))
        assertTrue(settingsCall.contains("onPickSaveLocation = null"))
        assertTrue(main.contains("saveLocationLabel = \"App storage / FlashReceived\""))
        assertTrue(main.contains("FlashDisplayNameDialog("))
        assertTrue(main.contains("FlashEncryptionSheet("))
        assertFalse(main.contains("private fun DisplayNameDialog("))
    }

    @Test
    fun `MainActivity passes bottomInset to FlashHubRoute and hosts TranssionGuideDialog inside FlashTheme`() {
        val main = repoFile("app/src/main/java/com/transfer/flash/MainActivity.kt")
        val hubCall = main.substringAfter("com.transfer.flash.ui.hub.FlashHubRoute(").substringBefore("val openSearchHit")
        assertTrue(hubCall.contains("bottomInset = tabBottomInset"))
        assertFalse(hubCall.contains("Modifier.padding(bottom = tabBottomInset)"))

        val flashAppBody = main.substringAfter("fun FlashApp(").substringBefore("private fun FlashShell(")
        assertTrue(flashAppBody.contains("TranssionGuideDialog("))
        assertTrue(flashAppBody.indexOf("FlashTheme(") < flashAppBody.indexOf("TranssionGuideDialog("))
    }

    @Test
    fun `TranssionGuideDialog uses FlashTheme tokens instead of MaterialTheme defaults`() {
        val guide = repoFile("app/src/main/java/com/transfer/flash/debug/TranssionGuideDialog.kt")
        assertTrue(guide.contains("FlashTheme.colors"))
        assertTrue(guide.contains("FlashTheme.typography"))
        assertTrue(guide.contains("FlashShapes"))
        assertTrue(guide.contains("FlashText("))
        assertTrue(guide.contains("colors.accentFill"))
        assertTrue(guide.contains("colors.onAccentFill"))
        assertFalse(guide.contains("MaterialTheme"))
        assertFalse(guide.contains("material3.Text"))
        assertFalse(guide.contains("TextButton("))
    }
}
