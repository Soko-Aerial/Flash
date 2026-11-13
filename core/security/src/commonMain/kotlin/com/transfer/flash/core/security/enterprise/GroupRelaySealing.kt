@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64Url
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.aesGcmOpen
import com.transfer.flash.core.security.crypto.aesGcmSeal
import com.transfer.flash.core.security.crypto.secureRandomBytes

/**
 * Symmetric group message encryption key scoped to one `(groupId, epoch, keyId)` tuple (HR10).
 *
 * The key bytes reside only on active group member devices (protected at rest by [GroupEpochKeyStore]) and are
 * distributed exclusively inside pairwise [HubSealing] (`SEALED_V1`) envelopes so the Hub relay and database hold
 * zero group message keys.
 */
@FlashInternalApi
public class GroupEpochKey(
    public val groupId: String,
    public val epoch: Long,
    public val keyId: String,
    keyBytes: ByteArray,
    public val rosterVersion: Long,
    memberDeviceIds: Set<String>,
    public val creatorDeviceId: String,
    public val createdAtMs: Long,
    public val canSend: Boolean = true,
) {
    private val _keyBytes: ByteArray = keyBytes.copyOf()

    public val keyBytes: ByteArray
        get() = _keyBytes.copyOf()

    public val memberDeviceIds: Set<String> = memberDeviceIds.toSet()

    public fun copy(
        canSend: Boolean = this.canSend,
    ): GroupEpochKey = GroupEpochKey(
        groupId = groupId,
        epoch = epoch,
        keyId = keyId,
        keyBytes = _keyBytes,
        rosterVersion = rosterVersion,
        memberDeviceIds = memberDeviceIds,
        creatorDeviceId = creatorDeviceId,
        createdAtMs = createdAtMs,
        canSend = canSend,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupEpochKey) return false
        return groupId == other.groupId &&
            epoch == other.epoch &&
            keyId == other.keyId &&
            rosterVersion == other.rosterVersion &&
            memberDeviceIds == other.memberDeviceIds &&
            creatorDeviceId == other.creatorDeviceId &&
            createdAtMs == other.createdAtMs &&
            canSend == other.canSend &&
            _keyBytes.contentEquals(other._keyBytes)
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + keyId.hashCode()
        result = 31 * result + _keyBytes.contentHashCode()
        return result
    }
}

/** Parsed metadata from an unsealed [GroupRelaySealing] wire envelope (safe to inspect before decryption). */
@FlashInternalApi
public data class GroupEnvelopeMeta(
    public val version: Int,
    public val groupId: String,
    public val epoch: Long,
    public val keyId: String,
    public val senderId: String,
    public val innerType: String,
)

/** Plaintext recovered by [GroupRelaySealing.open] along with its verified epoch metadata. */
@FlashInternalApi
public data class UnsealedGroupPayload(
    public val groupId: String,
    public val epoch: Long,
    public val keyId: String,
    public val innerType: String,
    public val body: String,
)

/** Pairwise key-distribution payload sent inside [HubSealing.ENVELOPE_TYPE] (`GROUP_KEY_DIST_V1`). */
@FlashInternalApi
public data class GroupKeyDistPayload(
    public val version: Int,
    public val groupId: String,
    public val epoch: Long,
    public val keyId: String,
    public val keyBytes: ByteArray,
    public val rosterVersion: Long,
    public val memberDeviceIds: Set<String>,
    public val creatorDeviceId: String,
    public val createdAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GroupKeyDistPayload) return false
        return version == other.version &&
            groupId == other.groupId &&
            epoch == other.epoch &&
            keyId == other.keyId &&
            rosterVersion == other.rosterVersion &&
            memberDeviceIds == other.memberDeviceIds &&
            creatorDeviceId == other.creatorDeviceId &&
            createdAtMs == other.createdAtMs &&
            keyBytes.contentEquals(other.keyBytes)
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + keyId.hashCode()
        result = 31 * result + keyBytes.contentHashCode()
        return result
    }
}

