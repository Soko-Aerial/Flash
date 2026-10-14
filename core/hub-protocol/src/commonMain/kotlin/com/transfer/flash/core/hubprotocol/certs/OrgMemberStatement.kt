package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import kotlinx.serialization.Serializable

/**
 * Organisation Member Statement (Plan B, ADR-134): cryptographically binds a device identity
 * to an organisation role and department, issued and signed by the organisation's delegated authority.
 *
 * - [deviceId]: The hub device identifier.
 * - [subjectPublicKey]: Base64 X.509 SPKI identity key of the device.
 * - [departmentId]: Optional department ID ("" when none).
 * - [role]: Role inside the organisation ([HubRoles.ALL]).
 * - [issuedAtMs]: Epoch timestamp in milliseconds when the statement was issued.
 * - [statementSerial]: Monotonic sequence number incremented on enrolment, role, or department changes.
 * - [issuerSignature]: ECDSA P-256 signature by the delegate authority over [canonicalBytes].
 */
@FlashInternalApi
@Serializable
public data class OrgMemberStatement(
    public val deviceId: String,
    public val subjectPublicKey: String,
    public val departmentId: String = "",
    public val role: String = HubRoles.MEMBER,
    public val issuedAtMs: Long,
    public val statementSerial: Long,
    public val issuerSignature: String,
) {
    public constructor(
        deviceId: String,
        subjectPublicKey: ByteArray,
        departmentId: String = "",
        role: String = HubRoles.MEMBER,
        issuedAtMs: Long,
        statementSerial: Long,
        issuerSignature: ByteArray,
    ) : this(
        deviceId = deviceId,
        subjectPublicKey = Base64.encode(subjectPublicKey),
        departmentId = departmentId,
        role = role,
        issuedAtMs = issuedAtMs,
        statementSerial = statementSerial,
        issuerSignature = Base64.encode(issuerSignature),
    )

    public val subjectPublicKeyBytes: ByteArray get() = Base64.decode(subjectPublicKey)
    public val issuerSignatureBytes: ByteArray get() = Base64.decode(issuerSignature)

    /** Canonical bytes (tag `flash-org-member-v1`) the issuer signs. [issuerSignature] is NOT part of them. */
    public fun canonicalBytes(): ByteArray =
        AuthCanonicalWriter(TAG)
            .text(deviceId)
            .bytes(subjectPublicKeyBytes)
            .text(departmentId)
            .text(role)
            .long(issuedAtMs)
            .long(statementSerial)
            .build()

    /** True when [issuerSignature] verifies against [issuerPublicKeySpkiBase64]. Never throws. */
    public fun verifySignature(verifier: EcdsaVerifier, issuerPublicKeySpkiBase64: String): Boolean {
        if (issuerSignature.isBlank() || issuerPublicKeySpkiBase64.isBlank()) return false
        return try {
            verifier.verify(
                publicKeySpki = Base64.decode(issuerPublicKeySpkiBase64),
                data = canonicalBytes(),
                signature = Base64.decode(issuerSignature),
            )
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Finds the delegation in [delegationChain] that signed this statement under [rootPublicKey].
     * If [preVerifiedDelegations] is supplied and contains a candidate delegation, skips re-verifying
     * the root signature on that delegation.
     */
    public fun findMatchingDelegation(
        delegationChain: List<DelegationCert>,
        rootPublicKey: String,
        verifier: EcdsaVerifier,
        preVerifiedDelegations: Set<DelegationCert>? = null,
    ): DelegationCert? = delegationChain.firstOrNull { delegation ->
        delegation.rootPublicKey == rootPublicKey &&
            (preVerifiedDelegations?.contains(delegation) == true || delegation.verifySignature(verifier)) &&
            verifySignature(verifier, delegation.delegatePublicKey)
    }

    /**
     * Verifies the statement against the delegation chain and organisation [rootPublicKey].
     *
     * Verification rules (Plan B §B1.1, B0.5):
     * 1. [role] must be valid ([HubRoles.isValid]).
     * 2. [deviceId], [subjectPublicKey], [rootPublicKey], [issuerSignature] must not be blank.
     * 3. [issuedAtMs] must not be more than 5 minutes in the future of [nowMs] (future timestamp guard).
     * 4. Finds matching delegation in [delegationChain] whose `delegatePublicKey` verifies [issuerSignature].
     * 5. The delegation must chain to [rootPublicKey], verify signature, and have been valid at statement issuance time:
     *    `delegation.isValidAt(issuedAtMs)` (B0.5 resolution: delegating offline freshness to the client lease).
     * 6. The delegation must carry appropriate scope for [role].
     * 7. If [revocations] is provided, checks that the device or the delegation serial is not revoked.
     *
     * Fails closed on any error or missing delegation.
     */
    public fun verify(
        delegationChain: List<DelegationCert>,
        rootPublicKey: String,
        verifier: EcdsaVerifier,
        nowMs: Long,
        revocations: RevocationList? = null,
        clockSkewToleranceMs: Long = DEFAULT_CLOCK_SKEW_MS,
        preVerifiedDelegations: Set<DelegationCert>? = null,
    ): ChainStatus = verifyWithDelegation(
        delegationChain = delegationChain,
        rootPublicKey = rootPublicKey,
        verifier = verifier,
        nowMs = nowMs,
        revocations = revocations,
        clockSkewToleranceMs = clockSkewToleranceMs,
        preVerifiedDelegations = preVerifiedDelegations,
    ).first

    public fun verifyWithDelegation(
        delegationChain: List<DelegationCert>,
        rootPublicKey: String,
        verifier: EcdsaVerifier,
        nowMs: Long,
        revocations: RevocationList? = null,
        clockSkewToleranceMs: Long = DEFAULT_CLOCK_SKEW_MS,
        preVerifiedDelegations: Set<DelegationCert>? = null,
    ): Pair<ChainStatus, DelegationCert?> {
        if (!HubRoles.isValid(role)) return ChainStatus.ROLE_INVALID to null
        if (deviceId.isBlank() || subjectPublicKey.isBlank() || rootPublicKey.isBlank() || issuerSignature.isBlank()) {
            return ChainStatus.MALFORMED to null
        }
        // Future timestamp guard: reject issuedAtMs > nowMs + 5 minutes
        if (issuedAtMs > nowMs + MAX_FUTURE_ISSUED_AT_MS) {
            return ChainStatus.NOT_YET_VALID to null
        }

        // Find the delegation in chain that signed this statement
        val matchingDelegation = findMatchingDelegation(
            delegationChain = delegationChain,
            rootPublicKey = rootPublicKey,
            verifier = verifier,
            preVerifiedDelegations = preVerifiedDelegations,
        ) ?: return ChainStatus.DELEGATION_INVALID to null

        // Check scope on delegation
        val neededScope = HubRoles.enrolScope(role)
        val allowedScope = if (role == HubRoles.ADMIN) {
            HubScopes.grantsExplicitly(matchingDelegation.scopes, neededScope)
        } else {
            matchingDelegation.hasScope(neededScope)
        }
        if (!allowedScope) return ChainStatus.SCOPE_DENIED to matchingDelegation

        // B0.5 resolution: delegation must be valid at statement issue time
        if (!matchingDelegation.isValidAt(issuedAtMs, clockSkewToleranceMs)) {
            return ChainStatus.DELEGATION_EXPIRED to matchingDelegation
        }

        // Revocation check if list provided
        if (revocations != null) {
            if (revocations.revokesDevice(deviceId)) return ChainStatus.REVOKED to matchingDelegation
            if (revocations.isDelegationRevoked(matchingDelegation.serial)) return ChainStatus.REVOKED to matchingDelegation
        }

        return ChainStatus.OK to matchingDelegation
    }

    public companion object {
        public const val TAG: String = "flash-org-member-v1"
        public const val MAX_FUTURE_ISSUED_AT_MS: Long = 5L * 60 * 1000 // 5 minutes
        public const val DEFAULT_CLOCK_SKEW_MS: Long = 60_000L
    }
}
