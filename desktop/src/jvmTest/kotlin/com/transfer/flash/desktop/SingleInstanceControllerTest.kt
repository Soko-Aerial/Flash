package com.transfer.flash.desktop

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SingleInstanceControllerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Before
    @After
    fun cleanup() {
        SingleInstanceController.release()
    }

    @Test
    fun acquireOrActivateSucceedsForFirstInstance() {
        val testDir = tempFolder.newFolder("single_inst_test_1")
        val acquired = SingleInstanceController.acquireOrActivate(testDir)
        assertTrue("First instance should acquire lock", acquired)

        // Clean up
        SingleInstanceController.release()
    }

    @Test
    fun activationMessageTriggersCallback() {
        val testDir = tempFolder.newFolder("single_inst_test_2")
        val acquired = SingleInstanceController.acquireOrActivate(testDir)
        assertTrue("Primary instance should acquire lock", acquired)

        val latch = CountDownLatch(1)
        SingleInstanceController.onActivate = {
            latch.countDown()
        }

        // Simulate a secondary instance attempting to acquire on the same directory
        val secondaryAcquired = SingleInstanceController.acquireOrActivate(testDir)
        assertFalse("Second instance should fail lock acquisition", secondaryAcquired)

        // Verify the onActivate callback was triggered
        val activated = latch.await(3, TimeUnit.SECONDS)
        assertTrue("onActivate should be called when second instance attempts launch", activated)

        SingleInstanceController.release()
    }

    @Test
    fun parseFilesFromArgsParsesExistingFiles() {
        val f1 = tempFolder.newFile("test_file_1.txt")
        val f2 = tempFolder.newFile("test_file_2.png")
        val parsed = SingleInstanceController.parseFilesFromArgs(
            arrayOf("--send", f1.absolutePath, "non_existent_file_abc.xyz", "\"${f2.absolutePath}\"", "--other-flag")
        )
        org.junit.Assert.assertEquals(2, parsed.size)
        assertTrue(parsed.contains(f1))
        assertTrue(parsed.contains(f2))
    }

    @Test
    fun sendFilesMessageTriggersOnShareFilesCallback() {
        val testDir = tempFolder.newFolder("single_inst_test_files")
        val acquired = SingleInstanceController.acquireOrActivate(testDir)
        assertTrue("Primary instance should acquire lock", acquired)

        val testFile = tempFolder.newFile("shared_document.pdf")
        val latch = CountDownLatch(1)
        var receivedFiles: List<File>? = null

        SingleInstanceController.onShareFiles = { files ->
            receivedFiles = files
            latch.countDown()
        }

        // Secondary instance launches with a file to send
        val secondaryAcquired = SingleInstanceController.acquireOrActivate(
            testDir,
            arrayOf("--send", testFile.absolutePath)
        )
        assertFalse("Second instance should fail lock acquisition", secondaryAcquired)

        val triggered = latch.await(3, TimeUnit.SECONDS)
        assertTrue("onShareFiles should be triggered on primary instance", triggered)
        org.junit.Assert.assertEquals(1, receivedFiles?.size)
        org.junit.Assert.assertEquals(testFile.absolutePath, receivedFiles?.first()?.absolutePath)

        SingleInstanceController.release()
    }

    @Test
    fun initialFilesBufferedWhenAcquiredWithArgs() {
        val testDir = tempFolder.newFolder("single_inst_test_initial")
        val testFile = tempFolder.newFile("boot_share.zip")

        val acquired = SingleInstanceController.acquireOrActivate(testDir, arrayOf(testFile.absolutePath))
        assertTrue("Primary instance should acquire lock", acquired)

        val initial = SingleInstanceController.consumeInitialShareFiles()
        org.junit.Assert.assertEquals(1, initial.size)
        org.junit.Assert.assertEquals(testFile.absolutePath, initial.first().absolutePath)

        // Second consume should be empty
        val secondConsume = SingleInstanceController.consumeInitialShareFiles()
        assertTrue(secondConsume.isEmpty())

        SingleInstanceController.release()
    }
}
