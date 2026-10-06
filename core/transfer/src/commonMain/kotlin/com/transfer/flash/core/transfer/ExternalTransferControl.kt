package com.transfer.flash.core.transfer

/**
 * Control operations for external transfer rows bridged into [RealFlashTransferRepository] (SW-8).
 */
public interface ExternalTransferControl {
    /** True if this control owns the transfer with [transferId]. */
    public fun owns(transferId: String): Boolean

    /** Receiver accepts an offered transfer. */
    public suspend fun accept(transferId: String)

    /** Receiver declines an offered transfer. */
    public suspend fun decline(transferId: String)

    /** User pauses the transfer. */
    public suspend fun pause(transferId: String)

    /** User resumes the transfer. */
    public suspend fun resume(transferId: String)

    /** Cancels the transfer (local delete for receiver, tombstone for origin). */
    public suspend fun cancel(transferId: String)

    /** Non-destructive pause triggered by the system (e.g. background service timeout). */
    public suspend fun pauseForSystem(transferId: String, reason: String)
}
