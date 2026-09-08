package com.transfer.flash.core.transfer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import kotlinx.coroutines.flow.StateFlow

/**
 * Public domain contract for orchestrating file transfers across peers.
 */
public interface FlashTransferRepository {
    public val activeTransfers: StateFlow<List<FlashTransfer>>

    public suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
    ): FlashResult<FlashTransferId>

    /**
     * F4 (group media): send with an explicit wire identity. Group sends fan out N per-member
     * transfers that must correlate — the receiver mints its chat row under the same
     * [wireFileId] and a later re-pull from another member resumes the original session
     * (identical `(transferId, wireFileId)` re-offer → resume, per [relaunchSend]'s contract).
     * Null keeps the generated-UUID default (1:1 sends unchanged).
     */
    public suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
        wireFileId: String?,
    ): FlashResult<FlashTransferId> = sendFile(targetDevice, fileUri, displayName, fileSize)

    /**
     * Sends with explicit transfer and file identities. The default delegates to the prior
     * wireFileId overload for source compatibility; implementations that support deterministic
     * transfer identity override it.
     */
    public suspend fun sendFile(
        targetDevice: FlashDevice,
        fileUri: String,
        displayName: String,
        fileSize: Long,
        transferId: String,
        wireFileId: String,
    ): FlashResult<FlashTransferId> =
        sendFile(targetDevice, fileUri, displayName, fileSize, wireFileId)

    public suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit>

    public suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit>
    public suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit>

    /**
     * Explicit user consent for an inbound transfer that arrived as an OFFER (#5). Flips the
     * transfer from [FlashTransferState.Offered] to Transferring, tells the host to resolve the
     * deferred destination sink, and sends the sender a RESUME so it starts streaming chunks
     * (a compliant sender parks after FILE_START until this arrives). No-op unless the transfer
     * exists and is currently Offered.
     */
    public suspend fun acceptIncoming(transferId: FlashTransferId): FlashResult<Unit> = FlashResult.Success(Unit)

    /**
     * Explicit user rejection of an inbound OFFER (#5). Marks the transfer Cancelled, tells the
     * host to drop the (never-materialized) pipeline session, and sends the sender a CANCEL so it
     * abandons the parked send. No bytes were ever written for a declined offer.
     */
    public suspend fun declineIncoming(transferId: FlashTransferId): FlashResult<Unit> = FlashResult.Success(Unit)

    public fun onInboundFrame(bytes: ByteArray): Boolean = false

    /**
     * Surfaces an inbound transfer as a pending OFFER (#5): inserted into [activeTransfers] with
     * [FlashTransferState.Offered] and awaiting [acceptIncoming]/[declineIncoming]. Called by the
     * engine when the receive pipeline opens a session that requires acceptance — BEFORE any
     * chunk is written or any destination file is created.
     */
    public fun onIncomingOffered(
        transferId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        peerName: String,
        peerDeviceId: String? = null,
    ): Unit = Unit

    /**
     * Receive-side tracking so inbound transfers surface in [activeTransfers] like sends do.
     * Called by the engine when the receive pipeline accepts a FILE_START.
     */
    public fun onIncomingStarted(
        transferId: String,
        fileId: String,
        fileName: String,
        totalBytes: Long,
        peerName: String,
        peerDeviceId: String? = null,
        localPath: String? = null,
    ): Unit = Unit

    /** Progress tick for an inbound transfer (bytes verified + written). */
    public fun onIncomingProgress(transferId: String, bytesDone: Long): Unit = Unit

    /** Terminal success for an inbound transfer; [verified] reflects the whole-file recheck. */
    public fun onIncomingCompleted(transferId: String, verified: Boolean, localPath: String? = null): Unit = Unit

    /** Terminal failure for an inbound transfer (e.g. unrecoverable rejection). */
    public fun onIncomingFailed(transferId: String, reason: String): Unit = Unit

    /**
     * True when a fresh inbound FILE_START for [transferId] is a **retry** of a transfer this
     * device already accepted, rather than a new offer that must pass the acceptance gate.
     *
     * A failed receive is torn down completely — sink closed, pipeline session dropped — so the
     * sender's retry arrives as a brand-new session. Without this check the host would re-open the
     * offer gate on a transfer the user already accepted: the sink stays deferred, every chunk is
     * dropped, no ACK ever goes back and the row sits frozen. Hosts call it on the session-started
     * edge and resolve the destination sink immediately when it is true.
     *
     * [FlashTransferState.Cancelled] is deliberately excluded — a declined offer must never be
     * silently accepted because the sender tried again — and so are `Offered` (the normal gate)
     * and `Completed` (nothing left to receive).
     */
    public fun isResumableInboundRetry(transferId: String): Boolean {
        val transfer = activeTransfers.value.firstOrNull { it.id.value == transferId } ?: return false
        if (transfer.direction != FlashTransferDirection.Receiving) return false
        return transfer.state == FlashTransferState.Transferring ||
            transfer.state == FlashTransferState.Verifying ||
            transfer.state == FlashTransferState.Paused ||
            transfer.state == FlashTransferState.Failed
    }
}
