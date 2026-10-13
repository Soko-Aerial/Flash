package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.certs.HubRoles
import com.transfer.flash.core.hubprotocol.certs.HubScopes
import com.transfer.flash.core.hubprotocol.certs.RenewalPolicy
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.identity.DeviceRecord
import com.transfer.flash.server.identity.EnrollOutcome
import com.transfer.flash.server.identity.EnrollRefusal
import com.transfer.flash.server.identity.HubIdentifiers
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.identity.InviteRecord
import com.transfer.flash.server.identity.InviteState
import com.transfer.flash.server.identity.RenewOutcome
import com.transfer.flash.server.identity.RenewRefusal
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64
import org.slf4j.LoggerFactory

/**
 * Scope associated with an enrolment invite.
 *
 * - [allowedGroups]: groups the device may JOIN WITHOUT approval after enrolment (pre-authorised).
 * - [autoJoinGroups]: groups the device is ADDED to at enrolment.
 */
data class InviteScope(
    val department: String? = null,
    val allowedGroups: Set<String> = emptySet(),
    val role: String = DeviceCert.ROLE_MEMBER,
    val expiry: Long,
    val maxUses: Int = 1,
    val autoJoinGroups: Set<String> = emptySet(),
    val note: String? = null,
    val createdBy: String? = null,
)

/** A read-only view of one invite (no secret). Kept for source compatibility with callers of `getTokenRecord`. */
data class InviteTokenRecord(
    val inviteId: String,
    val scope: InviteScope,
    val createdAt: Long,
    val usesLeft: Int,
    val revoked: Boolean,
)

/** Why an invite was refused in a way the client tells apart (ADR-124): the wire code and status follow from it. */
enum class InviteRefusal { EXPIRED, USED }

/**
 * An invite that is past its expiry or already used. It stays an [IllegalArgumentException] so every existing caller
 * keeps working; [AuthRoutes] maps it to `410 ERR_EXPIRED` / `409 ERR_CONFLICT`, which is what the client's
 * `EnterpriseEnrollmentClient.mapHttpFailure` turns into "this link expired" / "this link was already used".
 */
class InviteRefusedException(val refusal: InviteRefusal, message: String) : IllegalArgumentException(message)

/** An invite just created: the token exists ONLY here, the store keeps its hash. */
data class CreatedInvite(val token: String, val link: String, val record: InviteRecord)

/**
 * Result returned upon successful device enrolment.
 */
data class EnrollmentResult(
    val deviceCert: DeviceCert,
    val delegationChain: List<DelegationCert>,
    val rootFingerprint: String,
    /** The organisation root key (Base64 SPKI) the chain verifies against; its hash is [rootFingerprint]. */
    val rootPublicKey: String = "",
    val invite: InviteRecord? = null,
    val device: DeviceRecord? = null,
    val reissued: Boolean = false,
)

/** Raised to the enrolment hook after a device was durably registered (the directory adds the member). */
data class EnrollmentEvent(val device: DeviceRecord, val invite: InviteRecord, val reissued: Boolean)

/**
 * Enrolment for the standalone hub (SEC-04/07/08/18, ADR-111).
 *
 * - Invites are stored hashed in SQLite ([IdentityStore]); serials come from a durable counter; the device quota is
 *   the number of ACTIVE registry rows against `min(delegation.maxDevices, initialQuota)`.
 * - [enrollDevice] validates EVERYTHING first (token shape, device id, P-256 key, proof of possession, delegation
 *   window and scope), then ONE transaction checks the invite, the duplicate/revoked device, the quota, allocates the
 *   serial, signs the certificate and writes registry + history. A failure of any step leaves the token and the quota
 *   untouched.
 * - A second enrolment of an ACTIVE device id is refused unless it presents the SAME key (re-issue); a revoked id can
 *   not enrol until an administrator lifts the revocation.
 * - [renew] gives an ACTIVE device a new certificate (new serial, same key) inside the renewal window.
 *
 * @param delegations when present, its current delegation is used (hot reload, expiry); otherwise the fixed
 *   [delegationCert] given at construction.
 * @param requireHttpsInvites refuse to mint invite links for non-`https://` URLs (SEC-06). Main turns it on unless the
 *   operator explicitly allowed insecure transport.
 * @param onEnrolled called after the commit (never inside the transaction); an exception is logged, not propagated.
 */
