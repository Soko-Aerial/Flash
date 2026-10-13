package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.RenewalPolicy

/**
 * Persisted enterprise credentials obtained through successful enrollment with an Enterprise Hub.
 */
@FlashInternalApi
public data class EnrollmentCredentials(
    public val hubUrl: String,
    public val rootFingerprint: String,
    public val rootPublicKey: String,
    public val deviceCert: DeviceCert,
    public val delegationChain: List<DelegationCert> = emptyList(),
    public val enrolledAtMs: Long = 0L,
    /** The hub's display name as it said at enrolment/renewal; empty when it did not say. Shown in Settings. */
    public val hubName: String = "",
) {
    /**
     * True when the device certificate is past its validity NOW (it used to compare against [enrolledAtMs], which made
     * an expired credential read as valid for ever: CLI-04). Evaluated against the system clock on every read; use
     * [isExpiredAt] with an injected time in tests.
     */
    public val isExpired: Boolean get() = isExpiredAt(SystemTimeSource.nowMs())

    /** True when the certificate is past `validTo` at [nowMs] (no clock-skew tolerance: this is a "stop using it" test). */
    public fun isExpiredAt(nowMs: Long): Boolean = !deviceCert.isValidAt(nowMs, clockSkewToleranceMs = 0L)

    /** True when the client should ask the hub for a fresh certificate (inside the renewal window or already past it). */
    public fun needsRenewalAt(nowMs: Long): Boolean = RenewalPolicy.shouldRenew(deviceCert, nowMs)

    public val role: String get() = deviceCert.role
    public val department: String? get() = deviceCert.department
    public val deviceId: String get() = deviceCert.deviceId
}
