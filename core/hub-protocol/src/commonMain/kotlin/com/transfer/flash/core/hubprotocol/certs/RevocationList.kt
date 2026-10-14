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
 *
 * **Hashed device ids (`flash-revocation-v3`, ADR-129, FO-13).** `GET /api/v1/revocations` is public, so a list that names
 * device ids in the clear tells anyone who can reach the hub which devices were removed. A list with a non-blank [audience]
 * carries [revokedDeviceHashes] instead of [revokedDeviceIds]: each entry is [deviceHash] = lower-case hex
 * `SHA-256(canonical("flash-revoked-device-v1", audience, deviceId))`. A device hashes its own id and looks for it
 * ([isRevoked]); an outsider sees only hashes. [audience] is the organisation's server id (upper-case hex SHA-256 of the root
 * public key, [audienceOf]) and is signed, so a list of another organisation never verifies as ours ([verifyTrusted]).
 * A list with a blank [audience] is the plain v2 format and keeps verifying unchanged; a v2 list may not carry hashes and a
 * v3 list may not carry plain ids ([verifySignature] refuses both).
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
    /** The organisation server id this list is for; non-blank selects the hashed v3 format. Empty: the plain v2 format. */
    public val audience: String = "",
    /** v3 only: [deviceHash] of every revoked device id (lower-case hex). */
    public val revokedDeviceHashes: Set<String> = emptySet(),
) {
    /** True for the hashed v3 format. */
    public val isHashed: Boolean get() = audience.isNotBlank()

    /** Canonical bytes (tag `flash-revocation-v2`, or `flash-revocation-v3` when [isHashed]). [signature] is NOT part of them. */
    public fun canonicalBytes(): ByteArray {
        if (isHashed) {
            return AuthCanonicalWriter(TAG_V3)
                .long(version)
                .longSet(revokedSerials)
                .stringSet(revokedDeviceHashes)
                .longSet(revokedDelegationSerials)
                .text(audience)
                .text(issuerPublicKey)
                .build()
        }
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
        // The two formats never mix: the unsigned half of a mixed list would be ignored by one reader and honoured by another.
        if (isHashed && revokedDeviceIds.isNotEmpty()) return false
        if (!isHashed && revokedDeviceHashes.isNotEmpty()) return false
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
        if (isHashed && audience != audienceOf(rootPublicKey)) return false
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
        revokedSerials.contains(certSerial) || revokesDevice(deviceId)

    /** True when [deviceId] is revoked by id (plain list: the id is listed; hashed list: its [deviceHash] is). */
    public fun revokesDevice(deviceId: String): Boolean =
        if (isHashed) revokedDeviceHashes.contains(deviceHash(audience, deviceId)) else revokedDeviceIds.contains(deviceId)

    public fun isRevoked(cert: DeviceCert): Boolean = isRevoked(cert.serial, cert.deviceId)

    public fun isDelegationRevoked(delegationSerial: Long): Boolean = revokedDelegationSerials.contains(delegationSerial)

    public companion object {
        public const val TAG: String = "flash-revocation-v2"
        public const val TAG_V3: String = "flash-revocation-v3"
        private const val DEVICE_HASH_DOMAIN: String = "flash-revoked-device-v1"

        /** The revoked-device entry for [deviceId] in a list for [audience]: lower-case hex of a length-prefixed SHA-256 (no bare concatenation, SEC-14). */
        public fun deviceHash(audience: String, deviceId: String): String =
            Sha256.digest(AuthCanonicalWriter(DEVICE_HASH_DOMAIN).text(audience).text(deviceId).build()).toHubHex()

        /** The audience of the organisation whose root key is [rootPublicKey] (Base64 SPKI): the server id, upper-case hex SHA-256 of the key bytes. */
        public fun audienceOf(rootPublicKey: String): String =
            Sha256.digest(Base64.decode(rootPublicKey)).toHubHex().uppercase()
    }
}
