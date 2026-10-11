package com.transfer.flash.server.admin

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class StepUpChallenge(
    val challengeId: String,
    val adminUsername: String,
    val deviceId: String,
    val action: String,
    val nonce: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    var verified: Boolean = false
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

/**
 * Step-up challenge authentication service for high-risk / dangerous administrative actions:
 * - Creating or revoking admins
 * - Issuing delegation certificates
 * - Root trust modification
 *
 * Implements requirement: Generates cryptographic challenge requiring signature from
 * admin's physical enrolled device (ECDSA P-256 / SHA256withECDSA).
 */
class StepUpAuth {

    companion object {
        const val DEFAULT_CHALLENGE_TTL_MS: Long = 5 * 60 * 1000L // 5 minutes
    }

    private val secureRandom = SecureRandom()
    private val challenges = ConcurrentHashMap<String, StepUpChallenge>()

    /**
     * Creates a new step-up challenge for an admin action.
     */
    fun createChallenge(
        adminUsername: String,
        deviceId: String,
        action: String,
        ttlMs: Long = DEFAULT_CHALLENGE_TTL_MS
    ): StepUpChallenge {
        val nonceBytes = ByteArray(32)
        secureRandom.nextBytes(nonceBytes)
        val nonce = nonceBytes.toHex()

        val now = System.currentTimeMillis()
        val challenge = StepUpChallenge(
            challengeId = UUID.randomUUID().toString(),
            adminUsername = adminUsername,
            deviceId = deviceId,
            action = action,
            nonce = nonce,
            createdAtMs = now,
            expiresAtMs = now + ttlMs,
            verified = false
        )
        challenges[challenge.challengeId] = challenge
        return challenge
    }

    fun getChallenge(challengeId: String): StepUpChallenge? {
        val c = challenges[challengeId] ?: return null
        if (c.isExpired()) {
            challenges.remove(challengeId)
            return null
        }
        return c
    }

    /**
     * Verifies ECDSA P-256 (SHA256withECDSA) signature of the challenge nonce using the
     * device's public key (X.509 encoded bytes or hex).
     */
    fun verifySignature(
        challengeId: String,
        signatureBytes: ByteArray,
        devicePublicKeyX509: ByteArray
    ): Boolean {
        val challenge = challenges[challengeId] ?: return false
        if (challenge.isExpired()) {
            challenges.remove(challengeId)
            return false
        }

        return try {
            val keySpec = X509EncodedKeySpec(devicePublicKeyX509)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey = keyFactory.generatePublic(keySpec)

            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(publicKey)
            sig.update(challenge.nonce.toByteArray(Charsets.UTF_8))

            val isValid = sig.verify(signatureBytes)
            if (isValid) {
                challenge.verified = true
            }
            isValid
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Verifies ECDSA signature using hex-encoded strings for signature and public key.
     */
    fun verifySignatureHex(
        challengeId: String,
        signatureDerHex: String,
        devicePublicKeyDerHex: String
    ): Boolean {
        return try {
            val sigBytes = signatureDerHex.trim().hexToBytes()
            val pubBytes = devicePublicKeyDerHex.trim().hexToBytes()
            verifySignature(challengeId, sigBytes, pubBytes)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Verifies that the challenge is valid, verified, matches the action, and consumes it
     * to prevent replay attacks.
     */
    fun isActionAuthorizedAndConsume(challengeId: String, expectedAction: String): Boolean {
        val challenge = challenges[challengeId] ?: return false
        if (challenge.isExpired()) {
            challenges.remove(challengeId)
            return false
        }
        if (!challenge.verified) {
            return false
        }
        if (challenge.action != expectedAction) {
            return false
        }

        // Single-use: remove challenge to prevent replay
        challenges.remove(challengeId)
        return true
    }
}
