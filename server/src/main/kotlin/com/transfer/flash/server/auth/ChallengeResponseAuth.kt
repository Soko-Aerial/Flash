package com.transfer.flash.server.auth

import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.ChainStatus
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.DeviceCert
import com.transfer.flash.core.hubprotocol.certs.EcdsaVerifier
import com.transfer.flash.core.hubprotocol.certs.HubAuth
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
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
) {
    /** The wire form carried by `FLASH_HUB_WELCOME` and `/auth/verify`. */
    fun toHubPrincipal(): HubPrincipal = HubPrincipal(
        deviceId = deviceId,
        role = role,
        departmentId = department,
        certSerial = certSerial,
    )
}

/**
 * Cryptographic challenge issued by the server.
 */
data class AuthChallenge(
    val serverNonce: String,
    val timestamp: Long,
    val ttlSeconds: Long = ChallengeResponseAuth.DEFAULT_TTL_SECONDS,
)

/**
 * An authentication failure with the wire error code it maps to ([HubErrorCodes]). The message is for the server log;
 * clients are told only the code and a fixed sentence (SEC-16).
 */
class AuthRejected(val code: String, message: String) : SecurityException(message)

/**
 * Challenge-Response authenticator (E4, ADR-109), the ONE implementation behind both the relay handshake and the REST
 * `/auth/verify` bearer exchange.
 *
 * The device proves possession of its identity key by signing
 * `HubAuth.authSigningBytes(serverId, serverNonce, clientNonce, deviceId, certSerial)`, so the signature is bound to
 * this realm (`serverId`, the root fingerprint), to a server-chosen single-use nonce, to a client-chosen nonce, and to
 * the exact certificate. The chain is checked with the shared rules in `:core:hub-protocol` (closed role set, scope
 * rules, leaf window inside the delegation window, skew at both ends, revoked certificate / device / delegation).
 *
 * Two entry points:
 * - [verifyChallenge]: REST. Consumes a nonce from this object's own single-use store ([generateChallenge]).
 * - [verifyIdentity]: stateless; for the relay, where the nonce is generated per connection by the router and can
 *   never be presented twice because the socket that received it is the only one that can answer it.
 *
 * @param revocationListProvider must return a list that is ALREADY trusted (signature and issuer verified by whoever
 *   installed it). Production passes `{ revocationService.current() }`; the persisted [RevocationService] is also
 *   consulted directly.
 */
