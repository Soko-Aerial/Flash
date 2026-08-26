package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import kotlinx.coroutines.flow.StateFlow

/**
 * Public domain contract for orchestrating file transfers across peers.
 */
interface FlashTransferRepository {
    val activeTransfers: StateFlow<List<FlashTransfer>>

    suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
    ): FlashResult<FlashTransferId>

    suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit>
    suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit>
    suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit>

    /**
     * Explicit user consent for an inbound transfer that arrived as an OFFER (#5). Flips the
     * transfer from [FlashTransferState.Offered] to Transferring, tells the host to resolve the
     * deferred destination sink, and sends the sender a RESUME so it starts streaming chunks
     * (a compliant sender parks after FILE_START until this arrives). No-op unless the transfer
     * exists and is currently Offered.
     */
    suspend fun acceptIncoming(transferId: FlashTransferId): FlashResult<Unit> = FlashResult.Success(Unit)

    /**
     * Explicit user rejection of an inbound OFFER (#5). Marks the transfer Cancelled, tells the
     * host to drop the (never-materialized) pipeline session, and sends the sender a CANCEL so it
     * abandons the parked send. No bytes were ever written for a declined offer.
     */
    suspend fun declineIncoming(transferId: FlashTransferId): FlashResult<Unit> = FlashResult.Success(Unit)

    fun onInboundFrame(bytes: ByteArray): Boolean = false

    /**
     * Surfaces an inbound transfer as a pending OFFER (#5): inserted into [activeTransfers] with
     * [FlashTransferState.Offered] and awaiting [acceptIncoming]/[declineIncoming]. Called by the
     * engine when the receive pipeline opens a session that requires acceptance — BEFORE any
     * chunk is written or any destination file is created.
     */
    fun onIncomingOffered(
        transferId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        peerName: String,
        peerDeviceId: String? = null,
    ) = Unit

    /**
     * Receive-side tracking so inbound transfers surface in [activeTransfers] like sends do.
     * Called by the engine when the receive pipeline accepts a FILE_START.
     */
    fun onIncomingStarted(
        transferId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        peerName: String,
        peerDeviceId: String? = null,
        localPath: String? = null,
    ) = Unit

    /** Progress tick for an inbound transfer (bytes verified + written). */
    fun onIncomingProgress(transferId: String, bytesDone: Long) = Unit

    /** Terminal success for an inbound transfer; [verified] reflects the whole-file recheck. */
    fun onIncomingCompleted(transferId: String, verified: Boolean, localPath: String? = null) = Unit

    /** Terminal failure for an inbound transfer (e.g. unrecoverable rejection). */
    fun onIncomingFailed(transferId: String, reason: String) = Unit
}
