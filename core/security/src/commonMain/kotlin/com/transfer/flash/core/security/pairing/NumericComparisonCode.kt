package com.transfer.flash.core.security.pairing

import com.transfer.flash.core.security.crypto.constantTimeBytesEqual
import com.transfer.flash.core.security.crypto.sha256
import com.transfer.flash.core.security.crypto.toHexLower

/**
 * Derives the 6-digit numeric comparison code displayed on BOTH devices during
 * pairing (C2.6), following the general principle proven by Bluetooth pairing's
 * numeric comparison association model: each party computes a short confirmation
 * value from both parties' public key material, and the human compares the two
 * displays to detect a man-in-the-middle (Bluetooth Core spec, Security Manager
 * §g2; https://www.bluetooth.com/wp-content/uploads/Files/Specification/HTML/Core_v6.3/out/en/host/security-manager-specification.html).
 *
 * ## Construction
 *
 * ```text
 * code = first 5 bytes of SHA-256( sorted(fa, fb)[0] || sorted(fa, fb)[1] )
 *        interpreted big-endian, taken mod 1_000_000, formatted %06d
 * ```
 *
 * where `fa`/`fb` are the two devices' identity fingerprints normalized to
 * lowercase hex with separators (`:`, `-`, spaces) stripped, concatenated as
 * US-ASCII bytes.
 *
 * ## Why the fingerprints are SORTED before concatenation
 *
 * Sorting makes the function **symmetric**: `derive(a, b) == derive(b, a)`. Both
 * devices run the same code without having to negotiate who is "device A" — the
 * role assignment (initiator/responder) must not influence what the users see,
 * otherwise a mismatch would be trivially caused by role asymmetry instead of an
 * actual MITM. This mirrors Bluetooth's approach of feeding both parties' public
 * keys into one shared derivation so both displays match by construction.
 *
 * Five bytes (40 bits) mod 10^6 leaves ~19.9 bits of entropy in the code — equal
 * to Bluetooth's 6-digit space — which is the standard out-of-band comparison
 * budget: enough that an attacker forcing a colliding display has probability
 * 10^-6 per attempt, while the user only ever compares 6 digits.
 *
 * Pure function: no I/O, no clock, deterministic across platforms (SHA-256 via the
 * `PlatformCrypto` seam, ASCII encoding). Fully unit-tested including the symmetry
 * property.
 */
internal object NumericComparisonCode {

    /** Display length of the derived code (zero-padded decimal). */
    const val CODE_LENGTH = 6

    private const val MODULUS = 1_000_000L
    private const val HASH_BYTES_USED = 5

    /**
     * Derives the 6-digit display code from both devices' fingerprint hex strings.
     * Argument order is irrelevant (see class KDoc). Accepts uppercase/mixed case
     * hex and common separators (`:`, `-`, whitespace); they are normalized away.
     *
     * @throws IllegalArgumentException when either input has no hex characters after
     * normalization (blank or separator-only fingerprints cannot produce a pin).
     */
    fun derive(fingerprintHexA: String, fingerprintHexB: String): String {
        val a = normalizeHex(fingerprintHexA)
        val b = normalizeHex(fingerprintHexB)
        require(a.isNotEmpty() && b.isNotEmpty()) {
            "Numeric comparison requires non-blank fingerprints"
        }
        // Lexicographic sort = role-independent symmetric concatenation order.
        val (first, second) = if (a <= b) a to b else b to a
        // normalizeHex() emits only [0-9a-f], so UTF-8 and US-ASCII encodings are byte-identical.
        val digest = sha256(first.encodeToByteArray() + second.encodeToByteArray())
        var value = 0L
        for (i in 0 until HASH_BYTES_USED) {
            value = (value shl 8) or (digest[i].toLong() and 0xFFL)
        }
        return (value % MODULUS).toString().padStart(CODE_LENGTH, '0')
    }

    /**
     * SHA-256 hex digest of the displayed code, carried in
     * [FlashPairingFrame.PairConfirm.codeHashHex] so the responder can verify both
     * sides saw the same code. Domain-separated so a code hash can never collide
     * with any other protocol hash of the same digits.
     */
    fun confirmationHashHex(code: String): String {
        val payload = "flash-pairing-confirm-v1:$code".encodeToByteArray()
        return sha256(payload).toHexLower()
    }

    /**
     * Constant-time equality check for confirmation hashes (or any hex-derived
     * secret-ish material). Inputs are normalized to lowercase before comparing;
     * the platform comparison is constant-time for equal-length inputs.
     */
    fun hashesEqual(expectedHex: String, presentedHex: String): Boolean =
        constantTimeBytesEqual(
            normalizeHex(expectedHex).encodeToByteArray(),
            normalizeHex(presentedHex).encodeToByteArray(),
        )

    /** Lowercases and strips separators; keeps hex digits only. */
    fun normalizeHex(hex: String): String =
        buildString(hex.length) {
            for (c in hex.lowercase()) {
                if (c.isDigit() || c in 'a'..'f') append(c)
            }
        }
}
