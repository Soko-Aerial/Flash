package com.transfer.flash.core.security.crypto

/**
 * SHA-256 peer-key fingerprinting (D3) + display formatting (C2.3, feeds UI-031/UI-032).
 *
 * All equality checks go through [constantTimeEquals], which on JVM targets is
 * `MessageDigest.isEqual` — documented constant-time
 * (https://developer.android.com/reference/java/security/MessageDigest#isEqual(byte[],%20byte[])) —
 * so fingerprint/code comparisons never leak prefix-match timing to a malicious peer.
 *
 * Phase 07: the SHA-256 and comparison primitives moved behind the [PlatformCrypto] seam; the
 * algorithm, the encoding, and the display format are unchanged.
 */
public object FlashFingerprint {

    /** SHA-256 over the public key's encoded form (D3). */
    public fun fingerprint(publicKeyEncoded: ByteArray): ByteArray = sha256(publicKeyEncoded)

    /**
     * Stable uppercase colon-grouped hex form (`XX:XX:…`) for UI display.
     * Deterministic across processes/devices — the exact string shown on both pairing
     * devices must be byte-identical so users can compare visually.
     */
    public fun formatHexGroups(bytes: ByteArray): String =
        bytes.joinToString(separator = ":") { byte -> byte.toHexUpper() }

    /** Constant-time equality; safe for fingerprints and 6-digit pairing codes. */
    public fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        constantTimeBytesEqual(a, b)
}