/**
 * End-to-end authenticated encryption codec for Hub-relayed group frames (HR10).
 *
 * Wire envelope (`GROUP_SEALED_V1`):
 * `1.<b64url(groupId)>.<epoch>.<b64url(keyId)>.<b64url(senderId)>.<b64url(innerType)>.<b64url(nonce)>.<b64url(ciphertext)>.<b64url(signature)>`
 *
 * Cryptographic properties:
 * - Content encrypted under 256-bit AES-GCM (`epochKey`) with fresh 96-bit CSPRNG nonce.
 * - Binary length-prefixed AAD binds `(groupId, epoch, keyId, senderId, msgId, sentAt, innerType)` so a relayed
 *   ciphertext cannot be shifted to another group, epoch, key, timestamp, or message id.
 * - ECDSA-P256 author signature (`crypto.sign(aad || nonce || ciphertext)`) verified against `senderIdentityPublicKey`
 *   prevents both a malicious Hub and fellow epoch-key holders from spoofing another member's authorship.
 * - Key distribution (`GROUP_KEY_DIST_V1`) travels pairwise inside [HubSealing.ENVELOPE_TYPE] (`SEALED_V1`).
 */
@FlashInternalApi
public object GroupRelaySealing {

    public const val ENVELOPE_TYPE: String = HubContract.GROUP_SEALED_ENVELOPE_TYPE
    public const val KEY_DIST_INNER_TYPE: String = "GROUP_KEY_DIST_V1"
    public const val FORMAT_VERSION: Int = 1
    public const val VERSION_TAG: String = "1"
    public const val KEY_BYTES: Int = 32
    public const val NONCE_BYTES: Int = 12
    public const val MAX_MEMBERS: Int = 512

    private val AAD_DOMAIN: ByteArray = "flash-group-e2ee-v1".encodeToByteArray()

    public fun generateEpochKeyBytes(): ByteArray = secureRandomBytes(KEY_BYTES)

    public fun generateKeyId(): String = Base64Url.encode(secureRandomBytes(12))

    /** Inspects header metadata without decrypting. Returns null on malformed input or unsupported version. */
    public fun peekMetadata(payload: String): GroupEnvelopeMeta? {
        if (payload.isBlank()) return null
        val parts = payload.split('.')
        if (parts.size != 9) return null
        if (parts[0] != VERSION_TAG) return null
        val groupId = decodeString(parts[1]) ?: return null
        val epoch = parts[2].toLongOrNull() ?: return null
        val keyId = decodeString(parts[3]) ?: return null
        val senderId = decodeString(parts[4]) ?: return null
        val innerType = decodeString(parts[5]) ?: return null
        if (groupId.isBlank() || epoch < 1L || keyId.isBlank() || senderId.isBlank() || innerType.isBlank()) {
            return null
        }
        return GroupEnvelopeMeta(
            version = FORMAT_VERSION,
            groupId = groupId,
            epoch = epoch,
            keyId = keyId,
            senderId = senderId,
            innerType = innerType,
        )
    }

    /** Encrypts and signs [body] under [epochKey]. Returns null if [epochKey] cannot send or inputs are invalid. */
    public fun seal(
        crypto: FlashCrypto,
        epochKey: GroupEpochKey,
        senderDeviceId: String,
        msgId: String,
        sentAt: Long,
        innerType: String,
        body: String,
    ): String? {
        if (!epochKey.canSend) return null
        if (epochKey.groupId.isBlank() || epochKey.epoch < 1L || epochKey.keyId.isBlank()) return null
        if (senderDeviceId.isBlank() || msgId.isBlank() || innerType.isBlank()) return null
        if (senderDeviceId !in epochKey.memberDeviceIds) return null
        val rawKey = epochKey.keyBytes.copyOf()
        if (rawKey.size != KEY_BYTES) {
            rawKey.fill(0)
            return null
        }
        return try {
            val nonce = secureRandomBytes(NONCE_BYTES)
            val aad = buildAad(
                groupId = epochKey.groupId,
                epoch = epochKey.epoch,
                keyId = epochKey.keyId,
                senderDeviceId = senderDeviceId,
                msgId = msgId,
                sentAt = sentAt,
                innerType = innerType,
            )
            val ciphertext = aesGcmSeal(rawKey, nonce, aad, body.encodeToByteArray())
            val sigInput = concatBytes(aad, nonce, ciphertext)
            val signature = crypto.sign(sigInput)
            listOf(
                VERSION_TAG,
                Base64Url.encode(epochKey.groupId.encodeToByteArray()),
                epochKey.epoch.toString(),
                Base64Url.encode(epochKey.keyId.encodeToByteArray()),
                Base64Url.encode(senderDeviceId.encodeToByteArray()),
                Base64Url.encode(innerType.encodeToByteArray()),
                Base64Url.encode(nonce),
                Base64Url.encode(ciphertext),
                Base64Url.encode(signature),
            ).joinToString(".")
        } catch (_: Throwable) {
            null
        } finally {
            rawKey.fill(0)
        }
    }

