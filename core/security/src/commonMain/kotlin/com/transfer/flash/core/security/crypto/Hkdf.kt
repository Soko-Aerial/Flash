package com.transfer.flash.core.security.crypto

/**
 * HKDF (RFC 5869) with HMAC-SHA256 — extract-then-expand key derivation.
 *
 * Used to turn the raw ECDH shared secret (which is NOT uniformly random key material
 * and must never be used directly as an AES key) into a proper AES-256 session key.
 * Correctness is pinned against the official RFC 5869 Appendix A test vectors in
 * [com.transfer.flash.core.security.crypto.HkdfTest].
 *
 * Reference: https://www.rfc-editor.org/rfc/rfc5869
 */
internal object Hkdf {

    private const val HASH_LEN_BYTES = 32

    /** HKDF-Extract(salt, IKM) -> PRK. Empty salt defaults to HashLen zero octets (RFC section 2.2). */
    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(HASH_LEN_BYTES) else salt
        return hmacSha256(key = effectiveSalt, data = ikm)
    }

    /** HKDF-Expand(PRK, info, L) -> OKM (RFC section 2.3). */
    fun expand(prk: ByteArray, info: ByteArray, outLength: Int): ByteArray {
        require(outLength > 0) { "HKDF output length must be positive, was $outLength" }
        require(outLength <= 255 * HASH_LEN_BYTES) {
            "HKDF output length $outLength exceeds maximum ${255 * HASH_LEN_BYTES}"
        }
        var block = ByteArray(0)
        var counter = 1
        var produced = 0
        val okm = ByteArray(outLength)
        while (produced < outLength) {
            // T(n) = HMAC(PRK, T(n-1) || info || n). Previously three streamed Mac.update() calls;
            // HMAC of the concatenation is the same function of the same bytes, so the output is
            // unchanged — still pinned by the RFC 5869 Appendix A vectors in HkdfTest.
            block = hmacSha256(key = prk, data = block + info + byteArrayOf(counter.toByte()))
            val toCopy = minOf(block.size, outLength - produced)
            block.copyInto(destination = okm, destinationOffset = produced, startIndex = 0, endIndex = toCopy)
            produced += toCopy
            counter++
        }
        return okm
    }

    /** Convenience: Extract followed by Expand in one call. */
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLength: Int): ByteArray =
        expand(extract(salt, ikm), info, outLength)
}
