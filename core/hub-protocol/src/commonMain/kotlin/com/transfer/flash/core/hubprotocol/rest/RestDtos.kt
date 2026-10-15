package com.transfer.flash.core.hubprotocol.rest

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.OrgMemberStatement
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import kotlinx.serialization.Serializable

/**
 * REST DTOs of the hub (`/api/v1`, ADR-109). The server and the clients both encode and decode these classes through
 * [HubRestCodec]; the field names below ARE the wire (they follow what the server already emitted, so a hub that
 * predates this module stays readable: new fields are nullable or defaulted and old clients ignore them).
 */

/** Every non-2xx REST response. [message] is a fixed sentence, never exception text (SEC-16); [correlationId] ties it to a hub log line. */
@FlashInternalApi
@Serializable
public data class ErrorBody(
    public val code: String,
    public val message: String = "",
    public val correlationId: String = "",
)

// ---- authentication ------------------------------------------------------------------------------------------

/** Response of `GET|POST /api/v1/auth/challenge`. The nonce is single-use and valid [ttlSeconds]. */
@FlashInternalApi
@Serializable
public data class AuthChallengeResponse(
    public val serverId: String,
    public val serverNonce: String,
    public val issuedAt: Long,
    public val ttlSeconds: Long = HubContract.CHALLENGE_TTL_SECONDS,
)

/** Body of `POST /api/v1/auth/verify`: the same signed form as the relay HELLO (`HubAuth.authSigningBytes`). */
@FlashInternalApi
@Serializable
public data class AuthVerifyRequest(
    public val serverNonce: String,
    public val clientNonce: String,
    public val deviceId: String,
    public val deviceCert: DeviceCert,
    public val delegationChain: List<DelegationCert> = emptyList(),
    public val signature: String,
)

/** Response of `POST /api/v1/auth/verify`: a short-lived opaque bearer token bound to [principal]. */
@FlashInternalApi
@Serializable
public data class AuthVerifyResponse(
    public val status: String = STATUS_AUTHENTICATED,
    public val token: String,
    /** Absolute expiry, epoch milliseconds, hub clock. */
    public val expiresAt: Long,
    public val ttlSeconds: Long = HubContract.BEARER_TTL_SECONDS,
    public val principal: HubPrincipal,
) {
    public companion object {
        public const val STATUS_AUTHENTICATED: String = "AUTHENTICATED"
    }
}

// ---- enrolment -----------------------------------------------------------------------------------------------

/**
 * Body of `POST /api/v1/enroll`. [proof] is the device-key signature over
 * `HubAuth.enrollProofBytes(token, deviceId, subjectPublicKey, serverId)` (proof of possession: the hub certifies a
 * key only for the party that holds it).
 */
@FlashInternalApi
@Serializable
public data class EnrollRequest(
    public val token: String,
    public val deviceId: String,
    public val subjectPublicKey: String,
    public val friendlyName: String? = null,
    public val proof: String = "",
)

/** Response of `POST /api/v1/enroll` and `POST /api/v1/enroll/renew`. */
@FlashInternalApi
@Serializable
public data class EnrollResponse(
    public val status: String = STATUS_ENROLLED,
    public val serverId: String = "",
    /** Upper-case hex SHA-256 of [rootPublicKey]; the client compares it to the fingerprint pinned from the invite. */
    public val rootFingerprint: String = "",
    /** Base64 SPKI of the organisation root key. The client hashes it and MUST match [rootFingerprint] to its pin. */
    public val rootPublicKey: String = "",
    public val deviceCert: DeviceCert,
    public val delegationChain: List<DelegationCert> = emptyList(),
    public val hubName: String? = null,
) {
    public companion object {
        public const val STATUS_ENROLLED: String = "ENROLLED"
        public const val STATUS_RENEWED: String = "RENEWED"
    }
}

/**
 * Body of `POST /api/v1/enroll/renew`. Same signed form as [AuthVerifyRequest] with the renew tag
 * (`HubAuth.renewProofBytes`); [deviceCert] may be expired by up to the renewal grace.
 */
@FlashInternalApi
@Serializable
public data class RenewRequest(
    public val serverNonce: String,
    public val clientNonce: String,
    public val deviceId: String,
    public val deviceCert: DeviceCert,
    public val delegationChain: List<DelegationCert> = emptyList(),
    public val signature: String,
)

// ---- directory -----------------------------------------------------------------------------------------------

/** `GET /api/v1/directory/departments` element. */
@FlashInternalApi
@Serializable
public data class DepartmentDto(
    public val id: String,
    public val name: String,
    public val adminDeviceIds: List<String> = emptyList(),
    public val memberCount: Int = 0,
    public val groupCount: Int = 0,
    /** Plan A2: true when this department is hidden from outsiders. Old clients ignore it (`ignoreUnknownKeys`). */
    public val hidden: Boolean = false,
)

