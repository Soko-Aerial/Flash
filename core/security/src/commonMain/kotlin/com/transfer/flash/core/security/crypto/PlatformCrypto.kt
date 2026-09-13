package com.transfer.flash.core.security.crypto

/**
 * The complete set of cryptographic primitives `commonMain` needs, expressed as the smallest
 * possible `expect` surface (Phase 07 seam, D1 = Option B).
 *
 * ## Why this file exists
 *
 * Every algorithm choice in Flash — SHA-256 fingerprints, HMAC-SHA256 HKDF, AES-256-GCM frames,
 * ECDSA/ECDH over P-256 — was previously expressed directly in `java.security` / `javax.crypto`
 * terms. Under D1 = B (`commonMain` is stdlib + coroutines only, Kotlin/Native in scope) those
 * packages do not exist in shared code, so the *algorithms* stay in `commonMain` and only the
 * *primitive invocations* cross a platform boundary.
 *
 * ## The rule these functions exist to protect (CONVENTIONS.md R8)
 *
 * `core/security` behaviour must be **bit-identical** across this migration. That is why each
 * JVM-family `actual` is a direct transcription of the JCA call the shared code used to make —
 * `MessageDigest.getInstance("SHA-256")`, `Mac.getInstance("HmacSHA256")`,
 * `Cipher.getInstance("AES/GCM/NoPadding")`, `KeyAgreement.getInstance("ECDH")`. Nothing is
 * re-derived, re-ordered, or "improved". A future Kotlin/Native `actual` must reproduce the same
 * standard algorithms, and is pinned by [PlatformCryptoParityTest] plus the RFC 5869 vectors in
 * `HkdfTest` and the fixed SHA-256 vector in `FlashFingerprintTest`.
 *
 * All declarations are `internal`: they are module-private plumbing. The published API of
 * `core-security` is unchanged in shape — see [FlashCrypto].
 */

/** SHA-256 digest of [data]. Backed by `MessageDigest.getInstance("SHA-256")` on JVM targets. */
internal expect fun sha256(data: ByteArray): ByteArray

/**
 * HMAC-SHA256 of [data] under [key]. Backed by `Mac.getInstance("HmacSHA256")` on JVM targets.
 *
 * One-shot by design. [Hkdf] previously streamed `Mac.update()` calls; HMAC over the
 * concatenation of those chunks is the same function of the same input, so the output is
 * unchanged (still pinned by the RFC 5869 Appendix A vectors).
 */
internal expect fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

/**
 * [size] cryptographically secure random bytes. Backed by `SecureRandom` on JVM targets.
 *
 * Load-bearing for GCM nonce discipline — see the nonce section of [E2eFrameCodec]'s KDoc.
 * An `actual` MUST use the platform CSPRNG; `kotlin.random.Random` is not acceptable.
 */
internal expect fun secureRandomBytes(size: Int): ByteArray

/**
 * Constant-time byte-array equality. Backed by `MessageDigest.isEqual` on JVM targets, which is
 * documented constant-time for equal-length inputs and returns `false` for length mismatches.
 *
 * An `actual` must preserve both properties: no early exit on the first differing byte, and
 * `false` (never an exception) for different lengths — `FlashFingerprintTest` asserts the latter.
 */
internal expect fun constantTimeBytesEqual(a: ByteArray, b: ByteArray): Boolean

/**
 * AES-256-GCM encryption with a 128-bit tag appended to the ciphertext.
 *
 * @param key 32-byte AES-256 key.
 * @param nonce 12-byte GCM nonce, freshly random per invocation.
 * @param aad additional authenticated data; authenticated but not encrypted.
 * @return ciphertext with the 16-byte tag appended (JCA layout).
 */
internal expect fun aesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    plaintext: ByteArray,
): ByteArray

/**
 * Inverse of [aesGcmSeal].
 *
 * **Throws** when the tag does not verify — i.e. the input was tampered with, truncated, or
 * produced under a different key or AAD. The exception *type* is platform-defined
 * (`javax.crypto.AEADBadTagException` on JVM targets, preserving today's behaviour exactly);
 * callers must treat any throw as "unauthenticated frame, do not deliver" rather than
 * discriminating on the type. See [E2eFrameCodec.decrypt].
 */
internal expect fun aesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    ciphertext: ByteArray,
): ByteArray

/** Generates a fresh EC P-256 keypair (`secp256r1`). Memory-only; never persisted. */
internal expect fun ecP256GenerateKeyPair(): FlashEcKeyPair

/** ECDSA `SHA256withECDSA` signature over [data] using [privateKey]. */
internal expect fun ecP256Sign(privateKey: PlatformEcPrivateKey, data: ByteArray): ByteArray

/**
 * Verifies an ECDSA `SHA256withECDSA` [signature] over [data] against a peer public key in
 * X.509 SubjectPublicKeyInfo encoding.
 *
 * Returns `false` — never throws — for malformed keys or signatures, matching
 * [FlashCrypto.verify]'s documented contract.
 */
internal expect fun ecP256Verify(
    publicKeyEncoded: ByteArray,
    data: ByteArray,
    signature: ByteArray,
): Boolean

/**
 * Raw ECDH shared secret between [privateKey] and a peer public key in X.509
 * SubjectPublicKeyInfo encoding.
 *
 * The result is **not** a key. RFC 5869 §3.3 requires the HKDF extract step for
 * Diffie-Hellman outputs, so every caller routes this through [Hkdf] — see
 * [EcP256Ops.sessionKeyFromSharedSecret].
 */
internal expect fun ecP256SharedSecret(
    privateKey: PlatformEcPrivateKey,
    peerPublicKeyEncoded: ByteArray,
): ByteArray

// ─── Private-key persistence (Phase 26 / ADR-035) ────────────────────────────────
//
// The two functions below exist ONLY so the desktop's PersistedFlashCrypto can round-trip its
// identity key through `~/.flash/identity/id-key.bin`. Android does not use them (the identity
// key is Keystore-generated and non-exportable by design — R8); they are included in the shared
// seam so the encoding is pinned in ONE place with the same bit-exactness discipline as above.

/**
 * Exports [privateKey] in PKCS#8 (`PrivateKeyInfo`) encoding — the JCA `getEncoded()` form for
 * an EC key. Paired with [ecP256ParsePrivateKeyPkcs8]; the round trip is pinned by
 * `PersistedFlashCryptoTest`.
 */
internal expect fun ecP256ExportPrivateKeyPkcs8(privateKey: PlatformEcPrivateKey): ByteArray

/**
 * Parses a PKCS#8-encoded EC P-256 private key (as produced by [ecP256ExportPrivateKeyPkcs8]).
 *
 * Throws a platform-defined exception on malformed input (`InvalidKeySpecException` on JVM
 * targets); [PersistedFlashCrypto] treats any throw as "vault unreadable" and degrades loudly.
 */
internal expect fun ecP256ParsePrivateKeyPkcs8(encoded: ByteArray): PlatformEcPrivateKey
