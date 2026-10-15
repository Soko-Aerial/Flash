package com.transfer.flash.core.hubprotocol

import com.transfer.flash.core.common.annotation.FlashInternalApi

/**
 * The one place that names every path, header, version number and limit of the Flash enterprise-hub contract
 * (ADR-107, ADR-109). The `:server` routes and the client transports both reference these constants; neither side
 * may hard-code a path string.
 *
 * Public paths that already existed before the contract was extracted keep their spelling. Paths marked NEW
 * are defined here first and served by a later phase; a client must treat a 404 on them as "hub too old".
 */
@FlashInternalApi
public object HubContract {

    // ---- versions -------------------------------------------------------------------------------------------

    /** Relay wire version carried in the handshake frames (`v`). v1 was the unauthenticated query-string wire. */
    public const val RELAY_WIRE_VERSION: Int = 2

    /** REST API version, the `v1` in `/api/v1`. */
    public const val REST_API_VERSION: Int = 1

    // ---- paths ----------------------------------------------------------------------------------------------

    /** WebSocket relay endpoint. No query string carries identity (CLI-10): identity is proven by the handshake. */
    public const val PATH_RELAY: String = "/flash-relay"

    public const val PATH_API: String = "/api/v1"

    /** Browser landing page for an invite link (`GET`, existing; not part of the machine contract). */
    public const val PATH_ENROLL_LANDING: String = "/enroll"

    /** `GET` liveness probe (existing). The public body is [com.transfer.flash.core.hubprotocol.rest.HealthLite] at most. */
    public const val PATH_HEALTH: String = "/health"

    /** `GET` NEW: [com.transfer.flash.core.hubprotocol.rest.HubInfo]. Unauthenticated, no operational detail. */
    public const val PATH_HUB_INFO: String = "$PATH_API/hub"

    /** `GET` or `POST` (existing, both spellings kept): returns an [com.transfer.flash.core.hubprotocol.rest.AuthChallengeResponse]. */
    public const val PATH_AUTH_CHALLENGE: String = "$PATH_API/auth/challenge"

    /** `POST` (existing path, new body): [com.transfer.flash.core.hubprotocol.rest.AuthVerifyRequest] -> bearer token. */
    public const val PATH_AUTH_VERIFY: String = "$PATH_API/auth/verify"

    /** `POST` (existing path, new body with proof of possession): single-use invite token -> device certificate. */
    public const val PATH_ENROLL: String = "$PATH_API/enroll"

    /** `POST` NEW: renewal with a valid or recently-expired certificate; returns a certificate with a NEW serial. */
    public const val PATH_ENROLL_RENEW: String = "$PATH_API/enroll/renew"

    /** `GET` NEW, Bearer: the signed [com.transfer.flash.core.hubprotocol.certs.RevocationList]. 404 when none was published yet. */
    public const val PATH_REVOCATIONS: String = "$PATH_API/revocations"

    /**
     * `POST`, Bearer: the authenticated device leaves the organisation (CLI-09). Answers [com.transfer.flash.core.hubprotocol.rest.OkResponse].
     * The hub revokes the device's certificate and drops its mailbox and directory entries.
     */
    public const val PATH_DEVICE_UNENROLL: String = "$PATH_API/device/unenroll"

    /**
     * `PUT`, Bearer: [com.transfer.flash.core.hubprotocol.rest.SealingKeyRequest] publishes the device's end-to-end sealing
     * public key with the identity-key signature that binds it to the device (ADR-121, ADR-124). The hub verifies the
     * binding before it stores or serves the key; it never sees a private key. Answers [com.transfer.flash.core.hubprotocol.rest.OkResponse].
     * `DELETE` removes the key (the device stops being a sealing recipient).
     */
    public const val PATH_DEVICE_SEALING_KEY: String = "$PATH_API/device/sealing-key"

    /**
     * `GET` lists the step-up approvals waiting for THIS device; `POST <path>/{challengeId}` submits its signature (ADR-131, FO-14).
     * Only an administrator's enrolled approver phone gets any: for every other device the list is empty and a submit is refused.
     */
    public const val PATH_APPROVALS: String = "$PATH_API/approvals"

    /** `GET`, Bearer (existing path): departments. */
    public const val PATH_DIRECTORY_DEPARTMENTS: String = "$PATH_API/directory/departments"

