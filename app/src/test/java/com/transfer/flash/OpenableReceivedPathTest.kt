package com.transfer.flash

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenableReceivedPathTest {

    private val base = Files.createTempDirectory("flash-open").toFile()
    private val received = File(base, "FlashReceived").apply { mkdirs() }
    private val roots = listOf(received)

    @Test
    fun aFileUnderAReceivedRootIsOpenable() {
        val f = File(File(received, "t1").apply { mkdirs() }, "photo.jpg").apply { writeText("x") }
        assertTrue(isOpenableReceivedPath(f.path, roots))
    }

    @Test
    fun uriStringsAreRefused() {
        assertFalse(isOpenableReceivedPath("content://com.android.providers.media/external/images/1", roots))
        assertFalse(isOpenableReceivedPath("file://${received.path}/a.jpg", roots))
    }

    @Test
    fun aPathOutsideTheRootsOrEscapingThemIsRefused() {
        assertFalse(isOpenableReceivedPath(File(base, "secret.db").path, roots))
        assertFalse(isOpenableReceivedPath(received.path + File.separator + ".." + File.separator + "secret.db", roots))
        assertFalse(isOpenableReceivedPath(received.path, roots))
        assertFalse(isOpenableReceivedPath("", roots))
    }
}
