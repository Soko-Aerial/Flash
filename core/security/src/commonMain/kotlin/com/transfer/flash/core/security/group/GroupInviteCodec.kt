package com.transfer.flash.core.security.group

import com.transfer.flash.core.common.protocol.Base64Url

/**
 * Binary and URI codec for [GroupInvite] (ADR-044, ADR-073, GM-2).
 *
 * Wire specification (protocol.md):
 * ```text
 * flash://g/1/<base64url(payload), no padding>
 *
 * payload:
 * version      u8   1 (the "1" in the link repeats it)
 * groupId      lp   <= 64 bytes, starts with "g2-"
 * epoch        u32  >= 1 (big-endian)
 * secret       32B
 * groupName    lp   1..80 characters (GroupPolicy.MAX_GROUP_NAME_LENGTH), <= 320 bytes
 * inviterId    lp   <= 128 bytes
 * inviterFp    32B  SHA-256 of the inviter's identity public key
 * hintCount    u8   0..3
 * hints        hintCount x lp, each "host:port", <= 64 bytes
 * issuedAtMs   u64  display only (big-endian)
 * ```
 *
 * Guarantees:
 * - [decode] NEVER throws. Unknown versions, oversize fields, invalid counts,
 *   malformed UTF-8, and trailing bytes return `null`.
 * - The codec produces unpadded base64url strings.
 */
public object GroupInviteCodec {

    public const val URI_PREFIX: String = "flash://g/1/"
    public const val CURRENT_VERSION: Int = 1
    public const val MAX_GROUP_ID_BYTES: Int = 64
    public const val MAX_GROUP_NAME_BYTES: Int = 320
    public const val MAX_GROUP_NAME_CHARS: Int = 80
    public const val MAX_INVITER_ID_BYTES: Int = 128
    public const val MAX_HINTS: Int = 3
    public const val MAX_HINT_BYTES: Int = 64
    public const val FINGERPRINT_BYTES: Int = 32
    public const val SECRET_BYTES: Int = 32

    /**
     * Encodes [invite] into a binary byte array.
     */
    public fun encodeToBinary(invite: GroupInvite): ByteArray {
        val groupIdBytes = invite.groupId.encodeToByteArray()
        val groupNameBytes = invite.groupName.encodeToByteArray()
        val inviterIdBytes = invite.inviterDeviceId.encodeToByteArray()
        val hintsBytes = invite.addressHints.map { it.encodeToByteArray() }

        var totalSize = 1 + // version u8
            2 + groupIdBytes.size + // groupId lp
            4 + // epoch u32
            SECRET_BYTES + // secret 32B
            2 + groupNameBytes.size + // groupName lp
            2 + inviterIdBytes.size + // inviterId lp
            FINGERPRINT_BYTES + // inviterFp 32B
            1 + // hintCount u8
            8 // issuedAtMs u64

        for (hint in hintsBytes) {
            totalSize += 2 + hint.size
        }

        val out = ByteArray(totalSize)
        var offset = 0

        // version u8
        out[offset++] = (invite.version and 0xFF).toByte()

        // groupId lp
        offset = writeLp(out, offset, groupIdBytes)

        // epoch u32 (big-endian)
        offset = writeU32(out, offset, invite.epoch)

        // secret 32B
        val secretBytes = invite.secret.toByteArray()
        secretBytes.copyInto(out, offset)
        offset += SECRET_BYTES

        // groupName lp
        offset = writeLp(out, offset, groupNameBytes)

        // inviterId lp
        offset = writeLp(out, offset, inviterIdBytes)

        // inviterFp 32B
        invite.inviterKeyFingerprint.copyInto(out, offset)
        offset += FINGERPRINT_BYTES

        // hintCount u8
        out[offset++] = (invite.addressHints.size and 0xFF).toByte()

        // hints
        for (hint in hintsBytes) {
            offset = writeLp(out, offset, hint)
        }

        // issuedAtMs u64 (big-endian)
        writeU64(out, offset, invite.issuedAtMs)

        return out
    }

    /**
     * Encodes [invite] into the standard URI string `flash://g/1/<base64url>`.
     */
    public fun encode(invite: GroupInvite): String {
        val binary = encodeToBinary(invite)
        return URI_PREFIX + Base64Url.encode(binary)
    }

    /**
     * Decodes a [GroupInvite] from an invite link or base64url payload.
     * Returns `null` if malformed, invalid, or carrying extra trailing bytes.
     * Never throws.
     */
    public fun decode(linkOrPayload: String): GroupInvite? = runCatching {
        val trimmed = linkOrPayload.trim()
        val payload = if (trimmed.startsWith(URI_PREFIX, ignoreCase = true)) {
            trimmed.substring(URI_PREFIX.length)
        } else {
            trimmed
        }

        val bytes = Base64Url.decodeOrNull(payload) ?: return null
        decodeFromBinary(bytes)
    }.getOrNull()

