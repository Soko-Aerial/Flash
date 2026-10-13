package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import kotlinx.serialization.Serializable

/**
 * Monotonic, signed revocation list (E2, ADR-110).
 *
 * - [version]: strictly increasing; a higher version supersedes a lower one ([supersedes]).
 * - [revokedSerials]: revoked DEVICE certificate serials.
 * - [revokedDeviceIds]: revoked device ids (revokes every certificate that id ever held).
 * - [revokedDelegationSerials]: revoked DELEGATION serials. Listing a delegation serial invalidates every device
 *   certificate issued under it, which is how a compromised hub signing key is cut off. Only the ROOT may publish
 *   such an entry ([verifyTrusted]).
 * - [issuerPublicKey]: Base64 SPKI of the signer: the root, or a delegate holding the `revoke:member` scope.
 * - [signature]: ECDSA P-256 signature by the issuer over [canonicalBytes].
 *
 * Wire/tag change: `flash-revocation-v2` adds [revokedDelegationSerials] to the signed bytes. The v1 tag was never
 * used by any shipped list (the hub never produced one: SEC-05), so there is no v1 list in the field to keep verifying.
 */
@FlashInternalApi
@Serializable
public data class RevocationList(
    public val version: Long,
    public val revokedSerials: Set<Long> = emptySet(),
    public val revokedDeviceIds: Set<String> = emptySet(),
    public val revokedDelegationSerials: Set<Long> = emptySet(),
    public val issuerPublicKey: String,
    public val signature: String,
) {
    /** Canonical bytes (tag `flash-revocation-v2`). [signature] is NOT part of them. */
    public fun canonicalBytes(): ByteArray {
        return AuthCanonicalWriter(TAG)
            .long(version)
            .longSet(revokedSerials)
            .stringSet(revokedDeviceIds)
            .longSet(revokedDelegationSerials)
            .text(issuerPublicKey)
            .build()
    }

    /** True when [signature] verifies against [issuerPublicKey]. Says nothing about whether the issuer is trusted. */
    public fun verifySignature(verifier: EcdsaVerifier): Boolean {
        if (signature.isBlank() || issuerPublicKey.isBlank()) return false
        return try {
            verifier.verify(
                publicKeySpki = Base64.decode(issuerPublicKey),
                data = canonicalBytes(),
                signature = Base64.decode(signature),
            )
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Full "may I act on this list" check: the signature verifies AND the issuer is trusted.
     * - Issued by [rootPublicKey]: always acceptable.
     * - Issued by a delegate: [delegation] must be the delegation for that key, bind to [rootPublicKey], verify, be
     *   valid at [nowMs], carry the `revoke:member` scope, and the list must NOT revoke delegations
     *   ([revokedDelegationSerials] empty): a server may revoke devices, never the authority above itself.
     */
    public fun verifyTrusted(
        rootPublicKey: String,
        verifier: EcdsaVerifier,
        nowMs: Long,
        delegation: DelegationCert? = null,
        clockSkewToleranceMs: Long = DeviceCert.DEFAULT_CLOCK_SKEW_MS,
    ): Boolean {
        if (!verifySignature(verifier)) return false
        if (issuerPublicKey == rootPublicKey) return true
        if (delegation == null) return false
        if (revokedDelegationSerials.isNotEmpty()) return false
        if (delegation.rootPublicKey != rootPublicKey) return false
        if (delegation.delegatePublicKey != issuerPublicKey) return false
        if (!delegation.verifySignature(verifier)) return false
        if (!delegation.isValidAt(nowMs, clockSkewToleranceMs)) return false
        return delegation.hasScope(HubScopes.REVOKE_MEMBER)
    }

    /** True when this list replaces [other] (strictly higher version). */
    public fun supersedes(other: RevocationList?): Boolean = other == null || version > other.version

    /** True when the certificate serial or the device id is revoked. */
    public fun isRevoked(certSerial: Long, deviceId: String): Boolean =
        revokedSerials.contains(certSerial) || revokedDeviceIds.contains(deviceId)

    public fun isRevoked(cert: DeviceCert): Boolean = isRevoked(cert.serial, cert.deviceId)

    public fun isDelegationRevoked(delegationSerial: Long): Boolean = revokedDelegationSerials.contains(delegationSerial)

    public companion object {
        public const val TAG: String = "flash-revocation-v2"
    }
}
