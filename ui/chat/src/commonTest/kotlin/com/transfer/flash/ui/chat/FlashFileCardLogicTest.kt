package com.transfer.flash.ui.chat

import androidx.compose.ui.graphics.Color
import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FlashFileCardLogicTest {

    @Test
    fun `formatFileSize formats correctly across magnitude ranges`() {
        assertEquals("500 B", formatFileSize(500L))
        assertEquals("1.0 KB", formatFileSize(1024L))
        assertEquals("1.5 KB", formatFileSize(1536L))
        assertEquals("10.0 MB", formatFileSize(10 * 1024 * 1024L))
        assertEquals("1.25 GB", formatFileSize((1.25 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `fileCategoryColorFor resolves unique vibrant tints`() {
        val pdfColor = fileCategoryColorFor("pdf")
        val zipColor = fileCategoryColorFor("zip")
        val codeColor = fileCategoryColorFor("kt")
        val audioColor = fileCategoryColorFor("mp3")
        val videoColor = fileCategoryColorFor("mp4")
        val imgColor = fileCategoryColorFor("png")
        val otherColor = fileCategoryColorFor("unknown_ext")

        assertEquals(Color(0xFFE63946), pdfColor)
        assertEquals(Color(0xFFF77F00), zipColor)
        assertEquals(Color(0xFF4361EE), codeColor)
        assertEquals(Color(0xFF7209B7), audioColor)
        assertEquals(Color(0xFFD81159), videoColor)
        assertEquals(Color(0xFF00B4D8), imgColor)
        assertNotEquals(pdfColor, otherColor)
    }

    @Test
    fun `file attachment model holds complete P2P transfer state`() {
        val file = FlashFileAttachmentUi(
            id = "file-1",
            name = "Project_Specs.pdf",
            sizeBytes = 2_048_000L,
            mimeType = "application/pdf",
            transferStatus = FlashFileTransferStatus.Transferring,
            transferProgress = 0.5f,
            transferSpeedMbps = 24.5f,
            etaSeconds = 3,
        )

        assertEquals("Project_Specs.pdf", file.name)
        assertEquals(2_048_000L, file.sizeBytes)
        assertEquals(FlashFileTransferStatus.Transferring, file.transferStatus)
        assertEquals(0.5f, file.transferProgress, 0.001f)
        assertEquals(24.5f, file.transferSpeedMbps, 0.001f)
        assertEquals(3, file.etaSeconds)
    }
}
