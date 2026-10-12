package com.transfer.flash.server.admin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class AdminUser(
    val username: String,
    val passwordHash: String,
    val totpSecret: String,
    val role: String = "ORG_ADMIN",
    val enrolledDeviceId: String? = null
)

data class AdminSession(
    val token: String,
    val username: String,
    val role: String,
    val createdAtMs: Long,
    val expiresAtMs: Long
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

data class LockoutStatus(
    val isLocked: Boolean,
    val remainingSeconds: Long = 0
)

sealed interface AuthResult {
    data class Success(val session: AdminSession) : AuthResult
    data class Locked(val remainingSeconds: Long) : AuthResult
    data class InvalidCredentials(val remainingAttempts: Int) : AuthResult
    data class SetupRequired(val message: String) : AuthResult
}

/**
 * Authentication and authorization service for Flash Web Admin Dashboard.
 *
 * Implements owner decision D-E12:
 * - Username + PBKDF2WithHmacSHA256 password hashing (100,000 iterations, 16-byte salt).
 * - Mandatory RFC 6238 TOTP 2FA (6 digits, 30s step).
 * - First-run one-time setup token on server console if no admin exists.
 * - Secure 2-hour session tokens.
 * - Brute-force rate limiting: 5 failed attempts locks IP/account for 15 minutes.
 */
class AdminAuthService(
    private val printSetupTokenToConsole: Boolean = true,
    private val setupUrl: String? = null,
) {
    companion object {
        const val PBKDF2_ITERATIONS: Int = 100_000
        const val SALT_LENGTH_BYTES: Int = 16
        const val KEY_LENGTH_BITS: Int = 256
        const val TOTP_STEP_SECONDS: Long = 30L
        const val TOTP_DIGITS: Int = 6
        const val SESSION_TTL_MS: Long = 2 * 60 * 60 * 1000L // 2 hours
        const val MAX_FAILED_ATTEMPTS: Int = 5
        const val LOCKOUT_DURATION_MS: Long = 15 * 60 * 1000L // 15 minutes
    }

    private val secureRandom = SecureRandom()
    private val admins = ConcurrentHashMap<String, AdminUser>()
    private val sessions = ConcurrentHashMap<String, AdminSession>()
    private val failedAttempts = ConcurrentHashMap<String, FailureTracker>()

    private var activeSetupToken: String? = null

    init {
        checkAndInitializeFirstRun()
    }

    private fun checkAndInitializeFirstRun() {
        if (admins.isEmpty() && activeSetupToken == null) {
            val tokenBytes = ByteArray(24)
            secureRandom.nextBytes(tokenBytes)
            val token = tokenBytes.toHex()
            activeSetupToken = token
            if (printSetupTokenToConsole) {
                val targetUrl = setupUrl ?: "http://localhost:8443/admin/setup"
                println("=".repeat(78))
                println("[FLASH ADMIN] First-run setup required!")
                println("[FLASH ADMIN] One-time setup token: $token")
                println("[FLASH ADMIN] Navigate to $targetUrl to initialize org admin.")
                println("=".repeat(78))
            }
        }
    }

    fun hasAdmins(): Boolean = admins.isNotEmpty()

    fun getActiveSetupTokenForTesting(): String? = activeSetupToken

    /**
     * Initializes the first organization admin using the one-time setup token.
     */
    fun consumeSetupToken(
        token: String,
        username: String,
        password: String,
        totpSecret: String,
        enrolledDeviceId: String? = null
    ): AdminUser {
        val currentToken = activeSetupToken
            ?: throw IllegalStateException("No active setup token available or setup already completed")
        if (token.trim() != currentToken) {
            throw SecurityException("Invalid setup token")
        }
        require(username.isNotBlank()) { "Username cannot be blank" }
        require(password.length >= 8) { "Password must be at least 8 characters" }
        require(totpSecret.isNotBlank()) { "TOTP secret cannot be blank" }

        val hash = hashPassword(password)
        val admin = AdminUser(
            username = username.trim(),
            passwordHash = hash,
            totpSecret = totpSecret.trim(),
            role = "ORG_ADMIN",
            enrolledDeviceId = enrolledDeviceId?.trim()
        )
        admins[admin.username] = admin
        activeSetupToken = null // Token is single-use and invalidated
        return admin
    }

    /**
     * Authenticates an admin using username, password, and TOTP second factor.
     */
    fun authenticate(
        username: String,
        password: String,
        totpCode: String,
        clientIp: String = "127.0.0.1"
    ): AuthResult {
        if (!hasAdmins()) {
            return AuthResult.SetupRequired("No admin exists. First-run setup required with setup token.")
        }

        val accountKey = "user:${username.trim().lowercase()}"
        val ipKey = "ip:$clientIp"

        // Check rate limit locks
        val accountLock = checkLockout(accountKey)
        if (accountLock.isLocked) {
            return AuthResult.Locked(accountLock.remainingSeconds)
        }
        val ipLock = checkLockout(ipKey)
        if (ipLock.isLocked) {
            return AuthResult.Locked(ipLock.remainingSeconds)
        }

        val admin = admins[username.trim()]
        if (admin == null) {
            val tracker = recordFailure(accountKey, ipKey)
            val remaining = (MAX_FAILED_ATTEMPTS - tracker.count).coerceAtLeast(0)
            return AuthResult.InvalidCredentials(remaining)
        }

        val passwordValid = verifyPassword(password, admin.passwordHash)
        if (!passwordValid) {
            val tracker = recordFailure(accountKey, ipKey)
            val remaining = (MAX_FAILED_ATTEMPTS - tracker.count).coerceAtLeast(0)
            return AuthResult.InvalidCredentials(remaining)
        }

        val totpValid = validateTotp(admin.totpSecret, totpCode)
        if (!totpValid) {
            val tracker = recordFailure(accountKey, ipKey)
            val remaining = (MAX_FAILED_ATTEMPTS - tracker.count).coerceAtLeast(0)
            return AuthResult.InvalidCredentials(remaining)
        }

        // Success: clear failed attempts
        failedAttempts.remove(accountKey)
        failedAttempts.remove(ipKey)

        val session = createSession(admin.username, admin.role)
        return AuthResult.Success(session)
    }

    // --- Session Management ---

    fun createSession(username: String, role: String): AdminSession {
        val tokenBytes = ByteArray(32)
        secureRandom.nextBytes(tokenBytes)
        val token = tokenBytes.toHex()
        val now = System.currentTimeMillis()
        val session = AdminSession(
            token = token,
            username = username,
            role = role,
            createdAtMs = now,
            expiresAtMs = now + SESSION_TTL_MS
        )
        sessions[token] = session
        return session
    }

    fun validateSession(token: String?): AdminSession? {
        if (token.isNullOrBlank()) return null
        val session = sessions[token] ?: return null
        if (session.isExpired()) {
            sessions.remove(token)
            return null
        }
        return session
    }

    fun invalidateSession(token: String) {
        sessions.remove(token)
    }

    // --- Password Hashing (PBKDF2WithHmacSHA256) ---

    fun hashPassword(password: String, salt: ByteArray = generateSalt()): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = skf.generateSecret(spec).encoded
        return "${salt.toHex()}:${hash.toHex()}"
    }

    fun verifyPassword(password: String, storedSaltAndHash: String): Boolean {
        val parts = storedSaltAndHash.split(":")
        if (parts.size != 2) return false
        val salt = parts[0].hexToBytes()
        val expectedHash = parts[1].hexToBytes()
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val actualHash = skf.generateSecret(spec).encoded
        return MessageDigest.isEqual(expectedHash, actualHash)
    }

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        return salt
    }

    // --- RFC 6238 TOTP ---

    fun generateTotpSecret(): String {
        val bytes = ByteArray(20) // 160 bits
        secureRandom.nextBytes(bytes)
        return encodeBase32(bytes)
    }

    fun generateTotp(
        secretBase32: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000L,
        stepSeconds: Long = TOTP_STEP_SECONDS,
        digits: Int = TOTP_DIGITS
    ): String {
        val keyBytes = decodeBase32(secretBase32)
        val counter = timeSeconds / stepSeconds
        val counterBytes = ByteArray(8)
        var v = counter
        for (i in 7 downTo 0) {
            counterBytes[i] = (v and 0xff).toByte()
            v = v ushr 8
        }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA1"))
        val hash = mac.doFinal(counterBytes)

        val offset = hash[hash.size - 1].toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)

        var modulo = 1
        repeat(digits) { modulo *= 10 }
        val otp = binary % modulo
        return otp.toString().padStart(digits, '0')
    }

    fun validateTotp(
        secretBase32: String,
        code: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000L,
        window: Int = 1
    ): Boolean {
        val sanitizedCode = code.trim()
        if (sanitizedCode.length != TOTP_DIGITS) return false

        for (offset in -window..window) {
            val stepTime = timeSeconds + (offset * TOTP_STEP_SECONDS)
            val expected = generateTotp(secretBase32, stepTime)
            if (constantTimeEquals(sanitizedCode, expected)) {
                return true
            }
        }
        return false
    }

    fun getOtpAuthUri(username: String, secretBase32: String, issuer: String = "Flash"): String {
        return "otpauth://totp/$issuer:$username?secret=$secretBase32&issuer=$issuer"
    }

    // --- Rate Limiting & Lockout ---

    private data class FailureTracker(
        var count: Int = 0,
        var lockedUntilMs: Long = 0
    )

    private fun checkLockout(key: String): LockoutStatus {
        val tracker = failedAttempts[key] ?: return LockoutStatus(false)
        val now = System.currentTimeMillis()
        if (tracker.lockedUntilMs > now) {
            val remainingSec = (tracker.lockedUntilMs - now) / 1000L
            return LockoutStatus(true, remainingSec)
        }
        if (tracker.lockedUntilMs != 0L && tracker.lockedUntilMs <= now) {
            // Lock expired
            failedAttempts.remove(key)
            return LockoutStatus(false)
        }
        return LockoutStatus(false)
    }

    private fun recordFailure(accountKey: String, ipKey: String): FailureTracker {
        val now = System.currentTimeMillis()
        val accountTracker = failedAttempts.compute(accountKey) { _, existing ->
            val tr = existing ?: FailureTracker()
            tr.count += 1
            if (tr.count >= MAX_FAILED_ATTEMPTS) {
                tr.lockedUntilMs = now + LOCKOUT_DURATION_MS
            }
            tr
        }!!

        failedAttempts.compute(ipKey) { _, existing ->
            val tr = existing ?: FailureTracker()
            tr.count += 1
            if (tr.count >= MAX_FAILED_ATTEMPTS) {
                tr.lockedUntilMs = now + LOCKOUT_DURATION_MS
            }
            tr
        }

        return accountTracker
    }

    fun resetLockout(identifier: String) {
        failedAttempts.remove("user:${identifier.trim().lowercase()}")
        failedAttempts.remove("ip:$identifier")
    }

    fun getAdmin(username: String): AdminUser? = admins[username]

    fun listAdmins(): List<AdminUser> = admins.values.toList()

    fun addAdminDirectlyForTesting(admin: AdminUser) {
        admins[admin.username] = admin
    }
}

