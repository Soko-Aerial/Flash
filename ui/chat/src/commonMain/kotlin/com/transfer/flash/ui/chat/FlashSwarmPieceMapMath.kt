package com.transfer.flash.ui.chat

public enum class FlashPieceStatus {
    VerifiedSaved,
    InFlightDownloading,
    AvailableOnPeers,
    Missing,
}

public object FlashSwarmPieceMapMath {
    public const val DEFAULT_COLUMNS: Int = 16

    /**
     * Maps the engine's real piece blocks to display statuses. Codes match `FlashTransfer.pieceBlocks`:
     * 0 missing, 1 held by a connected member, 2 being fetched, 3 verified here. Nothing is estimated
     * here; an unknown code reads as missing rather than as progress.
     */
    public fun statusesFromBlocks(blocks: List<Int>): List<FlashPieceStatus> = blocks.map { code ->
        when (code) {
            3 -> FlashPieceStatus.VerifiedSaved
            2 -> FlashPieceStatus.InFlightDownloading
            1 -> FlashPieceStatus.AvailableOnPeers
            else -> FlashPieceStatus.Missing
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
