package com.transfer.flash.core.network.radio

import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * [RadioCrypto] on `javax.crypto`: HMAC-SHA-256, SHA-256, AES-256-GCM (128-bit tag), SecureRandom. Same algorithms as
 * `:core:security`; kept in this module because those primitives are `internal` there (see [RadioCrypto]).
 * A byte-identical copy lives in `androidMain`.
 */
public class JdkRadioCrypto(private val random: SecureRandom = SecureRandom()) : RadioCrypto {
    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    override fun aeadSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    override fun aeadOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertextAndTag: ByteArray): ByteArray? =
        try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            c.updateAAD(aad)
            c.doFinal(ciphertextAndTag)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also { random.nextBytes(it) }
}

/**
 * [RadioSignatureScheme] on `java.security` (`SHA256withECDSA`, P-256) with the DER to raw `r||s` conversion.
 * [privateKey] may be null for a verify-only instance. A byte-identical copy lives in `androidMain`.
 */
public class JdkRadioSignatureScheme(private val privateKey: PrivateKey? = null) : RadioSignatureScheme {
    override fun sign(data: ByteArray): ByteArray {
        val key = privateKey ?: error("verify-only instance")
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(key)
        s.update(data)
        return EcdsaRawSignature.derToRaw(s.sign()) ?: error("JCA produced a malformed DER signature")
    }

    override fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        try {
            val der = EcdsaRawSignature.rawToDer(signature) ?: return false
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey))
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(pub)
            s.update(data)
            s.verify(der)
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
}
