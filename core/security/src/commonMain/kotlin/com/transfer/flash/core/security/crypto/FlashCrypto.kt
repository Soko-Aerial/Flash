@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.crypto

import com.transfer.flash.core.common.protocol.FlashProtocol

/**
 * Cryptographic identity + session-key abstraction for Flash (plan C2 target abstraction).
 *
 * **Design decision (researched, see docs/security.md):**
 * - IDENTITY key = ECDSA P-256 signing key. On Android it lives in AndroidKeyStore and is
 *   non-exportable. AndroidKeyStore ECDH requires `KeyProperties.PURPOSE_AGREE_KEY`
 *   which only exists since API 31 (https://developer.android.com/reference/android/security/keystore/KeyProperties),
 *   so below API 31 the identity key CANNOT do key agreement — hence the split:
 * - SESSION keys = ephemeral software `"ECDH"` P-256 keypairs generated per pairing,
 *   never persisted, memory-only. Software `"ECDH"` KeyAgreement is available on Android since
 *   API 11+ (https://developer.android.com/reference/kotlin/javax/crypto/KeyAgreement), well under
 *   minSdk 24.
 *
 * The shared secret is always passed through HKDF-SHA256 ([Hkdf]) before use as an AES key
 * (RFC 5869 section 3.3: the extract step MUST NOT be skipped for Diffie-Hellman values).
 *
 * ## Phase 07 (KMP) note on the key types in this signature
 *
 * This interface used to speak `java.security.PublicKey` / `KeyPair` / `PrivateKey`. Those types
 * cannot exist in `commonMain` under D1 = Option B, so keys are now expressed in the *only* form
 * that ever crosses a process boundary anyway: X.509 SubjectPublicKeyInfo bytes for public keys
 * ([FlashEcKeyPair.publicKeyEncoded]) and an opaque platform handle for private keys
 * ([PlatformEcPrivateKey], never exposed). No algorithm, parameter, or encoding changed — the
 * primitives moved behind [PlatformCrypto]'s `expect` seam and the JVM `actual`s make the same
 * JCA calls as before. `verify` already took SPKI bytes and is untouched.
 */
public interface FlashCrypto {

    /**
     * Public half of the per-install identity keypair, in X.509 SubjectPublicKeyInfo encoding.
     * Only the public part is exposed; implementations must not hand out the private key handle
     * outside the object.
     */
    public val identityPublicKeyEncoded: ByteArray

    /** Signs [data] with the identity key (SHA256withECDSA). */
    public fun sign(data: ByteArray): ByteArray

    /**
     * Verifies [signature] over [data] against a peer identity public key given in its
     * X.509 SubjectPublicKeyInfo encoding (wire-friendly). Returns false on any parse or
     * verification failure — never throws for malformed input.
     */
    public fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean

    /**
     * Generates a fresh ephemeral ECDH P-256 keypair for session establishment.
     * The result is memory-only; callers must drop it after deriving the session key.
     */
    public fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair

    /**
     * Runs ECDH between our ephemeral private key and the peer's ephemeral public key (given in
     * X.509 SubjectPublicKeyInfo encoding), then derives a 32-byte AES-256 session key via
     * HKDF-SHA256 bound to [FlashProtocol.VERSION]. Both peers derive identical output.
     *
     * Takes the whole [selfEphemeral] keypair rather than its private half because
     * [PlatformEcPrivateKey] is deliberately not part of any public signature.
     */
    public fun ecdhSessionKey(
        selfEphemeral: FlashEcKeyPair,
        peerEphemeralPublicKey: ByteArray,
    ): ByteArray

    public companion object {
        public const val IDENTITY_KEY_ALIAS: String = "flash_identity"
        public const val EC_CURVE: String = "secp256r1"
        public const val ECDSA_SIGNATURE_ALGORITHM: String = "SHA256withECDSA"
        public const val KEY_AGREEMENT_ALGORITHM: String = "ECDH"

        /** AES-256 per D4 — 32-byte HKDF output. */
        public const val SESSION_KEY_SIZE_BYTES: Int = 32

        /** Empty salt => RFC 5869 default of HashLen zero octets (applied inside [Hkdf]). */
        public val EMPTY_SALT: ByteArray = ByteArray(0)

        /** Context string binding derived session keys to the Flash protocol version. */
        public val SESSION_INFO: ByteArray = "flash-e2e-v${FlashProtocol.VERSION}".encodeToByteArray()
    }
}

/**
 * Shared P-256 operations used by both crypto implementations so that the AndroidKeyStore-backed
 * class and the software class stay behaviorally identical.
 *
 * Stays in `commonMain`: the *composition* (which curve, which signature algorithm, HKDF over the
 * raw ECDH output) is the security-relevant part and must not be duplicated per platform. Only the
 * primitive calls are `expect`ed — see [PlatformCrypto].
 */
internal object EcP256Ops {

    fun ephemeralKeyPair(): FlashEcKeyPair = ecP256GenerateKeyPair()

    fun sign(privateKey: PlatformEcPrivateKey, data: ByteArray): ByteArray =
        ecP256Sign(privateKey, data)

    /** Returns false instead of throwing for malformed keys/signatures (see [FlashCrypto.verify]). */
    fun verify(publicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        ecP256Verify(publicKeyBytes, data, signature)

    fun agreedSecret(privateKey: PlatformEcPrivateKey, peerPublicKeyEncoded: ByteArray): ByteArray =
        ecP256SharedSecret(privateKey, peerPublicKeyEncoded)

    /** HKDF-SHA256(sharedSecret) -> 32-byte AES-256 key, info-bound to the protocol version. */
    fun sessionKeyFromSharedSecret(sharedSecret: ByteArray): ByteArray =
        Hkdf.derive(
            ikm = sharedSecret,
            salt = FlashCrypto.EMPTY_SALT,
            info = FlashCrypto.SESSION_INFO,
            outLength = FlashCrypto.SESSION_KEY_SIZE_BYTES,
        )
}
