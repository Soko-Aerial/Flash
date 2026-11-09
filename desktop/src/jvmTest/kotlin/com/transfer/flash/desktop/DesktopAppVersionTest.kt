package com.transfer.flash.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The About card must read the version the installer was built with, not a hardcoded string.
 * The packaged launcher and the jar manifest both carry that value (see `desktopAppVersion` in
 * desktop/build.gradle.kts); these cases pin the resolver's precedence and its development fallback.
 */
class DesktopAppVersionTest {

    @Test
    fun packagedLauncherVersionWins() {
        assertEquals("2.2.0", DesktopAppVersion.resolve("2.2.0", "2.2.0"))
    }

    @Test
    fun jarManifestIsUsedWhenThereIsNoPackagedLauncher() {
        assertEquals("2.2.0", DesktopAppVersion.resolve(null, "2.2.0"))
        assertEquals("2.2.0", DesktopAppVersion.resolve("  ", "  2.2.0\n"))
    }

    @Test
    fun missingOrBlankVersionFallsBackToDevInsteadOfInventingARelease() {
        assertEquals(DesktopAppVersion.UNKNOWN, DesktopAppVersion.resolve(null, null))
        assertEquals(DesktopAppVersion.UNKNOWN, DesktopAppVersion.resolve("", ""))
        assertEquals(DesktopAppVersion.UNKNOWN, DesktopAppVersion.resolve("   ", "   "))
    }
}
