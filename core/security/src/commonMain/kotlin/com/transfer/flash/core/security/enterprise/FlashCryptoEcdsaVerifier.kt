package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.core.security.crypto.FlashCrypto

/**
 * Client-side [EcdsaVerifier]: verifies hub certificates with the device's own [FlashCrypto] (the same P-256 /
 * `SHA256withECDSA` implementation the pairing layer uses). The server supplies a `java.security` adapter instead;
 * `HubSignedVectorTest` (here) and `HubSignedVectorServerTest` (`:server`) pin both to the same signed vector.
 */
@FlashInternalApi
public class FlashCryptoEcdsaVerifier(private val crypto: FlashCrypto) : EcdsaVerifier {
    override fun verify(publicKeySpki: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
        try {
            crypto.verify(signature = signature, data = data, peerPublicKey = publicKeySpki)
        } catch (_: Throwable) {
            false
        }
}
