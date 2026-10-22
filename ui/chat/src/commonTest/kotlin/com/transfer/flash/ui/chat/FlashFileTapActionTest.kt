package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/** RVW22 F29: "Tap to download" downloads; nothing is opened without a local file. */
class FlashFileTapActionTest {
    private fun file(
        status: FlashFileTransferStatus,
        uri: String? = null,
        mime: String = "application/pdf",
    ) = FlashFileAttachmentUi(id = "t", name = "f", sizeBytes = 1, mimeType = mime, transferStatus = status, localUri = uri)

    @Test
    fun `an offer that is not downloaded is accepted, not opened`() {
        assertEquals(FlashFileTapAction.Accept, flashFileTapAction(file(FlashFileTransferStatus.NotDownloaded)))
        assertEquals(FlashFileTapAction.Accept, flashFileTapAction(file(FlashFileTransferStatus.NotDownloaded, mime = "video/mp4")))
    }

    @Test
    fun `failed and paused cards resume`() {
        assertEquals(FlashFileTapAction.Resume, flashFileTapAction(file(FlashFileTransferStatus.Failed)))
        assertEquals(FlashFileTapAction.Resume, flashFileTapAction(file(FlashFileTransferStatus.Paused, "content://x")))
    }

    @Test
    fun `a downloaded file opens, a downloaded video plays`() {
        assertEquals(FlashFileTapAction.Open, flashFileTapAction(file(FlashFileTransferStatus.Downloaded, "content://x")))
        assertEquals(
            FlashFileTapAction.PlayVideo,
            flashFileTapAction(file(FlashFileTransferStatus.Downloaded, "content://x", mime = "video/mp4")),
        )
    }

    @Test
    fun `a file with no local copy is never handed to a viewer`() {
        assertEquals(FlashFileTapAction.Nothing, flashFileTapAction(file(FlashFileTransferStatus.Cancelled)))
        assertEquals(FlashFileTapAction.Nothing, flashFileTapAction(file(FlashFileTransferStatus.Transferring)))
    }
}
