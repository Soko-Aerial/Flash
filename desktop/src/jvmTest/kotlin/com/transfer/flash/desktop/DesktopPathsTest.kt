package com.transfer.flash.desktop

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Linux plan L1: XDG folders on Linux, the old locations everywhere else, existing data never abandoned. */
class DesktopPathsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val home get() = tmp.root

    @Test
    fun `windows and mac keep the legacy locations`() {
        assertEquals(File(home, ".flash"), DesktopPaths.stateDir("Windows 11", emptyMap(), home))
        assertEquals(File(home, ".flash"), DesktopPaths.stateDir("Mac OS X", emptyMap(), home))
        assertEquals(File(home, "FlashReceived"), DesktopPaths.receivedRoot("Windows 11", emptyMap(), home))
    }

    @Test
    fun `linux fresh install uses the XDG data folder default`() {
        assertEquals(File(home, ".local/share/flash"), DesktopPaths.stateDir("Linux", emptyMap(), home))
    }

    @Test
    fun `linux honours an absolute XDG_DATA_HOME and ignores a relative or empty one`() {
        val data = File(home, "custom-data")
        assertEquals(File(data, "flash"), DesktopPaths.stateDir("Linux", mapOf("XDG_DATA_HOME" to data.path), home))
        assertEquals(File(home, ".local/share/flash"), DesktopPaths.stateDir("Linux", mapOf("XDG_DATA_HOME" to "relative/dir"), home))
        assertEquals(File(home, ".local/share/flash"), DesktopPaths.stateDir("Linux", mapOf("XDG_DATA_HOME" to ""), home))
    }

    @Test
    fun `linux keeps a non-empty legacy folder when the XDG folder is empty`() {
        File(home, ".flash").apply { mkdirs() }.resolve("trust.properties").writeText("x")

        assertEquals(File(home, ".flash"), DesktopPaths.stateDir("Linux", emptyMap(), home))
    }

    @Test
    fun `linux prefers the XDG folder once it has data, even if a legacy folder exists`() {
        File(home, ".flash").apply { mkdirs() }.resolve("old").writeText("x")
        File(home, ".local/share/flash").apply { mkdirs() }.resolve("new").writeText("y")

        assertEquals(File(home, ".local/share/flash"), DesktopPaths.stateDir("Linux", emptyMap(), home))
    }

    @Test
    fun `an empty legacy folder does not count as data`() {
        File(home, ".flash").mkdirs()

        assertEquals(File(home, ".local/share/flash"), DesktopPaths.stateDir("Linux", emptyMap(), home))
    }

    @Test
    fun `received files go to the XDG download folder on linux`() {
        assertEquals(File(home, "Downloads/Flash"), DesktopPaths.receivedRoot("Linux", emptyMap(), home))

        File(home, ".config").mkdirs()
        File(home, ".config/user-dirs.dirs").writeText(
            "# comment\nXDG_DESKTOP_DIR=\"\$HOME/Bureau\"\nXDG_DOWNLOAD_DIR=\"\$HOME/Téléchargements\"\n",
        )
        assertEquals(File(home, "Téléchargements/Flash"), DesktopPaths.receivedRoot("Linux", emptyMap(), home))
    }

    @Test
    fun `user-dirs parsing handles absolute paths, missing keys and the disabled home value`() {
        val absolute = tmp.newFolder("elsewhere").absolutePath // absolute on every OS (a bare "/x" is not on Windows)
        assertEquals(File(absolute), DesktopPaths.parseUserDir("XDG_DOWNLOAD_DIR=\"$absolute\"", "XDG_DOWNLOAD_DIR", home))
        assertNull(DesktopPaths.parseUserDir("XDG_MUSIC_DIR=\"\$HOME/Music\"", "XDG_DOWNLOAD_DIR", home))
        assertNull(DesktopPaths.parseUserDir("XDG_DOWNLOAD_DIR=\"\$HOME\"", "XDG_DOWNLOAD_DIR", home))
        assertNull(DesktopPaths.parseUserDir("XDG_DOWNLOAD_DIR=\"relative\"", "XDG_DOWNLOAD_DIR", home))
    }

    @Test
    fun `a missing or unreadable user-dirs file falls back to Downloads`() {
        assertEquals(File(home, "Downloads"), DesktopPaths.downloadsDir(emptyMap(), home))
        File(home, ".config").mkdirs()
        File(home, ".config/user-dirs.dirs").mkdirs() // a directory where a file should be
        assertEquals(File(home, "Downloads"), DesktopPaths.downloadsDir(emptyMap(), home))
    }
}
