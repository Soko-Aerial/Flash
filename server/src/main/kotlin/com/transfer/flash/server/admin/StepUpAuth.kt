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

    val ALL: Set<String> = setOf(REVOKE_DEVICE, UNREVOKE_DEVICE, DELEGATION_INSTALL, DELEGATION_RELOAD, ADMIN_CREATE, ADMIN_DISABLE, SETTINGS_CHANGE, REVOCATION_INSTALL, RECOVERY_REGENERATE)
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
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs

    fun proofBytes(): ByteArray = HubStepUp.proofBytes(challengeId, action, adminUsername, target, nonce)
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
        val signer = resolveSigner(adminUsername)
            ?: return Created.Refused("ERR_NO_DEVICE", "This administrator has no enrolled admin device to confirm with")
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
