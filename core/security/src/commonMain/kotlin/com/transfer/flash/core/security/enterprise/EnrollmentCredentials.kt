package com.transfer.flash.core.security.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi

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
) {
    public val isExpired: Boolean get() = !deviceCert.isValidAt(enrolledAtMs)
    public val role: String get() = deviceCert.role
    public val department: String? get() = deviceCert.department
    public val deviceId: String get() = deviceCert.deviceId
}