    /**
     * Decodes a [GroupInvite] directly from raw binary bytes.
     * Returns `null` on any validation failure, truncation, or trailing bytes.
     */
    public fun decodeFromBinary(bytes: ByteArray): GroupInvite? {
        if (bytes.size < 1 + 2 + 4 + SECRET_BYTES + 2 + 2 + FINGERPRINT_BYTES + 1 + 8) {
            return null // Truncated before minimum viable invite size
        }

        var offset = 0

        // version u8
        val version = bytes[offset++].toInt() and 0xFF
        if (version != CURRENT_VERSION) return null

        // groupId lp
        val groupIdBytes = readLp(bytes, offset) ?: return null
        offset += 2 + groupIdBytes.size
        if (groupIdBytes.isEmpty() || groupIdBytes.size > MAX_GROUP_ID_BYTES) return null
        // R-16: malformed UTF-8 is refused, not silently replaced with U+FFFD (a lookalike of what was signed or compared).
        val groupId = runCatching { groupIdBytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
        if (!groupId.startsWith("g2-")) return null

        // epoch u32
        if (offset + 4 > bytes.size) return null
        val epoch = readU32(bytes, offset)
        offset += 4
        if (epoch < 1L) return null

        // secret 32B
        if (offset + SECRET_BYTES > bytes.size) return null
        val secretBytes = bytes.copyOfRange(offset, offset + SECRET_BYTES)
        offset += SECRET_BYTES
        val secret = runCatching { GroupSecret.fromBytes(secretBytes) }.getOrNull() ?: return null

        // groupName lp
        val groupNameBytes = readLp(bytes, offset) ?: return null
        offset += 2 + groupNameBytes.size
        if (groupNameBytes.isEmpty() || groupNameBytes.size > MAX_GROUP_NAME_BYTES) return null
        val groupName = runCatching { groupNameBytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
        if (groupName.isEmpty() || groupName.length > MAX_GROUP_NAME_CHARS) return null

        // inviterId lp
        val inviterIdBytes = readLp(bytes, offset) ?: return null
        offset += 2 + inviterIdBytes.size
        if (inviterIdBytes.isEmpty() || inviterIdBytes.size > MAX_INVITER_ID_BYTES) return null
        val inviterId = runCatching { inviterIdBytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null

        // inviterFp 32B
        if (offset + FINGERPRINT_BYTES > bytes.size) return null
        val inviterFp = bytes.copyOfRange(offset, offset + FINGERPRINT_BYTES)
        offset += FINGERPRINT_BYTES

        // hintCount u8
        if (offset >= bytes.size) return null
        val hintCount = bytes[offset++].toInt() and 0xFF
        if (hintCount > MAX_HINTS) return null

        val hints = ArrayList<String>(hintCount)
        for (i in 0 until hintCount) {
            val hintBytes = readLp(bytes, offset) ?: return null
            offset += 2 + hintBytes.size
            if (hintBytes.isEmpty() || hintBytes.size > MAX_HINT_BYTES) return null
            val hint = runCatching { hintBytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
            hints.add(hint)
        }

        // issuedAtMs u64
        if (offset + 8 > bytes.size) return null
        val issuedAtMs = readU64(bytes, offset)
        offset += 8

        // No trailing bytes allowed
        if (offset != bytes.size) return null

        return runCatching {
            GroupInvite(
                version = version,
                groupId = groupId,
                epoch = epoch,
                secret = secret,
                groupName = groupName,
                inviterDeviceId = inviterId,
                inviterKeyFingerprint = inviterFp,
                addressHints = hints,
                issuedAtMs = issuedAtMs,
            )
        }.getOrNull()
    }

    private fun writeLp(out: ByteArray, offset: Int, data: ByteArray): Int {
        val len = data.size
        out[offset] = ((len ushr 8) and 0xFF).toByte()
        out[offset + 1] = (len and 0xFF).toByte()
        data.copyInto(out, offset + 2)
        return offset + 2 + len
    }

    private fun readLp(bytes: ByteArray, offset: Int): ByteArray? {
        if (offset + 2 > bytes.size) return null
        val len = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
        if (offset + 2 + len > bytes.size) return null
        return bytes.copyOfRange(offset + 2, offset + 2 + len)
    }

    private fun writeU32(out: ByteArray, offset: Int, value: Long): Int {
        out[offset] = ((value ushr 24) and 0xFF).toByte()
        out[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 3] = (value and 0xFF).toByte()
        return offset + 4
    }

    private fun readU32(bytes: ByteArray, offset: Int): Long {
        val b0 = (bytes[offset].toLong() and 0xFF) shl 24
        val b1 = (bytes[offset + 1].toLong() and 0xFF) shl 16
        val b2 = (bytes[offset + 2].toLong() and 0xFF) shl 8
        val b3 = bytes[offset + 3].toLong() and 0xFF
        return b0 or b1 or b2 or b3
    }

    private fun writeU64(out: ByteArray, offset: Int, value: Long): Int {
        for (i in 0..7) {
            out[offset + i] = ((value ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
        return offset + 8
    }

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 0..7) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
