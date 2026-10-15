package com.transfer.flash.core.hubprotocol.certs

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.protocol.Base64
import kotlinx.serialization.Serializable

/**
 * Hub-signed roster of one hub group (Plan B Phase B4, section 10, ADR-135; tag `flash-hub-roster-v1`).
 *
 * It lets phones that are on the same network, with the hub unreachable, agree on WHO is in a hub group: a frame
 * is accepted only from a device that is in the newest valid statement and is an organisation colleague.
 *
 * - [version]: monotonic per group, assigned by the hub, +1 on every member or admin change. A lower version never replaces a higher one.
 * - [memberDeviceIds] / [adminDeviceIds]: sorted, unique lists (verification rejects anything else, so the signed bytes are canonical).
 * - [issuedAtMs] / [validUntilMs]: the statement is a lease; after [validUntilMs] a device does not use it.
 * - [issuerSignature]: Base64 ECDSA P-256 signature by the hub's delegate key over [canonicalBytes].
 */
@FlashInternalApi
@Serializable
public data class GroupRosterStatement(
    public val groupId: String,
    public val groupName: String = "",
    public val departmentId: String = "",
    public val version: Long,
    public val memberDeviceIds: List<String>,
    public val adminDeviceIds: List<String> = emptyList(),
    public val issuedAtMs: Long,
    public val validUntilMs: Long,
    public val issuerSignature: String = "",
) {
    /** Canonical bytes the issuer signs ([issuerSignature] is not part of them). */
    public fun canonicalBytes(): ByteArray =
        AuthCanonicalWriter(TAG)
            .text(groupId)
            .text(groupName)
            .text(departmentId)
            .long(version)
            .int(memberDeviceIds.size).also { w -> memberDeviceIds.forEach { w.text(it) } }
            .int(adminDeviceIds.size).also { w -> adminDeviceIds.forEach { w.text(it) } }
            .long(issuedAtMs)
            .long(validUntilMs)
            .build()

    public fun isMember(deviceId: String): Boolean = deviceId in memberDeviceIds

    /** True while the statement may be used: not before it was issued (with [skewMs]) and not after [validUntilMs]. */
    public fun isCurrent(nowMs: Long, skewMs: Long = DEFAULT_CLOCK_SKEW_MS): Boolean =
        nowMs >= issuedAtMs - skewMs && nowMs <= validUntilMs

    /** Verifies shape, size, signature chain and time. Fails closed; never throws. */
    public fun verify(
        delegationChain: List<DelegationCert>,
        rootPublicKey: String,
        verifier: EcdsaVerifier,
        nowMs: Long,
        revocations: RevocationList? = null,
        preVerifiedDelegations: Set<DelegationCert>? = null,
    ): RosterStatus {
        if (groupId.isBlank() || rootPublicKey.isBlank() || issuerSignature.isBlank() || version < 0L) return RosterStatus.MALFORMED
        if (memberDeviceIds.any { it.isBlank() } || adminDeviceIds.any { it.isBlank() }) return RosterStatus.MALFORMED
        if (!isSortedUnique(memberDeviceIds) || !isSortedUnique(adminDeviceIds)) return RosterStatus.MALFORMED
        if (memberDeviceIds.size > MAX_MEMBERS) return RosterStatus.TOO_LARGE
        if (canonicalBytes().size > MAX_CANONICAL_BYTES) return RosterStatus.TOO_LARGE
        if (validUntilMs <= issuedAtMs) return RosterStatus.MALFORMED
        if (issuedAtMs > nowMs + OrgMemberStatement.MAX_FUTURE_ISSUED_AT_MS) return RosterStatus.NOT_YET_VALID
        if (nowMs > validUntilMs) return RosterStatus.EXPIRED

        val signed = try {
            Base64.decode(issuerSignature)
        } catch (_: Throwable) {
            return RosterStatus.MALFORMED
        }
        val data = canonicalBytes()
        val delegation = delegationChain.firstOrNull { d ->
            d.rootPublicKey == rootPublicKey &&
                (preVerifiedDelegations?.contains(d) == true || d.verifySignature(verifier)) &&
                try {
                    verifier.verify(Base64.decode(d.delegatePublicKey), data, signed)
                } catch (_: Throwable) {
                    false
                }
        } ?: return RosterStatus.DELEGATION_INVALID
        if (!delegation.hasScope(HubRoles.enrolScope(HubRoles.MEMBER))) return RosterStatus.SCOPE_DENIED
        if (!delegation.isValidAt(issuedAtMs, OrgMemberStatement.DEFAULT_CLOCK_SKEW_MS)) return RosterStatus.DELEGATION_EXPIRED
        if (revocations != null && revocations.isDelegationRevoked(delegation.serial)) return RosterStatus.REVOKED
        return RosterStatus.OK
    }

    private fun isSortedUnique(ids: List<String>): Boolean =
        ids.zipWithNext().all { (a, b) -> a < b }

    public companion object {
        public const val TAG: String = "flash-hub-roster-v1"

        /** Plan section 10.2: offline group chat is supported up to this many members. */
        public const val MAX_MEMBERS: Int = 200
        public const val MAX_CANONICAL_BYTES: Int = 16 * 1024
        public const val DEFAULT_CLOCK_SKEW_MS: Long = 60_000L
    }
}

/** Result of [GroupRosterStatement.verify]. Only [OK] may be used. */
@FlashInternalApi
public enum class RosterStatus {
    OK,
    MALFORMED,
    TOO_LARGE,
    NOT_YET_VALID,
    EXPIRED,
    DELEGATION_INVALID,
    DELEGATION_EXPIRED,
    SCOPE_DENIED,
    REVOKED,
}
