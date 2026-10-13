package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.AuthCanonicalWriter
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.crypto.Hkdf
import com.transfer.flash.core.security.crypto.aesGcmOpen
import com.transfer.flash.core.security.crypto.aesGcmSeal
import com.transfer.flash.core.security.crypto.ecP256ExportPrivateKeyPkcs8
import com.transfer.flash.core.security.crypto.ecP256GenerateKeyPair
import com.transfer.flash.core.security.crypto.ecP256ParsePrivateKeyPkcs8
import com.transfer.flash.core.security.crypto.ecP256SharedSecret
import com.transfer.flash.core.security.crypto.ecP256Verify
import com.transfer.flash.core.security.crypto.secureRandomBytes

/**
 * A device's end-to-end SEALING key pair (CLI-05, ADR-121). The hub relays and stores chat payloads, so a payload that
 * must stay secret from the hub is sealed to the recipient's sealing key with [HubSealing.seal].
 *
 * Why a key of its own and not the certificate key: the device identity key is an ECDSA key. On Android it is a
 * non-exportable Keystore key generated for SIGN/VERIFY only, and `PURPOSE_AGREE_KEY` needs API 31 while the app's
 * minSdk is 24, so the certificate key cannot take part in an ECDH on every supported phone. The sealing key is a
 * software P-256 key whose PUBLIC half is bound to the device by an identity-key signature ([bindingBytes]).
 */
@FlashInternalApi
public class HubSealingKeyPair internal constructor(
    /** X.509 SPKI bytes of the public half: the value published to the directory. */
    public val publicKeyEncoded: ByteArray,
    internal val keyPair: FlashEcKeyPair,
) {
    /** PKCS#8 bytes of the private half, for the host's secure storage. Never log or transmit. */
    public fun exportPrivateKeyPkcs8(): ByteArray = ecP256ExportPrivateKeyPkcs8(keyPair.privateKey)

    /** The identity-key signature that binds this key to [deviceId] (Base64), to be published with the key. */
    public fun bindingSignature(crypto: FlashCrypto, deviceId: String): String =
        Base64.encode(crypto.sign(bindingBytes(deviceId, publicKeyEncoded)))

    public companion object {
        /** A new random sealing key pair. */
        public fun generate(): HubSealingKeyPair {
            val pair = ecP256GenerateKeyPair()
            return HubSealingKeyPair(pair.publicKeyEncoded, pair)
        }

        /** Restores a stored pair; throws when the PKCS#8 bytes are malformed. */
        public fun restore(publicKeyEncoded: ByteArray, privateKeyPkcs8: ByteArray): HubSealingKeyPair =
            HubSealingKeyPair(publicKeyEncoded, FlashEcKeyPair(publicKeyEncoded, ecP256ParsePrivateKeyPkcs8(privateKeyPkcs8)))

        /** Bytes the device identity key signs to claim [sealingPublicKey] for [deviceId]. */
        public fun bindingBytes(deviceId: String, sealingPublicKey: ByteArray): ByteArray =
            HubAuth.sealingKeyBindingBytes(deviceId, sealingPublicKey)

        /** True when [signature] (Base64) proves [identityPublicKey] vouches for [sealingPublicKey] as [deviceId]'s sealing key. */
        public fun verifyBinding(
            identityPublicKey: ByteArray,
            deviceId: String,
            sealingPublicKey: ByteArray,
            signature: String,
        ): Boolean {
            val sig = runCatching { Base64.decode(signature) }.getOrNull() ?: return false
            return ecP256Verify(identityPublicKey, bindingBytes(deviceId, sealingPublicKey), sig)
        }

        internal const val BINDING_TAG: String = HubAuth.SEALING_KEY_TAG
    }
}

/** The result of a successful [HubSealing.open]: the inner chat type and body, as the sender wrote them. */
@FlashInternalApi
public class UnsealedPayload(public val innerType: String, public val body: String)

/**
 * End-to-end sealing of a hub-relayed payload (ADR-121): ephemeral P-256 ECDH to the recipient's sealing key, HKDF-SHA256
 * with the domain `flash-hub-seal-v1`, AES-256-GCM, and an identity-key signature by the sender over the whole envelope.
 *
 * Wire form of the sealed payload (one text, the relay `payload` of a `SEALED_V1` message):
 * `v1.<epk>.<nonce>.<ciphertext>.<signature>` with every part Base64.
 *
 * The AAD binds the relay protocol version, sender, recipient, message id and send time, so a hub that replays a
 * sealed payload under another id, to another recipient or at another time cannot make it open (the AEAD and the
 * signature both fail). Opening needs the recipient's sealing private key AND the sender's identity public key, taken
 * from the authenticated directory (never from the payload itself).
 */
