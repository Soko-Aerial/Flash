package com.transfer.flash.server.admin

import kotlinx.serialization.Serializable

/**
 * JSON bodies of the administration API (`/admin/api/v1`). The console UI (a later phase) is built on these. Errors
 * are `com.transfer.flash.core.hubprotocol.rest.ErrorBody {code, message, correlationId}`. No DTO carries a secret
 * the server stored: passwords, TOTP secrets and recovery codes appear only in the ONE response that creates them.
 */

@Serializable
data class SetupStatusDto(val setupOpen: Boolean, val hasAdmins: Boolean, val delegationStatus: String = "")

@Serializable
data class SetupBeginBody(val token: String)

@Serializable
data class SetupBeginDto(val setupId: String, val totpSecret: String, val otpAuthUri: String, val qrSvg: String = "")

@Serializable
data class SetupCompleteBody(
    val setupId: String,
    val token: String,
    val username: String,
    val password: String,
    val confirmCode: String,
    /** The browser's step-up key (ADR-125), registered together with the first administrator. */
    val consoleKey: ConsoleKeyProof? = null,
)

/** A browser step-up key and the proof that the browser holds it: [proof] signs `ConsoleKeys.proofMessage(username, publicKey)`. */
@Serializable
data class ConsoleKeyProof(val publicKey: String, val proof: String, val label: String? = null)

@Serializable
data class ConsoleKeyEnrolBody(val publicKey: String, val proof: String, val code: String, val label: String? = null)

@Serializable
data class ConsoleKeyDto(val keyId: String)

@Serializable
data class SetupCompleteDto(val username: String, val recoveryCodes: List<String>)

@Serializable
data class LoginBody(val username: String, val password: String, val code: String)

@Serializable
data class LoginDto(
    val username: String,
    val role: String,
    val csrfToken: String,
    val expiresAtMs: Long,
    val recoveryCodeUsed: Boolean = false,
    val recoveryCodesRemaining: Int = 0,
)

@Serializable
data class SessionDto(
    val username: String,
    val role: String,
    val csrfToken: String,
    val expiresAtMs: Long,
    val stepUpAvailable: Boolean,
    val enrolledDeviceId: String? = null,
    /** The public id of THIS session (the row to mark as "this browser" in the session list). */
    val sessionId: String = "",
    val serverTimeMs: Long = 0,
    /** The bound approver phone (ADR-131), or null. */
    val approverDeviceId: String? = null,
    /** Plan A1: console display name; empty for older rows. */
    val displayName: String = "",
    /** Plan A1: departments this login is bound to (empty for org admins and empty-scope heads). */
    val departments: List<DepartmentRefDto> = emptyList(),
)

@Serializable
data class DepartmentRefDto(val id: String, val name: String = "")

@Serializable
data class SessionRowDto(val sessionId: String, val username: String, val createdAtMs: Long, val lastSeenMs: Long, val expiresAtMs: Long)

@Serializable
data class PasswordChangeBody(val oldPassword: String, val newPassword: String)

@Serializable
data class PasswordResetBody(val newPassword: String)

@Serializable
data class FreshCodeBody(val code: String)

@Serializable
data class RecoveryCodesDto(val recoveryCodes: List<String>)

@Serializable
data class BindDeviceBody(val deviceId: String, val code: String)

@Serializable
data class OkDto(val ok: Boolean = true)

@Serializable
data class DelegationDto(
    val status: String,
    val serial: Long? = null,
    val validFrom: Long? = null,
    val validTo: Long? = null,
    val daysLeft: Long? = null,
    val maxDevices: Int? = null,
    val scopes: List<String> = emptyList(),
    val rootFingerprint: String? = null,
    val delegatePublicKey: String? = null,
    val error: String? = null,
    val warning: String? = null,
    /** The hub's own public key (base64 SPKI): what `keytool issue-delegation --server-pub` needs. Public information. */
    val hubPublicKey: String? = null,
)

@Serializable
data class InviteCountsDto(val pending: Int = 0, val used: Int = 0, val expired: Int = 0, val revoked: Int = 0)

@Serializable
data class RevocationStatusDto(
    val listVersion: Long,
    val revokedSerials: Int,
    val revokedDeviceIds: Int,
    val unpublishedChanges: Boolean,
    val canSign: Boolean,
    /** Delegation serials the organisation root revoked (REV-04). When this hub's own is in it the hub issues nothing. */
    val revokedDelegations: List<Long> = emptyList(),
)