class EnrollmentService(
    val rootPublicKey: String,
    val serverPrivateKey: PrivateKey,
    delegationCert: DelegationCert,
    initialQuota: Int = delegationCert.maxDevices,
    private val secureRandom: SecureRandom = SecureRandom(),
    /** The realm audience the proof of possession is bound to (the root fingerprint, upper-case hex). */
    val serverId: String = FlashCrypto.sha256FingerprintHex(rootPublicKey),
    val store: IdentityStore = IdentityStore.inMemory(),
    private val delegations: DelegationManager? = null,
    private val revocations: RevocationService = RevocationService.None,
    private val requireHttpsInvites: Boolean = false,
    private val renewEarlyWindowMs: Long = com.transfer.flash.core.hubprotocol.HubContract.RENEW_EARLY_WINDOW_MS,
    private val renewGraceMs: Long = com.transfer.flash.core.hubprotocol.HubContract.RENEW_GRACE_MS,
    private val onEnrolled: (EnrollmentEvent) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(EnrollmentService::class.java)
    private val fixedDelegation: DelegationCert = delegationCert
    private val quotaCeiling: Int = initialQuota

    /** The delegation currently in force (hot-reloadable when a [DelegationManager] was supplied). */
    val delegationCert: DelegationCert get() = delegations?.current ?: fixedDelegation

    /** Devices that may still be enrolled: the ceiling minus the ACTIVE registry rows. */
    val currentRemainingQuota: Int
        get() = (maxActiveDevices() - store.activeDeviceCount()).coerceAtLeast(0).toInt()

    private fun maxActiveDevices(): Int = minOf(delegationCert.maxDevices, quotaCeiling)

    // ------------------------------------------------------------------------------------------------ invites

    /** Creates an invite and returns its token and deep link; the token can not be read back later. */
    fun createInvite(serverUrl: String, scope: InviteScope, currentTimeMs: Long = System.currentTimeMillis()): CreatedInvite {
        require(HubRoles.isValid(scope.role)) { "Unknown role" }
        require(scope.maxUses in 1..MAX_INVITE_USES) { "maxUses must be between 1 and $MAX_INVITE_USES" }
        require(scope.expiry > currentTimeMs) { "Invite expiry must be in the future" }
        if (requireHttpsInvites) {
            check(serverUrl.startsWith("https://", ignoreCase = true)) {
                "Refusing to create an invite for a non-HTTPS hub URL; configure FLASH_EXTERNAL_URL with https:// or run with FLASH_ALLOW_INSECURE=true (development only)"
            }
        }
        val tokenBytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val record = store.createInvite(
            token = token,
            department = scope.department,
            role = scope.role,
            allowedGroups = scope.allowedGroups,
            autoJoinGroups = scope.autoJoinGroups,
            expiresAt = scope.expiry,
            maxUses = scope.maxUses,
            nowMs = currentTimeMs,
            note = scope.note,
            createdBy = scope.createdBy,
        )
        // One builder shared with the clients (percent-encodes the server URL and token).
        return CreatedInvite(token, EnrollmentInvite.buildLink(serverUrl, token, serverId), record)
    }

    /**
     * Generates a single-use invite token and constructs the deep link:
     * `flash://enroll?server=<url>&token=<token>&root=<fingerprint>`
     */
    fun generateInviteLink(
        serverUrl: String,
        scope: InviteScope,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): String = createInvite(serverUrl, scope, currentTimeMs).link

    /** Looks up an invite by its token without consuming it. */
    fun getTokenRecord(token: String): InviteTokenRecord? {
        val record = store.inviteByToken(token) ?: return null
        return InviteTokenRecord(
            inviteId = record.inviteId,
            scope = InviteScope(
                department = record.department,
                allowedGroups = record.allowedGroups,
                role = record.role,
                expiry = record.expiresAt,
                maxUses = record.maxUses,
                autoJoinGroups = record.autoJoinGroups,
                note = record.note,
                createdBy = record.createdBy,
            ),
            createdAt = record.createdAt,
            usesLeft = (record.maxUses - record.uses).coerceAtLeast(0),
            revoked = record.revokedAt != null,
        )
    }

    /** True when [token] names a pending invite (used by the browser landing page; reveals nothing else). */
    fun isInvitePending(token: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        token.length in 1..MAX_TOKEN_CHARS && store.inviteByToken(token)?.state(nowMs) == InviteState.PENDING

    // ------------------------------------------------------------------------------------------------ enrol

    /**
     * Enrols a new device using an invite token.
     *
     * @param proof Base64 signature by the device key over `HubAuth.enrollProofBytes(token, deviceId,
     *   subjectPublicKey, serverId)`. It proves the requester holds the private half of [subjectPublicKey], so a
     *   token holder cannot enrol somebody else's public key, or one it invented.
     */
    fun enrollDevice(
        token: String,
        deviceId: String,
        subjectPublicKey: String,
        proof: String,
        friendlyName: String? = null,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): Result<EnrollmentResult> {
        // 1. Shape of the request. Nothing is consumed here.
        if (token.isBlank() || token.length > MAX_TOKEN_CHARS) {
            return Result.failure(IllegalArgumentException("Invalid or unknown enrollment token"))
        }
        if (!HubIdentifiers.isValidDeviceId(deviceId)) {
            return Result.failure(IllegalArgumentException("Invalid device id"))
        }
        val keyBytes = Spki.parseP256(subjectPublicKey)?.encoded
            ?: return Result.failure(IllegalArgumentException("Malformed subject public key"))
        val proofOk = try {
            JvmEcdsaVerifier.verify(
                publicKeySpki = keyBytes,
                data = HubAuth.enrollProofBytes(token, deviceId, subjectPublicKey, serverId),
                signature = Base64.getDecoder().decode(proof),
            )
        } catch (_: Exception) {
            false
        }
        if (!proofOk) {
            return Result.failure(SecurityException("Proof of possession of the device key is missing or invalid"))
        }

        // 2. The hub's own authority (no skew: the hub's own clock decides).
        val delegation = delegationCert
        if (delegations != null && !delegations.canIssue) {
            return Result.failure(IllegalStateException("Server delegation certificate is not active"))
        }
        if (revocations.isDelegationRevoked(delegationCert.serial)) {
            return Result.failure(IllegalStateException("Server delegation certificate was revoked by the organisation root"))
        }
        if (!delegation.isValidAt(currentTimeMs, 0L)) {
            return Result.failure(IllegalStateException("Server delegation certificate has expired"))
        }
        if (!delegation.verifySignature()) {
            return Result.failure(IllegalStateException("Server delegation certificate signature is invalid"))
        }

        // 3. One transaction: invite, duplicate/revoked device, quota, serial, signature, registry.
        val outcome = try {
            store.enroll(
                token = token,
                deviceId = deviceId,
                subjectPublicKey = subjectPublicKey,
                friendlyName = friendlyName,
                nowMs = currentTimeMs,
                maxActiveDevices = maxActiveDevices(),
                delegationSerial = delegation.serial,
            ) { serial, invite ->
                if (!HubRoles.isValid(invite.role)) throw IllegalStateException("Invite carries an unknown role")
                val needed = HubRoles.enrolScope(invite.role)
                val allowed = if (invite.role == HubRoles.ADMIN) {
                    HubScopes.grantsExplicitly(delegation.scopes, needed)
                } else {
                    delegation.hasScope(needed)
                }
                if (!allowed) throw SecurityException("Server delegation lacks authority to issue role '${invite.role}'")
                if (revocations.isRevoked(serial, deviceId)) throw SecurityException("This device id has been revoked")
                signLeaf(delegation, deviceId, subjectPublicKey, invite.role, invite.department, serial, currentTimeMs)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return when (outcome) {
            is EnrollOutcome.Refused -> Result.failure(refusal(outcome.reason))
            is EnrollOutcome.Enrolled -> {
                try {
                    onEnrolled(EnrollmentEvent(outcome.device, outcome.invite, outcome.reissued))
                } catch (t: Throwable) {
                    // The device IS enrolled; the directory is reconciled at the next start. Never hide the success.
                    log.warn("enrolment hook failed for {}: {}", deviceId, t.javaClass.simpleName)
                }
                Result.success(
                    EnrollmentResult(
                        deviceCert = outcome.cert,
                        delegationChain = listOf(delegation),
                        rootFingerprint = serverId,
                        rootPublicKey = rootPublicKey,
                        invite = outcome.invite,
                        device = outcome.device,
                        reissued = outcome.reissued,
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ renew

    /**
     * Renews [presented] (already proven to be held by the caller: see [ChallengeResponseAuth.verifyRenewal]).
     *
     * The presented certificate must be one this hub signed (`issuerPublicKey` is the delegate key), carry a valid
     * signature, not be revoked, and be inside the renewal window ([RenewalPolicy]: a day before expiry, three days
     * of grace after). Role and department come from the REGISTRY, not from the presented certificate.
     */
    fun renew(presented: DeviceCert, currentTimeMs: Long = System.currentTimeMillis()): Result<EnrollmentResult> {
        val delegation = delegationCert
        if (delegations != null && !delegations.canIssue) {
            return Result.failure(IllegalStateException("Server delegation certificate is not active"))
        }
        if (revocations.isDelegationRevoked(delegationCert.serial)) {
            return Result.failure(IllegalStateException("Server delegation certificate was revoked by the organisation root"))
        }
        if (!delegation.isValidAt(currentTimeMs, 0L)) {
            return Result.failure(IllegalStateException("Server delegation certificate has expired"))
        }
        if (!HubRoles.isValid(presented.role) || !presented.verifySignature() ||
            presented.issuerPublicKey != delegation.delegatePublicKey
        ) {
            return Result.failure(SecurityException("The presented certificate was not issued by this hub"))
        }
        if (revocations.isRevoked(presented.serial, presented.deviceId)) {
            return Result.failure(SecurityException("This device has been revoked"))
        }
        if (!RenewalPolicy.isRenewable(presented, currentTimeMs, renewEarlyWindowMs, renewGraceMs)) {
            return Result.failure(IllegalArgumentException("The certificate is outside its renewal window"))
        }
        val outcome = try {
            store.renew(
                deviceId = presented.deviceId,
                presentedSerial = presented.serial,
                subjectPublicKey = presented.subjectPublicKey,
                nowMs = currentTimeMs,
                delegationSerial = delegation.serial,
            ) { serial, current ->
                val needed = HubRoles.enrolScope(current.role)
                val allowed = if (current.role == HubRoles.ADMIN) {
                    HubScopes.grantsExplicitly(delegation.scopes, needed)
                } else {
                    delegation.hasScope(needed)
                }
                if (!allowed) throw SecurityException("Server delegation lacks authority to issue role '${current.role}'")
                signLeaf(delegation, current.deviceId, current.subjectPublicKey, current.role, current.department, serial, currentTimeMs)
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return when (outcome) {
            is RenewOutcome.Refused -> Result.failure(
                when (outcome.reason) {
                    RenewRefusal.NOT_ENROLLED, RenewRefusal.UNKNOWN_SERIAL -> SecurityException("This device is not enrolled here")
                    RenewRefusal.NOT_ACTIVE -> SecurityException("This device is no longer enrolled")
                    RenewRefusal.KEY_MISMATCH -> SecurityException("The certificate key does not match the registered key")
                },
            )
            is RenewOutcome.Renewed -> Result.success(
                EnrollmentResult(
                    deviceCert = outcome.cert,
                    delegationChain = listOf(delegation),
                    rootFingerprint = serverId,
                    rootPublicKey = rootPublicKey,
                    device = outcome.device,
                ),
            )
        }
    }

    // ------------------------------------------------------------------------------------------------ internals

    private fun signLeaf(
        delegation: DelegationCert,
        deviceId: String,
        subjectPublicKey: String,
        role: String,
        department: String?,
        serial: Long,
        nowMs: Long,
    ): DeviceCert {
        // SEC-18: the leaf never outlives the delegation, and never predates it either (a delegation issued moments
        // ago must still contain the 10 s back-date).
        val validFrom = maxOf(nowMs - DeviceCert.ISSUE_BACKDATE_MS, delegation.validFrom)
        val validTo = minOf(nowMs + DeviceCert.DEFAULT_VALIDITY_MS, delegation.validTo)
        check(validTo > nowMs) { "Server delegation certificate has expired" }
        return DeviceCert.issue(
            issuerPrivateKey = serverPrivateKey,
            issuerPublicKey = delegation.delegatePublicKey,
            deviceId = deviceId,
            subjectPublicKey = subjectPublicKey,
            role = role,
            department = department,
            validFrom = validFrom,
            validTo = validTo,
            serial = serial,
        )
    }

    private fun refusal(reason: EnrollRefusal): Exception = when (reason) {
        EnrollRefusal.INVITE_UNKNOWN -> IllegalArgumentException("Invalid or unknown enrollment token")
        EnrollRefusal.INVITE_EXPIRED -> InviteRefusedException(InviteRefusal.EXPIRED, "Enrollment token has expired")
        EnrollRefusal.INVITE_USED -> InviteRefusedException(InviteRefusal.USED, "Enrollment token has already been consumed")
        EnrollRefusal.INVITE_REVOKED -> IllegalArgumentException("Enrollment token has been revoked")
        EnrollRefusal.DEVICE_REVOKED -> SecurityException("This device id has been revoked")
        EnrollRefusal.DEVICE_CONFLICT -> IllegalStateException("Device id is already enrolled with a different key")
        EnrollRefusal.QUOTA -> IllegalStateException("Server enrollment quota exceeded")
    }

    companion object {
        const val TOKEN_BYTES: Int = 32
        const val MAX_TOKEN_CHARS: Int = 128
        const val MAX_INVITE_USES: Int = 1000
    }
}
