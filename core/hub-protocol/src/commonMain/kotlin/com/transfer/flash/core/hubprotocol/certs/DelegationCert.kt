package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import kotlinx.serialization.Serializable

/**
 * Delegated Authority Certificate (E2b, ENTERPRISE-HYBRID-PLAN): lets the (offline) organisation root key delegate
 * bounded trust to a server or departmental authority.
 *
 * - [rootPublicKey]: Base64 SPKI of the organisation root key (the signer).
 * - [delegatePublicKey]: Base64 SPKI of the delegated key (for example the hub's signing key).
 * - [scopes]: allowed actions (`enrol:member`, `revoke:member`, ...). Matching rules: [HubScopes].
 * - [validFrom] / [validTo]: validity window in epoch milliseconds. No leaf certificate may extend past it.
 * - [maxDevices]: how many device certificates this delegation may issue.
 * - [serial]: delegation serial. A compromised delegate key is revoked by listing this serial in
 *   [RevocationList.revokedDelegationSerials] (ADR-110).
 * - [signature]: ECDSA P-256 signature by the root over [canonicalBytes].
 */
@FlashInternalApi
@Serializable
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
    /** Canonical bytes (tag `flash-delegation-v1`) the root signs. [signature] is NOT part of them. */
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

    /** True when [signature] is a valid signature by [rootPublicKey] over [canonicalBytes]. Never throws. */
    public fun verifySignature(verifier: EcdsaVerifier): Boolean {
        if (signature.isBlank() || rootPublicKey.isBlank()) return false
        return try {
            verifier.verify(
                publicKeySpki = Base64.decode(rootPublicKey),
                data = canonicalBytes(),
                signature = Base64.decode(signature),
            )
        } catch (_: Throwable) {
            false
        }
    }

    /** True when [timestamp] is inside the window, with [clockSkewToleranceMs] at BOTH ends. */
    public fun isValidAt(
        timestamp: Long,
        clockSkewToleranceMs: Long = DEFAULT_CLOCK_SKEW_MS,
    ): Boolean = timestamp >= validFrom - clockSkewToleranceMs && timestamp <= validTo + clockSkewToleranceMs

    /** Scope check with the single wildcard rule of [HubScopes.grants]. */
    public fun hasScope(scope: String): Boolean = HubScopes.grants(scopes, scope)

    public companion object {
        public const val TAG: String = "flash-delegation-v1"

        public const val SCOPE_ENROL_MEMBER: String = HubScopes.ENROL_MEMBER
        public const val SCOPE_ENROL_DEPT_ADMIN: String = HubScopes.ENROL_DEPT_ADMIN
        public const val SCOPE_ENROL_ADMIN: String = HubScopes.ENROL_ADMIN
        public const val SCOPE_REVOKE_MEMBER: String = HubScopes.REVOKE_MEMBER

        public const val DEFAULT_CLOCK_SKEW_MS: Long = DeviceCert.DEFAULT_CLOCK_SKEW_MS
    }
}
