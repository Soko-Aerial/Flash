package com.transfer.flash.core.messaging.protocol

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

/**
 * A real ECDSA P-256 [GroupCrypto] for host tests: the same algorithm, key encoding (X.509 SPKI)
 * and hash the production `FlashCrypto` uses, built on plain JCA so `:core:messaging` tests need
 * no dependency on `:core:security`. Each instance is a distinct device identity.
 */
class TestGroupCrypto : GroupCrypto {
    private val pair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }
        .generateKeyPair()

    override val publicKey: ByteArray = pair.public.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(data)
            sign()
        }

    override fun verify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean =
        try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey)))
                update(data)
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }

    override fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    /** The pin-store form of this identity's fingerprint. */
    fun fingerprintHex(): String = sha256(publicKey).joinToString("") { "%02X".format(it) }
}
