package com.transfer.flash.core.security.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android `actual`s for the [PlatformCrypto] seam — a direct transcription of the JCA calls that
 * `core/security`'s shared code made before Phase 07, so behaviour is bit-identical (R8).
 *
 * This file is byte-for-byte the same as its `jvmMain` counterpart. That duplication is
 * **intentional**, not an oversight: D1 = Option B forbids a `jvmAndAndroidMain` parent source
 * set, because such a parent would let `java.*` leak back into code that Kotlin/Native has to
 * compile. See CONVENTIONS.md R5.
 */

internal actual typealias PlatformEcPrivateKey = java.security.PrivateKey

private const val HMAC_SHA256 = "HmacSHA256"
private const val AES = "AES"
private const val AES_GCM = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128

private val secureRandom = SecureRandom()

internal actual fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance(HMAC_SHA256).run {
        init(SecretKeySpec(key, HMAC_SHA256))
        doFinal(data)
    }

internal actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(secureRandom::nextBytes)

internal actual fun constantTimeBytesEqual(a: ByteArray, b: ByteArray): Boolean =
    MessageDigest.isEqual(a, b)

internal actual fun aesGcmSeal(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    plaintext: ByteArray,
): ByteArray = Cipher.getInstance(AES_GCM).run {
    init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(GCM_TAG_BITS, nonce))
    updateAAD(aad)
    doFinal(plaintext)
}

internal actual fun aesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    ciphertext: ByteArray,
): ByteArray = Cipher.getInstance(AES_GCM).run {
    init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(GCM_TAG_BITS, nonce))
    updateAAD(aad)
    doFinal(ciphertext)
}

internal actual fun ecP256GenerateKeyPair(): FlashEcKeyPair {
    val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(FlashCrypto.EC_CURVE))
    }.generateKeyPair()
    return FlashEcKeyPair(
        publicKeyEncoded = keyPair.public.encoded,
        privateKey = keyPair.private,
    )
}

internal actual fun ecP256Sign(privateKey: PlatformEcPrivateKey, data: ByteArray): ByteArray =
    Signature.getInstance(FlashCrypto.ECDSA_SIGNATURE_ALGORITHM).run {
        initSign(privateKey)
        update(data)
        sign()
    }

internal actual fun ecP256Verify(
    publicKeyEncoded: ByteArray,
    data: ByteArray,
    signature: ByteArray,
): Boolean = try {
    val publicKey = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(publicKeyEncoded))
    Signature.getInstance(FlashCrypto.ECDSA_SIGNATURE_ALGORITHM).run {
        initVerify(publicKey)
        update(data)
        verify(signature)
    }
} catch (_: Exception) {
    false
}

// Phase 26 (ADR-035): included for seam completeness — Android's identity key is
// Keystore-generated and non-exportable, so NO production Android path calls these. If one
// ever does, treat it as a security regression, not a feature.

internal actual fun ecP256ExportPrivateKeyPkcs8(privateKey: PlatformEcPrivateKey): ByteArray =
    privateKey.encoded // software keys only — Keystore handles throw here by design

internal actual fun ecP256ParsePrivateKeyPkcs8(encoded: ByteArray): PlatformEcPrivateKey =
    KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(encoded))

internal actual fun ecP256SharedSecret(
    privateKey: PlatformEcPrivateKey,
    peerPublicKeyEncoded: ByteArray,
): ByteArray {
    val peerPublicKey = KeyFactory.getInstance("EC")
        .generatePublic(X509EncodedKeySpec(peerPublicKeyEncoded))
    val agreement = KeyAgreement.getInstance(FlashCrypto.KEY_AGREEMENT_ALGORITHM)
    agreement.init(privateKey)
    agreement.doPhase(peerPublicKey, true)
    return agreement.generateSecret()
}