    /** `GET`, Bearer (existing path): groups visible to the authenticated principal. */
    public const val PATH_DIRECTORY_GROUPS: String = "$PATH_API/directory/groups"

    /** `GET`, Bearer (existing path): members of `?departmentId=`. */
    public const val PATH_DIRECTORY_MEMBERS: String = "$PATH_API/directory/members"

    /** `GET`, Bearer (Plan A2): org-wide people list `?cursor=&limit=&q=&departmentId=`. */
    public const val PATH_DIRECTORY_PEOPLE: String = "$PATH_API/directory/people"

    /** `GET`, Bearer: [com.transfer.flash.core.hubprotocol.rest.DirectorySyncDto] synchronisation stream. */
    public const val PATH_DIRECTORY_SYNC: String = "$PATH_API/directory/sync"

    /** `POST`, Bearer (existing path): [com.transfer.flash.core.hubprotocol.rest.JoinRequestBody]. */
    public const val PATH_GROUP_JOIN_REQUEST: String = "$PATH_API/groups/join-request"

    /** `GET`, Bearer, group admin only. NEW: pending [com.transfer.flash.core.hubprotocol.rest.JoinRequestDto] list (`?groupId=`). */
    public const val PATH_GROUP_JOIN_REQUESTS: String = "$PATH_API/groups/join-requests"

    /** `POST`, Bearer, group admin only. NEW: [com.transfer.flash.core.hubprotocol.rest.JoinDecisionBody]. */
    public const val PATH_GROUP_JOIN_DECISION: String = "$PATH_API/groups/join-requests/decision"

    /** `GET`, Bearer: [com.transfer.flash.core.hubprotocol.rest.MeDto], the caller's own directory identity (hub-derived, never client-asserted). */
    public const val PATH_DIRECTORY_ME: String = "$PATH_API/directory/me"

    /**
     * `POST`, Bearer, organisation admin or the department's admin: [com.transfer.flash.core.hubprotocol.rest.CreateDepartmentBody] (create).
     * Departments and groups are also administered from the admin console (`/admin/api/v1`); these are the device-side equivalents.
     */
    public const val PATH_DEPARTMENTS: String = "$PATH_API/departments"

    /** `POST` create ([com.transfer.flash.core.hubprotocol.rest.CreateGroupBody]), `PUT /{id}` update, `DELETE /{id}` delete; Bearer, group/department/organisation admin as the directory rules say. */
    public const val PATH_GROUPS: String = "$PATH_API/groups"

    /** `POST`, Bearer, group admin: [com.transfer.flash.core.hubprotocol.rest.GroupMemberBody] adds or removes a member. */
    public const val PATH_GROUP_MEMBERS: String = "$PATH_API/groups/members"

    /** `GET`, `POST`: announcements feed and posting (Plan A3, ADR-138). */
    public const val PATH_ANNOUNCEMENTS: String = "$PATH_API/announcements"

    /** Admin console announcements routes (Plan A3). */
    public const val PATH_ADMIN_ANNOUNCEMENTS: String = "/admin/api/v1/announcements"

    /** Feature capability for announcement push over WebSocket relay (Plan A3 §3.7). */
    public const val FEATURE_ANNOUNCEMENTS_V1: String = "ann1"


    // ---- headers --------------------------------------------------------------------------------------------

    public const val HEADER_AUTHORIZATION: String = "Authorization"
    public const val BEARER_PREFIX: String = "Bearer "
    public const val HEADER_RETRY_AFTER: String = "Retry-After"

    /** Optional request header; the server echoes it (or invents one) into [com.transfer.flash.core.hubprotocol.rest.ErrorBody.correlationId]. */
    public const val HEADER_CORRELATION_ID: String = "X-Correlation-Id"

    // ---- timing ---------------------------------------------------------------------------------------------

    /** Both ends give up on an unfinished relay handshake after this long (REL-04, CLI-08). */
    public const val HANDSHAKE_TIMEOUT_MS: Long = 10_000L

    /** A REST or relay server nonce is valid this long and for ONE use. */
    public const val CHALLENGE_TTL_SECONDS: Long = 60L

    /** Lifetime of the opaque bearer token returned by `/auth/verify`. */
    public const val BEARER_TTL_SECONDS: Long = 15L * 60L

    /** Renewal opens this long BEFORE the certificate's `validTo`... */
    public const val RENEW_EARLY_WINDOW_MS: Long = 24L * 60L * 60L * 1000L

