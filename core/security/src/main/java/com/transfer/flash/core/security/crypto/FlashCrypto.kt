@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.security.crypto

import com.transfer.flash.core.common.protocol.FlashProtocol
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

/**
 * Cryptographic identity + session-key abstraction for Flash (plan C2 target abstraction).
 *
 * **Design decision (researched, see docs/security.md):**
 * - IDENTITY key = ECDSA P-256 signing key. On Android it lives in AndroidKeyStore and is
 *   non-exportable. AndroidKeyStore ECDH requires [android.security.keystore.KeyProperties.PURPOSE_AGREE_KEY]
 *   which only exists since API 31 (https://developer.android.com/reference/android/security/keystore/KeyProperties),
 *   so below API 31 the identity key CANNOT do key agreement — hence the split:
 * - SESSION keys = ephemeral software JCA `"ECDH"` P-256 keypairs generated per pairing,
 *   never persisted, memory-only. Software `"ECDH"` KeyAgreement is available on Android since
 *   API 11+ (https://developer.android.com/reference/kotlin/javax/crypto/KeyAgreement), well under
 *   minSdk 24.
 *
 * The shared secret is always passed through HKDF-SHA256 ([Hkdf]) before use as an AES key
 * (RFC 5869 section 3.3: the extract step MUST NOT be skipped for Diffie-Hellman values).
 */
public interface FlashCrypto {

    /**
     * Public half of the per-install identity keypair. Only the public part is exposed;
     * implementations must not hand out the private key handle outside the object.
     */
    public val identityPublicKey: PublicKey

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
    public fun generateEphemeralEcdhKeyPair(): KeyPair

    /**
     * Runs ECDH between our ephemeral private key and the peer's ephemeral public key,
     * then derives a 32-byte AES-256 session key via HKDF-SHA256 bound to
     * [FlashProtocol.VERSION]. Both peers derive identical output.
     */
    public fun ecdhSessionKey(selfEphemeralPrivateKey: PrivateKey, peerEphemeralPublicKey: PublicKey): ByteArray

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
        public val SESSION_INFO: ByteArray = "flash-e2e-v${FlashProtocol.VERSION}".toByteArray()
    }
}

/**
 * Shared pure-JCA P-256 operations used by both crypto implementations so that the
 * AndroidKeyStore-backed class and the software class stay behaviorally identical.
 */
internal object EcP256Ops {

    fun ephemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec(FlashCrypto.EC_CURVE))
        }.generateKeyPair()

    fun sign(privateKey: PrivateKey, data: ByteArray): ByteArray =
        Signature.getInstance(FlashCrypto.ECDSA_SIGNATURE_ALGORITHM).run {
            initSign(privateKey)
            update(data)
            sign()
        }

    /** Returns false instead of throwing for malformed keys/signatures (see [FlashCrypto.verify]). */
    fun verify(publicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        Signature.getInstance(FlashCrypto.ECDSA_SIGNATURE_ALGORITHM).run {
            initVerify(publicKey)
            update(data)
            verify(signature)
        }
    } catch (_: Exception) {
        false
    }

    fun agreedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val agreement = KeyAgreement.getInstance(FlashCrypto.KEY_AGREEMENT_ALGORITHM)
        agreement.init(privateKey)
        agreement.doPhase(peerPublicKey, true)
        return agreement.generateSecret()
    }

    /** HKDF-SHA256(sharedSecret) -> 32-byte AES-256 key, info-bound to the protocol version. */
    fun sessionKeyFromSharedSecret(sharedSecret: ByteArray): ByteArray =
        Hkdf.derive(
            ikm = sharedSecret,
            salt = FlashCrypto.EMPTY_SALT,
            info = FlashCrypto.SESSION_INFO,
            outLength = FlashCrypto.SESSION_KEY_SIZE_BYTES,
        )
}
