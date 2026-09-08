package com.transfer.flash.core.network.tls

/**
 * Seam between the C4 TLS layer and the persistent TOFU trust store (C2.5 / `RoomTrustedStore`).
 *
 * The network module deliberately knows nothing about Room or `:core:security` (Ground Rule R2):
 * the engine wiring layer injects an implementation backed by the paired-peer table once C4/C7
 * integration lands. Until then, tests supply in-memory maps.
 *
 * Contract for implementers:
 * - [fingerprintHex] arrives **normalized** (uppercase, no colons/spaces — see [normalize]).
 *   Store lookups should normalize stored values identically ([normalize]) before comparing.
 * - Equality checks MUST be constant-time (`MessageDigest.isEqual`) so pin comparisons never
 *   leak prefix-match timing — same convention as `FlashFingerprint.constantTimeEquals`.
 */
public fun interface FlashPinVerifier {

    /**
     * @param deviceId       stable identity of the remote peer this connection claims to be.
     * @param fingerprintHex normalized SHA-256 fingerprint (uppercase hex, no separators) of a
     *                       public key presented in the peer's certificate chain.
     * @return true iff [fingerprintHex] is the key previously pinned for [deviceId].
     */
    public fun isPinned(deviceId: String, fingerprintHex: String): Boolean

    public companion object {
        /** Uppercase, strip colons/spaces so any human-formatted input compares equal. */
        public fun normalize(raw: String): String =
            raw.replace(":", "").replace(" ", "").uppercase()
    }
}