/**
 * `GET /api/v1/directory/groups` element. [visibility] and [joinMode] are [HubDirectoryValues] strings.
 * [memberDeviceIds] is filled only for a principal who may see the roster (a member or an admin of the group);
 * [onlineCount] is a hub-side count of connected members; [pendingCount] is present for the group's admins only.
 */
@FlashInternalApi
@Serializable
public data class GroupDto(
    public val id: String,
    public val name: String,
    public val departmentId: String = "",
    public val visibility: String = HubDirectoryValues.VISIBILITY_LISTED,
    public val joinMode: String = HubDirectoryValues.JOIN_REQUEST_APPROVAL,
    public val adminDeviceIds: List<String> = emptyList(),
    public val memberDeviceIds: List<String> = emptyList(),
    public val memberCount: Int = 0,
    public val onlineCount: Int? = null,
    public val pendingCount: Int? = null,
)

/** `GET /api/v1/directory/members` element. */
@FlashInternalApi
@Serializable
public data class MemberDto(
    public val deviceId: String,
    public val departmentId: String = "",
    public val displayName: String = "",
    public val role: String = HubDirectoryValues.ROLE_MEMBER,
    public val online: Boolean = false,
    /**
     * Base64 SPKI of the member's device (certificate) key. Present only for an authenticated caller who is in scope
     * of the member. A client uses it to verify the AUTHOR of a sealed message (signature by the device key) and to
     * cache the member against its certificate serial (CLI-05).
     */
    public val subjectPublicKey: String? = null,
    /**
     * Base64 SPKI of the member's separate ECDH SEALING key, and the device-key signature that binds it to this device
     * (`HubAuth.sealingKeyBindingBytes`, ADR-121 / ADR-124). Both are null until the member's device published them.
     * The hub verified the binding before storing it, but a client MUST verify it again against the member's
     * certified [subjectPublicKey] (a hub that swaps in its own sealing key cannot forge the signature). Served only to
     * an authenticated caller who is in scope of the member, like [subjectPublicKey].
     */
    public val sealingPublicKey: String? = null,
    public val sealingSignature: String? = null,
)

/**
 * Body of `PUT /api/v1/device/sealing-key`: [sealingPublicKey] is the Base64 X.509 SPKI of a P-256 key, [signature] the
 * Base64 DER ECDSA signature of the device identity key over `HubAuth.sealingKeyBindingBytes(deviceId, spkiBytes)`.
 */
@FlashInternalApi
@Serializable
public data class SealingKeyRequest(
    public val sealingPublicKey: String,
    public val signature: String,
)

/** Response of `POST /api/v1/device/unenroll`. */
@FlashInternalApi
@Serializable
public data class OkResponse(
    public val ok: Boolean = true,
)

/** Body of `POST /api/v1/groups/join-request`. */
@FlashInternalApi
@Serializable
public data class JoinRequestBody(
    public val groupId: String,
    public val message: String? = null,
)

/** Response of `POST /api/v1/groups/join-request`. [status] is a [HubDirectoryValues] join status. */
@FlashInternalApi
@Serializable
public data class JoinResponse(
    public val status: String,
    public val groupId: String? = null,
    public val name: String? = null,
    public val requestId: String? = null,
    public val reason: String? = null,
)

/** `GET /api/v1/groups/join-requests?groupId=` element (group admins only). */
@FlashInternalApi
@Serializable
public data class JoinRequestDto(
    public val requestId: String,
    public val groupId: String,
    public val deviceId: String,
    public val displayName: String = "",
    public val requestedAt: Long = 0L,
    public val message: String? = null,
)

/** Body of `POST /api/v1/groups/join-requests/decision`. */
@FlashInternalApi
@Serializable
public data class JoinDecisionBody(
    public val requestId: String,
    public val approve: Boolean,
    public val reason: String? = null,
)

// ---- hub information -----------------------------------------------------------------------------------------

/** `GET /api/v1/hub`: what a client may know before authenticating. No counts, no operational detail. */
@FlashInternalApi
@Serializable
public data class HubInfo(
    public val serverId: String,
    public val name: String = "",
    public val relayWireVersion: Int = HubContract.RELAY_WIRE_VERSION,
    public val restApiVersion: Int = HubContract.REST_API_VERSION,
    public val maxFrameBytes: Int = HubContract.MAX_FRAME_BYTES,
    public val maxPayloadBytes: Int = HubContract.MAX_PAYLOAD_BYTES,
    public val serverTime: Long = 0L,
    public val announcementLimits: AnnouncementLimitsDto? = null,
)


/** `GET /health`: liveness only. */
@FlashInternalApi
@Serializable
public data class HealthLite(
    public val status: String = "ok",
)

/**
 * One confirmation waiting for the administrator's phone (ADR-131, FO-14). The phone signs
 * `HubStepUp.approvalBytes(challengeId, action, admin, target, nonce)` and shows [action], [target] and [code] first.
 */
