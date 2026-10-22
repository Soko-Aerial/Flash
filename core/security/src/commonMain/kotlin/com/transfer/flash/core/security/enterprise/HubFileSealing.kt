package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.security.crypto.Sha256Stream
import com.transfer.flash.core.security.crypto.aesGcmOpen
import com.transfer.flash.core.security.crypto.aesGcmSeal
import com.transfer.flash.core.security.crypto.secureRandomBytes
import com.transfer.flash.core.security.crypto.toHexLower

/**
 * The secret of ONE sealed hub file (ADR-215 D1): a 32-byte AES-256 key and an 8-byte nonce prefix, both from the
 * platform CSPRNG. It travels only inside a sealed chat payload (P3); the hub never receives it.
 *
 * [toString] never prints the material. [encode] is the only way out, and its result is a secret: never log it.
 */
@FlashInternalApi
public class HubFileKey internal constructor(material: ByteArray) {

    private val material: ByteArray = material.copyOf()

    init {
        require(material.size == MATERIAL_BYTES) { "a hub file key is $MATERIAL_BYTES bytes" }
    }

    internal val key: ByteArray get() = material.copyOfRange(0, KEY_BYTES)

    internal val noncePrefix: ByteArray get() = material.copyOfRange(KEY_BYTES, MATERIAL_BYTES)

    /** `hfk1.<Base64 of key || noncePrefix>`, the form a sealed message carries. A secret. */
    public fun encode(): String = PREFIX + Base64.encode(material)

    override fun toString(): String = "HubFileKey(redacted)"

    public companion object {
        internal const val KEY_BYTES: Int = 32
        internal const val NONCE_PREFIX_BYTES: Int = 8
        internal const val MATERIAL_BYTES: Int = KEY_BYTES + NONCE_PREFIX_BYTES
        private const val PREFIX: String = "hfk1."

        /** A fresh random key for one file. */
        public fun generate(): HubFileKey = HubFileKey(secureRandomBytes(MATERIAL_BYTES))

        /** Parses [encode]'s output; null (never a throw) for anything else. */
        public fun decode(text: String): HubFileKey? {
            if (!text.startsWith(PREFIX) || text.length > PREFIX.length + 64) return null
            val bytes = runCatching { Base64.decode(text.substring(PREFIX.length)) }.getOrNull() ?: return null
            return if (bytes.size == MATERIAL_BYTES) HubFileKey(bytes) else null
        }
    }
}

/**
 * How one plaintext of [plainBytes] bytes is cut into sealed chunks of [cipherChunkBytes] (every chunk but the last).
 *
 * Plaintext chunk `i` becomes ciphertext chunk `i`, [HubContract.SEALED_CHUNK_OVERHEAD_BYTES] bytes longer, so the index
 * of a chunk means the same thing on the sender, the hub and the recipient, and a byte range of the plaintext maps to
 * exactly one chunk. [cipherChunkBytes] is what the hub calls `chunkSize`.
 */
@FlashInternalApi
public class HubFileLayout private constructor(
    public val plainBytes: Long,
    public val cipherChunkBytes: Int,
    public val chunkCount: Int,
) {
    /** Plaintext bytes in every chunk but the last. */
    public val plainChunkBytes: Int get() = cipherChunkBytes - HubFileSealing.TAG_BYTES

    /** Size of the whole sealed blob: what the hub stores and calls `totalBytes`. */
    public val cipherBytes: Long get() = plainBytes + chunkCount.toLong() * HubFileSealing.TAG_BYTES

    /** Where chunk [index] starts in the plaintext. */
    public fun plainOffset(index: Int): Long {
        checkIndex(index)
        return index.toLong() * plainChunkBytes
    }

    /** Plaintext bytes in chunk [index] (the last one may be shorter). */
    public fun plainLength(index: Int): Int {
        checkIndex(index)
        return if (index < chunkCount - 1) plainChunkBytes else (plainBytes - plainOffset(index)).toInt()
    }

    /** Ciphertext bytes of chunk [index]: what one `PUT` carries and one `GET` must answer. */
    public fun cipherLength(index: Int): Int = plainLength(index) + HubFileSealing.TAG_BYTES

    private fun checkIndex(index: Int) {
        require(index in 0 until chunkCount) { "chunk index $index outside 0..${chunkCount - 1}" }
    }

    override fun equals(other: Any?): Boolean = other is HubFileLayout &&
        other.plainBytes == plainBytes && other.cipherChunkBytes == cipherChunkBytes && other.chunkCount == chunkCount

    override fun hashCode(): Int = (plainBytes.hashCode() * 31 + cipherChunkBytes) * 31 + chunkCount

    override fun toString(): String = "HubFileLayout(plain=$plainBytes chunk=$cipherChunkBytes count=$chunkCount)"

    public companion object {
        /**
         * The layout of a [plainBytes]-byte file, or null when it cannot go through a hub: empty (situation 11: the LAN
         * path handles it), a chunk size outside `(TAG_BYTES + 1)..MAX_CHUNK_BYTES`, or more than
         * [HubContract.MAX_FILE_CHUNKS] chunks.
         */
        public fun of(plainBytes: Long, cipherChunkBytes: Int = HubContract.MAX_CHUNK_BYTES): HubFileLayout? {
            if (plainBytes <= 0L) return null
            if (cipherChunkBytes !in (HubFileSealing.TAG_BYTES + 1)..HubContract.MAX_CHUNK_BYTES) return null
            val plainChunk = (cipherChunkBytes - HubFileSealing.TAG_BYTES).toLong()
            // RVW22 F15: compare before adding; plainBytes + plainChunk - 1 wrapped to a negative count near Long.MAX_VALUE.
            if (plainBytes > plainChunk * HubContract.MAX_FILE_CHUNKS) return null
            val count = (plainBytes + plainChunk - 1) / plainChunk
            return HubFileLayout(plainBytes, cipherChunkBytes, count.toInt())
        }
    }
}

