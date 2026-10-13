package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.server.auth.JvmEcdsaVerifier
import com.transfer.flash.server.auth.Spki
import java.util.Base64

/**
 * The step-up key of a BROWSER (ADR-125). The admin console generates a non-extractable ECDSA P-256 key with WebCrypto, keeps it in
 * IndexedDB and registers its public half here. Two facts the rest of the server needs:
 *
 *  - WebCrypto signs ECDSA as IEEE P1363 (`r || s`, 64 bytes for P-256) while the JVM and the phones use DER. [ConsoleKeys.verify] accepts
 *    BOTH, so one verifier serves a browser, a phone and the desktop app.
 *  - The key is registered with a proof of possession: a signature by the new key over [ConsoleKeys.proofMessage], so an administrator
 *    can not register a public key whose private half they do not hold.
 */
object ConsoleKeys {
    /** Domain tag of the proof of possession; also the first line of [proofMessage]. */
    const val PROOF_TAG: String = "flash-console-key-v1"

    private const val P256_COORDINATE_BYTES = 32

    /** The exact UTF-8 text the browser signs when it registers a key: `flash-console-key-v1`, a newline, the user name, a newline, the base64 SPKI. */
    fun proofMessage(username: String, spkiBase64: String): ByteArray =
        "$PROOF_TAG\n$username\n$spkiBase64".toByteArray(Charsets.UTF_8)

    /** True when [spkiBase64] is a well-formed P-256 public key of a plausible size. */
    fun isValidKey(spkiBase64: String): Boolean = spkiBase64.length in 80..200 && Spki.parseP256(spkiBase64) != null

    /**
     * Verifies [signature] (DER, or 64-byte P1363) of [data] against [spki]. A 64-byte value is tried as P1363 first and, only if that
     * fails, as DER (a DER signature can in theory also be 64 bytes). Never throws.
     */
    fun verify(spki: ByteArray, data: ByteArray, signature: ByteArray, verifier: EcdsaVerifier = JvmEcdsaVerifier): Boolean {
        val candidates = if (signature.size == 2 * P256_COORDINATE_BYTES) {
            listOfNotNull(p1363ToDer(signature), signature)
        } else {
            listOf(signature)
        }
        return candidates.any { runCatching { verifier.verify(spki, data, it) }.getOrDefault(false) }
    }

    fun verifyBase64(spkiBase64: String, data: ByteArray, signatureBase64: String, verifier: EcdsaVerifier = JvmEcdsaVerifier): Boolean = try {
        verify(Base64.getDecoder().decode(spkiBase64), data, Base64.getDecoder().decode(signatureBase64), verifier)
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Converts `r || s` (64 bytes) to an ASN.1 DER `SEQUENCE { INTEGER r, INTEGER s }`; null when [p1363] has the wrong length. */
    fun p1363ToDer(p1363: ByteArray): ByteArray? {
        if (p1363.size != 2 * P256_COORDINATE_BYTES) return null
        val r = derInteger(p1363.copyOfRange(0, P256_COORDINATE_BYTES))
        val s = derInteger(p1363.copyOfRange(P256_COORDINATE_BYTES, 2 * P256_COORDINATE_BYTES))
        val body = r + s
        // Two 32-byte integers are at most 2 * 35 bytes: the length always fits one byte.
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private fun derInteger(unsigned: ByteArray): ByteArray {
        var start = 0
        while (start < unsigned.size - 1 && unsigned[start] == 0.toByte()) start++
        val trimmed = unsigned.copyOfRange(start, unsigned.size)
        val padded = if (trimmed[0].toInt() and 0x80 != 0) byteArrayOf(0) + trimmed else trimmed
        return byteArrayOf(0x02, padded.size.toByte()) + padded
    }
}
