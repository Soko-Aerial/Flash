package com.transfer.flash.core.transfer

/**
 * Port bridging `:core:transfer` (`RealFlashTransferRepository`) with the cross-route continuity
 * coordinator in `:core:engine` (`TransferRouteCoordinator`), allowing LAN and Hub routes to share
 * single active-route ownership, verified byte checkpoints, and atomic completion without circular
 * module dependencies.
 */
public interface TransferRouteContinuityPort {
    /**
     * Attempts to register or acquire `LAN` route ownership for [fileId].
     * Returns `false` when `HUB` actively owns the route or the logical transfer is already terminal
     * (`COMPLETED` / `CANCELLED`) or `INCOMPATIBLE`.
     */
    public fun tryAcquireLanRoute(
        transferId: String,
        fileId: String,
        fileName: String,
        plainBytes: Long,
        peerDeviceId: String?,
        isSending: Boolean,
        chunkSize: Int,
        localPath: String? = null,
        fileSha256Hex: String? = null,
    ): Boolean

    /** Records chunk indices cryptographically verified over LAN against [fileId] / [transferId]. */
    public fun recordLanVerifiedChunks(
        transferId: String,
        fileId: String,
        chunkIndexes: Collection<Int>,
    )

    /**
     * Returns LAN chunk indices 100%-covered by verified bytes previously stored during an interrupted
     * Hub download for [fileId].
     */
    public fun coveredLanChunksFromHub(
        fileId: String,
        fileName: String,
        plainBytes: Long,
        senderDeviceId: String?,
        lanChunkSize: Int,
    ): List<Int>

    /**
     * Copies verified plaintext spans from Hub partial storage into [destPath] for any 100%-covered
     * LAN chunk index so `ReceivePipeline` skips re-transferring those chunks over LAN.
     */
    public suspend fun adoptHubBytesIntoLanFile(
        transferId: String,
        fileId: String,
        fileName: String,
        plainBytes: Long,
        senderDeviceId: String?,
        lanChunkSize: Int,
        destPath: String,
    ): List<Int>

    /** Releases active `LAN` route ownership while preserving verified byte spans on disk. */
    public fun releaseLanRoute(transferId: String, fileId: String)

    /**
     * Atomically marks [fileId] as completed on `LAN`. On receive, provides [publishedPath] and
     * [actualSha256Hex]; on send, completes outbound continuity.
     * Returns `false` if another route already completed or cancelled this logical transfer.
     */
    public fun completeLanTransfer(
        transferId: String,
        fileId: String,
        isSending: Boolean,
        publishedPath: String?,
        actualSha256Hex: String?,
    ): Boolean

    /** Clears unverified/corrupt verified spans after a whole-file integrity mismatch. */
    public fun failLanIntegrity(transferId: String, fileId: String)

    /** Marks the logical transfer cancelled across both routes. */
    public fun cancelLogicalTransfer(transferId: String, fileId: String?)

    /** Returns `true` if another route already marked [fileId] completed or cancelled. */
    public fun isTerminalElsewhere(fileId: String): Boolean
}
