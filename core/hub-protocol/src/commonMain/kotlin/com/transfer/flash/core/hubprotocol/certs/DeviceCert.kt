package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import kotlinx.serialization.Serializable

/**
 * Device Certificate (E2, E2b, ENTERPRISE-HYBRID-PLAN): binds a device identity to a role and department inside the
 * organisation realm.
 *
 * - [deviceId]: installation-unique device identifier.
 * - [subjectPublicKey]: Base64 X.509 SPKI identity key of the device (the key that signs handshakes).
 * - [role]: one of [HubRoles.ALL]; anything else never verifies.
 * - [department]: optional department / unit id.
 * - [validFrom] / [validTo]: validity window in epoch milliseconds.
 * - [serial]: certificate serial, unique per issuance (renewal issues a NEW serial).
 * - [issuerPublicKey]: Base64 SPKI of the signer (the root key, or the delegated server key).
 * - [issuerSignature]: ECDSA P-256 signature by the issuer over [canonicalBytes].
 *
 * `canonicalBytes()` is pinned by golden vectors (`DeviceCert golden vector` test); changing it breaks every issued
 * certificate and requires a new tag.
 */
@FlashInternalApi
@Serializable
public data class DeviceCert(
    public val deviceId: String,
    public val subjectPublicKey: String,
    public val role: String,
    public val department: String? = null,
    public val validFrom: Long,
    public val validTo: Long,
    public val serial: Long,
    public val issuerPublicKey: String,
    public val issuerSignature: String,
) {
    /** Canonical bytes (tag `flash-device-cert-v1`) the issuer signs. [issuerSignature] is NOT part of them. */
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

    /** True when [issuerSignature] is a valid signature by [issuerPublicKey] over [canonicalBytes]. Never throws. */
    public fun verifySignature(verifier: EcdsaVerifier): Boolean {
        if (issuerSignature.isBlank() || issuerPublicKey.isBlank()) return false
        return try {
            verifier.verify(
                publicKeySpki = Base64.decode(issuerPublicKey),
                data = canonicalBytes(),
                signature = Base64.decode(issuerSignature),
            )
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * True when [timestamp] is inside the validity window, with [clockSkewToleranceMs] of tolerance at BOTH ends
     * (SEC-18: the previous code tolerated skew only before `validFrom`).
     */
    public fun isValidAt(
        timestamp: Long,
        clockSkewToleranceMs: Long = DEFAULT_CLOCK_SKEW_MS,
    ): Boolean = timestamp >= validFrom - clockSkewToleranceMs && timestamp <= validTo + clockSkewToleranceMs

    /** The last instant this certificate can be honoured: its own `validTo`, clamped to the delegation's. */
    public fun effectiveValidTo(delegation: DelegationCert?): Long =
        if (delegation == null) validTo else minOf(validTo, delegation.validTo)

    /**
     * Full chain check up to the organisation [rootPublicKey]. Order: role, signature, issuer trust and scope,
     * revocation, then time. A revoked certificate therefore reports [ChainStatus.REVOKED] even when it is also expired.
     *
     * @param delegation the delegation above this certificate, or null for a certificate signed by the root itself.
     * @param revocations a verified revocation list, if the caller has one (a list is NOT verified here).
     * @param clockSkewToleranceMs tolerance at both ends of the leaf and delegation windows.
     * @param expiryGraceMs extra time after the LEAF's `validTo` that is still accepted (renewal only; the
     *   delegation window never gets grace).
     */
    public fun checkChain(
        delegation: DelegationCert?,
        rootPublicKey: String,
        verifier: EcdsaVerifier,
        timestamp: Long,
        revocations: RevocationList? = null,
        clockSkewToleranceMs: Long = DEFAULT_CLOCK_SKEW_MS,
        expiryGraceMs: Long = 0L,
    ): ChainStatus {
        if (!HubRoles.isValid(role)) return ChainStatus.ROLE_INVALID
        if (deviceId.isBlank() || subjectPublicKey.isBlank() || rootPublicKey.isBlank()) return ChainStatus.MALFORMED
        if (!verifySignature(verifier)) return ChainStatus.BAD_SIGNATURE

        if (delegation == null) {
            if (issuerPublicKey != rootPublicKey) return ChainStatus.UNTRUSTED_ISSUER
        } else {
            if (delegation.rootPublicKey != rootPublicKey) return ChainStatus.DELEGATION_INVALID
            if (!delegation.verifySignature(verifier)) return ChainStatus.DELEGATION_INVALID
            if (issuerPublicKey != delegation.delegatePublicKey) return ChainStatus.UNTRUSTED_ISSUER
            val needed = HubRoles.enrolScope(role)
            val allowed = if (role == HubRoles.ADMIN) {
                HubScopes.grantsExplicitly(delegation.scopes, needed)
            } else {
                delegation.hasScope(needed)
            }
            if (!allowed) return ChainStatus.SCOPE_DENIED
        }

        if (revocations != null) {
            if (revocations.isRevoked(this)) return ChainStatus.REVOKED
            if (delegation != null && revocations.isDelegationRevoked(delegation.serial)) return ChainStatus.REVOKED
        }

        if (timestamp < validFrom - clockSkewToleranceMs) return ChainStatus.NOT_YET_VALID
        if (timestamp > validTo + clockSkewToleranceMs + expiryGraceMs) return ChainStatus.EXPIRED

        if (delegation != null) {
            if (timestamp < delegation.validFrom - clockSkewToleranceMs) return ChainStatus.DELEGATION_INVALID
            if (timestamp > delegation.validTo + clockSkewToleranceMs) return ChainStatus.DELEGATION_EXPIRED
            // The leaf may not outlive (or predate) the authority that signed it.
            if (validTo > delegation.validTo) return ChainStatus.LEAF_OUTSIDE_DELEGATION
            if (validFrom < delegation.validFrom - clockSkewToleranceMs) return ChainStatus.LEAF_OUTSIDE_DELEGATION
        }
        return ChainStatus.OK
    }

    /** Convenience: [checkChain] is [ChainStatus.OK]. */
    public fun verifyChain(
        delegation: DelegationCert?,
        rootPublicKey: String,
        verifier: EcdsaVerifier,
        timestamp: Long,
        revocations: RevocationList? = null,
        clockSkewToleranceMs: Long = DEFAULT_CLOCK_SKEW_MS,
        expiryGraceMs: Long = 0L,
    ): Boolean = checkChain(
        delegation, rootPublicKey, verifier, timestamp, revocations, clockSkewToleranceMs, expiryGraceMs,
    ) == ChainStatus.OK

    public companion object {
        public const val TAG: String = "flash-device-cert-v1"

        public const val ROLE_MEMBER: String = HubRoles.MEMBER
        public const val ROLE_ADMIN: String = HubRoles.ADMIN
        public const val ROLE_DEPT_ADMIN: String = HubRoles.DEPT_ADMIN

        /** Default validity for client device certificates: 7 days (D-E9). */
        public const val DEFAULT_VALIDITY_MS: Long = 7L * 24 * 60 * 60 * 1000

        /** Default clock skew tolerance buffer: 60 seconds. */
        public const val DEFAULT_CLOCK_SKEW_MS: Long = 60_000L

        /** Issuers back-date `validFrom` by this much so a freshly minted certificate verifies on a slightly slow device clock. */
        public const val ISSUE_BACKDATE_MS: Long = 10_000L
    }
}

/** Why a certificate chain was accepted or rejected. [OK] is the only success. */
@FlashInternalApi
public enum class ChainStatus {
    OK,

    /** Empty ids/keys. */
    MALFORMED,

    /** The role is not in [HubRoles.ALL]. */
    ROLE_INVALID,

    /** The leaf signature does not verify against its stated issuer key. */
    BAD_SIGNATURE,

    /** The leaf was not signed by the root (no delegation) or by the delegate named in the delegation. */
    UNTRUSTED_ISSUER,

    /** The delegation does not bind to the trusted root, does not verify, or is not yet valid. */
    DELEGATION_INVALID,

    /** The delegation window has ended. */
    DELEGATION_EXPIRED,

    /** The delegation lacks the `enrol:<role>` scope (admin needs the literal `enrol:admin`). */
    SCOPE_DENIED,

    /** The leaf's window is not contained in the delegation's. */
    LEAF_OUTSIDE_DELEGATION,

    /** The leaf serial / device id, or the delegation serial, is on the revocation list. */
    REVOKED,

    NOT_YET_VALID,
    EXPIRED;

    public val isOk: Boolean get() = this == OK

    /** The wire error code for a rejected chain ([com.transfer.flash.core.hubprotocol.HubErrorCodes]). */
    public fun toErrorCode(): String = when (this) {
        OK -> ""
        REVOKED -> com.transfer.flash.core.hubprotocol.HubErrorCodes.ERR_REVOKED
        EXPIRED, NOT_YET_VALID, DELEGATION_EXPIRED -> com.transfer.flash.core.hubprotocol.HubErrorCodes.ERR_EXPIRED
        else -> com.transfer.flash.core.hubprotocol.HubErrorCodes.ERR_AUTH
    }
}
