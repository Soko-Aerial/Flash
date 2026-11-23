package com.transfer.flash.desktop

import com.transfer.flash.ui.settings.FlashThemeMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSettingsSyncAndRevealTest {

    private val tempDir: File = Files.createTempDirectory("flash-desktop-settings-sync").toFile()

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `theme and non-OS setting changes do not spawn autoStart or context-menu sync`() {
        val autoStartCalls = CopyOnWriteArrayList<Boolean>()
        val contextMenuCalls = CopyOnWriteArrayList<Boolean>()
        val store = DesktopSettingsStore(
            stateDir = tempDir,
            syncAutoStart = { autoStartCalls += it },
            syncContextMenu = { enabled, _ -> contextMenuCalls += enabled },
            osSyncExecutor = { it.run() },
        )

        val initial = store.loadSettings()
        store.saveSettings(initial)
        // First write registers default Windows context menu once and skips disabled autoStart.
        assertEquals(0, autoStartCalls.size)
        assertEquals(1, contextMenuCalls.size)

        store.saveSettings(initial.copy(themeMode = FlashThemeMode.Dark, launchAnimation = false))
        assertEquals(0, autoStartCalls.size)
        assertEquals(1, contextMenuCalls.size)

        store.saveSettings(initial.copy(themeMode = FlashThemeMode.Light, autoStartOnBoot = true))
        assertEquals(listOf(true), autoStartCalls.toList())
        assertEquals(1, contextMenuCalls.size)

        store.saveSettings(initial.copy(themeMode = FlashThemeMode.System, autoStartOnBoot = true, windowsContextMenu = false))
        assertEquals(listOf(true), autoStartCalls.toList())
        assertEquals(listOf(true, false), contextMenuCalls.toList())
    }

    @Test
    fun `OS sync runs off the calling thread when autoStartOnBoot changes`() {
        val callerThread = Thread.currentThread().name
        val syncThreadNames = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        val store = DesktopSettingsStore(
            stateDir = tempDir,
            syncAutoStart = {
                syncThreadNames += Thread.currentThread().name
                latch.countDown()
            },
            syncContextMenu = { _, _ -> },
        )

        val initial = store.loadSettings()
        store.saveSettings(initial.copy(autoStartOnBoot = true))
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertTrue(syncThreadNames.isNotEmpty())
        assertTrue(syncThreadNames.none { it == callerThread })
    }

    @Test
    fun `buildRevealCommand selects per-OS invocation and quotes paths cleanly`() {
        val plain = File(tempDir, "report.pdf")
        val withSpaces = File(tempDir, "my report.pdf")

        val winPlain = DesktopHelpers.buildRevealCommand("Windows 11", plain)!!
        assertEquals("explorer.exe", winPlain[0])
        assertTrue(winPlain.last().contains("report.pdf"))

        val winSpaces = DesktopHelpers.buildRevealCommand("Windows 11", withSpaces)!!
        assertEquals(listOf("explorer.exe", "/select,", withSpaces.canonicalPath), winSpaces)

        val linuxCmd = DesktopHelpers.buildRevealCommand("Linux", withSpaces)!!
        assertEquals(listOf("xdg-open", tempDir.canonicalPath), linuxCmd)
    }
}
