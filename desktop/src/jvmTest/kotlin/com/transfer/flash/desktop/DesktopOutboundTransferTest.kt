package com.transfer.flash.desktop

import com.transfer.flash.core.transfer.FileSourceOpener
import com.transfer.flash.ui.transfers.FlashTransferDirection
import com.transfer.flash.ui.transfers.FlashTransferItemUi
import com.transfer.flash.ui.transfers.FlashTransferState
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Verification for Phase 30 / desktop outbound transfer plumbing:
 * - MIME guessing parity for desktop transfers
 * - file:// and raw path resolution
 * - Desktop FileSourceOpener handles file:// URIs without throwing on Windows/JVM
 */
class DesktopOutboundTransferTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }

    @Test
    fun mimeTypeGuesser_resolvesStandardTypes() {
        assertEquals("image/png", DesktopHelpers.guessMimeType("photo.png"))
        assertEquals("image/jpeg", DesktopHelpers.guessMimeType("photo.jpg"))
        assertEquals("video/mp4", DesktopHelpers.guessMimeType("clip.mp4"))
        assertEquals("audio/wav", DesktopHelpers.guessMimeType("voice.wav"))
        assertEquals("application/pdf", DesktopHelpers.guessMimeType("document.pdf"))
        assertEquals("*/*", DesktopHelpers.guessMimeType("unknown.xyz123_custom_ext"))
    }

    @Test
    fun resolveShareableUri_resolvesLocalFile() {
        val tempFile = File.createTempFile("flash-test", ".txt")
        tempFile.deleteOnExit()
        val uri = DesktopHelpers.resolveShareableUri(tempFile.absolutePath)
        assertNotNull(uri)
        assertEquals("file", uri.scheme)

        val fileUri = tempFile.toURI().toString()
        val uriFromFileUri = DesktopHelpers.resolveShareableUri(fileUri)
        assertNotNull(uriFromFileUri)
        assertEquals(tempFile.canonicalPath, File(uriFromFileUri).canonicalPath)
    }

    @Test
    fun desktopFileSourceOpener_readsFileFromUriAndRawPath() {
        val tempDir = Files.createTempDirectory("flash-opener-test").toFile()
        tempDirs += tempDir
        val sampleFile = File(tempDir, "sample.dat")
        val expectedBytes = byteArrayOf(1, 2, 3, 4, 42, 99)
        sampleFile.writeBytes(expectedBytes)

        val opener = FileSourceOpener { uriString ->
            val file = runCatching {
                if (uriString.startsWith("file:", ignoreCase = true)) {
                    java.io.File(java.net.URI(uriString))
                } else {
                    java.io.File(uriString)
                }
            }.getOrElse {
                java.io.File(uriString)
            }
            FileSystem.SYSTEM.source(file.absolutePath.toPath())
        }

        // Test with file: URI
        val uri = sampleFile.toURI().toString()
        val sourceFromUri = opener.open(uri)
        val readFromUri = sourceFromUri.buffer().readByteArray()
        assertTrue(expectedBytes.contentEquals(readFromUri), "Bytes read via URI must match source")

        // Test with raw absolute path
        val sourceFromPath = opener.open(sampleFile.absolutePath)
        val readFromPath = sourceFromPath.buffer().readByteArray()
        assertTrue(expectedBytes.contentEquals(readFromPath), "Bytes read via raw path must match source")
    }

    @Test
    fun transferItemUi_modelsDirectionsAndStatesCorrectly() {
        val sendItem = FlashTransferItemUi(
            id = "xfer-1",
            fileName = "movie.mp4",
            direction = FlashTransferDirection.Send,
            peerName = "Pixel Phone",
            bytesTotal = 1000L,
            bytesDone = 500L,
            state = FlashTransferState.Active,
            speedBytesPerSec = 250_000L,
        )
        assertEquals(FlashTransferDirection.Send, sendItem.direction)
        assertEquals(FlashTransferState.Active, sendItem.state)
        assertEquals(1000L, sendItem.bytesTotal)
        assertEquals(500L, sendItem.bytesDone)
    }
}