@FlashInternalApi
public object HubSealing {

    /** The relay `type` carrying a sealed payload; the inner chat type travels inside the ciphertext. */
    public const val ENVELOPE_TYPE: String = "SEALED_V1"

    private const val VERSION_TAG: String = "v1"
    private const val NONCE_BYTES: Int = 12
    private const val KEY_BYTES: Int = 32
    private const val KEY_INFO_TAG: String = "flash-hub-seal-v1-key"
    private const val AAD_TAG: String = "flash-hub-seal-v1-aad"
    private const val SIG_TAG: String = "flash-hub-seal-v1-sig"

    /**
     * Seals [body] (a chat text of kind [innerType]) for [recipientDeviceId]. Returns the payload text for a
     * [ENVELOPE_TYPE] relay message. [crypto] signs with the device identity key.
     */
    public fun seal(
        crypto: FlashCrypto,
        senderDeviceId: String,
        recipientDeviceId: String,
        recipientSealingPublicKey: ByteArray,
        msgId: String,
        sentAt: Long,
        innerType: String,
        body: String,
    ): String {
        require('\n' !in innerType) { "inner type must be a single line" }
        val ephemeral = ecP256GenerateKeyPair()
        val key = deriveKey(
            ecP256SharedSecret(ephemeral.privateKey, recipientSealingPublicKey),
            ephemeral.publicKeyEncoded,
            recipientSealingPublicKey,
        )
        val nonce = secureRandomBytes(NONCE_BYTES)
        val aad = aad(senderDeviceId, recipientDeviceId, msgId, sentAt)
        val ciphertext = aesGcmSeal(key, nonce, aad, "$innerType\n$body".encodeToByteArray())
        val signature = crypto.sign(signedBytes(aad, ephemeral.publicKeyEncoded, nonce, ciphertext))
        return listOf(
            VERSION_TAG,
            Base64.encode(ephemeral.publicKeyEncoded),
            Base64.encode(nonce),
            Base64.encode(ciphertext),
            Base64.encode(signature),
        ).joinToString(".")
    }

    /**
     * Opens a sealed [payload]. Returns null (never throws) when the payload is malformed, the sender signature does
     * not verify under [senderIdentityPublicKey], or the AEAD fails (wrong key, tampered ciphertext, or any of the
     * bound fields differing from what the sender sealed).
     */
    public fun open(
        recipientKey: HubSealingKeyPair,
        senderIdentityPublicKey: ByteArray,
        senderDeviceId: String,
        recipientDeviceId: String,
        msgId: String,
        sentAt: Long,
        payload: String,
    ): UnsealedPayload? = runCatching {
        val parts = payload.split('.')
        if (parts.size != 5 || parts[0] != VERSION_TAG) return null
        val epk = Base64.decode(parts[1])
        val nonce = Base64.decode(parts[2])
        val ciphertext = Base64.decode(parts[3])
        val signature = Base64.decode(parts[4])
        if (nonce.size != NONCE_BYTES) return null
        val aad = aad(senderDeviceId, recipientDeviceId, msgId, sentAt)
        // Authenticate the sender first: an unsigned or forged envelope never reaches the decryption.
        if (!ecP256Verify(senderIdentityPublicKey, signedBytes(aad, epk, nonce, ciphertext), signature)) return null
        val key = deriveKey(ecP256SharedSecret(recipientKey.keyPair.privateKey, epk), epk, recipientKey.publicKeyEncoded)
        val plain = aesGcmOpen(key, nonce, aad, ciphertext).decodeToString()
        val cut = plain.indexOf('\n')
        if (cut <= 0) return null
        UnsealedPayload(plain.substring(0, cut), plain.substring(cut + 1))
    }.getOrNull()

    private fun deriveKey(shared: ByteArray, epk: ByteArray, recipientPublicKey: ByteArray): ByteArray =
        Hkdf.derive(
            ikm = shared,
            salt = ByteArray(0),
            info = AuthCanonicalWriter(KEY_INFO_TAG).bytes(epk).bytes(recipientPublicKey).build(),
            outLength = KEY_BYTES,
        )

    internal fun aad(senderDeviceId: String, recipientDeviceId: String, msgId: String, sentAt: Long): ByteArray =
        AuthCanonicalWriter(AAD_TAG)
            .int(HubContract.RELAY_WIRE_VERSION)
            .text(senderDeviceId)
            .text(recipientDeviceId)
            .text(msgId)
            .long(sentAt)
            .build()

    private fun signedBytes(aad: ByteArray, epk: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        AuthCanonicalWriter(SIG_TAG).bytes(aad).bytes(epk).bytes(nonce).bytes(ciphertext).build()
}
