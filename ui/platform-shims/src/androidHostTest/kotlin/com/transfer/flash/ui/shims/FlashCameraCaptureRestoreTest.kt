package com.transfer.flash.ui.shims

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.io.File
import java.nio.file.Files

/**
 * The restore half of `rememberFlashCameraCaptureLauncher` (review 2026-10-22, F50).
 *
 * A rotation or a low-memory kill destroys the composition while the camera app is in front, so the
 * result of `TakePicture` is delivered to a fresh composition. The composable now keeps the capture in
 * `rememberSaveable` as two strings; this class covers what those strings mean when they come back,
 * which is the part a compile cannot show and a device would only show by luck.
 */
class FlashCameraCaptureRestoreTest {

    private lateinit var dir: File

    @BeforeTest
    fun createDir() {
        dir = Files.createTempDirectory("flash-camera-capture").toFile()
    }

    @AfterTest
    fun deleteDir() {
        dir.deleteRecursively()
    }

    @Test
    fun `a saved capture whose file is still there is reported`() {
        val file = File(dir, "flash_photo_1.jpg").apply { writeBytes(ByteArray(64) { 1 }) }

        val picked = assertNotNull(restoredCapture("content://flash/1", file.absolutePath))

        assertEquals("content://flash/1", picked.uri)
        assertEquals("flash_photo_1.jpg", picked.name)
        assertEquals(64L, picked.size)
    }

    @Test
    fun `a restored path whose file was deleted reports nothing instead of a dangling photo`() {
        // The cache directory is the platform's to clear, and it may do so between the capture and the
        // result being delivered after a process death.
        assertNull(restoredCapture("content://flash/2", File(dir, "gone.jpg").absolutePath))
    }

    @Test
    fun `a zero-length capture is not a photo`() {
        // What a camera killed mid-write leaves behind: the file exists but holds no image.
        val empty = File(dir, "flash_photo_empty.jpg").apply { createNewFile() }

        assertNull(restoredCapture("content://flash/3", empty.absolutePath))
    }

    @Test
    fun `missing or blank saved values report nothing`() {
        val file = File(dir, "flash_photo_4.jpg").apply { writeBytes(ByteArray(8) { 1 }) }

        assertNull(restoredCapture(null, file.absolutePath))
        assertNull(restoredCapture("content://flash/4", null))
        assertNull(restoredCapture("   ", file.absolutePath))
        assertNull(restoredCapture("content://flash/4", "  "))
    }
}
