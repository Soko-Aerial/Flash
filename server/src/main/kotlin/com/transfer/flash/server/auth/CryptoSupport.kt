package com.transfer.flash.server.auth

import com.transfer.flash.core.security.crypto.FlashCrypto
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * JVM crypto support extensions and helpers for Flash delegated authority and certificate signing.
 * Uses ECDSA P-256 (`SHA256withECDSA`, `secp256r1`) matching [FlashCrypto] specifications.
 */

fun FlashCrypto.Companion.generateEcKeyPair(): KeyPair {
    val kpg = KeyPairGenerator.getInstance("EC")
    kpg.initialize(ECGenParameterSpec(FlashCrypto.EC_CURVE))
    return kpg.generateKeyPair()
}

fun FlashCrypto.Companion.sign(
    privateKey: PrivateKey,
    data: ByteArray,
): String {
    val sig = Signature.getInstance(FlashCrypto.ECDSA_SIGNATURE_ALGORITHM)
    sig.initSign(privateKey)
    sig.update(data)
    return Base64.getEncoder().encodeToString(sig.sign())
}

fun FlashCrypto.Companion.verifySignature(
    publicKeyBase64: String,
    data: ByteArray,
    signatureBase64: String,
): Boolean {
    return try {
        val pubKeyBytes = Base64.getDecoder().decode(publicKeyBase64)
        val sigBytes = Base64.getDecoder().decode(signatureBase64)
        verifySignature(pubKeyBytes, data, sigBytes)
    } catch (_: Exception) {
        false
    }
}

fun FlashCrypto.Companion.verifySignature(
    publicKeyEncoded: ByteArray,
    data: ByteArray,
    signature: ByteArray,
): Boolean {
    return try {
        val keyFactory = KeyFactory.getInstance("EC")
        val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyEncoded))
        val sig = Signature.getInstance(FlashCrypto.ECDSA_SIGNATURE_ALGORITHM)
        sig.initVerify(pubKey)
        sig.update(data)
        sig.verify(signature)
    } catch (_: Exception) {
        false
    }
}

fun FlashCrypto.Companion.parsePrivateKeyPkcs8(encodedBase64: String): PrivateKey {
    val bytes = Base64.getDecoder().decode(encodedBase64)
    val keyFactory = KeyFactory.getInstance("EC")
    return keyFactory.generatePrivate(PKCS8EncodedKeySpec(bytes))
}

fun FlashCrypto.Companion.sha256FingerprintHex(publicKeyBase64: String): String {
    val bytes = Base64.getDecoder().decode(publicKeyBase64)
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02X".format(it) }
}

fun PublicKey.toBase64Spki(): String = Base64.getEncoder().encodeToString(encoded)
fun KeyPair.toBase64Spki(): String = public.toBase64Spki()
fun PrivateKey.toBase64Pkcs8(): String = Base64.getEncoder().encodeToString(encoded)
