package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.security.crypto.constantTimeBytesEqual
import com.transfer.flash.core.security.crypto.secureRandomBytes
import com.transfer.flash.core.security.crypto.sha256
import com.transfer.flash.core.security.crypto.toHexLower

/**
 * Pairing protocol v2 primitives (ADR-042, audit 2026-09-23 S2).
 *
 * v1 derived the 6-digit code from the two identity fingerprints alone: static, attacker-known inputs,
 * so a man-in-the-middle could grind a keypair until both screens matched (~10^6 key generations), and
 * the code covered neither the ephemeral keys that become the E2E session key nor the key TLS had
 * actually authenticated. v2 follows Bluetooth numeric comparison's commitment pattern:
 *
 * ```text
 * I -> R : PAIR_REQUEST  fp_I, epk_I, commit_I = H(commit ‖ fp_I ‖ epk_I ‖ N_I)   (N_I stays hidden)
 * R -> I : PAIR_NONCE    fp_R, epk_R, N_R                                        (R reveals first)
 * I -> R : PAIR_REVEAL   N_I                                 (R checks commit_I before any code exists)
 * code   = H(code ‖ fp_I ‖ fp_R ‖ epk_I ‖ epk_R ‖ N_I ‖ N_R) mod 10^6
 * ```
 *
 * A MITM facing I must send N_R' before it learns N_I; facing R it has committed to its own nonce
 * before it sees N_R. Either way it cannot steer the code, so a match survives with probability 10^-6.
 * Each side also requires the peer's fingerprint to equal the key TLS pinned for that connection, so the
 * code covers the identities the transport actually authenticated.
 *
 * Every field is length-prefixed and the two hashes are domain-separated, so no concatenation of
 * different inputs can collide.
 */
internal object PairingV2 {

    const val PROTOCOL_VERSION: Int = 2
    const val NONCE_BYTES: Int = 16

    private const val COMMIT_DOMAIN = "flash-pair-v2-commit"
    private const val CODE_DOMAIN = "flash-pair-v2-code"
    private const val MODULUS = 1_000_000L
    private const val HASH_BYTES_USED = 5

    fun newNonce(): ByteArray = secureRandomBytes(NONCE_BYTES)

    /** Initiator commitment to its nonce, bound to its identity and ephemeral key. */
    fun commitHex(fingerprintHex: String, ephemeralPublicKey: ByteArray, nonce: ByteArray): String =
        sha256(
            encode(
                COMMIT_DOMAIN.encodeToByteArray(),
                NumericComparisonCode.normalizeHex(fingerprintHex).encodeToByteArray(),
                ephemeralPublicKey,
                nonce,
            ),
        ).toHexLower()

    /** Constant-time check that [nonce] opens [commitHex] for the given identity and key. */
    fun commitMatches(commitHex: String, fingerprintHex: String, ephemeralPublicKey: ByteArray, nonce: ByteArray): Boolean =
        constantTimeBytesEqual(
            NumericComparisonCode.normalizeHex(commitHex).encodeToByteArray(),
            commitHex(fingerprintHex, ephemeralPublicKey, nonce).encodeToByteArray(),
        )

    /**
     * The 6-digit code. Inputs are in ROLE order (initiator first), not sorted: both sides know who
     * initiated, and fixing the order makes a reflected session derive a different code.
     */
    fun deriveCode(
        initiatorFingerprintHex: String,
        responderFingerprintHex: String,
        initiatorEphemeralPublicKey: ByteArray,
        responderEphemeralPublicKey: ByteArray,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
    ): String {
        val digest = sha256(
            encode(
                CODE_DOMAIN.encodeToByteArray(),
                NumericComparisonCode.normalizeHex(initiatorFingerprintHex).encodeToByteArray(),
                NumericComparisonCode.normalizeHex(responderFingerprintHex).encodeToByteArray(),
                initiatorEphemeralPublicKey,
                responderEphemeralPublicKey,
                initiatorNonce,
                responderNonce,
            ),
        )
        var value = 0L
        for (i in 0 until HASH_BYTES_USED) value = (value shl 8) or (digest[i].toLong() and 0xFFL)
        return (value % MODULUS).toString().padStart(NumericComparisonCode.CODE_LENGTH, '0')
    }

    /** True when a peer-claimed fingerprint is the key TLS pinned for that peer (both normalized). */
    fun matchesPinnedIdentity(claimedFingerprintHex: String, pinnedFingerprintHex: String?): Boolean {
        if (pinnedFingerprintHex == null) return false
        val claimed = NumericComparisonCode.normalizeHex(claimedFingerprintHex)
        val pinned = NumericComparisonCode.normalizeHex(pinnedFingerprintHex)
        if (claimed.isEmpty() || pinned.isEmpty()) return false
        return constantTimeBytesEqual(claimed.encodeToByteArray(), pinned.encodeToByteArray())
    }

    /** 4-byte big-endian length prefix per field. */
    private fun encode(vararg fields: ByteArray): ByteArray {
        var out = ByteArray(0)
        for (f in fields) {
            val len = f.size
            out += byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte())
            out += f
        }
        return out
    }
}
