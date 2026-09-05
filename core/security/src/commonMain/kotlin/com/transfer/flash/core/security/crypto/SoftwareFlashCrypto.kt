package com.transfer.flash.core.security.crypto

/**
 * Pure-software [FlashCrypto] implementation.
 *
 * # ⚠️ NOT FOR PRODUCTION IDENTITY STORAGE ⚠️
 *
 * The identity keypair generated here is an ordinary in-memory key: it is exportable,
 * lives in the process heap, and is lost on process death. It exists ONLY for:
 *  - host unit tests (AndroidKeyStore is unavailable off-device), and
 *  - as an explicitly-chosen emergency fallback if a device's keystore is corrupted
 *    (any such use must be flagged loudly and tracked as security debt per AGENTS.md §19).
 *
 * Production Android builds must use `KeystoreFlashCrypto`, whose identity key is non-exportable
 * and hardware-backed. The ephemeral-ECDH + HKDF session-key path is identical in both classes;
 * ephemeral keys are memory-only by design everywhere.
 *
 * Lives in `commonMain` as of Phase 07: every primitive it needs now comes from the
 * [PlatformCrypto] seam, so desktop and any future Kotlin/Native target get the same
 * implementation rather than a re-derived one.
 */
internal class SoftwareFlashCrypto : FlashCrypto {

    private val identityKeyPair: FlashEcKeyPair by lazy { EcP256Ops.ephemeralKeyPair() }

    override val identityPublicKeyEncoded: ByteArray
        get() = identityKeyPair.publicKeyEncoded

    override fun sign(data: ByteArray): ByteArray =
        EcP256Ops.sign(identityKeyPair.privateKey, data)

    override fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean =
        EcP256Ops.verify(peerPublicKey, data, signature)

    override fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair = EcP256Ops.ephemeralKeyPair()

    override fun ecdhSessionKey(
        selfEphemeral: FlashEcKeyPair,
        peerEphemeralPublicKey: ByteArray,
    ): ByteArray =
        EcP256Ops.sessionKeyFromSharedSecret(
            EcP256Ops.agreedSecret(selfEphemeral.privateKey, peerEphemeralPublicKey)
        )
}
