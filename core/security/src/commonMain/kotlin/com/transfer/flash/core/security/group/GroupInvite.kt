package com.transfer.flash.core.security.group

/**
 * An invite to join a v2 Flash group by group id and shared secret (ADR-044, ADR-073, GM-2).
 *
 * An invite link is formatted as:
 * `flash://g/1/<base64url>`
 *
 * Security:
 * - The secret and the raw invite link are sensitive and must NEVER be logged or leaked.
 * - [toString] is redacted: it includes [groupId], [epoch], [inviterDeviceId], [groupName],
 *   and fingerprint hex, but redacts [secret] so loggers cannot accidentally leak it.
 */
public class GroupInvite(
    public val version: Int = 1,
    public val groupId: String,
    public val epoch: Long,
    public val secret: GroupSecret,
    public val groupName: String,
    public val inviterDeviceId: String,
    public val inviterKeyFingerprint: ByteArray,
    public val addressHints: List<String> = emptyList(),
    public val issuedAtMs: Long,
) {
    init {
        require(version == 1) { "Only version 1 is supported: $version" }
        require(groupId.startsWith("g2-") && groupId.encodeToByteArray().size in 1..64) {
            "groupId must start with 'g2-' and be 1..64 bytes"
        }
        require(epoch >= 1L && epoch <= 0xFFFFFFFFL) { "epoch must be in 1..4294967295: $epoch" }
        require(groupName.isNotEmpty() && groupName.length <= 80 && groupName.encodeToByteArray().size <= 320) {
            "groupName must be 1..80 characters and <= 320 bytes"
        }
        require(inviterDeviceId.isNotEmpty() && inviterDeviceId.encodeToByteArray().size <= 128) {
            "inviterDeviceId must be 1..128 bytes"
        }
        require(inviterKeyFingerprint.size == 32) {
            "inviterKeyFingerprint must be exactly 32 bytes (SHA-256): ${inviterKeyFingerprint.size}"
        }
        require(addressHints.size <= 3) {
            "at most 3 address hints are allowed: ${addressHints.size}"
        }
        for (hint in addressHints) {
            require(hint.encodeToByteArray().size in 1..64) {
                "address hint must be 1..64 bytes: $hint"
            }
        }
    }

    /**
     * Lowercase 64-character hex representation of [inviterKeyFingerprint].
     */
    public val inviterFingerprintHex: String get() = buildString(64) {
        for (b in inviterKeyFingerprint) {
            val v = b.toInt() and 0xFF
            append(HEX_DIGITS[v ushr 4])
            append(HEX_DIGITS[v and 0x0F])
        }
    }

    override fun toString(): String {
        return "GroupInvite(v=$version, groupId=$groupId, epoch=$epoch, secret=GroupSecret(redacted), " +
            "groupName='$groupName', inviter=$inviterDeviceId, inviterFp=$inviterFingerprintHex, " +
            "hints=$addressHints, issuedAtMs=$issuedAtMs)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupInvite) return false
        return version == other.version &&
            groupId == other.groupId &&
            epoch == other.epoch &&
            secret.constantTimeEquals(other.secret) &&
            groupName == other.groupName &&
            inviterDeviceId == other.inviterDeviceId &&
            inviterKeyFingerprint.contentEquals(other.inviterKeyFingerprint) &&
            addressHints == other.addressHints &&
            issuedAtMs == other.issuedAtMs
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + groupId.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + secret.hashCode()
        result = 31 * result + groupName.hashCode()
        result = 31 * result + inviterDeviceId.hashCode()
        result = 31 * result + inviterKeyFingerprint.contentHashCode()
        result = 31 * result + addressHints.hashCode()
        result = 31 * result + issuedAtMs.hashCode()
        return result
    }

    private companion object {
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
