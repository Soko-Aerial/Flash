package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * The only cryptographic capability the hub contract needs: "did the holder of this P-256 key sign these bytes".
 *
 * `:core:hub-protocol` deliberately has no crypto dependency (no `java.security`, no `FlashCrypto`), so signature
 * verification is injected. `:server` supplies a `java.security` adapter, the clients supply a `FlashCrypto` adapter
 * (`FlashCryptoEcdsaVerifier` in `:core:security`). Both MUST implement ECDSA P-256 with SHA-256 over an ASN.1/DER
 * signature and an X.509 SubjectPublicKeyInfo key, and both MUST return false (never throw) for malformed input.
 * A golden signed vector pins that the two adapters agree (`HUB-GV-*` tests).
 */
@FlashInternalApi
public fun interface EcdsaVerifier {
    /**
     * @param publicKeySpki the signer's X.509 SubjectPublicKeyInfo bytes.
     * @param data the exact bytes that were signed.
     * @param signature the DER-encoded ECDSA signature.
     */
    public fun verify(publicKeySpki: ByteArray, data: ByteArray, signature: ByteArray): Boolean
}