@Serializable
data class AuditHeadDto(val index: Long? = null, val hash: String? = null, val entries: Int = 0)

@Serializable
data class OverviewDto(
    val hubName: String,
    val serverId: String,
    val devicesByStatus: Map<String, Long>,
    val onlineDevices: Int,
    val invites: InviteCountsDto,
    val departments: Int,
    val groups: Int,
    val pendingJoinRequests: Int,
    val queueDepth: Long,
    val queueBytes: Long,
    val delegation: DelegationDto,
    val revocations: RevocationStatusDto?,
    val audit: AuditHeadDto,
    val deviceQuota: Int,
)

@Serializable
data class DeviceDto(
    val deviceId: String,
    val name: String,
    val department: String? = null,
    val role: String,
    val status: String,
    val serial: Long,
    val enrolledAt: Long,
    val certExpiry: Long,
    val lastSeenAt: Long? = null,
    val online: Boolean = false,
    val subjectPublicKey: String = "",
    val inviteId: String? = null,
)

@Serializable
data class DeviceUpdateBody(val name: String? = null, val department: String? = null, val moveToDepartment: Boolean = false)

@Serializable
data class RevokeBody(val reason: String = "revoked by administrator")

@Serializable
data class RevokeSerialBody(val serial: Long, val reason: String = "revoked by administrator")

@Serializable
data class RevocationEntryDto(val kind: String, val subject: String, val reason: String, val revokedAt: Long, val revokedBy: String)

@Serializable
data class RevocationsDto(val status: RevocationStatusDto, val entries: List<RevocationEntryDto>)

@Serializable
data class InviteDto(
    val inviteId: String,
    val state: String,
    val department: String? = null,
    val role: String,
    val allowedGroups: List<String> = emptyList(),
    val autoJoinGroups: List<String> = emptyList(),
    val createdAt: Long,
    val expiresAt: Long,
    val maxUses: Int,
    val uses: Int,
    val note: String? = null,
    val createdBy: String? = null,
)

@Serializable
data class InviteCreateBody(
    val department: String? = null,
    val role: String = "member",
    val allowedGroups: List<String> = emptyList(),
    val autoJoinGroups: List<String> = emptyList(),
    val ttlMinutes: Long? = null,
    val maxUses: Int? = null,
    val note: String? = null,
)

/** The only response that ever carries the invite secret: [link] and [token] are not retrievable afterwards. */
@Serializable
data class InviteCreatedDto(val invite: InviteDto, val token: String, val link: String, val qrSvg: String)

@Serializable
data class BulkInviteBody(val csv: String, val ttlMinutes: Long? = null, val role: String = "member")

@Serializable
data class BulkInviteRowDto(val line: Int, val name: String, val created: InviteCreatedDto? = null, val error: String? = null)

@Serializable
data class BulkInviteDto(val rows: List<BulkInviteRowDto>)

@Serializable
data class DepartmentBody(val id: String? = null, val name: String? = null, val adminDeviceIds: List<String>? = null, val hidden: Boolean? = null)

@Serializable
data class AdminDepartmentDto(val id: String, val name: String, val adminDeviceIds: List<String>, val memberCount: Int, val groupCount: Int, val hidden: Boolean = false)

@Serializable
data class GroupBody(
    val id: String? = null,
    val name: String? = null,
    val departmentId: String? = null,
    val visibility: String? = null,
    val joinMode: String? = null,
    val adminDeviceIds: List<String>? = null,
)

@Serializable
data class AdminGroupDto(
    val id: String,
    val name: String,
    val departmentId: String,
    val visibility: String,
    val joinMode: String,
    val adminDeviceIds: List<String>,
    val memberDeviceIds: List<String>,
    val onlineCount: Int,
    val pendingCount: Int,
)

@Serializable
data class RejectBody(val reason: String? = null)

@Serializable
data class GroupMemberAddBody(val deviceId: String)

@Serializable
data class AdminJoinRequestDto(
    val requestId: String,
    val groupId: String,
    val deviceId: String,
    val displayName: String,
    val status: String,
    val requestedAt: Long,
    val message: String? = null,
)

@Serializable
data class AdminDto(
    val username: String,
    val role: String,
    val disabled: Boolean,
    val enrolledDeviceId: String? = null,
    val createdAtMs: Long,
    val createdBy: String? = null,
    val recoveryCodesRemaining: Int = 0,
    val approverDeviceId: String? = null,
    /** Plan A1: console display name and bound departments (empty = none). */
    val displayName: String = "",
    val departments: List<String> = emptyList(),
    val linkedDeviceId: String? = null,
)