    /** ...and stays open this long AFTER it (the server may configure a different grace; these are the defaults). */
    public const val RENEW_GRACE_MS: Long = 72L * 60L * 60L * 1000L

    /** Clock-skew tolerance applied at both ends of every validity window. */
    public const val CLOCK_SKEW_MS: Long = 60_000L

    // ---- sizes (REL-04, SEC-16) -----------------------------------------------------------------------------

    /** Largest relay text frame, in UTF-8 bytes, in either direction. Both WebSocket stacks set this as `maxFrameSize`. */
    public const val MAX_FRAME_BYTES: Int = 256 * 1024

    /** Largest `payload` of one `FLASH_RELAY`/`FLASH_DELIVER`, in UTF-8 bytes. */
    public const val MAX_PAYLOAD_BYTES: Int = 64 * 1024

    /** Largest frame accepted before the handshake completes (a HELLO with a certificate chain is a few KiB). */
    public const val MAX_HANDSHAKE_FRAME_BYTES: Int = 16 * 1024

    /** Largest REST request or response body, in UTF-8 bytes. */
    public const val MAX_REST_BODY_BYTES: Int = 64 * 1024

    /** Longest identifier (device id, message id, group id, nonce) accepted in any field. */
    public const val MAX_ID_LENGTH: Int = 128

    /** Longest display name accepted in any field. */
    public const val MAX_NAME_LENGTH: Int = 128
}

/**
 * Closed set of machine-readable error codes. Appear in relay `FLASH_ERROR.code`, in REST
 * [com.transfer.flash.core.hubprotocol.rest.ErrorBody.code] and in logs. A code never carries raw exception text.
 */
@FlashInternalApi
public object HubErrorCodes {
    /** Authentication failed: bad signature, unknown or untrusted issuer, malformed credentials. */
    public const val ERR_AUTH: String = "ERR_AUTH"

    /** The device certificate (or the delegation above it) is revoked. Terminal for the client until re-enrolled. */
    public const val ERR_REVOKED: String = "ERR_REVOKED"

    /** The device certificate (or its delegation) is outside its validity window. Renew, do not retry blindly. */
    public const val ERR_EXPIRED: String = "ERR_EXPIRED"

    public const val ERR_RATE_LIMIT: String = "ERR_RATE_LIMIT"
    public const val ERR_TOO_LARGE: String = "ERR_TOO_LARGE"

    /** The frame or body is malformed, out of sequence or of an unknown type. */
    public const val ERR_PROTOCOL: String = "ERR_PROTOCOL"

    public const val ERR_UNSUPPORTED_VERSION: String = "ERR_UNSUPPORTED_VERSION"

    /** The handshake did not finish within [HubContract.HANDSHAKE_TIMEOUT_MS]. */
    public const val ERR_HANDSHAKE_TIMEOUT: String = "ERR_HANDSHAKE_TIMEOUT"

    /** The caller is not a member of the group/department the request names. */
    public const val ERR_NOT_MEMBER: String = "ERR_NOT_MEMBER"

    /** The addressed device is not enrolled in this realm. */
    public const val ERR_UNKNOWN_RECIPIENT: String = "ERR_UNKNOWN_RECIPIENT"

    public const val ERR_QUOTA_EXCEEDED: String = "ERR_QUOTA_EXCEEDED"
    public const val ERR_FORBIDDEN: String = "ERR_FORBIDDEN"

    /** Enrolment: the hub's delegation does not include the scope needed to issue the invite's role (ERROR-400). */
    public const val ERR_ROLE_NOT_ALLOWED: String = "ERR_ROLE_NOT_ALLOWED"
    public const val ERR_NOT_FOUND: String = "ERR_NOT_FOUND"
    public const val ERR_CONFLICT: String = "ERR_CONFLICT"
    public const val ERR_PIN_LIMIT: String = "ERR_PIN_LIMIT"

    /** Request-level validation failure (REST). */
    public const val ERR_BAD_REQUEST: String = "ERR_BAD_REQUEST"

    public const val ERR_INTERNAL: String = "ERR_INTERNAL"

    /** Codes after which a client must NOT reconnect on its own. */
    public val TERMINAL: Set<String> = setOf(ERR_REVOKED, ERR_UNSUPPORTED_VERSION)
}
