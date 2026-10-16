package com.transfer.flash.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** AD-2 (PANE-U1): the dragged list width survives a restart, can be forgotten, and a corrupt file means "no preference". */
class DesktopPaneLayoutPrefsTest {
    private fun dir() = Files.createTempDirectory("pane-prefs").toFile().also { it.deleteOnExit() }

    @Test fun noFileMeansNoPreference() {
        assertNull(DesktopPaneLayoutPrefs(dir()).listPaneWidthDp())
    }

    @Test fun aSavedWidthSurvivesANewInstanceAndCanBeForgotten() {
        val d = dir()
        DesktopPaneLayoutPrefs(d).setListPaneWidthDp(512.5f)
        assertEquals(512.5f, DesktopPaneLayoutPrefs(d).listPaneWidthDp())
        DesktopPaneLayoutPrefs(d).setListPaneWidthDp(null)
        assertNull(DesktopPaneLayoutPrefs(d).listPaneWidthDp())
    }

    @Test fun aCorruptValueMeansNoPreference() {
        val d = dir()
        File(d, "layout.properties").writeText("list_pane_width_dp=banana\n")
        assertNull(DesktopPaneLayoutPrefs(d).listPaneWidthDp())
        File(d, "layout.properties").writeText("list_pane_width_dp=NaN\n")
        assertNull(DesktopPaneLayoutPrefs(d).listPaneWidthDp())
    }

    @Test fun savingNonFiniteForgetsThePreference() {
        val d = dir()
        DesktopPaneLayoutPrefs(d).setListPaneWidthDp(400f)
        DesktopPaneLayoutPrefs(d).setListPaneWidthDp(Float.NaN)
        assertNull(DesktopPaneLayoutPrefs(d).listPaneWidthDp())
    }
}
