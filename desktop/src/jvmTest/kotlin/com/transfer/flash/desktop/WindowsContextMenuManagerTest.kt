package com.transfer.flash.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WindowsContextMenuManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun resolveLaunchCommandProducesValidCommand() {
        val testDir = tempFolder.newFolder("context_menu_test_dir")
        val cmd = WindowsContextMenuManager.resolveLaunchCommand(testDir)
        assertNotNull("Launch command should not be null", cmd)
        assertTrue("Command should contain %1 parameter", cmd!!.contains("%1"))
    }

    @Test
    fun setContextMenuEnabledCreatesCommandSubkeySuccessfully() {
        val testDir = tempFolder.newFolder("reg_test_dir")
        val success = WindowsContextMenuManager.setContextMenuEnabled(true, testDir)
        assertTrue("setContextMenuEnabled should return true", success)
        assertTrue("isContextMenuRegistered should return true", WindowsContextMenuManager.isContextMenuRegistered())

        val fileProcess = ProcessBuilder("reg.exe", "query", "HKCU\\Software\\Classes\\*\\shell\\Flash\\command", "/ve")
            .redirectErrorStream(true)
            .start()
        val fileOutput = fileProcess.inputStream.bufferedReader().use { it.readText() }
        val fileExitCode = fileProcess.waitFor()
        assertEquals("reg query file command should exit with 0. Output: $fileOutput", 0, fileExitCode)
        assertTrue("Output should contain %1: $fileOutput", fileOutput.contains("%1"))

        val dirProcess = ProcessBuilder("reg.exe", "query", "HKCU\\Software\\Classes\\Directory\\shell\\Flash\\command", "/ve")
            .redirectErrorStream(true)
            .start()
        val dirOutput = dirProcess.inputStream.bufferedReader().use { it.readText() }
        val dirExitCode = dirProcess.waitFor()
        assertEquals("reg query directory command should exit with 0. Output: $dirOutput", 0, dirExitCode)
        assertTrue("Output should contain %1: $dirOutput", dirOutput.contains("%1"))

        // Unregister
        val removeSuccess = WindowsContextMenuManager.setContextMenuEnabled(false, testDir)
        assertTrue("Disabling context menu should succeed", removeSuccess)
        // Verify deletion
        val verifyProcess = ProcessBuilder("reg.exe", "query", "HKCU\\Software\\Classes\\*\\shell\\Flash")
            .redirectErrorStream(true)
            .start()
        assertEquals(1, verifyProcess.waitFor())

        // Restore context menu for user
        WindowsContextMenuManager.setContextMenuEnabled(true)
    }

    @Test
    fun ensureIconFileGeneratesValidIcoFile() {
        val testDir = tempFolder.newFolder("icon_test_dir")
        val iconPath = WindowsContextMenuManager.ensureIconFile(testDir)
        assertNotNull("Icon path should not be null", iconPath)

        val iconFile = File(iconPath!!)
        assertTrue("Icon file should exist", iconFile.exists())
        assertTrue("Icon file should have valid length", iconFile.length() > 22)

        val bytes = iconFile.readBytes()
        // Check ICO header: 0, 0 (reserved), 1, 0 (type 1 = icon), 1, 0 (count = 1)
        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(1, bytes[2].toInt())
        assertEquals(0, bytes[3].toInt())
        assertEquals(1, bytes[4].toInt())
        assertEquals(0, bytes[5].toInt())
        // Check entry width and height: 32x32
        assertEquals(32, bytes[6].toInt())
        assertEquals(32, bytes[7].toInt())
    }
}
