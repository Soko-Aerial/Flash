package com.transfer.flash.core.swarm.model

/**
 * Pure piece calculation utilities for swarm content (SW-3).
 *
 * Enforces wire limits:
 * - Piece size: power of two from 64 KiB to 1 MiB
 * - Piece count: at most 16,384
 * - Max swarmable size: 16,384 * 1 MiB = 16 GiB. Files larger or zero-length are not swarmable (return null).
 */
public object PieceMath {
    public const val MIN_PIECE_SIZE: Int = 64 * 1024 // 65,536 bytes
    public const val MAX_PIECE_SIZE: Int = 1024 * 1024 // 1,048,576 bytes
    public const val MAX_PIECE_COUNT: Int = 16384
    public const val MAX_SWARMABLE_FILE_SIZE: Long = 16384L * 1024 * 1024 // 17,179,869,184 bytes (16 GiB)

    /**
     * Determines the piece size for a given file size.
     * Returns the smallest power of two >= 64 KiB that results in at most 16,384 pieces,
     * capped at [maxPiece].
     * Returns null if [totalSize] <= 0 or if the file requires more than 16,384 pieces even at [maxPiece].
     */
    public fun choosePieceSize(totalSize: Long, maxPiece: Int = MAX_PIECE_SIZE): Int? {
        if (totalSize <= 0 || totalSize > MAX_SWARMABLE_FILE_SIZE) return null
        val cap = maxPiece.coerceAtMost(MAX_PIECE_SIZE).coerceAtLeast(MIN_PIECE_SIZE)
        var size = MIN_PIECE_SIZE
        while (size <= cap) {
            val count = (totalSize + size - 1) / size
            if (count <= MAX_PIECE_COUNT) {
                return size
            }
            if (size == cap) break
            val next = size shl 1
            if (next <= 0 || next > cap) break
            size = next
        }
        return null
    }

    /**
     * Number of pieces needed to cover [totalSize] using [pieceSize].
     */
    public fun pieceCount(totalSize: Long, pieceSize: Int): Int {
        require(totalSize in 1..MAX_SWARMABLE_FILE_SIZE) {
            "totalSize must be in 1..$MAX_SWARMABLE_FILE_SIZE, got $totalSize"
        }
        require(pieceSize in MIN_PIECE_SIZE..MAX_PIECE_SIZE) {
            "pieceSize must be in $MIN_PIECE_SIZE..$MAX_PIECE_SIZE, got $pieceSize"
        }
        val count = (totalSize + pieceSize - 1) / pieceSize
        require(count <= MAX_PIECE_COUNT) { "pieceCount exceeds limit: $count > $MAX_PIECE_COUNT" }
        return count.toInt()
    }

    /**
     * Byte offset of the piece at [index] within the whole file.
     */
    public fun pieceOffset(index: Int, pieceSize: Int): Long {
        require(index >= 0) { "index must be >= 0, got $index" }
        require(pieceSize > 0) { "pieceSize must be > 0, got $pieceSize" }
        return index.toLong() * pieceSize.toLong()
    }

    /**
     * Byte length of the piece at [index]. The final piece may be shorter than [pieceSize].
     */
    public fun pieceLength(index: Int, totalSize: Long, pieceSize: Int): Int {
        val totalPieces = pieceCount(totalSize, pieceSize)
        require(index in 0 until totalPieces) {
            "index $index out of bounds for piece count $totalPieces"
        }
        return if (index == totalPieces - 1) {
            (totalSize - pieceOffset(index, pieceSize)).toInt()
        } else {
            pieceSize
        }
    }
}
