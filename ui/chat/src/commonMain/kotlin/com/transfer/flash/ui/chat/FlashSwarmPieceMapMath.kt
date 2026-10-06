package com.transfer.flash.ui.chat

public enum class FlashPieceStatus {
    VerifiedSaved,
    InFlightDownloading,
    AvailableOnPeers,
    Missing,
}

public object FlashSwarmPieceMapMath {
    public const val DEFAULT_BLOCK_COUNT: Int = 64
    public const val DEFAULT_COLUMNS: Int = 16

    /**
     * Computes the status for each micro-block in a grid of [totalBlocks].
     */
    public fun computeBlockStatuses(
        totalBlocks: Int = DEFAULT_BLOCK_COUNT,
        progress: Float,
        holdersOnline: Int = 0,
        isDownloading: Boolean = false,
    ): List<FlashPieceStatus> {
        val clampedProgress = progress.coerceIn(0f, 1f)
        if (clampedProgress >= 1f) {
            return List(totalBlocks) { FlashPieceStatus.VerifiedSaved }
        }

        val verifiedCount = (totalBlocks * clampedProgress).toInt().coerceIn(0, totalBlocks)
        val inFlightCount = if (isDownloading && verifiedCount < totalBlocks) {
            minOf(4, totalBlocks - verifiedCount)
        } else {
            0
        }

        // Swarm peers hold extra pieces beyond what this device has downloaded
        val swarmRatio = (clampedProgress + (0.25f * holdersOnline.coerceIn(0, 4))).coerceIn(0f, 1f)
        val swarmCount = (totalBlocks * swarmRatio).toInt().coerceIn(verifiedCount + inFlightCount, totalBlocks)

        return List(totalBlocks) { index ->
            when {
                index < verifiedCount -> FlashPieceStatus.VerifiedSaved
                index < verifiedCount + inFlightCount -> FlashPieceStatus.InFlightDownloading
                index < swarmCount -> FlashPieceStatus.AvailableOnPeers
                else -> FlashPieceStatus.Missing
            }
        }
    }

    /**
     * Summary text e.g. "48 / 64 pieces verified • 4 downloading".
     */
    public fun summaryText(
        statuses: List<FlashPieceStatus>,
    ): String {
        val verified = statuses.count { it == FlashPieceStatus.VerifiedSaved }
        val inFlight = statuses.count { it == FlashPieceStatus.InFlightDownloading }
        val onPeers = statuses.count { it == FlashPieceStatus.AvailableOnPeers }
        val total = statuses.size

        return if (verified == total) {
            "All $total pieces verified & saved"
        } else if (inFlight > 0) {
            "$verified of $total pieces • $inFlight in-flight"
        } else if (onPeers > 0) {
            "$verified of $total pieces • $onPeers on swarm"
        } else {
            "$verified of $total pieces verified"
        }
    }
}
