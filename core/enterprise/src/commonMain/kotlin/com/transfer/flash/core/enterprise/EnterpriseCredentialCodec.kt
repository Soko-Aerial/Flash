@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.StoredEnrollment
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials

/**
 * Serialises [EnrollmentCredentials] for local storage with the shared kotlinx.serialization model (CLI-02: this
 * replaces `EnterpriseJsonHelper`, a regex parser that mis-read any value containing an escaped quote or a brace).
 *
 * The stored field names are the ones the previous writer produced, so credentials saved by an older build still
 * load. A value that no longer parses yields null (the device then shows as not enrolled and can re-enrol); it never
 * throws into the store's initialiser.
 */
@FlashInternalApi
public object EnterpriseCredentialCodec {

    public fun serialize(credentials: EnrollmentCredentials): String = HubRestCodec.encode(
        StoredEnrollment(
            hubUrl = credentials.hubUrl,
            rootFingerprint = credentials.rootFingerprint,
            rootPublicKey = credentials.rootPublicKey,
            enrolledAtMs = credentials.enrolledAtMs,
            deviceCert = credentials.deviceCert,
            delegationChain = credentials.delegationChain,
            hubName = credentials.hubName,
        ),
    )

    public fun parse(json: String): EnrollmentCredentials? {
        // The stored blob is local and trusted for size; a wallet of chain certificates stays far below the REST cap.
        val stored = HubRestCodec.decodeOrNull<StoredEnrollment>(json, maxBytes = MAX_STORED_BYTES) ?: return null
        return EnrollmentCredentials(
            hubUrl = stored.hubUrl,
            rootFingerprint = stored.rootFingerprint.uppercase(),
            rootPublicKey = stored.rootPublicKey,
            deviceCert = stored.deviceCert,
            delegationChain = stored.delegationChain,
            enrolledAtMs = if (stored.enrolledAtMs > 0L) stored.enrolledAtMs else stored.deviceCert.validFrom,
            hubName = stored.hubName,
        )
    }

    private const val MAX_STORED_BYTES: Int = 256 * 1024
}
