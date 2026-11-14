package com.transfer.flash.core.transfer

import com.transfer.flash.core.transfer.model.FlashTransfer

/**
 * HR8: Handover hook allowing [RealFlashTransferRepository] to transfer an interrupted or retried
 * outbound 1:1 LAN transfer to the Hub route while preserving `transferId`, `wireFileId`, and identity.
 */
public fun interface TransferRouteFallback {
    /**
     * Attempts to hand [transfer] over to the Hub route.
     * Returns `true` if the fallback owner took responsibility (including explicit refusal when Hub
     * cannot carry the file), so the LAN route should not relaunch.
     */
    public suspend fun tryHandoverToHub(transfer: FlashTransfer, verifiedBytes: Long): Boolean
}