@Serializable
data class AdminCreateBody(
    val username: String,
    val password: String,
    /** Plan A1: "DEPT_HEAD" creates a head login (org admin + step-up); anything else means ORG_ADMIN. */
    val role: String? = null,
    val displayName: String? = null,
    val departments: List<String> = emptyList(),
    val linkedDeviceId: String? = null,
)

@Serializable
data class HeadDepartmentsBody(val departments: List<String> = emptyList())

@Serializable
data class HeadDepartmentsDto(val username: String, val departments: List<String>)

@Serializable
data class AdminCredentialsDto(
    val username: String,
    val password: String? = null,
    val totpSecret: String,
    val otpAuthUri: String,
    val recoveryCodes: List<String>,
    /** The authenticator enrolment QR (server-rendered SVG of [otpAuthUri]); the console redraws it from an allow-list, never as markup. */
    val qrSvg: String = "",
)

@Serializable
data class AuditEntryDto(
    val index: Long,
    val timestampMs: Long,
    val actor: String,
    val action: String,
    val details: String,
    val hash: String,
    val signed: Boolean,
)

@Serializable
data class AuditPageDto(val total: Long, val entries: List<AuditEntryDto>)

@Serializable
data class AuditVerifyDto(val ok: Boolean, val entries: Long, val anchors: Long, val firstBadIndex: Long? = null, val reason: String? = null)

@Serializable
data class SettingsDto(
    val hubName: String,
    val externalUrl: String,
    val tlsMode: String,
    val port: Int,
    val adminPort: Int,
    val adminOnPublicPort: Boolean,
    val deviceQuota: Int,
    val mailboxTtlDays: Int,
    val challengeTimeoutMs: Long,
    val trustedProxies: Int,
    val allowInsecure: Boolean,
    val inviteDefaultTtlMinutes: Long,
    val inviteDefaultMaxUses: Int,
    val delegationFile: String,
)

@Serializable
data class SettingChangeBody(val key: String, val value: String)

@Serializable
data class StepUpChallengeBody(val action: String, val target: String = "")

@Serializable
data class StepUpChallengeDto(
    val challengeId: String,
    val action: String,
    val target: String,
    val deviceId: String,
    val nonce: String,
    val expiresAtMs: Long,
    /** Base64 of the exact bytes the device signs ([com.transfer.flash.core.hubprotocol.certs.HubStepUp.proofBytes]). */
    val proofBytes: String,
    /** True when the administrator's approver phone must approve as well (ADR-131). */
    val approvalRequired: Boolean = false,
    /** The six digits the phone shows for this challenge ([com.transfer.flash.core.hubprotocol.certs.HubStepUp.approvalCode]); empty when not required. */
    val approvalCode: String = "",
    /** False when the browser key is not asked (signer replacement): the console waits for the phone only. */
    val browserKeyRequired: Boolean = true,
)

/** Where a confirmation stands, for the console that is waiting for the phone (ADR-131). */
@Serializable
data class StepUpStatusDto(
    val verified: Boolean,
    val approvalRequired: Boolean,
    val approved: Boolean,
    val expiresAtMs: Long,
)

@Serializable
data class ApproverBindBody(val deviceId: String, val code: String = "")

@Serializable
data class ApproverRemoveBody(val code: String)

@Serializable
data class StepUpVerifyBody(val challengeId: String, val signature: String)

/** A point-in-time snapshot of the relay's counters for the console (the console keeps its own history, the hub keeps none). */
@Serializable
data class MetricsDto(
    val serverTimeMs: Long,
    val uptimeSeconds: Long,
    val onlineDevices: Int,
    val queueDepth: Long,
    val queueBytes: Long,
    val oldestWaitingMs: Long? = null,
    val messagesDelivered: Long = 0,
    val messagesQueued: Long = 0,
    val messagesRejected: Long = 0,
    val redeliveries: Long = 0,
    val framesIn: Long = 0,
    val framesOut: Long = 0,
    val bytesIn: Long = 0,
    val bytesOut: Long = 0,
    val rateLimitHits: Long = 0,
    val handshakesOk: Long = 0,
    val handshakesRefused: Long = 0,
    val mailboxDelivered: Long = 0,
    val mailboxExpired: Long = 0,
    val mailboxDropped: Long = 0,
)
