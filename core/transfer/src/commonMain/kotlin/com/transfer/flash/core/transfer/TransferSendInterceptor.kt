package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.transfer.model.FlashTransferId

/**
 * Another owner that may take a 1:1 send before the direct pipeline does (ADR-216): the hub file binding, when no
 * direct path to the peer exists and the organisation hub can carry the file sealed.
 *
 * Installed with [RealFlashTransferRepository.sendInterceptor]. The owner that answers a transfer id must publish that
 * id's row through [RealFlashTransferRepository.attachExternalRows] and control it through its
 * [ExternalTransferControl], so the caller (which goes on to create the chat bubble with that id) cannot tell the
 * difference. Null means "not mine", and the direct pipeline sends exactly as it did before.
 */
public fun interface TransferSendInterceptor {
    public suspend fun intercept(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
    ): FlashTransferId?
}