@FlashInternalApi
@Serializable
public data class ApprovalDto(
    public val challengeId: String,
    public val action: String,
    public val admin: String,
    public val target: String,
    public val nonce: String,
    /** The six digits the console shows too ([HubStepUp.approvalCode]). */
    public val code: String,
    public val expiresAtMs: Long,
)

/** `POST /api/v1/approvals/{challengeId}`: the phone's signature, Base64 (DER or IEEE P1363) over the approval bytes. */
@FlashInternalApi
@Serializable
public data class ApprovalSubmitBody(public val signature: String)

/** The string values the directory DTOs carry. Strings (not enums) so a newer hub's new value does not break an older client. */
@FlashInternalApi
public object HubDirectoryValues {
    public const val VISIBILITY_LISTED: String = "LISTED"
    public const val VISIBILITY_HIDDEN: String = "HIDDEN"

    public const val JOIN_OPEN_IN_SCOPE: String = "OPEN_IN_SCOPE"
    public const val JOIN_REQUEST_APPROVAL: String = "REQUEST_APPROVAL"
    public const val JOIN_ADMIN_ONLY: String = "ADMIN_ONLY"

    public const val ROLE_ORG_ADMIN: String = "ORG_ADMIN"
    public const val ROLE_DEPT_ADMIN: String = "DEPT_ADMIN"
    public const val ROLE_MEMBER: String = "MEMBER"

    public const val JOIN_STATUS_JOINED: String = "JOINED"
    public const val JOIN_STATUS_PENDING: String = "PENDING"
    public const val JOIN_STATUS_REJECTED: String = "REJECTED"
}

// ---- local persistence ---------------------------------------------------------------------------------------

/**
 * The on-disk / SharedPreferences form of a device's enrolment (CLI-02: replaces the hand-written JSON and regex
 * parser). Field names are the ones the previous hand-written serializer already wrote, so credentials stored by an
 * older build still load; unknown keys are ignored, and a missing `rootFingerprint` reads as empty.
 */
@FlashInternalApi
@Serializable
public data class StoredEnrollment(
    public val hubUrl: String,
    public val rootFingerprint: String = "",
    public val rootPublicKey: String,
    public val enrolledAtMs: Long = 0L,
    public val deviceCert: DeviceCert,
    public val delegationChain: List<DelegationCert> = emptyList(),
    /** The hub's display name; absent in blobs written before CLIENT Phase 2. */
    public val hubName: String = "",
)

// ---- directory sync (Plan B, ADR-134) ------------------------------------------------------------------------

/**
 * Element of `DirectorySyncDto.departments`: synchronised department metadata.
 * [hidden] is defaulted to false; Plan A2 sets it when hidden departments are enabled.
 */
@FlashInternalApi
@Serializable
public data class DepartmentSyncDto(
    public val id: String,
    public val name: String,
    public val headDeviceIds: List<String> = emptyList(),
    public val memberCount: Int = 0,
    public val hidden: Boolean = false,
)

/**
 * Element of `DirectorySyncDto.members`: synchronised member upsert with signed [OrgMemberStatement].
 */
@FlashInternalApi
@Serializable
public data class DirectoryMemberSyncDto(
    public val deviceId: String,
    public val displayName: String = "",
    public val departmentId: String = "",
    public val role: String = HubDirectoryValues.ROLE_MEMBER,
    public val statement: OrgMemberStatement,
    public val online: Boolean? = null,
)

/**
 * Forward-compatibility DTO for Phase B4: synchronised hub group roster statements.
 */
@FlashInternalApi
@Serializable
public data class GroupRosterDto(
    public val groupId: String,
    public val version: Long = 0L,
    public val statement: String = "",
    public val validUntilMs: Long = 0L,
)

/**
 * Response of `GET /api/v1/directory/sync?since=<version>&cursor=<opaque>&limit=<n>`.
 */
@FlashInternalApi
@Serializable
public data class DirectorySyncDto(
    public val directoryEpoch: String,
    public val version: Long,
    public val fromVersion: Long,
    public val fullResync: Boolean,
    public val nextCursor: String? = null,
    public val serverTimeMs: Long,
    public val offlineGraceDays: Int = DEFAULT_OFFLINE_GRACE_DAYS,
    public val delegationChain: List<DelegationCert> = emptyList(),
    public val members: List<DirectoryMemberSyncDto> = emptyList(),
    public val removedDeviceIds: List<String> = emptyList(),
    public val departments: List<DepartmentSyncDto> = emptyList(),
    public val removedDepartmentIds: List<String> = emptyList(),
    public val groupRosters: List<GroupRosterDto> = emptyList(),
    public val removedGroupIds: List<String> = emptyList(),
) {
    public companion object {
        public const val DEFAULT_OFFLINE_GRACE_DAYS: Int = 30
        public const val MIN_OFFLINE_GRACE_DAYS: Int = 1
        public const val MAX_OFFLINE_GRACE_DAYS: Int = 365
    }
}
