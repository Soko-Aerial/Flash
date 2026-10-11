package com.transfer.flash.server.auth

import com.transfer.flash.core.security.crypto.FlashCrypto
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Authenticated device principal resulting from a verified challenge-response handshake.
 */
data class ServerDevicePrincipal(
    val deviceId: String,
    val role: String,
    val department: String?,
    val certSerial: Long,
)

/**
 * Cryptographic challenge issued by the server.
 */
data class AuthChallenge(
    val serverNonce: String,
    val timestamp: Long,
    val ttlSeconds: Long = ChallengeResponseAuth.DEFAULT_TTL_SECONDS,
)

/**
 * Challenge-Response Handshake Authenticator (E4, ENTERPRISE-HYBRID-PLAN).
 *
 * Verifies peer possession of their identity key via an ephemeral challenge:
 * 1. Server generates a cryptographically secure 32-byte nonce with a 60-second TTL.
 * 2. Client returns `(serverNonce, clientNonce, deviceId, signature, deviceCert, delegationChain)`.
 * 3. Client signature covers `(serverNonce + clientNonce + deviceId)` signed by client's identity key.
 * 4. Server verifies nonce freshness and single-use, certificate chain validity, revocation status,
 *    and cryptographic signature against `subjectPublicKey` from the cert.
 */
class ChallengeResponseAuth(
    private val rootPublicKey: String,
    private val revocationListProvider: () -> RevocationList? = { null },
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val activeChallenges = ConcurrentHashMap<String, Long>()

    /**
     * Generates a fresh 32-byte server nonce with a 60-second TTL.
     */
    fun generateChallenge(currentTimeMs: Long = System.currentTimeMillis()): AuthChallenge {
        cleanExpired(currentTimeMs)
        val nonceBytes = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val nonceBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        activeChallenges[nonceBase64] = currentTimeMs
        return AuthChallenge(
            serverNonce = nonceBase64,
            timestamp = currentTimeMs,
            ttlSeconds = DEFAULT_TTL_SECONDS,
        )
    }

    /**
     * Verifies the client's challenge-response and device certificate.
     * Consumes the server nonce on verification attempt to prevent replay attacks.
     */
    fun verifyChallenge(
        serverNonce: String,
        clientNonce: String,
        deviceId: String,
        signature: String,
        deviceCert: DeviceCert,
        delegationCert: DelegationCert? = null,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): Result<ServerDevicePrincipal> {
        // 1. Verify and consume server nonce (single-use)
        val issuedAt = activeChallenges.remove(serverNonce)
            ?: return Result.failure(IllegalArgumentException("Invalid or already consumed server nonce"))

        if (currentTimeMs - issuedAt > TTL_MS) {
            return Result.failure(IllegalArgumentException("Server nonce expired"))
        }

        // 2. Validate device ID binding
        if (deviceCert.deviceId != deviceId) {
            return Result.failure(SecurityException("Device ID in certificate does not match caller device ID"))
        }

        // 3. Check revocation status
        val revList = revocationListProvider()
        if (revList != null && revList.isRevoked(deviceCert)) {
            return Result.failure(SecurityException("Device certificate (serial ${deviceCert.serial}) or deviceId ($deviceId) is revoked"))
        }

        // 4. Verify certificate chain against the trusted root key
        if (!deviceCert.verifyChain(delegationCert, rootPublicKey, currentTimeMs)) {
            return Result.failure(SecurityException("Device certificate chain verification failed"))
        }

        // 5. Verify client's ECDSA signature over (serverNonce + clientNonce + deviceId)
        val challengePayload = (serverNonce + clientNonce + deviceId).encodeToByteArray()
        val signatureValid = FlashCrypto.verifySignature(
            publicKeyBase64 = deviceCert.subjectPublicKey,
            data = challengePayload,
            signatureBase64 = signature,
        )
        if (!signatureValid) {
            return Result.failure(SecurityException("Invalid challenge signature"))
        }

        // Success: return authenticated principal
        return Result.success(
            ServerDevicePrincipal(
                deviceId = deviceCert.deviceId,
                role = deviceCert.role,
                department = deviceCert.department,
                certSerial = deviceCert.serial,
            )
        )
    }

    private fun cleanExpired(currentTimeMs: Long) {
        val iterator = activeChallenges.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTimeMs - entry.value > TTL_MS) {
                iterator.remove()
            }
        }
    }

    companion object {
        const val NONCE_BYTES: Int = 32
        const val DEFAULT_TTL_SECONDS: Long = 60L
        const val TTL_MS: Long = DEFAULT_TTL_SECONDS * 1000L
    }
}