// --- Cryptographic & Encoding Utilities ---

private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

fun encodeBase32(data: ByteArray): String {
    if (data.isEmpty()) return ""
    val out = StringBuilder((data.size * 8 + 4) / 5)
    var buffer = 0
    var bitsLeft = 0
    for (b in data) {
        buffer = (buffer shl 8) or (b.toInt() and 0xff)
        bitsLeft += 8
        while (bitsLeft >= 5) {
            bitsLeft -= 5
            val index = (buffer shr bitsLeft) and 0x1f
            out.append(BASE32_ALPHABET[index])
        }
    }
    if (bitsLeft > 0) {
        val index = (buffer shl (5 - bitsLeft)) and 0x1f
        out.append(BASE32_ALPHABET[index])
    }
    return out.toString()
}

fun decodeBase32(base32: String): ByteArray {
    val clean = base32.trim().uppercase().replace("=", "")
    val out = ArrayList<Byte>()
    var buffer = 0
    var bitsLeft = 0
    for (char in clean) {
        val index = BASE32_ALPHABET.indexOf(char)
        if (index == -1) continue
        buffer = (buffer shl 5) or index
        bitsLeft += 5
        if (bitsLeft >= 8) {
            bitsLeft -= 8
            out.add(((buffer shr bitsLeft) and 0xff).toByte())
        }
    }
    val result = ByteArray(out.size)
    for (i in out.indices) {
        result[i] = out[i]
    }
    return result
}

fun ByteArray.toHex(): String {
    val hexChars = "0123456789abcdef"
    val result = StringBuilder(size * 2)
    for (b in this) {
        val i = b.toInt() and 0xff
        result.append(hexChars[i shr 4])
        result.append(hexChars[i and 0x0f])
    }
    return result.toString()
}

fun String.hexToBytes(): ByteArray {
    val len = length
    require(len % 2 == 0) { "Hex string must have an even length" }
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        val high = Character.digit(this[i], 16)
        val low = Character.digit(this[i + 1], 16)
        require(high != -1 && low != -1) { "Invalid hex character in string" }
        data[i / 2] = ((high shl 4) + low).toByte()
        i += 2
    }
    return data
}

private fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].code xor b[i].code)
    }
    return result == 0
}
