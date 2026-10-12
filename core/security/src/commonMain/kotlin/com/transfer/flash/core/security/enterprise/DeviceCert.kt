package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.security.crypto.FlashCrypto

/**
 * Device Certificate model (E2, E2b, ENTERPRISE-HYBRID-PLAN).
 *
 * Binds a device identity to a role and department within the organization realm:
 * - [deviceId]: Hardware / installation unique identifier.
 * - [subjectPublicKey]: Base64-encoded X.509 SPKI identity key of the device.
 * - [role]: Assigned role ("member", "admin", "dept_admin").
 * - [department]: Optional department or unit ID.
 * - [validFrom] and [validTo]: Validity time window in epoch milliseconds.
 * - [serial]: Monotonic device certificate serial number.
 * - [issuerPublicKey]: Base64-encoded X.509 SPKI of the certificate signer (Root or Delegated Key).
 * - [issuerSignature]: ECDSA P-256 signature by the issuer key over [canonicalBytes].
 */
@FlashInternalApi
public data class DeviceCert(
    public val deviceId: String,
    public val subjectPublicKey: String,
    public val role: String,
    public val department: String?,
    public val validFrom: Long,
    public val validTo: Long,
    public val serial: Long,
    public val issuerPublicKey: String,
    public val issuerSignature: String,
) {
    /**
     * Serializes the device cert fields into canonical bytes (tag `flash-device-cert-v1`)
     * for signature creation and verification.
     */
    public fun canonicalBytes(): ByteArray {
        return AuthCanonicalWriter(TAG)
            .text(deviceId)
            .text(subjectPublicKey)
            .text(role)
            .text(department.orEmpty())
            .long(validFrom)
            .long(validTo)
            .long(serial)
            .text(issuerPublicKey)
            .build()
    }

    /**
     * Verifies that the certificate signature was produced by [issuerPublicKey].
     */
    public fun verifySignature(crypto: FlashCrypto): Boolean {
        if (issuerSignature.isBlank() || issuerPublicKey.isBlank()) return false
        return try {
            val pubKeyBytes = Base64.decode(issuerPublicKey)
            val sigBytes = Base64.decode(issuerSignature)
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
     * Checks if this certificate is within its validity window at [timestamp].
     * Includes a default [clockSkewToleranceMs] window (default 60s) to prevent rejected
     * handshakes on freshly minted certificates or slight client/server clock drifts.
     */
    public fun isValidAt(
        timestamp: Long,
        clockSkewToleranceMs: Long = DEFAULT_CLOCK_SKEW_MS,
    ): Boolean {
        return timestamp in (validFrom - clockSkewToleranceMs)..validTo
    }

    /**
     * Verifies the full certificate chain up to the organization's [rootPublicKey].
     *
     * @param delegation Optional delegation certificate if issued by a delegated server/authority.
     * @param rootPublicKey Expected organization root public key (Base64 SPKI).
     * @param crypto [FlashCrypto] implementation used to perform verification.
     * @param timestamp Verification time in epoch millis.
     * @return true if both the device cert and the delegation chain are valid and authorized.
     */
    public fun verifyChain(
        delegation: DelegationCert?,
        rootPublicKey: String,
        crypto: FlashCrypto,
        timestamp: Long,
    ): Boolean {
        if (!isValidAt(timestamp)) return false
        if (!verifySignature(crypto)) return false

        return if (delegation == null) {
            // Direct root issuance: issuer must be the root key itself
            issuerPublicKey == rootPublicKey
        } else {
            // Delegated issuance (E2b):
            // 1. Delegation cert must bind to the expected root public key
            if (delegation.rootPublicKey != rootPublicKey) return false
            // 2. Delegation cert must be valid at this timestamp
            if (!delegation.isValidAt(timestamp)) return false
            // 3. Delegation signature must verify against root key
            if (!delegation.verifySignature(crypto)) return false
            // 4. Issuer of this device cert must match the delegate key
            if (issuerPublicKey != delegation.delegatePublicKey) return false
            // 5. Delegation scope check: a delegated server cannot issue roles it lacks scope for.
            // Under E2b: delegations may not grant REALM admin roles ("admin").
            if (role == ROLE_ADMIN && !delegation.hasScope(DelegationCert.SCOPE_ENROL_ADMIN)) {
                return false
            }
            // Check that delegation has scope to enrol this role (e.g. "enrol:member")
            delegation.hasScope("enrol:$role")
        }
    }

    public companion object {
        public const val TAG: String = "flash-device-cert-v1"

        public const val ROLE_MEMBER: String = "member"
        public const val ROLE_ADMIN: String = "admin"
        public const val ROLE_DEPT_ADMIN: String = "dept_admin"

        /** Default validity for client device certificates: 7 days (D-E9). */
        public const val DEFAULT_VALIDITY_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** Default clock skew tolerance buffer: 60 seconds. */
        public const val DEFAULT_CLOCK_SKEW_MS: Long = 60_000L
    }
}
