package com.transfer.flash.core.security.crypto

import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey

/**
 * Pure-JCA software [FlashCrypto] implementation.
 *
 * # ⚠️ NOT FOR PRODUCTION IDENTITY STORAGE ⚠️
 *
 * The identity keypair generated here is an ordinary in-memory JCA key: it is exportable,
 * lives in the process heap, and is lost on process death. It exists ONLY for:
 *  - JVM unit tests (AndroidKeyStore is unavailable on the JVM), and
 *  - as an explicitly-chosen emergency fallback if a device's keystore is corrupted
 *    (any such use must be flagged loudly and tracked as security debt per AGENTS.md §19).
 *
 * Production builds must use [KeystoreFlashCrypto], whose identity key is non-exportable and
 * hardware-backed. The ephemeral-ECDH + HKDF session-key path is identical in both classes;
 * ephemeral keys are memory-only by design everywhere.
 */
internal class SoftwareFlashCrypto : FlashCrypto {

    private val identityKeyPair: KeyPair by lazy { EcP256Ops.ephemeralKeyPair() }

    override val identityPublicKey: PublicKey
        get() = identityKeyPair.public

    override fun sign(data: ByteArray): ByteArray =
        EcP256Ops.sign(identityKeyPair.private, data)

    override fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean =
        EcP256Ops.verify(peerPublicKey, data, signature)

    override fun generateEphemeralEcdhKeyPair(): KeyPair = EcP256Ops.ephemeralKeyPair()

    override fun ecdhSessionKey(
        selfEphemeralPrivateKey: PrivateKey,
        peerEphemeralPublicKey: PublicKey,
    ): ByteArray =
        EcP256Ops.sessionKeyFromSharedSecret(
            EcP256Ops.agreedSecret(selfEphemeralPrivateKey, peerEphemeralPublicKey)
        )
}
