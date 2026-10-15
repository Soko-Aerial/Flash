package com.transfer.flash.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HUBT-U96: the desktop Hub preferences and the unsent-draft store survive a new instance, and clearing drafts keeps the preferences. */
class DesktopHubUiPrefsTest {

    private fun dir() = Files.createTempDirectory("hub-prefs").toFile().also { it.deleteOnExit() }

    @Test fun segmentAndTipSurviveANewInstance() {
        val d = dir()
        DesktopHubUiPrefs(d).apply { setLastSegment("PEOPLE"); setMovedTipSeen() }
        val again = DesktopHubUiPrefs(d)
        assertEquals("PEOPLE", again.lastSegment())
        assertTrue(again.movedTipSeen())
    }

    @Test fun defaultsWhenNothingWasSaved() {
        val p = DesktopHubUiPrefs(dir())
        assertNull(p.lastSegment())
        assertFalse(p.movedTipSeen())
        assertNull(p.load("org"))
    }

    @Test fun aDraftSurvivesAndClearRemovesOnlyThatDraft() {
        val d = dir()
        DesktopHubUiPrefs(d).apply { save("org", "Title", "Body"); save("dept:1", "T2", "B2") }
        val again = DesktopHubUiPrefs(d)
        assertEquals("Title" to "Body", again.load("org"))
        again.clear("org")
        assertNull(again.load("org"))
        assertEquals("T2" to "B2", again.load("dept:1"))
    }

    @Test fun clearDraftsKeepsThePreferences() {
        val d = dir()
        val p = DesktopHubUiPrefs(d)
        p.setLastSegment("NEWS"); p.setMovedTipSeen(); p.save("org", "t", "b")
        p.clearDrafts()
        assertNull(p.load("org"))
        assertEquals("NEWS", p.lastSegment())
        assertTrue(p.movedTipSeen())
    }

    @Test fun anOversizeDraftIsCapped() {
        val p = DesktopHubUiPrefs(dir())
        p.save("org", "t", "x".repeat(20_000))
        assertEquals(8_000, p.load("org")!!.second.length)
    }

    @Test fun aCorruptFileGivesDefaultsNotAnException() {
        val d = dir()
        java.io.File(d, "hub-ui.properties").writeBytes(byteArrayOf(0x5C, 0x75, 0x00, 0x7F))
        val p = DesktopHubUiPrefs(d)
        assertNull(p.load("org"))
        assertFalse(p.movedTipSeen())
    }
}
