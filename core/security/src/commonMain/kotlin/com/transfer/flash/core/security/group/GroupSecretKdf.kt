package com.transfer.flash.core.security.group

import com.transfer.flash.core.security.crypto.Hkdf

/**
 * HKDF-SHA256 key derivation for group secrets (ADR-073, protocol "Keys and commitment").
 *
 * Derives authentication and beacon keys bound to the group id and epoch.
 */
public object GroupSecretKdf {

    private const val INFO_AUTH_PREFIX = "flash-gsa-v1"
    private const val INFO_BEACON_PREFIX = "flash-gbeacon-v1"
    public const val KEY_LENGTH_BYTES: Int = 32

    /**
     * Derives K_auth(epoch):
     * `HKDF-SHA256(ikm = secret, salt = empty, info = "flash-gsa-v1" ‖ lp(groupId) ‖ u32 epoch, length = 32)`
     */
    public fun authKey(secret: GroupSecret, groupId: String, epoch: Long): ByteArray {
        validateInputs(groupId, epoch)
        val info = buildInfo(INFO_AUTH_PREFIX, groupId, epoch)
        return Hkdf.derive(
            ikm = secret.toByteArray(),
            salt = ByteArray(0),
            info = info,
            outLength = KEY_LENGTH_BYTES,
        )
    }

    /**
     * Derives K_beacon(epoch):
     * `HKDF-SHA256(ikm = secret, salt = empty, info = "flash-gbeacon-v1" ‖ lp(groupId) ‖ u32 epoch, length = 32)`
     */
    public fun beaconKey(secret: GroupSecret, groupId: String, epoch: Long): ByteArray {
        validateInputs(groupId, epoch)
        val info = buildInfo(INFO_BEACON_PREFIX, groupId, epoch)
        return Hkdf.derive(
            ikm = secret.toByteArray(),
            salt = ByteArray(0),
            info = info,
            outLength = KEY_LENGTH_BYTES,
        )
    }

    private fun validateInputs(groupId: String, epoch: Long) {
        require(groupId.isNotEmpty()) { "groupId must not be empty" }
        require(epoch in 1L..0xFFFF_FFFFL) { "epoch must be in 1..4294967295 (u32), was $epoch" }
    }

    private fun buildInfo(prefix: String, groupId: String, epoch: Long): ByteArray {
        val prefixBytes = prefix.encodeToByteArray()
        val groupBytes = groupId.encodeToByteArray()
        require(groupBytes.size <= 0xFFFF) { "groupId length exceeds u16 max" }

        val out = ByteArray(prefixBytes.size + 2 + groupBytes.size + 4)
        var offset = 0

        prefixBytes.copyInto(out, destinationOffset = offset)
        offset += prefixBytes.size

        // lp(groupId): u16 big-endian length prefix followed by UTF-8 bytes
        out[offset] = ((groupBytes.size ushr 8) and 0xFF).toByte()
        out[offset + 1] = (groupBytes.size and 0xFF).toByte()
        offset += 2

        groupBytes.copyInto(out, destinationOffset = offset)
        offset += groupBytes.size

        // u32 big-endian epoch
        out[offset] = ((epoch ushr 24) and 0xFF).toByte()
        out[offset + 1] = ((epoch ushr 16) and 0xFF).toByte()
        out[offset + 2] = ((epoch ushr 8) and 0xFF).toByte()
        out[offset + 3] = (epoch and 0xFF).toByte()

        return out
    }
}
