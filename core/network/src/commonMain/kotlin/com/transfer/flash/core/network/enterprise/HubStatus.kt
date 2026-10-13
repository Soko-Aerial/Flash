@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.network.relay.RelayFailure
import com.transfer.flash.core.network.relay.RelayFailureKind
import com.transfer.flash.core.network.relay.RelaySessionState
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials

/** The one-glance state of the device's link to its organisation hub. */
@FlashInternalApi
public enum class HubLinkState {
    /** No organisation is set up on this device. */
    NOT_ENROLLED,

    /** Enrolled, not connected, nothing scheduled yet. */
    DISCONNECTED,

    CONNECTING,

    /** Opened a connection and proving who this device is. */
    AUTHENTICATING,

    /** Connected and signed in: hub messages flow. */
    ONLINE,

    /** Enrolled but the hub is not reachable; see [HubStatus.offlineReason] and [HubStatus.nextRetryAtMs]. */
    OFFLINE,

    /** Connected, but the certificate is inside its renewal window and renewing keeps failing. */
    CERT_EXPIRING,

    /** The certificate ran out (renewal did not happen in time): the device cannot sign in until it is renewed or enrols again. */
    CERT_EXPIRED,

    /** The organisation removed this device. */
    REVOKED,

    /** The hub that answered is not this organisation's hub. */
    SERVER_MISMATCH,
}

/** Why an enrolled device is [HubLinkState.OFFLINE]. */
@FlashInternalApi
public enum class HubOfflineReason { NO_NETWORK, HUB_NOT_ANSWERING, REFUSED, KICKED, UPDATE_NEEDED, OTHER }

/**
 * What Settings shows about the hub link. Plain data derived by [HubStatusMath]; carries nothing secret (the
 * fingerprint is the public pin).
 */
@FlashInternalApi
public data class HubStatus(
    public val state: HubLinkState = HubLinkState.NOT_ENROLLED,
    public val hubName: String = "",
    /** Host (and port) of the hub, without scheme or path. */
    public val hubHost: String = "",
    /** The organisation's root fingerprint in groups of four ("AB12 CD34 ..."), first 16 digits. */
    public val rootFingerprintShort: String = "",
    public val role: String = "",
    public val department: String = "",
    public val certValidToMs: Long? = null,
    /** Whole days until the certificate ends (0 when it already did or ends today); null when not enrolled. */
    public val certDaysLeft: Int? = null,
    public val lastConnectedAtMs: Long? = null,
    public val nextRetryAtMs: Long? = null,
    public val offlineReason: HubOfflineReason? = null,
    /** True while automatic renewal is due and failing. */
    public val renewalFailed: Boolean = false,
    /** True when unenrolling could not tell the hub yet and will be retried. */
    public val pendingUnenrol: Boolean = false,
    /** A plain sentence when saving the organisation settings failed, else null. */
    public val storageError: String? = null,
) {
    public val isEnrolled: Boolean get() = state != HubLinkState.NOT_ENROLLED && state != HubLinkState.REVOKED
    public val needsAttention: Boolean
        get() = state == HubLinkState.REVOKED || state == HubLinkState.SERVER_MISMATCH ||
            state == HubLinkState.CERT_EXPIRED || state == HubLinkState.CERT_EXPIRING
}

/** Everything [HubStatusMath.derive] looks at. */
@FlashInternalApi
public data class HubStatusInputs(
    public val credentials: EnrollmentCredentials?,
    public val relayState: RelaySessionState,
    public val relayFailure: RelayFailure?,
    public val authFailure: HubAuthFailure?,
    public val renewalFailure: EnrollFailureReason?,
    public val nextRetryAtMs: Long?,
    public val lastConnectedAtMs: Long?,
    /** The organisation removed this device and the credentials were already wiped; kept until the user dismisses it. */
    public val revokedNotice: Boolean,
    public val pendingUnenrol: Boolean,
    public val storageError: String?,
    public val nowMs: Long,
)

/** Pure derivation of [HubStatus]; one place decides the precedence between the possible causes. */
@FlashInternalApi
public object HubStatusMath {

    private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