    /**
     * Verifies author signature + AAD scope + active epoch membership, then decrypts [payload] using [epochKey].
     * Returns null on any mismatch, tampering, invalid signature, or non-member sender.
     */
    public fun open(
        crypto: FlashCrypto,
        epochKey: GroupEpochKey,
        senderIdentityPublicKey: ByteArray,
        senderDeviceId: String,
        expectedGroupId: String,
        msgId: String,
        sentAt: Long,
        payload: String,
    ): UnsealedGroupPayload? {
        if (senderDeviceId.isBlank() || expectedGroupId.isBlank() || msgId.isBlank() || senderIdentityPublicKey.isEmpty()) {
            return null
        }
        val parts = payload.split('.')
        if (parts.size != 9 || parts[0] != VERSION_TAG) return null
        val groupId = decodeString(parts[1]) ?: return null
        val epoch = parts[2].toLongOrNull() ?: return null
        val keyId = decodeString(parts[3]) ?: return null
        val senderId = decodeString(parts[4]) ?: return null
        val innerType = decodeString(parts[5]) ?: return null
        val nonce = Base64Url.decodeOrNull(parts[6]) ?: return null
        val ciphertext = Base64Url.decodeOrNull(parts[7]) ?: return null
        val signature = Base64Url.decodeOrNull(parts[8]) ?: return null

        if (groupId != expectedGroupId || groupId != epochKey.groupId) return null
        if (epoch < 1L || epoch != epochKey.epoch) return null
        if (keyId != epochKey.keyId) return null
        if (senderId != senderDeviceId || senderId !in epochKey.memberDeviceIds) return null
        if (innerType.isBlank() || nonce.size != NONCE_BYTES || ciphertext.size < 16) return null

        val rawKey = epochKey.keyBytes.copyOf()
        if (rawKey.size != KEY_BYTES) {
            rawKey.fill(0)
            return null
        }
        return try {
            val aad = buildAad(
                groupId = groupId,
                epoch = epoch,
                keyId = keyId,
                senderDeviceId = senderId,
                msgId = msgId,
                sentAt = sentAt,
                innerType = innerType,
            )
            val sigInput = concatBytes(aad, nonce, ciphertext)
            if (!crypto.verify(signature, sigInput, senderIdentityPublicKey)) return null
            val plaintext = aesGcmOpen(rawKey, nonce, aad, ciphertext)
            UnsealedGroupPayload(
                groupId = groupId,
                epoch = epoch,
                keyId = keyId,
                innerType = innerType,
                body = plaintext.decodeToString(),
            )
        } catch (_: Throwable) {
            null
        } finally {
            rawKey.fill(0)
        }
    }

    /** Encodes a [GroupKeyDistPayload] for transport inside pairwise [HubSealing.ENVELOPE_TYPE]. */
    public fun encodeKeyDist(payload: GroupKeyDistPayload): String {
        val sortedMembers = payload.memberDeviceIds.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .joinToString(",") { Base64Url.encode(it.encodeToByteArray()) }
        return listOf(
            payload.version.toString(),
            Base64Url.encode(payload.groupId.encodeToByteArray()),
            payload.epoch.toString(),
            Base64Url.encode(payload.keyId.encodeToByteArray()),
            Base64Url.encode(payload.keyBytes),
            payload.rosterVersion.toString(),
            sortedMembers,
            Base64Url.encode(payload.creatorDeviceId.encodeToByteArray()),
            payload.createdAtMs.toString(),
        ).joinToString(".")
    }

