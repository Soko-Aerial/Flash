package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.security.crypto.FlashCrypto

/**
 * Delegated Authority Certificate (E2b, ENTERPRISE-HYBRID-PLAN).
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
@FlashInternalApi
public data class DelegationCert(
    public val rootPublicKey: String,
    public val delegatePublicKey: String,
    public val scopes: Set<String>,
    public val validFrom: Long,
    public val validTo: Long,
    public val maxDevices: Int,
    public val serial: Long,
    public val signature: String,
) {
    /**
     * Serializes the delegation cert fields into canonical bytes (tag `flash-delegation-v1`)
     * for signature creation and verification.
     */
    public fun canonicalBytes(): ByteArray {
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
    public fun verifySignature(crypto: FlashCrypto): Boolean {
        if (signature.isBlank() || rootPublicKey.isBlank()) return false
        return try {
            val pubKeyBytes = Base64.decode(rootPublicKey)
            val sigBytes = Base64.decode(signature)
            crypto.verify(
                signature = sigBytes,
                data = canonicalBytes(),
                peerPublicKey = pubKeyBytes,
            )
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if the delegation certificate is currently valid according to its time window.
     */
    public fun isValidAt(timestamp: Long): Boolean {
        return timestamp in validFrom..validTo
    }

    /**
     * Checks whether this delegation grants permission for [scope].
     * Scopes can be wildcards (e.g., "enrol:*" matches "enrol:member").
     */
    public fun hasScope(scope: String): Boolean {
        if (scopes.contains(scope) || scopes.contains("*")) return true
        val prefix = scope.substringBefore(":", "")
        return if (prefix.isNotEmpty()) scopes.contains("$prefix:*") else false
    }

    public companion object {
        public const val TAG: String = "flash-delegation-v1"
        public const val SCOPE_ENROL_MEMBER: String = "enrol:member"
        public const val SCOPE_ENROL_DEPT_ADMIN: String = "enrol:dept_admin"
        public const val SCOPE_ENROL_ADMIN: String = "enrol:admin"
        public const val SCOPE_REVOKE_MEMBER: String = "revoke:member"
    }
}
