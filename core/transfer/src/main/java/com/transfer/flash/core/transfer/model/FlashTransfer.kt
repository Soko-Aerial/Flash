package com.transfer.flash.core.transfer.model

@JvmInline
value class FlashTransferId(val value: String)

enum class FlashTransferDirection {
    Sending,
    Receiving,
}

enum class FlashTransferState {
    Offered,
    Queued,
    Transferring,
    Paused,
    Verifying,
    Completed,
    Failed,
    Cancelled,
}

data class FlashTransfer(
    val id: FlashTransferId,
    val peerName: String,
    val fileName: String,
    val direction: FlashTransferDirection,
    val bytesDone: Long,
    val bytesTotal: Long,
    val state: FlashTransferState,
    val speedBytesPerSec: Long = 0L,
    val etaSeconds: Long = 0L,
    val errorMessage: String? = null,
    /**
     * Original content URI / descriptor this transfer reads from (send-side only). Required for
     * resume: `fileName` is a display label, not an openable source.
     */
    val sourceUri: String? = null,
    /**
     * Stable wire-level file id for this transfer. MUST survive pause/resume: the receiver's
     * pipeline keys sessions on (transferId, fileId) — a new fileId under an existing
     * transferId is a SESSION_CONFLICT rejection.
     */
    val wireFileId: String? = null,
    /** Counterpart device id — required to route wire control frames (pause/resume/cancel). */
    val peerDeviceId: String? = null,
    /**
     * Absolute path to the completed file on THIS device: the received file for inbound transfers,
     * or the source file for outbound ones. Openable/shareable via FileProvider. Null until known.
     */
    val localPath: String? = null,
)
