package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.core.hubprotocol.certs.HubStepUp
import com.transfer.flash.server.auth.JvmEcdsaVerifier
import com.transfer.flash.server.auth.RevocationService
import com.transfer.flash.server.identity.DeviceStatus
import com.transfer.flash.server.identity.IdentityStore
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The actions that require a step-up proof (SEC-12). Each is one string in the signed bytes. */
object StepUpActions {
    const val REVOKE_DEVICE = "device.revoke"
    const val UNREVOKE_DEVICE = "device.unrevoke"
    const val DELEGATION_INSTALL = "delegation.install"
    const val DELEGATION_RELOAD = "delegation.reload"
    const val ADMIN_CREATE = "admin.create"
    const val ADMIN_DISABLE = "admin.disable"
    const val SETTINGS_CHANGE = "settings.change"
    const val REVOCATION_INSTALL = "revocation.install"
    const val RECOVERY_REGENERATE = "recovery.regenerate"

    /** Replacing the browser key / bound device of the signer (REV-11). With an approver phone bound this needs the phone, not the browser key. */
    const val SIGNER_REPLACE = "admin.signer.replace"

    /** Binding or replacing the approver phone itself (ADR-131). */
    const val APPROVER_CHANGE = "approver.change"

    val ALL: Set<String> = setOf(
        REVOKE_DEVICE, UNREVOKE_DEVICE, DELEGATION_INSTALL, DELEGATION_RELOAD, ADMIN_CREATE, ADMIN_DISABLE, SETTINGS_CHANGE,
        REVOCATION_INSTALL, RECOVERY_REGENERATE, SIGNER_REPLACE, APPROVER_CHANGE,
    )

    /**
     * The actions an administrator with an approver phone bound must ALSO have approved on that phone (ADR-131, FO-14, option C of
     * ADR-127 P-2): a stolen browser key plus a stolen session can no longer take over the hub. Everyday actions stay browser-only.
     */
    val PHONE_APPROVED: Set<String> = setOf(DELEGATION_INSTALL, ADMIN_CREATE, SIGNER_REPLACE, APPROVER_CHANGE)

    /** Actions where the browser key is NOT asked: its loss is exactly why the signer is being replaced. */
    val WITHOUT_BROWSER_KEY: Set<String> = setOf(SIGNER_REPLACE)
}

/** One outstanding step-up challenge; [proofBytes] is exactly what the administrator's device signs. */
class StepUpChallenge(
    val challengeId: String,
    val adminUsername: String,
    val deviceId: String,
    val action: String,
    val target: String,
    val nonce: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    @Volatile var verified: Boolean = false,
    /** The phone that must approve as well (ADR-131), or null when this challenge needs only the browser key. */
    val approverDeviceId: String? = null,
    @Volatile var approved: Boolean = false,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    val approvalRequired: Boolean get() = approverDeviceId != null

    /** Six digits both the console and the phone show. */
    val approvalCode: String get() = HubStepUp.approvalCode(challengeId, nonce)

    fun proofBytes(): ByteArray = HubStepUp.proofBytes(challengeId, action, adminUsername, target, nonce)

    fun approvalBytes(): ByteArray = HubStepUp.approvalBytes(challengeId, action, adminUsername, target, nonce)
}

/**
 * Step-up authorisation for dangerous console actions (SEC-12): the administrator proves, with the private key of the
 * device they enrolled, that THEY authorise THIS action on THIS target now.
 *
 * What makes it sound (the old version trusted a public key the client sent along, so anyone could "prove" anything):
 *  - the verifying key is resolved SERVER-SIDE, from the registry row of the device bound to the administrator, and only
 *    if that device is ACTIVE, not revoked and holds an admin role;
 *  - the signed bytes bind the challenge id, the action, the administrator, the target and a server-chosen nonce under
 *    their own domain tag ([HubStepUp.proofBytes]), so a proof for one action never authorises another or another target;
 *  - a challenge verifies once, authorises once ([authorize] consumes it), expires after [ttlMs], and the store is bounded.
 */
