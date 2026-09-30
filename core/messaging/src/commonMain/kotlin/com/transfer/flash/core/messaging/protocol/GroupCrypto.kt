package com.transfer.flash.core.messaging.protocol

/**
 * The signing seam of v2 groups (ADR-044 V1).
 *
 * `:core:messaging` deliberately has no dependency on `:core:security` (Ground Rule R2), so the
 * host hands the repository this port, backed by the device's `FlashCrypto` (ECDSA P-256,
 * `SHA256withECDSA`) and `FlashFingerprint` (SHA-256). It is the same identity key the TLS layer
 * presents, so a signature here is a statement by the key the peer's pin points at.
 *
 * A repository built without one creates only legacy groups and ignores v2 frames.
 */
public interface GroupCrypto {
    /** This device's identity public key, X.509 SubjectPublicKeyInfo bytes. */
    public val publicKey: ByteArray

    /** Signs [data] with the local identity key. */
    public fun sign(data: ByteArray): ByteArray

    /**
     * Verifies [signature] over [data] against [publicKey] (SPKI bytes). Returns false, and never
     * throws, for any malformed input.
     */
    public fun verify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean

    /** SHA-256 of [data]. */
    public fun sha256(data: ByteArray): ByteArray
}
