package com.transfer.flash.core.swarm.model

import com.transfer.flash.core.swarm.codec.ManifestCodec
import com.transfer.flash.core.transfer.chunked.IncrementalSha256

/**
 * Streaming manifest builder (SW-3).
 * Feeds byte blocks in and computes piece hashes and whole-file hash without
 * retaining more than one piece in memory.
 */
public class ManifestBuilder(public val pieceSize: Int) {
    init {
        require(pieceSize in PieceMath.MIN_PIECE_SIZE..PieceMath.MAX_PIECE_SIZE) {
            "pieceSize $pieceSize outside allowed range [${PieceMath.MIN_PIECE_SIZE}, ${PieceMath.MAX_PIECE_SIZE}]"
        }
        require((pieceSize and (pieceSize - 1)) == 0) { "pieceSize $pieceSize must be a power of two" }
    }

    private val wholeFileDigest = IncrementalSha256()
    private var pieceDigest = IncrementalSha256()
    private var currentPieceBytes = 0
    private var totalBytes = 0L
    private val pieceHashes = mutableListOf<ByteArray>()
    private var isBuilt = false

    public fun addBlock(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
        check(!isBuilt) { "ManifestBuilder already built" }
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size) {
            "Invalid offset/length: offset=$offset length=$length size=${bytes.size}"
        }
        if (length == 0) return

        wholeFileDigest.update(bytes, offset, length)
        var remaining = length
        var curOffset = offset

        while (remaining > 0) {
            val needed = pieceSize - currentPieceBytes
            val chunk = if (remaining < needed) remaining else needed
            pieceDigest.update(bytes, curOffset, chunk)
            currentPieceBytes += chunk
            totalBytes += chunk
            curOffset += chunk
            remaining -= chunk

            if (currentPieceBytes == pieceSize) {
                pieceHashes.add(pieceDigest.digestRaw())
                pieceDigest = IncrementalSha256()
                currentPieceBytes = 0
            }
        }
    }

    public fun build(): SwarmManifest {
        check(!isBuilt) { "ManifestBuilder already built" }
        check(totalBytes > 0L) { "Cannot build manifest for empty file" }
        isBuilt = true

        if (currentPieceBytes > 0) {
            pieceHashes.add(pieceDigest.digestRaw())
            currentPieceBytes = 0
        }

        val fileSha256 = wholeFileDigest.digestRaw()
        val tempManifest = SwarmManifest(
            version = 1,
            pieceSize = pieceSize,
            totalSize = totalBytes,
            fileSha256 = fileSha256,
            pieceHashes = pieceHashes,
            root = ContentRoot("0".repeat(64)), // Placeholder for canonical root computation
        )
        val canonicalBytes = ManifestCodec.encodeCanonical(tempManifest)
        val root = ManifestCodec.computeRoot(canonicalBytes)
        return tempManifest.copy(root = root)
    }
}
