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

/**
 * Deterministic canonical byte serializer for certificates, delegations, and revocation lists.
 * Every field is written with a 4-byte big-endian length prefix followed by its payload.
 */
internal class AuthCanonicalWriter(tag: String) {
    private val parts = mutableListOf<ByteArray>()
    private var total = 0

    init {
        text(tag)
    }

    fun text(value: String): AuthCanonicalWriter = bytes(value.encodeToByteArray())

    fun bytes(value: ByteArray): AuthCanonicalWriter {
        val len = value.size
        add(
            byteArrayOf(
                (len ushr 24).toByte(),
                (len ushr 16).toByte(),
                (len ushr 8).toByte(),
                len.toByte(),
            )
        )
        add(value)
        return this
    }

    fun long(value: Long): AuthCanonicalWriter = bytes(
        ByteArray(8) { i -> (value ushr (56 - 8 * i)).toByte() }
    )

    fun int(value: Int): AuthCanonicalWriter = bytes(
        ByteArray(4) { i -> (value ushr (24 - 8 * i)).toByte() }
    )

    fun bool(value: Boolean): AuthCanonicalWriter = bytes(byteArrayOf(if (value) 1 else 0))

    fun stringSet(values: Set<String>): AuthCanonicalWriter {
        val sorted = values.sorted()
        int(sorted.size)
        for (v in sorted) {
            text(v)
        }
        return this
    }

    fun longSet(values: Set<Long>): AuthCanonicalWriter {
        val sorted = values.sorted()
        int(sorted.size)
        for (v in sorted) {
            long(v)
        }
        return this
    }

    fun build(): ByteArray {
        val out = ByteArray(total)
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    private fun add(part: ByteArray) {
        parts.add(part)
        total += part.size
    }
}
