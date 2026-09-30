package com.transfer.flash.core.messaging.protocol

/** What the trust store answered to a vouch. Mirrors the security module's verdict, which `:core:messaging` cannot see. */
public enum class GroupVouchVerdict {
    /** Compatible with what is stored: installed, refreshed, or it replaced an unverified pin. */
    ACCEPT,

    /** The device is paired under another key; pairing wins. */
    CONFLICT_PAIRED,

    /** Another group vouched another key for this device. */
    CONFLICT_VOUCHED,

    /** The fingerprint or group id was blank. */
    INVALID,
}

/**
 * The seam to the host's trust store for vouched members (ADR-044 V2, `docs/group/v2-vouched-trust-plan.md`).
 *
 * An owner-signed active certificate is a vouch for the subject's key. [SignedGroups] installs it as a pin **before**
 * the member connects, so the real device matches it and an impostor is refused by the TLS layer, and withdraws it
 * when the member is tombstoned. A null port means vouching is off: every member must then be paired (V1).
 *
 * The fingerprint arguments are the uppercase hex SHA-256 of the SPKI, colons allowed.
 */
public interface GroupVouching {
    /** What [vouch] would answer, without changing anything. */
    public fun verdict(deviceId: String, fingerprintHex: String, groupId: String): GroupVouchVerdict

    /** Makes [fingerprintHex] the pin of [deviceId] on behalf of [groupId] when [verdict] allows it. */
    public fun vouch(deviceId: String, fingerprintHex: String, groupId: String): GroupVouchVerdict

    /** True when [groupId] already vouches exactly [fingerprintHex] for [deviceId] (nothing left to install). */
    public fun isVouched(deviceId: String, fingerprintHex: String, groupId: String): Boolean

    /** Withdraws [groupId]'s vouch for [deviceId]; the pin goes with the last vouching group unless the device is paired. */
    public fun revoke(deviceId: String, groupId: String)
}
