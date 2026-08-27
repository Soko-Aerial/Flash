package com.transfer.flash.core.security.crypto

import java.security.MessageDigest

/**
 * SHA-256 peer-key fingerprinting (D3) + display formatting (C2.3, feeds UI-031/UI-032).
 *
 * All equality checks go through [constantTimeEquals] ([MessageDigest.isEqual] is documented
 * constant-time: https://developer.android.com/reference/java/security/MessageDigest#isEqual(byte[],%20byte[]))
 * so fingerprint/code comparisons never leak prefix-match timing to a malicious peer.
 */
public object FlashFingerprint {

    /** SHA-256 over the public key's encoded form (D3 — java.security SHA-256, zero deps). */
    public fun fingerprint(publicKeyEncoded: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(publicKeyEncoded)

    /**
     * Stable uppercase colon-grouped hex form (`XX:XX:…`) for UI display.
     * Deterministic across processes/devices — the exact string shown on both pairing
     * devices must be byte-identical so users can compare visually.
     */
    public fun formatHexGroups(bytes: ByteArray): String =
        bytes.joinToString(separator = ":") { byte -> "%02X".format(byte) }

    /** Constant-time equality; safe for fingerprints and 6-digit pairing codes. */
    public fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)
}
