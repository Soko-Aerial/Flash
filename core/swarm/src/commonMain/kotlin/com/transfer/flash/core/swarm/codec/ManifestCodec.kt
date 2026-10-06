package com.transfer.flash.core.swarm.codec

import com.transfer.flash.core.swarm.model.ContentRoot
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.swarm.model.SwarmManifest
import com.transfer.flash.core.transfer.chunked.Sha256
import okio.Buffer

/**
 * Binary codec for canonical swarm manifests ("FSWM", SW-3).
 *
 * Header:
 * - magic: 4B "FSWM"
 * - version: u8 (1)
 * - pieceSize: u32 LE
 * - totalSize: u64 LE
 * - fileSha256: 32B
 * - pieceCount: u32 LE
 * - pieceHashes: pieceCount * 32B
 *
 * root = SHA-256(canonicalBytes)
 */
public object ManifestCodec {
    public const val MAGIC: String = "FSWM"
    public const val VERSION: Int = 1
    public const val HEADER_SIZE: Int = 53
    public const val FRAGMENT_SIZE: Int = 65536 // 64 KiB
    public const val MAX_FRAGMENTS: Int = 9

    private val MAGIC_BYTES: ByteArray = byteArrayOf('F'.code.toByte(), 'S'.code.toByte(), 'W'.code.toByte(), 'M'.code.toByte())

    public fun encodeCanonical(manifest: SwarmManifest): ByteArray {
        val buffer = Buffer()
        buffer.write(MAGIC_BYTES)
        buffer.writeByte(manifest.version)
        buffer.writeIntLe(manifest.pieceSize)
        buffer.writeLongLe(manifest.totalSize)
        buffer.write(manifest.fileSha256)
        buffer.writeIntLe(manifest.pieceHashes.size)
        for (hash in manifest.pieceHashes) {
            buffer.write(hash)
        }
        return buffer.readByteArray()
    }

    public fun computeRoot(canonicalBytes: ByteArray): ContentRoot {
        return ContentRoot.fromBytes(Sha256.digest(canonicalBytes))
    }

    public fun decode(bytes: ByteArray, expectedRoot: ContentRoot? = null): SwarmManifest? {
        if (bytes.size < HEADER_SIZE) return null
        val buffer = Buffer().write(bytes)
        val magic = buffer.readByteArray(4)
        if (!magic.contentEquals(MAGIC_BYTES)) return null
        val version = buffer.readByte().toInt() and 0xFF
        if (version != VERSION) return null
        val pieceSize = buffer.readIntLe()
        if (pieceSize !in PieceMath.MIN_PIECE_SIZE..PieceMath.MAX_PIECE_SIZE) return null
        if ((pieceSize and (pieceSize - 1)) != 0) return null
        val totalSize = buffer.readLongLe()
        if (totalSize <= 0 || totalSize > PieceMath.MAX_SWARMABLE_FILE_SIZE) return null
        val fileSha256 = buffer.readByteArray(32)
        val pieceCount = buffer.readIntLe()
        if (pieceCount <= 0 || pieceCount > PieceMath.MAX_PIECE_COUNT) return null
        val expectedPieceCount = PieceMath.pieceCount(totalSize, pieceSize)
        if (pieceCount != expectedPieceCount) return null
        if (bytes.size != HEADER_SIZE + pieceCount * 32) return null

        val hashes = ArrayList<ByteArray>(pieceCount)
        for (i in 0 until pieceCount) {
            hashes.add(buffer.readByteArray(32))
        }

        val root = computeRoot(bytes)
        if (expectedRoot != null && root != expectedRoot) return null

        return SwarmManifest(
            version = version,
            pieceSize = pieceSize,
            totalSize = totalSize,
            fileSha256 = fileSha256,
            pieceHashes = hashes,
            root = root,
        )
    }

    public fun fragment(canonicalBytes: ByteArray): List<ByteArray> {
        val fragments = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < canonicalBytes.size) {
            val len = (canonicalBytes.size - offset).coerceAtMost(FRAGMENT_SIZE)
            fragments.add(canonicalBytes.copyOfRange(offset, offset + len))
            offset += len
        }
        require(fragments.size <= MAX_FRAGMENTS) {
            "Manifest fragments exceed limit: ${fragments.size} > $MAX_FRAGMENTS"
        }
        return fragments
    }

    public class Reassembler(public val expectedRoot: ContentRoot) {
        private var expectedCount: Int? = null
        private val receivedFragments = mutableMapOf<Int, ByteArray>()

        public fun addFragment(fragmentIndex: Int, fragmentCount: Int, data: ByteArray): SwarmManifest? {
            if (fragmentCount !in 1..MAX_FRAGMENTS) return null
            if (fragmentIndex !in 0 until fragmentCount) return null
            if (expectedCount == null) {
                expectedCount = fragmentCount
            } else if (expectedCount != fragmentCount) {
                return null
            }
            if (fragmentIndex < fragmentCount - 1 && data.size != FRAGMENT_SIZE) return null
            if (fragmentIndex == fragmentCount - 1 && (data.isEmpty() || data.size > FRAGMENT_SIZE)) return null

            receivedFragments[fragmentIndex] = data
            if (receivedFragments.size == fragmentCount) {
                val totalBytes = receivedFragments.values.sumOf { it.size }
                val assembled = ByteArray(totalBytes)
                var at = 0
                for (i in 0 until fragmentCount) {
                    val part = receivedFragments[i] ?: return null
                    part.copyInto(assembled, at)
                    at += part.size
                }
                return decode(assembled, expectedRoot)
            }
            return null
        }
    }
}