class ChallengeResponseAuth(
    private val rootPublicKey: String,
    private val revocationListProvider: () -> RevocationList? = { null },
    private val secureRandom: SecureRandom = SecureRandom(),
    val serverId: String = FlashCrypto.sha256FingerprintHex(rootPublicKey),
    private val clockSkewToleranceMs: Long = DeviceCert.DEFAULT_CLOCK_SKEW_MS,
    private val verifier: EcdsaVerifier = JvmEcdsaVerifier,
    /** Authoritative, persisted revocation state (SEC-05); consulted in addition to [revocationListProvider]. */
    private val revocations: RevocationService = RevocationService.None,
    /** Upper bound on outstanding REST nonces: an unauthenticated flood can not grow memory without limit (SEC-09). */
    private val maxActiveChallenges: Int = DEFAULT_MAX_ACTIVE_CHALLENGES,
) {
    private val activeChallenges = ConcurrentHashMap<String, Long>()

    /** The organisation root key this hub trusts. */
    val trustedRootPublicKey: String get() = rootPublicKey

    /**
     * Generates a fresh 32-byte server nonce with a 60-second TTL.
     */
    fun generateChallenge(currentTimeMs: Long = System.currentTimeMillis()): AuthChallenge {
        if (activeChallenges.size >= maxActiveChallenges / 2) cleanExpired(currentTimeMs)
        if (activeChallenges.size >= maxActiveChallenges) {
            // Still full of live nonces: drop the oldest ones rather than refuse a legitimate device or grow forever.
            activeChallenges.entries.sortedBy { it.value }.take(maxActiveChallenges / 10 + 1).forEach { activeChallenges.remove(it.key) }
        }
        val nonceBase64 = newNonce()
        activeChallenges[nonceBase64] = currentTimeMs
        return AuthChallenge(
            serverNonce = nonceBase64,
            timestamp = currentTimeMs,
            ttlSeconds = DEFAULT_TTL_SECONDS,
        )
    }

    /** A fresh URL-safe random nonce that is NOT registered in the REST store (the relay router owns its nonces). */
    fun newNonce(): String {
        val nonceBytes = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
    }

    /** Number of outstanding REST nonces (tests, metrics). */
    val activeChallengeCount: Int get() = activeChallenges.size

    /** Removes expired nonces; called by a timer so an idle hub does not keep stale entries (SEC-09). */
    fun sweepExpired(currentTimeMs: Long = System.currentTimeMillis()) = cleanExpired(currentTimeMs)

    /**
     * Consumes a REST server nonce (single use). Returns false when it is unknown, already used or expired.
     */
    fun consumeNonce(serverNonce: String, currentTimeMs: Long = System.currentTimeMillis()): Boolean {
        val issuedAt = activeChallenges.remove(serverNonce) ?: return false
        return currentTimeMs - issuedAt <= TTL_MS
    }

    /**
     * Verifies the proof of possession of a RENEWAL request: the nonce is consumed (single use) and the signature
     * over `HubAuth.renewProofBytes` must verify with the key of [deviceCert]. The caller (EnrollmentService.renew)
     * then checks that this hub issued the certificate and that it is inside its renewal window.
     */
    fun verifyRenewal(
        serverNonce: String,
        clientNonce: String,
        deviceId: String,
        signature: String,
        deviceCert: DeviceCert,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): Result<Unit> {
        if (!consumeNonce(serverNonce, currentTimeMs)) {
            return Result.failure(AuthRejected(HubErrorCodes.ERR_AUTH, "Invalid or already consumed server nonce"))
        }
        if (!HubAuth.isValidDeviceId(deviceId) || deviceCert.deviceId != deviceId ||
            clientNonce.isBlank() || clientNonce.length > MAX_CLIENT_NONCE_CHARS
        ) {
            return Result.failure(AuthRejected(HubErrorCodes.ERR_AUTH, "Device id or nonce mismatch"))
        }
        val bytes = HubAuth.renewProofBytes(serverId, serverNonce, clientNonce, deviceId, deviceCert.serial, deviceCert.subjectPublicKey)
        val ok = try {
            verifier.verify(Base64.getDecoder().decode(deviceCert.subjectPublicKey), bytes, Base64.getDecoder().decode(signature))
        } catch (_: Exception) {
            false
        }
        return if (ok) Result.success(Unit) else Result.failure(AuthRejected(HubErrorCodes.ERR_AUTH, "Invalid renewal signature"))
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

        return verifyIdentity(serverNonce, clientNonce, deviceId, signature, deviceCert, delegationCert, currentTimeMs)
    }

    /**
     * Stateless identity check: device id binding, certificate chain (incl. revocation and windows), then the
     * possession signature. The caller owns nonce freshness and single use.
     */
    fun verifyIdentity(
        serverNonce: String,
        clientNonce: String,
        deviceId: String,
        signature: String,
        deviceCert: DeviceCert,
        delegationCert: DelegationCert? = null,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): Result<ServerDevicePrincipal> {
        if (!HubAuth.isValidDeviceId(deviceId) || deviceCert.deviceId != deviceId) {
            return Result.failure(AuthRejected(HubErrorCodes.ERR_AUTH, "Device ID in certificate does not match caller device ID"))
        }
        if (clientNonce.isBlank() || clientNonce.length > MAX_CLIENT_NONCE_CHARS || serverNonce.isBlank()) {
            return Result.failure(AuthRejected(HubErrorCodes.ERR_AUTH, "Missing or oversized nonce"))
        }

        // Certificate chain, revocation (certificate serial, device id, delegation serial) and time windows.
        val status = deviceCert.checkChain(
            delegation = delegationCert,
            rootPublicKey = rootPublicKey,
            verifier = verifier,
            timestamp = currentTimeMs,
            revocations = revocationListProvider(),
            clockSkewToleranceMs = clockSkewToleranceMs,
        )
        if (status == ChainStatus.OK && revocations.isRevoked(deviceCert.serial, deviceId)) {
            return Result.failure(
                AuthRejected(HubErrorCodes.ERR_REVOKED, "Device certificate (serial ${deviceCert.serial}) or deviceId ($deviceId) is revoked"),
            )
        }
        if (status != ChainStatus.OK) {
            val detail = when (status) {
                ChainStatus.REVOKED -> "Device certificate (serial ${deviceCert.serial}) or deviceId ($deviceId) is revoked"
                else -> "Device certificate chain verification failed: $status"
            }
            return Result.failure(AuthRejected(status.toErrorCode(), detail))
        }

        // Possession of the key the certificate binds.
        val signedBytes = HubAuth.authSigningBytes(
            serverId = serverId,
            serverNonce = serverNonce,
            clientNonce = clientNonce,
            deviceId = deviceId,
            certSerial = deviceCert.serial,
        )
        val signatureValid = try {
            verifier.verify(
                publicKeySpki = Base64.getDecoder().decode(deviceCert.subjectPublicKey),
                data = signedBytes,
                signature = Base64.getDecoder().decode(signature),
            )
        } catch (_: Exception) {
            false
        }
        if (!signatureValid) {
            return Result.failure(AuthRejected(HubErrorCodes.ERR_AUTH, "Invalid challenge signature"))
        }

        return Result.success(
            ServerDevicePrincipal(
                deviceId = deviceCert.deviceId,
                role = deviceCert.role,
                department = deviceCert.department,
                certSerial = deviceCert.serial,
            ),
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
        const val MAX_CLIENT_NONCE_CHARS: Int = 128
        const val DEFAULT_MAX_ACTIVE_CHALLENGES: Int = 20_000
    }
}