class StepUpAuth(
    private val admins: AdminAuthService,
    private val identities: IdentityStore,
    private val revocations: RevocationService = RevocationService.None,
    private val verifier: EcdsaVerifier = JvmEcdsaVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_CHALLENGE_TTL_MS,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    companion object {
        const val DEFAULT_CHALLENGE_TTL_MS: Long = 5 * 60 * 1000L
        const val MAX_OUTSTANDING: Int = 256
        const val MAX_PER_ADMIN: Int = 8
    }

    sealed interface Created {
        data class Ok(val challenge: StepUpChallenge) : Created
        data class Refused(val code: String, val message: String) : Created
    }

    private val challenges = ConcurrentHashMap<String, StepUpChallenge>()

    /** True when [admin] has an enrolled signer (an active admin-role device, or a browser console key) that can sign a proof. */
    fun hasUsableDevice(admin: String): Boolean = resolveSigner(admin) != null

    /**
     * Opens a challenge for [action] on [target]. Refused when [action] is not a step-up action, or the administrator has no
     * usable enrolled device (they must enrol one in the Flash app first).
     */
    fun createChallenge(adminUsername: String, action: String, target: String): Created {
        if (action !in StepUpActions.ALL) return Created.Refused("ERR_BAD_ACTION", "Unknown action")
        val needsBrowserKey = action !in StepUpActions.WITHOUT_BROWSER_KEY
        val approverId = admins.approverOf(adminUsername)
        // ADR-131: with a phone bound the dangerous actions need it. If that phone can no longer sign (revoked, disabled), refuse rather
        // than fall back to the browser key alone: that fallback is the very hole the phone closes. A recovery code unbinds it.
        val approver = if (approverId != null && action in StepUpActions.PHONE_APPROVED) {
            resolveApprover(approverId)
                ?: return Created.Refused("ERR_APPROVER_UNAVAILABLE", "The phone that approves this action is no longer available. Remove it with a recovery code.")
        } else {
            null
        }
        if (!needsBrowserKey && approver == null) {
            return Created.Refused("ERR_NO_APPROVER", "This action needs the administrator's approver phone, and none is bound")
        }
        val signer = if (needsBrowserKey) {
            resolveSigner(adminUsername) ?: return Created.Refused("ERR_NO_DEVICE", "This administrator has no enrolled admin device to confirm with")
        } else {
            Signer(approver!!.id, approver.spki)
        }
        val now = clock()
        evict(now)
        if (challenges.values.count { it.adminUsername == adminUsername } >= MAX_PER_ADMIN) {
            return Created.Refused("ERR_TOO_MANY", "Too many confirmations are open")
        }
        if (challenges.size >= MAX_OUTSTANDING) return Created.Refused("ERR_TOO_MANY", "Too many confirmations are open")
        val challenge = StepUpChallenge(
            challengeId = UUID.randomUUID().toString(),
            adminUsername = adminUsername,
            deviceId = signer.id,
            action = action,
            target = target.take(256),
            nonce = ByteArray(32).also(secureRandom::nextBytes).toHex(),
            createdAtMs = now,
            expiresAtMs = now + ttlMs,
            // A challenge that does not use the browser key is verified by the phone alone: nothing else will ever sign it.
            verified = !needsBrowserKey,
            approverDeviceId = approver?.id,
        )
        challenges[challenge.challengeId] = challenge
        return Created.Ok(challenge)
    }

    fun getChallenge(challengeId: String): StepUpChallenge? {
        val c = challenges[challengeId] ?: return null
        if (c.isExpired(clock())) {
            challenges.remove(challengeId)
            return null
        }
        return c
    }

    /**
     * Verifies [signature] (ECDSA, DER or the 64-byte IEEE P1363 form WebCrypto produces, see [ConsoleKeys]) over the challenge's
     * proof bytes with the key the SERVER resolves for the administrator. A wrong signature leaves the challenge open for a retry
     * only until it expires.
     */
    fun verify(challengeId: String, signature: ByteArray): Boolean {
        val challenge = getChallenge(challengeId) ?: return false
        if (challenge.verified) return false
        val key = resolveSigner(challenge.adminUsername) ?: return false
        // The signer must still be the one the challenge was made for.
        if (key.id != challenge.deviceId) return false
        val ok = try {
            ConsoleKeys.verify(Base64.getDecoder().decode(key.spki), challenge.proofBytes(), signature, verifier)
        } catch (_: Exception) {
            false
        }
        if (ok) challenge.verified = true
        return ok
    }

    /**
     * The approver phone's answer (ADR-131). Checked against the key the SERVER holds for the device bound to this challenge, over the
     * approval bytes (their own domain, so a browser proof is not an approval). Accepts once; a wrong signature changes nothing.
     */
    fun approve(challengeId: String, deviceId: String, signature: ByteArray): Boolean {
        val challenge = getChallenge(challengeId) ?: return false
        if (challenge.approverDeviceId == null || challenge.approverDeviceId != deviceId || challenge.approved) return false
        // The administrator must still be bound to this phone, and the phone must still be able to sign.
        if (admins.approverOf(challenge.adminUsername) != deviceId) return false
        val approver = resolveApprover(deviceId) ?: return false
        val ok = try {
            ConsoleKeys.verify(Base64.getDecoder().decode(approver.spki), challenge.approvalBytes(), signature, verifier)
        } catch (_: Exception) {
            false
        }
        if (ok) challenge.approved = true
        return ok
    }

    /** The confirmations waiting for [deviceId] to approve (it is the challenge's approver, the challenge is open and not yet approved). */
    fun pendingApprovals(deviceId: String): List<StepUpChallenge> {
        evict(clock())
        return challenges.values.filter { it.approverDeviceId == deviceId && !it.approved }.sortedBy { it.createdAtMs }
    }

    fun verifyBase64(challengeId: String, signatureBase64: String): Boolean = try {
        verify(challengeId, Base64.getDecoder().decode(signatureBase64))
    } catch (_: IllegalArgumentException) {
        false
    }

    /**
     * Authorises one action: the challenge must be verified, unexpired, made for exactly this admin, [action] and
     * [target]. It is consumed whether or not the match holds, so a proof can not be tried against several actions.
     */
    fun authorize(challengeId: String, adminUsername: String, action: String, target: String): Boolean {
        val challenge = challenges.remove(challengeId) ?: return false
        if (challenge.isExpired(clock())) return false
        return challenge.verified &&
            (!challenge.approvalRequired || challenge.approved) &&
            challenge.adminUsername == adminUsername &&
            challenge.action == action &&
            challenge.target == target.take(256)
    }

    fun outstanding(): Int {
        evict(clock())
        return challenges.size
    }

    private fun evict(now: Long) {
        challenges.entries.removeIf { it.value.isExpired(now) }
    }

    /** An approver phone: an ACTIVE, not revoked, admin-role device of this hub (ADR-131). Resolved server-side from the registry. */
    private fun resolveApprover(deviceId: String): Signer? {
        val device = identities.device(deviceId) ?: return null
        if (device.status != DeviceStatus.ACTIVE || device.role != DeviceCert.ROLE_ADMIN) return null
        if (revocations.isRevoked(device.serial, device.deviceId)) return null
        return Signer(device.deviceId, device.subjectPublicKey)
    }

    /** The key that may sign for an administrator: its id (device id or console key id) and the base64 SPKI. */
    private class Signer(val id: String, val spki: String)

    /** Resolved SERVER-SIDE from the registry or the console key table, never from the client. */
    private fun resolveSigner(adminUsername: String): Signer? {
        val admin = admins.getAdmin(adminUsername)?.takeUnless { it.disabled } ?: return null
        val id = admin.enrolledDeviceId ?: return null
        if (id.startsWith(AdminAuthService.CONSOLE_KEY_PREFIX)) {
            return admins.consoleKey(id, adminUsername)?.let { Signer(id, it) }
        }
        val device = identities.device(id) ?: return null
        if (device.status != DeviceStatus.ACTIVE) return null
        if (device.role != DeviceCert.ROLE_ADMIN) return null
        if (revocations.isRevoked(device.serial, device.deviceId)) return null
        return Signer(device.deviceId, device.subjectPublicKey)
    }
}
