package com.transfer.flash.ui.chat

import com.transfer.flash.core.messaging.model.FlashFileAttachmentUi
import com.transfer.flash.core.messaging.model.FlashFileTransferStatus

/** What a tap on a file card does. */
internal enum class FlashFileTapAction { Resume, Accept, PlayVideo, Open, Nothing }

/**
 * A failed or paused card says "Tap to retry"/"Tap to resume"; an offer that has not been fetched says "Tap to
 * download" and has nothing to open yet (it used to fall through to an external viewer with a null URI, RVW22 F29).
 * Only a file that exists locally is opened or played.
 */
internal fun flashFileTapAction(file: FlashFileAttachmentUi): FlashFileTapAction = when {
    file.transferStatus == FlashFileTransferStatus.Failed || file.transferStatus == FlashFileTransferStatus.Paused ->
        FlashFileTapAction.Resume
    file.transferStatus == FlashFileTransferStatus.NotDownloaded -> FlashFileTapAction.Accept
    file.localUri == null -> FlashFileTapAction.Nothing
    file.mimeType.startsWith("video/") -> FlashFileTapAction.PlayVideo
    else -> FlashFileTapAction.Open
}
