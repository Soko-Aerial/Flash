package com.transfer.flash.core.swarm.sim

import com.transfer.flash.core.swarm.model.Bitfield
import com.transfer.flash.core.swarm.model.PieceMath
import com.transfer.flash.core.transfer.chunked.Sha256

/**
 * In-memory storage mock with crash and partial file semantics (SW-5).
 * - Tracks unpersisted writes vs durable synced bits.
 * - Crash semantics: unsynced writes are lost on crash.
 * - Memory efficient: does not allocate full payload for huge transfers unless specified.
 */
class SimStorage(
    val pieceCount: Int,
    val pieceSize: Int,
    val totalSize: Long,
    val fileSha256: ByteArray,
    val pieceHashes: List<ByteArray>,
) {
    val writtenBits: Bitfield = Bitfield(pieceCount)
    val persistedBits: Bitfield = Bitfield(pieceCount)

    var corruptReads: Boolean = false
    var freeSpaceBytes: Long = Long.MAX_VALUE
    var isPartialDeleted: Boolean = false

    private val customPieces = mutableMapOf<Int, ByteArray>()

    fun setPiecePayload(index: Int, bytes: ByteArray) {
        customPieces[index] = bytes
    }

    fun writePiece(index: Int, bytes: ByteArray): Boolean {
        if (index !in 0 until pieceCount) return false
        val pieceLen = PieceMath.pieceLength(index, totalSize, pieceSize)
        if (freeSpaceBytes < pieceLen) return false

        writtenBits.set(index, true)
        customPieces[index] = bytes
        isPartialDeleted = false
        return true
    }

    fun sync(bits: ByteArray) {
        val newPersisted = Bitfield.fromByteArray(pieceCount, bits)
        for (i in 0 until pieceCount) {
            persistedBits.set(i, newPersisted.get(i))
        }
    }

    fun crash() {
        // Drop unsynced writes
        for (i in 0 until pieceCount) {
            val isPersisted = persistedBits.get(i)
            writtenBits.set(i, isPersisted)
            if (!isPersisted) {
                customPieces.remove(i)
            }
        }
    }

    fun readPiece(index: Int): ByteArray? {
        if (index !in 0 until pieceCount) return null
        if (!writtenBits.get(index)) return null

        val pieceLen = PieceMath.pieceLength(index, totalSize, pieceSize)
        val payload = customPieces[index] ?: generateDefaultPieceBytes(index, pieceLen)

        if (corruptReads) {
            val corrupted = payload.copyOf()
            if (corrupted.isNotEmpty()) {
                corrupted[0] = (corrupted[0].toInt() xor 0xFF).toByte()
            }
            return corrupted
        }
        return payload
    }

    fun deletePartial() {
        isPartialDeleted = true
        for (i in 0 until pieceCount) {
            writtenBits.set(i, false)
            persistedBits.set(i, false)
        }
        customPieces.clear()
    }

    fun verifyWholeFile(expectedFileSha: ByteArray): Boolean {
        if (writtenBits.count() < pieceCount) return false
        return fileSha256.contentEquals(expectedFileSha)
    }

    companion object {
        fun generateDefaultPieceBytes(index: Int, length: Int): ByteArray {
            val bytes = ByteArray(length)
            val fillByte = (index and 0xFF).toByte()
            bytes.fill(fillByte)
            return bytes
        }
    }
}