    /** Decodes a [GroupKeyDistPayload] previously extracted from verified pairwise [HubSealing]. */
    public fun decodeKeyDist(text: String): GroupKeyDistPayload? {
        if (text.isBlank()) return null
        val parts = text.split('.')
        if (parts.size != 9) return null
        val version = parts[0].toIntOrNull() ?: return null
        if (version != FORMAT_VERSION) return null
        val groupId = decodeString(parts[1]) ?: return null
        val epoch = parts[2].toLongOrNull() ?: return null
        val keyId = decodeString(parts[3]) ?: return null
        val keyBytes = Base64Url.decodeOrNull(parts[4]) ?: return null
        val rosterVersion = parts[5].toLongOrNull() ?: return null
        if (parts[6].isBlank()) return null
        val memberTokens = parts[6].split(',')
        if (memberTokens.isEmpty() || memberTokens.size > MAX_MEMBERS) return null
        val members = LinkedHashSet<String>(memberTokens.size)
        for (token in memberTokens) {
            val memberId = decodeString(token) ?: return null
            if (memberId.isBlank()) return null
            members += memberId
        }
        val creatorDeviceId = decodeString(parts[7]) ?: return null
        val createdAtMs = parts[8].toLongOrNull() ?: return null
        if (groupId.isBlank() || epoch < 1L || keyId.isBlank() || keyBytes.size != KEY_BYTES) return null
        if (rosterVersion < 0L || creatorDeviceId.isBlank() || creatorDeviceId !in members) return null
        return GroupKeyDistPayload(
            version = version,
            groupId = groupId,
            epoch = epoch,
            keyId = keyId,
            keyBytes = keyBytes,
            rosterVersion = rosterVersion,
            memberDeviceIds = members,
            creatorDeviceId = creatorDeviceId,
            createdAtMs = createdAtMs,
        )
    }

    private fun decodeString(encoded: String): String? {
        val bytes = Base64Url.decodeOrNull(encoded) ?: return null
        return runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull()
    }

    private fun buildAad(
        groupId: String,
        epoch: Long,
        keyId: String,
        senderDeviceId: String,
        msgId: String,
        sentAt: Long,
        innerType: String,
    ): ByteArray {
        val groupB = groupId.encodeToByteArray()
        val keyIdB = keyId.encodeToByteArray()
        val senderB = senderDeviceId.encodeToByteArray()
        val msgB = msgId.encodeToByteArray()
        val typeB = innerType.encodeToByteArray()
        val total = AAD_DOMAIN.size + 4 + groupB.size + 8 + 4 + keyIdB.size + 4 + senderB.size + 4 + msgB.size + 8 + 4 + typeB.size
        val out = ByteArray(total)
        var pos = 0
        fun putBytes(src: ByteArray) {
            src.copyInto(out, pos)
            pos += src.size
        }
        fun putInt(value: Int) {
            out[pos++] = ((value ushr 24) and 0xFF).toByte()
            out[pos++] = ((value ushr 16) and 0xFF).toByte()
            out[pos++] = ((value ushr 8) and 0xFF).toByte()
            out[pos++] = (value and 0xFF).toByte()
        }
        fun putLong(value: Long) {
            for (shift in 56 downTo 0 step 8) {
                out[pos++] = ((value ushr shift) and 0xFFL).toByte()
            }
        }
        putBytes(AAD_DOMAIN)
        putInt(groupB.size); putBytes(groupB)
        putLong(epoch)
        putInt(keyIdB.size); putBytes(keyIdB)
        putInt(senderB.size); putBytes(senderB)
        putInt(msgB.size); putBytes(msgB)
        putLong(sentAt)
        putInt(typeB.size); putBytes(typeB)
        return out
    }

    private fun concatBytes(a: ByteArray, b: ByteArray, c: ByteArray): ByteArray {
        val out = ByteArray(a.size + b.size + c.size)
        a.copyInto(out, 0)
        b.copyInto(out, a.size)
        c.copyInto(out, a.size + b.size)
        return out
    }
}
