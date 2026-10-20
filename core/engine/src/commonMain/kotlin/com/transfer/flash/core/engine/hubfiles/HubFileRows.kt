package com.transfer.flash.core.engine.hubfiles

import com.transfer.flash.core.persistence.db.entity.HubFileEntity
import com.transfer.flash.core.persistence.db.entity.HubFileStates
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferId
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.core.transfer.model.FlashTransferWaitReason

/** What a run in progress knows that the table does not (yet): bytes so far and the current speed. */
internal data class HubFileLive(val bytesDone: Long, val speedBytesPerSec: Long, val etaSeconds: Long)

/**
 * The Transfers screen's and the bubble's view of one `hub_file` row (ADR-216). Pure: [running] says whether a run is
 * active right now, which only the binding knows.
 *
 * - UPLOADING / DOWNLOADING: Transferring while a run is active, Paused when the person paused it, otherwise Queued,
 *   with the last transient reason as the error text (deviation D16: a transient reason is shown alone, never as "could
 *   not take this file").
 * - READY / QUEUED (sent) and DONE (received): Completed. For a sender the bytes are on the hub; whether the recipient
 *   has the ticket is the chat bubble's tick, not the transfer's state.
 * - OFFERED: Offered, waiting for Accept (or the auto-download setting).
 */
internal object HubFileRows {

    fun toTransfer(row: HubFileEntity, live: HubFileLive?, running: Boolean, peerName: String?): FlashTransfer {
        val state = when (row.state) {
            HubFileStates.UPLOADING, HubFileStates.DOWNLOADING -> when {
                row.paused -> FlashTransferState.Paused
                running -> FlashTransferState.Transferring
                else -> FlashTransferState.Queued
            }
            HubFileStates.READY, HubFileStates.QUEUED, HubFileStates.DONE -> FlashTransferState.Completed
            HubFileStates.OFFERED -> FlashTransferState.Offered
            HubFileStates.FAILED -> FlashTransferState.Failed
            else -> FlashTransferState.Cancelled
        }
        val active = state == FlashTransferState.Transferring
        return FlashTransfer(
            id = FlashTransferId(row.transferId),
            peerName = peerName?.ifBlank { null } ?: row.peerId.take(8),
            fileName = row.fileName,
            direction = if (row.direction == HubFileStates.OUT) FlashTransferDirection.Sending else FlashTransferDirection.Receiving,
            bytesDone = if (state == FlashTransferState.Completed) row.plainBytes else maxOf(row.bytesDone, live?.bytesDone ?: 0L),
            bytesTotal = row.plainBytes,
            state = state,
            speedBytesPerSec = if (active) live?.speedBytesPerSec ?: 0L else 0L,
            etaSeconds = if (active) live?.etaSeconds ?: 0L else 0L,
            errorMessage = row.errorText,
            sourceUri = row.sourceUri,
            wireFileId = row.fileId,
            peerDeviceId = row.peerId,
            localPath = row.localPath,
            isEncrypted = true,
            // Only with a reason to show: the Transfers screen then prints that reason, and a row waiting to start says
            // "Queued" rather than "Waiting for Wi-Fi", which the hub path does not need.
            waitReason = if (state == FlashTransferState.Queued && !row.errorText.isNullOrBlank()) FlashTransferWaitReason.WaitingForNetwork else null,
            viaHub = true,
        )
    }
}
