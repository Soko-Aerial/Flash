package com.transfer.flash.core.engine.group

import com.transfer.flash.core.messaging.protocol.GroupCrypto
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashFingerprint

/**
 * Backs the messaging module's [GroupCrypto] port with the device's own [FlashCrypto] (ADR-044 V1).
 *
 * `:core:messaging` has no dependency on `:core:security`, so each host hands the chat repository
 * this adapter instead. It adds nothing of its own: signing and verification are the same ECDSA
 * P-256 / `SHA256withECDSA` calls the pairing layer already makes with the identity key, and the
 * hash is [FlashFingerprint]'s SHA-256. That key is also the one the TLS certificate presents, so a
 * signature made here is a statement by the key the peer's pin points at.
 *
 * The argument order differs from [FlashCrypto.verify] only in the key: [GroupCrypto] mirrors the
 * rest of the group protocol (`signature, data, key`), which is the order [FlashCrypto] uses too.
 */
public class FlashGroupCrypto(private val crypto: FlashCrypto) : GroupCrypto {
    override val publicKey: ByteArray get() = crypto.identityPublicKeyEncoded

    override fun sign(data: ByteArray): ByteArray = crypto.sign(data)

    override fun verify(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean =
        crypto.verify(signature, data, publicKey)

    override fun sha256(data: ByteArray): ByteArray = FlashFingerprint.fingerprint(data)
}
