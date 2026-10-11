package com.transfer.flash.server.auth

import com.transfer.flash.core.security.crypto.FlashCrypto
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Scope associated with an enrollment invite token.
 */
data class InviteScope(
    val department: String? = null,
    val allowedGroups: Set<String> = emptySet(),
    val role: String = DeviceCert.ROLE_MEMBER,
    val expiry: Long,
    val maxUses: Int = 1,
)

/**
 * Internal record for tracking single-use or scoped invite tokens.
 */
data class InviteTokenRecord(
    val token: String,
    val scope: InviteScope,
    val createdAt: Long,
    val remainingUses: AtomicInteger,
)

/**
 * Result returned upon successful device enrollment.
 */
data class EnrollmentResult(
    val deviceCert: DeviceCert,
    val delegationChain: List<DelegationCert>,
    val rootFingerprint: String,
)

/**
 * Enrollment Service for the standalone server module (E2b, E2c, ENTERPRISE-HYBRID-PLAN).
 *
 * Provides:
 * 1. Single-use invite token generator for `flash://enroll?server=<url>&token=<single-use>&root=<fingerprint>`.
 * 2. In-memory / persistent registry tracking invite tokens, departmental scopes, and expirations.
 * 3. Enrolment execution: validates single-use token, checks delegation quota, issues signed [DeviceCert]
 *    via server delegated key, decrements remaining device quota, and returns [EnrollmentResult].
 */
class EnrollmentService(
    val rootPublicKey: String,
    val serverPrivateKey: PrivateKey,
    val delegationCert: DelegationCert,
    initialQuota: Int = delegationCert.maxDevices,
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    private val tokenRegistry = ConcurrentHashMap<String, InviteTokenRecord>()
    private val deviceSerialSeq = AtomicLong(1000L)
    private val remainingQuota = AtomicInteger(initialQuota)

    val currentRemainingQuota: Int
        get() = remainingQuota.get()

    /**
     * Generates a single-use invite token and constructs the deep link:
     * `flash://enroll?server=<url>&token=<token>&root=<fingerprint>`
     */
    fun generateInviteLink(
        serverUrl: String,
        scope: InviteScope,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): String {
        val tokenBytes = ByteArray(TOKEN_BYTES).also(secureRandom::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)

        val record = InviteTokenRecord(
            token = token,
            scope = scope,
            createdAt = currentTimeMs,
            remainingUses = AtomicInteger(scope.maxUses),
        )
        tokenRegistry[token] = record

        val rootFp = FlashCrypto.sha256FingerprintHex(rootPublicKey)
        val encodedServerUrl = URLEncoder.encode(serverUrl, StandardCharsets.UTF_8.toString())
        return "flash://enroll?server=$encodedServerUrl&token=$token&root=$rootFp"
    }

    /**
     * Looks up an invite token record without consuming it.
     */
    fun getTokenRecord(token: String): InviteTokenRecord? = tokenRegistry[token]

    /**
     * Enrolls a new device using an invite token.
     * Validates single-use, checks delegation quota, issues a signed [DeviceCert],
     * decrements remaining quota, and returns [EnrollmentResult].
     */
    fun enrollDevice(
        token: String,
        deviceId: String,
        subjectPublicKey: String,
        friendlyName: String? = null,
        currentTimeMs: Long = System.currentTimeMillis(),
    ): Result<EnrollmentResult> {
        // 1. Look up token in registry
        val record = tokenRegistry[token]
            ?: return Result.failure(IllegalArgumentException("Invalid or unknown enrollment token"))

        // 2. Check token expiration
        if (currentTimeMs > record.scope.expiry) {
            tokenRegistry.remove(token)
            return Result.failure(IllegalArgumentException("Enrollment token has expired"))
        }

        // 3. Atomically consume token use
        val usesLeft = record.remainingUses.decrementAndGet()
        if (usesLeft < 0) {
            tokenRegistry.remove(token)
            return Result.failure(IllegalArgumentException("Enrollment token has already been consumed"))
        }
        if (usesLeft == 0) {
            tokenRegistry.remove(token)
        }

        // 4. Validate server delegation validity and scopes
        if (!delegationCert.isValidAt(currentTimeMs)) {
            return Result.failure(IllegalStateException("Server delegation certificate has expired"))
        }
        if (!delegationCert.verifySignature()) {
            return Result.failure(IllegalStateException("Server delegation certificate signature is invalid"))
        }
        if (!delegationCert.hasScope("enrol:${record.scope.role}")) {
            return Result.failure(SecurityException("Server delegation lacks authority to issue role '${record.scope.role}'"))
        }

        // 5. Check and decrement server device quota
        val newQuota = remainingQuota.decrementAndGet()
        if (newQuota < 0) {
            remainingQuota.incrementAndGet() // rollback
            return Result.failure(IllegalStateException("Server enrollment quota exceeded"))
        }

        // 6. Validate subject public key formatting
        try {
            val keyBytes = Base64.getDecoder().decode(subjectPublicKey)
            if (keyBytes.isEmpty()) {
                return Result.failure(IllegalArgumentException("Subject public key cannot be empty"))
            }
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Malformed subject public key: ${e.message}"))
        }

        // 7. Issue new DeviceCert signed by server delegated private key
        val serial = deviceSerialSeq.incrementAndGet()
        val validFrom = currentTimeMs
        val validTo = currentTimeMs + DeviceCert.DEFAULT_VALIDITY_MS

        val cert = DeviceCert.issue(
            issuerPrivateKey = serverPrivateKey,
            issuerPublicKey = delegationCert.delegatePublicKey,
            deviceId = deviceId,
            subjectPublicKey = subjectPublicKey,
            role = record.scope.role,
            department = record.scope.department,
            validFrom = validFrom,
            validTo = validTo,
            serial = serial,
        )

        val rootFp = FlashCrypto.sha256FingerprintHex(rootPublicKey)
        return Result.success(
            EnrollmentResult(
                deviceCert = cert,
                delegationChain = listOf(delegationCert),
                rootFingerprint = rootFp,
            )
        )
    }

    companion object {
        const val TOKEN_BYTES: Int = 32
    }
}