/** Incremental SHA-256 over a sealed blob or a plaintext, for files too large to hold in memory. One stream per instance. */
@FlashInternalApi
public class HubFileHasher {
    private val stream = Sha256Stream()

    public fun update(data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        stream.update(data, offset, length)
    }

    /** Lowercase hex digest; ends the stream. */
    public fun digestHex(): String = stream.digest().toHexLower()
}

/**
 * Per-chunk sealing of a file that crosses the hub (ADR-215, `docs/plans/HUB-FILES-PLAN.md` section 4.3).
 *
 * - AES-256-GCM with the file's own random key ([HubFileKey]); the hub never sees it.
 * - Nonce = `noncePrefix(8) || index(4, big-endian)`. Unique per chunk under one key, and DETERMINISTIC: a resumed upload
 *   re-seals chunk `i` to exactly the bytes it sent before, which is what the hub's idempotent `PUT` compares. Never seal
 *   different plaintext under the same key and index: [HubFileSealing] callers re-hash the source before resuming.
 * - AAD = `"flash-hub-file-v1" || 0x00 || fileId (32 ASCII) || index(4) || chunkCount(4) || plainBytes(8)`. A chunk moved
 *   to another index or file, a file cut short or padded, or a chunk count the hub changed all fail to open.
 *
 * [openChunk] never throws: any failure (wrong length, wrong key, tampered bytes) is null, which the caller treats as a
 * chunk the hub must not be trusted with.
 */
@FlashInternalApi
public object HubFileSealing {

    /** Bytes added to every chunk: the GCM tag. Equal to [HubContract.SEALED_CHUNK_OVERHEAD_BYTES] by construction. */
    public const val TAG_BYTES: Int = HubContract.SEALED_CHUNK_OVERHEAD_BYTES

    private const val AAD_TAG: String = "flash-hub-file-v1"
    private const val FILE_ID_BYTES: Int = 16

    /** A fresh file id: 16 CSPRNG bytes as 32 lowercase hex, the shape the hub requires. */
    public fun newFileId(): String = secureRandomBytes(FILE_ID_BYTES).toHexLower()

    /** Seals plaintext chunk [index]. [plain] must be exactly [HubFileLayout.plainLength] bytes. */
    public fun sealChunk(key: HubFileKey, fileId: String, layout: HubFileLayout, index: Int, plain: ByteArray): ByteArray {
        require(plain.size == layout.plainLength(index)) {
            "chunk $index must be ${layout.plainLength(index)} bytes, was ${plain.size}"
        }
        return aesGcmSeal(key.key, nonce(key, index), aad(fileId, layout, index), plain)
    }

    /** Opens ciphertext chunk [index]; null when its length is wrong or it does not authenticate. */
    public fun openChunk(key: HubFileKey, fileId: String, layout: HubFileLayout, index: Int, cipher: ByteArray): ByteArray? {
        if (index !in 0 until layout.chunkCount) return null
        if (cipher.size != layout.cipherLength(index)) return null
        return runCatching { aesGcmOpen(key.key, nonce(key, index), aad(fileId, layout, index), cipher) }.getOrNull()
    }

    private fun nonce(key: HubFileKey, index: Int): ByteArray = key.noncePrefix + index.toBigEndian4()

    private fun aad(fileId: String, layout: HubFileLayout, index: Int): ByteArray =
        AAD_TAG.encodeToByteArray() + byteArrayOf(0) + fileId.encodeToByteArray() +
            index.toBigEndian4() + layout.chunkCount.toBigEndian4() + layout.plainBytes.toBigEndian8()
}

private fun Int.toBigEndian4(): ByteArray =
    byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte())

private fun Long.toBigEndian8(): ByteArray = ByteArray(8) { i -> (this ushr (56 - 8 * i)).toByte() }
