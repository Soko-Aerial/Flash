package com.transfer.flash.server.auth

import com.transfer.flash.core.security.crypto.FlashCrypto
import java.security.PrivateKey

/**
 * Delegated Authority Certificate (E2b, ADR-085 / ENTERPRISE-HYBRID-PLAN).
 *
 * Allows an offline Root Key to delegate bounded trust to a server or departmental authority:
 * - [rootPublicKey]: Base64-encoded X.509 SPKI of the organization root key.
 * - [delegatePublicKey]: Base64-encoded X.509 SPKI of the delegated key (e.g. server signing key).
 * - [scopes]: Allowed actions (e.g. "enrol:member", "revoke:member").
 * - [validFrom] and [validTo]: Validity time window in epoch milliseconds.
 * - [maxDevices]: Quota limit on how many device certificates this delegation is permitted to issue.
 * - [serial]: Monotonic delegation serial number.
 * - [signature]: ECDSA P-256 signature by the root private key over [canonicalBytes].
 */
data class DelegationCert(
    val rootPublicKey: String,
    val delegatePublicKey: String,
    val scopes: Set<String>,
    val validFrom: Long,
    val validTo: Long,
    val maxDevices: Int,
    val serial: Long,
    val signature: String,
) {
    /**
     * Serializes the delegation cert fields into canonical bytes (tag `flash-delegation-v1`)
     * for signature creation and verification.
     */
    fun canonicalBytes(): ByteArray {
        return AuthCanonicalWriter(TAG)
            .text(rootPublicKey)
            .text(delegatePublicKey)
            .stringSet(scopes)
            .long(validFrom)
            .long(validTo)
            .int(maxDevices)
            .long(serial)
            .build()
    }

    /**
     * Verifies that the delegation certificate's signature is valid against [rootPublicKey].
     */
    fun verifySignature(): Boolean {
        if (signature.isBlank() || rootPublicKey.isBlank()) return false
        return FlashCrypto.verifySignature(
            publicKeyBase64 = rootPublicKey,
            data = canonicalBytes(),
            signatureBase64 = signature,
        )
    }

    /**
     * Checks if the delegation certificate is currently valid according to its time window.
     */
    fun isValidAt(timestamp: Long = System.currentTimeMillis()): Boolean {
        return timestamp in validFrom..validTo
    }

    /**
     * Checks if this delegation grants the requested [scope].
     * Supports wildcard `*` or specific action scopes (e.g. `enrol:member`).
     */
    fun hasScope(scope: String): Boolean {
        return scopes.contains(scope) || scopes.contains("*")
    }

    companion object {
        const val TAG: String = "flash-delegation-v1"

        const val SCOPE_ENROL_MEMBER: String = "enrol:member"
        const val SCOPE_REVOKE_MEMBER: String = "revoke:member"
        const val SCOPE_ENROL_ADMIN: String = "enrol:admin"

        /**
         * Helper to construct and sign a new [DelegationCert] using the root private key.
         */
        fun issue(
            rootPrivateKey: PrivateKey,
            rootPublicKey: String,
            delegatePublicKey: String,
            scopes: Set<String>,
            validFrom: Long,
            validTo: Long,
            maxDevices: Int,
            serial: Long,
        ): DelegationCert {
            val unsigned = DelegationCert(
                rootPublicKey = rootPublicKey,
                delegatePublicKey = delegatePublicKey,
                scopes = scopes,
                validFrom = validFrom,
                validTo = validTo,
                maxDevices = maxDevices,
                serial = serial,
                signature = "",
            )
            val sig = FlashCrypto.sign(rootPrivateKey, unsigned.canonicalBytes())
            return unsigned.copy(signature = sig)
        }
    }
}
