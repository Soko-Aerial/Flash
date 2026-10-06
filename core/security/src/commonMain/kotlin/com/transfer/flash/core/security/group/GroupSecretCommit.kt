package com.transfer.flash.core.security.group

import com.transfer.flash.core.security.crypto.constantTimeBytesEqual
import com.transfer.flash.core.security.crypto.sha256
import com.transfer.flash.core.security.crypto.toHexLower

/**
 * Commitment derivation for group secret rotation (ADR-073, protocol "Keys and commitment").
 *
 * `commit(e) = SHA-256("flash-gs-commit-v1" ‖ lp(groupId) ‖ u32 epoch ‖ secret)`
 */
public object GroupSecretCommit {

    private const val COMMIT_TAG = "flash-gs-commit-v1"
    public const val COMMIT_LENGTH_BYTES: Int = 32

    /** Computes the 32-byte SHA-256 commitment of the secret for (groupId, epoch). */
    public fun of(groupId: String, epoch: Long, secret: GroupSecret): ByteArray {
        require(groupId.isNotEmpty()) { "groupId must not be empty" }
        require(epoch in 1L..0xFFFF_FFFFL) { "epoch must be in 1..4294967295 (u32), was $epoch" }

        val tagBytes = COMMIT_TAG.encodeToByteArray()
        val groupBytes = groupId.encodeToByteArray()
        require(groupBytes.size <= 0xFFFF) { "groupId length exceeds u16 max" }
        val secretBytes = secret.toByteArray()

        val data = ByteArray(tagBytes.size + 2 + groupBytes.size + 4 + secretBytes.size)
        var offset = 0

        tagBytes.copyInto(data, destinationOffset = offset)
        offset += tagBytes.size

        // lp(groupId): u16 big-endian length prefix followed by UTF-8 bytes
        data[offset] = ((groupBytes.size ushr 8) and 0xFF).toByte()
        data[offset + 1] = (groupBytes.size and 0xFF).toByte()
        offset += 2

        groupBytes.copyInto(data, destinationOffset = offset)
        offset += groupBytes.size

        // u32 big-endian epoch
        data[offset] = ((epoch ushr 24) and 0xFF).toByte()
        data[offset + 1] = ((epoch ushr 16) and 0xFF).toByte()
        data[offset + 2] = ((epoch ushr 8) and 0xFF).toByte()
        data[offset + 3] = (epoch and 0xFF).toByte()
        offset += 4

        secretBytes.copyInto(data, destinationOffset = offset)

        return sha256(data)
    }

    /** Computes the commitment and formats it as a 64-character lowercase hex string. */
    public fun ofHex(groupId: String, epoch: Long, secret: GroupSecret): String =
        of(groupId, epoch, secret).toHexLower()

    /** Constant-time verification that [commit] matches the secret for (groupId, epoch). */
    public fun matches(commit: ByteArray, groupId: String, epoch: Long, secret: GroupSecret): Boolean {
        if (commit.size != COMMIT_LENGTH_BYTES) return false
        val computed = of(groupId, epoch, secret)
        return constantTimeBytesEqual(commit, computed)
    }

    /** Constant-time verification that [commitHex] matches the secret for (groupId, epoch). */
    public fun matchesHex(commitHex: String, groupId: String, epoch: Long, secret: GroupSecret): Boolean {
        val expected = ofHex(groupId, epoch, secret)
        return constantTimeBytesEqual(
            commitHex.lowercase().encodeToByteArray(),
            expected.encodeToByteArray(),
        )
    }
}
