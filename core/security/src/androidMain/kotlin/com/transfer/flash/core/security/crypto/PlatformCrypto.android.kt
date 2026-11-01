package com.transfer.flash.core.security.crypto

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.LinkedHashMap
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
internal val sharedSecureRandom: SecureRandom = secureRandom

// Cache provider selection, not keyed Cipher/Mac instances: doFinal retains their
// initialized key state. Fresh operations also avoid provider-specific IV reuse state.
private val ecProvider = KeyFactory.getInstance("EC").provider
private val aesGcmProvider = Cipher.getInstance(AES_GCM).provider
private val hmacSha256Provider = Mac.getInstance(HMAC_SHA256).provider

private class SpkiKey(encoded: ByteArray) {
    private val bytes = encoded.copyOf()
    private val hashCode = bytes.contentHashCode()
    fun keySpec(): X509EncodedKeySpec = X509EncodedKeySpec(bytes)
    override fun hashCode(): Int = hashCode
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SpkiKey) return false
        return bytes.contentEquals(other.bytes)
    }
}

private val spkiLock = Any()
private val spkiCache = LinkedHashMap<SpkiKey, java.security.PublicKey>(128, 0.75f, true)

private fun getOrParsePublicKey(encoded: ByteArray): java.security.PublicKey {
    val key = SpkiKey(encoded)
    synchronized(spkiLock) {
        spkiCache[key]?.let { return it }
    }
    // Parse the same immutable snapshot used by the map key. KeyFactory's provider
    // has no portable concurrent-use contract, so each miss owns its factory.
    val parsed = KeyFactory.getInstance("EC", ecProvider).generatePublic(key.keySpec())
    synchronized(spkiLock) {
        spkiCache[key]?.let { return it }
        if (spkiCache.size >= 1024) {
            val it = spkiCache.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        spkiCache[key] = parsed
    }
    return parsed
}

private val sha256Digest: ThreadLocal<MessageDigest> = ThreadLocal.withInitial {
    MessageDigest.getInstance("SHA-256")
}

internal actual fun sha256(data: ByteArray): ByteArray {
    val md = sha256Digest.get()!!
    md.reset()
    return md.digest(data)
}

internal actual class Sha256Stream actual constructor() {
    private val digest: MessageDigest = MessageDigest.getInstance("SHA-256")

    actual fun update(data: ByteArray, offset: Int, length: Int) {
        digest.update(data, offset, length)
    }

    actual fun digest(): ByteArray = digest.digest()
}

internal actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance(HMAC_SHA256, hmacSha256Provider)
    mac.init(SecretKeySpec(key, HMAC_SHA256))
    return mac.doFinal(data)
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
): ByteArray {
    val cipher = Cipher.getInstance(AES_GCM, aesGcmProvider)
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(GCM_TAG_BITS, nonce))
    cipher.updateAAD(aad)
    return cipher.doFinal(plaintext)
}

internal actual fun aesGcmOpen(
    key: ByteArray,
    nonce: ByteArray,
    aad: ByteArray,
    ciphertext: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance(AES_GCM, aesGcmProvider)
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), GCMParameterSpec(GCM_TAG_BITS, nonce))
    cipher.updateAAD(aad)
    return cipher.doFinal(ciphertext)
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
    val publicKey = getOrParsePublicKey(publicKeyEncoded)
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
    KeyFactory.getInstance("EC", ecProvider).generatePrivate(PKCS8EncodedKeySpec(encoded))

internal actual fun ecP256SharedSecret(
    privateKey: PlatformEcPrivateKey,
    peerPublicKeyEncoded: ByteArray,
): ByteArray {
    val peerPublicKey = getOrParsePublicKey(peerPublicKeyEncoded)
    val agreement = KeyAgreement.getInstance(FlashCrypto.KEY_AGREEMENT_ALGORITHM)
    agreement.init(privateKey)
    agreement.doPhase(peerPublicKey, true)
    return agreement.generateSecret()
}
