package com.transfer.flash.core.transfer.model

@JvmInline
public value class FlashTransferId(public val value: String)

public enum class FlashTransferDirection {
    Sending,
    Receiving,
}

public enum class FlashTransferState {
    Offered,
    Queued,
    Transferring,
    Paused,
    Verifying,
    Completed,
    Failed,
    Cancelled,
}

public data class FlashTransfer(
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
    /** Whether this transfer was transmitted with end-to-end encryption. */
    val isEncrypted: Boolean = false,
    /** High-level wait reason when state is Queued (§5.5). */
    val waitReason: FlashTransferWaitReason? = null,
    /** Whether all missing pieces are distributed to peers, permitting the sender to safely disconnect (SW-11). */
    val canGoOffline: Boolean = false,
    /** Number of peers actively holding pieces of this content online. */
    val holdersOnline: Int = 0,
    /**
     * Real piece map of a group swarm transfer, at most 64 blocks: 0 missing, 1 held by a connected
     * member, 2 being fetched, 3 verified here. Empty for every non-swarm (1:1) transfer, so the UI
     * shows a piece map only when there is real piece state to show.
     */
    val pieceBlocks: List<Int> = emptyList(),
    /**
     * Sender side of a group swarm transfer: how far each member has got, so the sender can see who already has the
     * whole file and may leave. Empty for a receiver and for every 1:1 transfer.
     */
    val recipients: List<FlashTransferRecipient> = emptyList(),
)

/** One member's copy of a file being sent to a group (see [FlashTransfer.recipients]). */
public data class FlashTransferRecipient(
    /** The member's device id; the UI resolves the name. */
    val peerId: String,
    val bytesHeld: Long,
    val bytesTotal: Long,
    /** The member has the whole file (it stays true if the member goes offline afterwards). */
    val hasAll: Boolean,
    val online: Boolean,
    /** How fast the member's copy is growing right now, bytes per second; 0 when idle, complete or offline. */
    val rateBytesPerSec: Long = 0L,
)
