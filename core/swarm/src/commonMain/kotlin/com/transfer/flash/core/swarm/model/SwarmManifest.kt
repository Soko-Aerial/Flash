package com.transfer.flash.core.swarm.model

/**
 * Manifest representing a swarmable file and its verified piece hashes (SW-3).
 */
public data class SwarmManifest(
    public val version: Int,
    public val pieceSize: Int,
    public val totalSize: Long,
    public val fileSha256: ByteArray,
    public val pieceHashes: List<ByteArray>,
    public val root: ContentRoot,
) {
    init {
        require(version == 1) { "Only manifest version 1 is supported, got $version" }
        require(pieceSize in PieceMath.MIN_PIECE_SIZE..PieceMath.MAX_PIECE_SIZE) {
            "pieceSize $pieceSize outside allowed range [${PieceMath.MIN_PIECE_SIZE}, ${PieceMath.MAX_PIECE_SIZE}]"
        }
        require((pieceSize and (pieceSize - 1)) == 0) { "pieceSize $pieceSize must be a power of two" }
        require(totalSize > 0) { "totalSize must be > 0, got $totalSize" }
        require(fileSha256.size == 32) { "fileSha256 must be 32 bytes, got ${fileSha256.size}" }
        val expectedPieceCount = PieceMath.pieceCount(totalSize, pieceSize)
        require(pieceHashes.size == expectedPieceCount) {
            "pieceHashes count ${pieceHashes.size} does not match expected $expectedPieceCount"
        }
        for ((idx, h) in pieceHashes.withIndex()) {
            require(h.size == 32) { "pieceHash at index $idx must be 32 bytes, got ${h.size}" }
        }
    }

    public val pieceCount: Int get() = pieceHashes.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SwarmManifest) return false
        if (version != other.version) return false
        if (pieceSize != other.pieceSize) return false
        if (totalSize != other.totalSize) return false
        if (!fileSha256.contentEquals(other.fileSha256)) return false
        if (root != other.root) return false
        if (pieceHashes.size != other.pieceHashes.size) return false
        for (i in pieceHashes.indices) {
            if (!pieceHashes[i].contentEquals(other.pieceHashes[i])) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + pieceSize
        result = 31 * result + totalSize.hashCode()
        result = 31 * result + fileSha256.contentHashCode()
        result = 31 * result + root.hashCode()
        for (h in pieceHashes) {
            result = 31 * result + h.contentHashCode()
        }
        return result
    }
}
