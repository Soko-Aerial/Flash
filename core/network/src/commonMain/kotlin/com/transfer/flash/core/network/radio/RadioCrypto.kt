package com.transfer.flash.core.network.radio

/**
 * The cryptographic primitives the radio frame format needs, as a seam.
 *
 * Why a seam and not a call into `:core:security`: its AEAD/HKDF primitives are `internal` to that module and its only public
 * AEAD wrapper (`SecureBinaryFrameCodec`, `FSEC`) draws a random nonce and writes a 22-byte header, both wrong for a 220-byte
 * radio budget (radio plan section 6.0 item 1: counter nonce, 12 + 16 byte overhead). Wiring the engine to its pairing session
 * key is therefore an adapter (`RadioPeerKeys` supplier) written when the radio is wired into the engine. The algorithms are the
 * same as the rest of Flash: HMAC-SHA-256, HKDF-SHA-256, AES-256-GCM with a 128-bit tag, ECDSA P-256 / SHA-256.
 *
 * JVM implementation: `JdkRadioCrypto` (jvmMain and androidMain, `javax.crypto` / `java.security`).
 */
public interface RadioCrypto {
    /** HMAC-SHA-256. */
    public fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** SHA-256. */
    public fun sha256(data: ByteArray): ByteArray

    /** AES-256-GCM seal with a 12-byte [nonce]; returns ciphertext followed by the 16-byte tag. */
    public fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    /** AES-256-GCM open; returns null when the tag does not verify (never throws on forged input). */
    public fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray?

    /** Cryptographically secure random bytes. */
    public fun randomBytes(count: Int): ByteArray
}

/** ECDSA P-256 signing for broadcast frames (no pairwise key). Signatures are the raw 64-byte `r||s` form. */
public interface RadioSignatureScheme {
    /** Raw `r||s` (32 + 32 bytes, big-endian) signature of [data] with the local identity key. */
    public fun sign(data: ByteArray): ByteArray

    /** Verifies a raw 64-byte signature against an X.509 SubjectPublicKeyInfo [publicKey]; false on any malformed input. */
    public fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}

/** HKDF-SHA-256 (RFC 5869) on top of [RadioCrypto.hmacSha256]; pinned by the RFC 5869 test vectors in `RadioHkdfTest`. */
public object RadioHkdf {
    /** Extract then expand. An empty [salt] means 32 zero bytes (RFC 5869 section 2.2). */
    public fun derive(crypto: RadioCrypto, ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32)) { "HKDF length out of range: $length" }
        val prk = crypto.hmacSha256(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        var block = ByteArray(0)
        val okm = ByteArray(length)
        var produced = 0
        var counter = 1
        while (produced < length) {
            block = crypto.hmacSha256(prk, block + info + byteArrayOf(counter.toByte()))
            val n = minOf(block.size, length - produced)
            block.copyInto(okm, produced, 0, n)
            produced += n
            counter++
        }
        return okm
    }
}

/**
 * ECDSA signature helpers. The JCA produces and consumes ASN.1 DER (`SEQUENCE { INTEGER r, INTEGER s }`, 70 to 72 bytes for
 * P-256); the radio frame carries the fixed 64-byte `r||s` form (radio plan correction E1).
 */
public object EcdsaRawSignature {
    /** Raw signature length for P-256. */
    public const val RAW_SIZE: Int = 64

    /** DER to raw; null if [der] is not a well-formed P-256 ECDSA signature. */
    public fun derToRaw(der: ByteArray): ByteArray? {
        var p = 0
        fun byteAt(i: Int): Int = if (i in der.indices) der[i].toInt() and 0xFF else -1
        if (byteAt(p++) != 0x30) return null
        var seqLen = byteAt(p++)
        if (seqLen < 0) return null
        if (seqLen == 0x81) seqLen = byteAt(p++) else if (seqLen > 0x81) return null
        if (seqLen < 0 || p + seqLen != der.size) return null
        val out = ByteArray(RAW_SIZE)
        for (half in 0..1) {
            if (byteAt(p++) != 0x02) return null
            val l = byteAt(p++)
            if (l < 1 || p + l > der.size) return null
            var start = p
            var n = l
            while (n > 1 && der[start].toInt() == 0) {
                start++
                n--
            }
            if (n > 32) return null
            der.copyInto(out, destinationOffset = half * 32 + (32 - n), startIndex = start, endIndex = start + n)
            p += l
        }
        return if (p == der.size) out else null
    }

    /** Raw to DER; null if [raw] is not 64 bytes. */
    public fun rawToDer(raw: ByteArray): ByteArray? {
        if (raw.size != RAW_SIZE) return null
        fun intBytes(from: Int): ByteArray {
            var s = from
            while (s < from + 31 && raw[s].toInt() == 0) s++
            val body = raw.copyOfRange(s, from + 32)
            return if (body[0].toInt() and 0x80 != 0) byteArrayOf(0) + body else body
        }
        val r = intBytes(0)
        val s = intBytes(32)
        val inner = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return if (inner.size < 0x80) {
            byteArrayOf(0x30, inner.size.toByte()) + inner
        } else {
            byteArrayOf(0x30, 0x81.toByte(), inner.size.toByte()) + inner
        }
    }
}
