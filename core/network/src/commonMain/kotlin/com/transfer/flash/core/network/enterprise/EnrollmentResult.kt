package com.transfer.flash.core.network.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.security.enterprise.EnrollmentCredentials

/**
 * Why an enrolment (or a renewal) did not complete. Each reason has a plain-language sentence in the UI layer
 * (`FlashHubStatusMath.enrollFailureText`); none of them carries protocol text.
 */
@FlashInternalApi
public enum class EnrollFailureReason {
    /** The link has no `root=` pin: the device cannot tell whose hub it would be trusting. */
    INVITE_NOT_PINNED,

    /** The hub address is not secure (plain `http`/`ws`) and is not a trusted-development address. */
    CLEARTEXT_REFUSED,

    /** The hub address in the link cannot be used. */
    INVALID_ADDRESS,

    /** The link's single-use code is past its expiry. */
    INVITE_EXPIRED,

    /** The link's single-use code was already used (or this device is already enrolled by it). */
    INVITE_USED,

    /** The hub refused the code (unknown, malformed, wrong organisation). */
    INVITE_REJECTED,

    /** The hub has no seats left (device or mailbox quota). */
    QUOTA_FULL,

    /** Too many attempts; try again later. */
    RATE_LIMITED,

    /** The hub that answered is not the organisation's: its root key does not match the link's pin. */
    WRONG_ORGANISATION,

    /** The certificate the hub issued is for another device or another key. */
    WRONG_DEVICE,

    /** The certificate chain the hub returned does not verify, or is outside its validity. */
    CERTIFICATE_INVALID,

    /** The hub could not be reached (offline, DNS, TLS refused, timeout). */
    UNREACHABLE,

    /** The hub answered with something that is not the expected response. */
    UNEXPECTED_RESPONSE,

    /** The hub reported an internal fault. */
    HUB_ERROR,

    /** Everything verified, but the device could not save the result. Nothing was stored. */
    STORAGE_FAILED,
}

/** The outcome of [EnterpriseEnrollmentClient.enroll] and [HubSession.renew]. */
@FlashInternalApi
public sealed interface EnrollmentOutcome {
    public data class Enrolled(val credentials: EnrollmentCredentials) : EnrollmentOutcome

    /** [detail] is for the log only (a status code or a mismatch kind), never for the user. */
    public data class Failed(val reason: EnrollFailureReason, val detail: String = "") : EnrollmentOutcome
}