    public fun derive(i: HubStatusInputs): HubStatus {
        val creds = i.credentials
        val failureKind = i.relayFailure?.kind
        val base = HubStatus(
            hubName = creds?.hubName.orEmpty(),
            hubHost = creds?.let { HubUrlPolicy.parse(it.hubUrl)?.let { p -> if (p.port != null) "${p.host}:${p.port}" else p.host } } ?: "",
            rootFingerprintShort = creds?.let { shortFingerprint(it.rootFingerprint) }.orEmpty(),
            role = creds?.role.orEmpty(),
            department = creds?.department.orEmpty(),
            certValidToMs = creds?.deviceCert?.validTo,
            certDaysLeft = creds?.let { daysLeft(it.deviceCert.validTo, i.nowMs) },
            lastConnectedAtMs = i.lastConnectedAtMs,
            nextRetryAtMs = i.nextRetryAtMs,
            renewalFailed = i.renewalFailure != null && creds?.needsRenewalAt(i.nowMs) == true,
            pendingUnenrol = i.pendingUnenrol,
            storageError = i.storageError,
        )

        // A REVOKED answer only matters while credentials exist; after the wipe only the sticky notice keeps it on screen.
        val revoked = i.revokedNotice ||
            (creds != null && (failureKind == RelayFailureKind.REVOKED || i.authFailure == HubAuthFailure.REVOKED || i.relayState == RelaySessionState.REVOKED))
        if (revoked) return base.copy(state = HubLinkState.REVOKED, nextRetryAtMs = null)
        if (creds == null) return HubStatus(state = HubLinkState.NOT_ENROLLED, pendingUnenrol = i.pendingUnenrol, storageError = i.storageError)

        if (failureKind == RelayFailureKind.SERVER_MISMATCH || i.authFailure == HubAuthFailure.SERVER_MISMATCH) {
            return base.copy(state = HubLinkState.SERVER_MISMATCH)
        }
        if (creds.isExpiredAt(i.nowMs) || failureKind == RelayFailureKind.CERT_EXPIRED || i.authFailure == HubAuthFailure.CERT_EXPIRED) {
            return base.copy(state = HubLinkState.CERT_EXPIRED)
        }

        return when (i.relayState) {
            RelaySessionState.CONNECTED ->
                base.copy(state = if (base.renewalFailed) HubLinkState.CERT_EXPIRING else HubLinkState.ONLINE)
            RelaySessionState.CONNECTING -> base.copy(state = HubLinkState.CONNECTING)
            RelaySessionState.AUTHENTICATING -> base.copy(state = HubLinkState.AUTHENTICATING)
            RelaySessionState.SUSPENDED, RelaySessionState.DISCONNECTED, RelaySessionState.REVOKED ->
                if (i.relayFailure != null || i.nextRetryAtMs != null || i.authFailure != null) {
                    base.copy(state = HubLinkState.OFFLINE, offlineReason = offlineReason(i.relayFailure, i.authFailure))
                } else {
                    base.copy(state = HubLinkState.DISCONNECTED)
                }
        }
    }

    public fun offlineReason(failure: RelayFailure?, authFailure: HubAuthFailure?): HubOfflineReason = when (failure?.kind) {
        RelayFailureKind.NETWORK -> HubOfflineReason.NO_NETWORK
        RelayFailureKind.HANDSHAKE_TIMEOUT, RelayFailureKind.KEEPALIVE_TIMEOUT -> HubOfflineReason.HUB_NOT_ANSWERING
        RelayFailureKind.AUTH_REJECTED, RelayFailureKind.PROTOCOL -> HubOfflineReason.REFUSED
        RelayFailureKind.KICKED -> HubOfflineReason.KICKED
        RelayFailureKind.UNSUPPORTED_VERSION -> HubOfflineReason.UPDATE_NEEDED
        RelayFailureKind.OVERFLOW -> HubOfflineReason.OTHER
        null, RelayFailureKind.REVOKED, RelayFailureKind.SERVER_MISMATCH, RelayFailureKind.CERT_EXPIRED ->
            when (authFailure) {
                HubAuthFailure.UNREACHABLE -> HubOfflineReason.NO_NETWORK
                HubAuthFailure.REJECTED, HubAuthFailure.ADDRESS_REFUSED -> HubOfflineReason.REFUSED
                else -> HubOfflineReason.OTHER
            }
    }

    /** First 16 hex digits in groups of four, upper case: "AB12 CD34 EF56 7890". */
    public fun shortFingerprint(fingerprint: String): String =
        fingerprint.uppercase().filter { it.isLetterOrDigit() }.take(16).chunked(4).joinToString(" ")

    /** Whole days from [nowMs] to [validToMs], never negative. */
    public fun daysLeft(validToMs: Long, nowMs: Long): Int = ((validToMs - nowMs).coerceAtLeast(0L) / DAY_MS).toInt()
}
