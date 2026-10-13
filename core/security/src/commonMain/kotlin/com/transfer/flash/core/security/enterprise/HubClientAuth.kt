package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.certs.toHubHex
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashFingerprint
import com.transfer.flash.core.security.crypto.secureRandomBytes

/**
 * Client-side helpers for the hub authentication forms of `:core:hub-protocol` ([HubAuth]): they sign the canonical
 * bytes with the device identity key and mint nonces from the platform CSPRNG. Kept here (not in hub-protocol)
 * because that module does no cryptography of its own.
 */
@FlashInternalApi
public object HubClientAuth {

    /** A fresh single-use client nonce (18 random bytes, Base64). */
    public fun newNonce(): String = Base64.encode(secureRandomBytes(NONCE_BYTES))

    /** Upper-case hex SHA-256 of the Base64 SPKI [rootPublicKeySpki]: the `serverId` / invite `root=` value. */
    public fun rootFingerprintHex(rootPublicKeySpki: String): String =
        FlashFingerprint.fingerprint(Base64.decode(rootPublicKeySpki)).toHubHex().uppercase()

    /** Base64 signature for the relay HELLO / REST verify ([HubAuth.authSigningBytes]). */
    public fun signAuth(
        crypto: FlashCrypto,
        serverId: String,
        serverNonce: String,
        clientNonce: String,
        deviceId: String,
        certSerial: Long,
    ): String = Base64.encode(
        crypto.sign(HubAuth.authSigningBytes(serverId, serverNonce, clientNonce, deviceId, certSerial)),
    )

    /** Base64 proof of possession for enrolment ([HubAuth.enrollProofBytes]). */
    public fun signEnrollProof(
        crypto: FlashCrypto,
        token: String,
        deviceId: String,
        subjectPublicKey: String,
        serverId: String,
    ): String = Base64.encode(crypto.sign(HubAuth.enrollProofBytes(token, deviceId, subjectPublicKey, serverId)))

    /** Base64 signature for renewal ([HubAuth.renewProofBytes]). */
    public fun signRenew(
        crypto: FlashCrypto,
        serverId: String,
        serverNonce: String,
        clientNonce: String,
        deviceId: String,
        currentCertSerial: Long,
        subjectPublicKey: String,
    ): String = Base64.encode(
        crypto.sign(HubAuth.renewProofBytes(serverId, serverNonce, clientNonce, deviceId, currentCertSerial, subjectPublicKey)),
    )

    private const val NONCE_BYTES: Int = 18
}
