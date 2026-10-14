package com.transfer.flash.server.admin

import com.transfer.flash.server.auth.SecretBox
import com.transfer.flash.server.auth.SecureFiles
import com.transfer.flash.server.storage.Migration
import com.transfer.flash.server.storage.Migrator
import com.transfer.flash.server.storage.ServerDatabase
import com.transfer.flash.server.storage.immediate
import com.transfer.flash.server.storage.longOrNull
import com.transfer.flash.server.storage.query
import com.transfer.flash.server.storage.queryOne
import com.transfer.flash.server.storage.scalarLong
import com.transfer.flash.server.storage.update
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class AdminUser(
    val username: String,
    val passwordHash: String,
    val totpSecret: String,
    val role: String = "ORG_ADMIN",
    val enrolledDeviceId: String? = null,
    val disabled: Boolean = false,
    val createdAtMs: Long = 0L,
    val createdBy: String? = null,
    /** The enrolled admin phone whose approval the most dangerous actions also need (ADR-131, FO-14); null = browser key only. */
    val approverDeviceId: String? = null,
    /** Console display name (Plan A1); empty for older rows. */
    val displayName: String = "",
    /**
     * The head's phone/desktop device id, chosen by the org admin (Plan A1 §3.1).
     * Different from [enrolledDeviceId] (the step-up signer) and [approverDeviceId]
     * (the FO-14 co-signing phone): this link only aligns announcement authors.
     */
    val linkedDeviceId: String? = null,
) {
    companion object {
        const val ROLE_ORG_ADMIN: String = "ORG_ADMIN"
        const val ROLE_DEPT_HEAD: String = "DEPT_HEAD"
    }
}

data class AdminSession(
    val token: String,
    val username: String,
    val role: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    /** Per-session anti-CSRF token: the console API requires it in `X-CSRF-Token` on every state-changing request. */
    val csrfToken: String = "",
    val lastSeenMs: Long = createdAtMs,
    /** Short public handle for listing and revoking a session (never the token itself). */
    val sessionId: String = "",
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs >= expiresAtMs
}

data class LockoutStatus(
    val isLocked: Boolean,
    val remainingSeconds: Long = 0
)

sealed interface AuthResult {
    data class Success(val session: AdminSession, val recoveryCodeUsed: Boolean = false) : AuthResult
    data class Locked(val remainingSeconds: Long) : AuthResult
    data class InvalidCredentials(val remainingAttempts: Int) : AuthResult
    data class SetupRequired(val message: String) : AuthResult
}

/** First-run setup handle: the server-generated TOTP secret the new administrator must enrol in an authenticator. */
data class SetupBegin(val setupId: String, val totpSecret: String, val otpAuthUri: String)

/** A newly created or reset administrator: secrets are returned ONCE and stored only as hashes / for TOTP use. */
data class AdminCredentials(
    val username: String,
    val password: String?,
    val totpSecret: String,
    val otpAuthUri: String,
    val recoveryCodes: List<String>,
)

/**
 * Authentication and authorization service for the Flash web admin console, persisted in SQLite (component `admin`) so
 * administrators, sessions and lockouts survive a restart and `/admin/setup` never reopens (SEC-09, SEC-10).
 *
 * - Passwords: PBKDF2-HMAC-SHA256, [PBKDF2_ITERATIONS] (600 000) iterations, 16-byte salt, stored as the versioned string
 *   `pbkdf2-sha256$iterations$saltHex$hashHex`. The legacy `saltHex:hashHex` (100 000) form still verifies and is
 *   upgraded on the next successful login.
 * - Second factor: RFC 6238 TOTP; each 30 s step is accepted ONCE per administrator (replay protection); 10 single-use
 *   recovery codes (stored as SHA-256) replace it when the authenticator is lost.
 * - Brute force: the attempt is RESERVED (counted) in one transaction BEFORE the password is checked, so parallel guesses
 *   can not outrun the counter; 5 failures lock the account and the address for 15 minutes. Unknown users cost the same
 *   time as known ones (a dummy hash is verified) and get the same answer.
 * - Setup: one token (file `<dataDir>/setup-token.txt`, owner-only, never printed in production) compared in constant time,
 *   consumed atomically with the creation of the first administrator; 5 wrong tokens lock setup for 15 minutes.
 * - Sessions: 256-bit random tokens stored hashed, absolute 2 h lifetime, 30 min idle timeout, at most 5 per
 *   administrator, each with its own CSRF token.
 *
 * @param printSetupTokenToConsole announce first-run setup on the console (the token FILE path, never the token).
 * @param devPrintSetupToken development only: also print the token itself.
 * @param passwordIterations iterations for NEW hashes; production keeps the default, tests may lower it.
 */
class AdminAuthService(
    private val printSetupTokenToConsole: Boolean = true,
    private val setupUrl: String? = null,
    private val db: ServerDatabase = ServerDatabase.inMemory(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val passwordIterations: Int = PBKDF2_ITERATIONS,
    private val setupTokenFile: Path? = null,
    private val explicitSetupToken: String? = null,
    private val devPrintSetupToken: Boolean = false,
    private val secureRandom: SecureRandom = SecureRandom(),
    /** Seals the authenticator seeds at rest (REV-03). [SecretBox.None] keeps them as given (tests). */
    private val secrets: SecretBox = SecretBox.None,
) {
    companion object {
        const val PBKDF2_ITERATIONS: Int = 600_000
        const val LEGACY_PBKDF2_ITERATIONS: Int = 100_000
        const val SALT_LENGTH_BYTES: Int = 16
        const val KEY_LENGTH_BITS: Int = 256
        const val TOTP_STEP_SECONDS: Long = 30L
        const val TOTP_DIGITS: Int = 6
        const val SESSION_TTL_MS: Long = 2 * 60 * 60 * 1000L // 2 hours
        const val SESSION_IDLE_MS: Long = 30 * 60 * 1000L
        const val MAX_SESSIONS_PER_ADMIN: Int = 5
        const val MAX_FAILED_ATTEMPTS: Int = 5
        const val LOCKOUT_DURATION_MS: Long = 15 * 60 * 1000L // 15 minutes
        const val MIN_PASSWORD_CHARS: Int = 10
        const val RECOVERY_CODE_COUNT: Int = 10
        const val COMPONENT: String = "admin"

        /** Prefix of the key id of a browser console key; a registry device id never starts with it (see [StepUpAuth]). */
        const val CONSOLE_KEY_PREFIX: String = "console-"
        const val PASSWORD_SCHEME: String = "pbkdf2-sha256"
        private const val FAILURE_DECAY_MS: Long = 60 * 60 * 1000L
        private const val MAX_PENDING_SETUPS: Int = 16
        private const val SETUP_TTL_MS: Long = 30 * 60 * 1000L
        private val USERNAME = Regex("^[A-Za-z0-9._@-]{3,64}$")

        val MIGRATIONS: List<Migration> = listOf(
            Migration(1, "admin: users, sessions, lockouts, recovery codes, settings") { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS admin_users(username TEXT PRIMARY KEY, password_hash TEXT NOT NULL, totp_secret TEXT NOT NULL, " +
                            "role TEXT NOT NULL, enrolled_device_id TEXT, disabled INTEGER NOT NULL DEFAULT 0, last_totp_step INTEGER NOT NULL DEFAULT -1, " +
                            "created_at INTEGER NOT NULL, created_by TEXT)",
                    )
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS admin_sessions(token_hash TEXT PRIMARY KEY, username TEXT NOT NULL, role TEXT NOT NULL, " +
                            "created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, last_seen INTEGER NOT NULL, csrf_token TEXT NOT NULL, ip TEXT)",
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_admin_sessions_user ON admin_sessions(username)")
                    st.execute("CREATE TABLE IF NOT EXISTS admin_lockouts(key TEXT PRIMARY KEY, failures INTEGER NOT NULL, locked_until INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                    st.execute("CREATE TABLE IF NOT EXISTS admin_recovery(code_hash TEXT PRIMARY KEY, username TEXT NOT NULL, created_at INTEGER NOT NULL, used_at INTEGER)")
                    st.execute("CREATE INDEX IF NOT EXISTS idx_admin_recovery_user ON admin_recovery(username)")
                    st.execute("CREATE TABLE IF NOT EXISTS admin_settings(key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL, updated_by TEXT)")
                }
            },
            Migration(2, "admin: browser console keys used to confirm dangerous actions (ADR-125)") { conn ->
                conn.createStatement().use { st ->
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS admin_console_keys(key_id TEXT PRIMARY KEY, username TEXT NOT NULL, spki TEXT NOT NULL, " +
                            "label TEXT, created_at INTEGER NOT NULL)",
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_admin_console_keys_user ON admin_console_keys(username)")
                }
            },
            Migration(3, "admin: the phone that co-signs the most dangerous actions (ADR-131, FO-14)") { conn ->
                conn.createStatement().use { it.execute("ALTER TABLE admin_users ADD COLUMN approver_device_id TEXT") }
            },
            Migration(4, "admin: department heads with scoped logins (Plan A1)") { conn ->
                conn.createStatement().use { st ->
                    st.execute("ALTER TABLE admin_users ADD COLUMN display_name TEXT NOT NULL DEFAULT ''")
                    st.execute("ALTER TABLE admin_users ADD COLUMN linked_device_id TEXT")
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS admin_user_departments(username TEXT NOT NULL, department_id TEXT NOT NULL, added_by TEXT NOT NULL, added_at INTEGER NOT NULL, " +
                            "PRIMARY KEY(username, department_id))",
                    )
                    st.execute("CREATE INDEX IF NOT EXISTS idx_admin_user_departments_dept ON admin_user_departments(department_id)")
                }
            },
        )
    }

    private val log = LoggerFactory.getLogger(AdminAuthService::class.java)
    private val setupLock = Any()

    @Volatile
    private var activeSetupToken: String? = null
    private var setupFailures = 0
    private var setupLockedUntil = 0L
    private val pendingSetups = LinkedHashMap<String, Pair<String, Long>>()
    private val issuedSecrets = LinkedHashMap<String, Long>()

    private val dummyHash: String by lazy { hashPassword("not-a-real-password-" + secureRandom.nextLong()) }

    init {
        Migrator(db).apply { register(COMPONENT, MIGRATIONS) }.migrate()
        checkAndInitializeFirstRun()
    }

    // ------------------------------------------------------------------------------------------------ setup

    private fun checkAndInitializeFirstRun() {
        if (hasAdmins()) {
            // A token file left over from before the first admin existed must not stay usable.
            setupTokenFile?.let { runCatching { Files.deleteIfExists(it) } }
            return
        }
        val token = explicitSetupToken?.takeIf { it.length >= 16 }
            ?: readTokenFile()
            ?: ByteArray(24).also(secureRandom::nextBytes).toHex().also { writeTokenFile(it) }
        activeSetupToken = token
        if (printSetupTokenToConsole) {
            val targetUrl = setupUrl ?: "https://<this hub>/admin/setup"
            println("=".repeat(78))
            println("[FLASH ADMIN] First-run setup required.")
            println(
                if (setupTokenFile != null) {
                    "[FLASH ADMIN] The one-time setup token is in the file: ${setupTokenFile.toAbsolutePath()}"
                } else {
                    "[FLASH ADMIN] The one-time setup token was supplied through the environment."
                },
            )
            if (devPrintSetupToken) println("[FLASH ADMIN] DEVELOPMENT ONLY - setup token: $token")
            println("[FLASH ADMIN] Open $targetUrl to create the first organisation administrator.")
            println("=".repeat(78))
        }
    }

    private fun readTokenFile(): String? {
        val file = setupTokenFile ?: return null
        if (!Files.isRegularFile(file)) return null
        return runCatching { Files.readString(file).trim() }.getOrNull()?.takeIf { it.length >= 16 }
    }

    private fun writeTokenFile(token: String) {
        val file = setupTokenFile ?: return
        SecureFiles.writeOwnerOnly(file, token.encodeToByteArray())
    }

    fun hasAdmins(): Boolean = db.withConnection { it.scalarLong("SELECT COUNT(*) FROM admin_users") > 0 }

    /** True while the first-run setup token can still be redeemed. */
    fun isSetupOpen(): Boolean = activeSetupToken != null && !hasAdmins()

    fun getActiveSetupTokenForTesting(): String? = activeSetupToken

    /**
     * Starts the first-run setup: the server picks the TOTP secret (the client never chooses key material).
     * REV-07: the one-time [token] is required HERE too (not only at completion), so nobody without it can fill the pending slots or
     * obtain a QR; a wrong token counts toward the same lockout as at completion. A full table is refused ([IllegalStateException]),
     * an earlier pending setup is never evicted.
     */
    fun beginSetup(token: String): SetupBegin {
        val secret = generateTotpSecret()
        val id = ByteArray(16).also(secureRandom::nextBytes).toHex()
        synchronized(setupLock) {
            val now = clock()
            checkSetupTokenLocked(token, now)
            pendingSetups.entries.removeIf { now - it.value.second > SETUP_TTL_MS }
            check(pendingSetups.size < MAX_PENDING_SETUPS) { "Too many setups are pending" }
            pendingSetups[id] = secret to now
        }
        return SetupBegin(id, secret, getOtpAuthUri("admin", secret))
    }

    /** Verifies the setup token in constant time and applies the failure lockout; the caller holds [setupLock]. */
    private fun checkSetupTokenLocked(token: String, now: Long) {
        val currentToken = activeSetupToken
            ?: throw IllegalStateException("No active setup token available or setup already completed")
        if (setupLockedUntil > now) throw SecurityException("Setup is temporarily locked. Try again later.")
        if (!constantTimeEquals(sha256(token.trim()), sha256(currentToken))) {
            setupFailures++
            if (setupFailures >= MAX_FAILED_ATTEMPTS) {
                setupLockedUntil = now + LOCKOUT_DURATION_MS
                setupFailures = 0
            }
            throw SecurityException("Invalid setup token")
        }
    }

    /** Completes setup started by [beginSetup]: checks the token, the password and a live code from the authenticator. */
    fun completeSetup(setupId: String, token: String, username: String, password: String, confirmCode: String, enrolledDeviceId: String? = null): AdminUser {
        val secret = synchronized(setupLock) { pendingSetups[setupId]?.first }
            ?: throw IllegalStateException("Setup session not found or expired")
        if (matchTotpStep(secret, confirmCode, clock() / 1000L) == null) {
            throw IllegalArgumentException("Invalid 6-digit code. Check your authenticator app.")
        }
        val admin = consumeSetupToken(token, username, password, secret, enrolledDeviceId)
        synchronized(setupLock) { pendingSetups.remove(setupId) }
        return admin
    }

    /**
     * Initializes the first organization admin using the one-time setup token. [totpSecret] must be one this service
     * generated ([generateTotpSecret] / [beginSetup]) and is validated as strict base32.
     */
    fun consumeSetupToken(
        token: String,
        username: String,
        password: String,
        totpSecret: String,
        enrolledDeviceId: String? = null
    ): AdminUser = synchronized(setupLock) {
        val now = clock()
        checkSetupTokenLocked(token, now)
        require(USERNAME.matches(username.trim())) { "Username must be 3-64 letters, digits or . _ @ -" }
        require(password.length >= MIN_PASSWORD_CHARS) { "Password must be at least $MIN_PASSWORD_CHARS characters" }
        val secret = totpSecret.trim().uppercase().trimEnd('=')
        require(isStrictBase32(secret) && secret.length >= 16) { "TOTP secret is not valid" }
        require(synchronized(issuedSecrets) { issuedSecrets.containsKey(secret) } || pendingSetups.values.any { it.first == secret }) {
            "TOTP secret was not issued by this server"
        }

        val admin = AdminUser(
            username = username.trim(),
            passwordHash = hashPassword(password),
            totpSecret = secret,
            role = "ORG_ADMIN",
            enrolledDeviceId = enrolledDeviceId?.trim()?.ifBlank { null },
            createdAtMs = now,
            createdBy = "setup",
        )
        db.immediate { conn ->
            check(conn.scalarLong("SELECT COUNT(*) FROM admin_users") == 0L) { "No active setup token available or setup already completed" }
            insertAdmin(conn, admin)
        }
        activeSetupToken = null // single use
        setupTokenFile?.let { runCatching { Files.deleteIfExists(it) } }
        admin
    }

    // ------------------------------------------------------------------------------------------------ login

    /**
     * Authenticates an admin using username, password, and a TOTP code (or a recovery code).
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
        val name = username.trim().take(64)
        val accountKey = "user:${name.lowercase()}"
        val ipKey = "ip:${clientIp.take(64)}"

        // Reserve this attempt BEFORE any secret is checked (SEC-10).
        val reservation = reserve(listOf(accountKey, ipKey))
        if (reservation.lockedSeconds > 0) return AuthResult.Locked(reservation.lockedSeconds)
        val remaining = (MAX_FAILED_ATTEMPTS - reservation.maxFailures).coerceAtLeast(0)

        val admin = loadAdmin(name)
        // Unknown and disabled administrators cost one password hash like everybody else.
        val passwordOk = verifyPassword(password, admin?.passwordHash ?: dummyHash) && admin != null && !admin.disabled
        if (!passwordOk) return AuthResult.InvalidCredentials(remaining)

        val second = verifySecondFactor(admin!!, totpCode)
        if (second == SecondFactor.NONE) return AuthResult.InvalidCredentials(remaining)

        // Success: clear the counters and upgrade an old password hash.
        db.withConnection { it.update("DELETE FROM admin_lockouts WHERE key IN (?, ?)", accountKey, ipKey) }
        if (passwordNeedsUpgrade(admin.passwordHash)) {
            db.withConnection { it.update("UPDATE admin_users SET password_hash = ? WHERE username = ?", hashPassword(password), admin.username) }
        }
        return AuthResult.Success(createSession(admin.username, admin.role, clientIp), recoveryCodeUsed = second == SecondFactor.RECOVERY)
    }

    private enum class SecondFactor { NONE, TOTP, RECOVERY }

    private fun verifySecondFactor(admin: AdminUser, code: String): SecondFactor {
        val trimmed = code.trim()
        if (trimmed.length == TOTP_DIGITS && trimmed.all { it.isDigit() }) {
            return if (consumeTotp(admin.username, admin.totpSecret, trimmed)) SecondFactor.TOTP else SecondFactor.NONE
        }
        return if (consumeRecoveryCode(admin.username, trimmed)) SecondFactor.RECOVERY else SecondFactor.NONE
    }

    /** Accepts [code] once: its step must be newer than the last step this administrator used. */
    private fun consumeTotp(username: String, secret: String, code: String): Boolean {
        val step = matchTotpStep(secret, code, clock() / 1000L) ?: return false
        val changed = db.withConnection {
            it.update("UPDATE admin_users SET last_totp_step = ? WHERE username = ? AND last_totp_step < ?", step, username, step)
        }
        return changed == 1
    }

    /** True when [code] is a fresh authenticator code for [username] (used to re-confirm sensitive changes). */
    fun confirmFreshTotp(username: String, code: String): Boolean {
        val admin = loadAdmin(username) ?: return false
        return consumeTotp(admin.username, admin.totpSecret, code.trim())
    }

    /** True when [username] has a signer for step-up confirmations (a browser key or a bound admin device). */
    fun hasStepUpSigner(username: String): Boolean = loadAdmin(username)?.enrolledDeviceId != null

    /** Consumes one recovery code of [username] (a replacement of the step-up signer needs one: REV-11). */
    fun confirmRecoveryCode(username: String, code: String): Boolean = consumeRecoveryCode(username, code.trim())

    private fun consumeRecoveryCode(username: String, raw: String): Boolean {
        val normalized = raw.uppercase().filter { it.isLetterOrDigit() }
        if (normalized.length != 10) return false
        val changed = db.withConnection {
            it.update(
                "UPDATE admin_recovery SET used_at = ? WHERE code_hash = ? AND username = ? AND used_at IS NULL",
                clock(), sha256Hex(normalized), username,
            )
        }
        return changed == 1
    }

    private class Reservation(val lockedSeconds: Long, val maxFailures: Int)

    /** Atomically checks the locks and counts one more attempt on every key. */
    private fun reserve(keys: List<String>): Reservation = db.immediate { conn ->
        val now = clock()
        conn.update("DELETE FROM admin_lockouts WHERE locked_until < ? AND updated_at < ?", now, now - FAILURE_DECAY_MS)
        var lockedFor = 0L
        var maxFailures = 0
        val states = keys.map { key ->
            val row = conn.queryOne("SELECT failures, locked_until, updated_at FROM admin_lockouts WHERE key = ?", key) {
                Triple(it.getInt(1), it.getLong(2), it.getLong(3))
            }
            val expired = row != null && (row.second in 1..now || now - row.third > FAILURE_DECAY_MS)
            Triple(key, if (row == null || expired) 0 else row.first, if (row == null || expired) 0L else row.second)
        }
        for ((_, _, lockedUntil) in states) {
            if (lockedUntil > now) lockedFor = maxOf(lockedFor, (lockedUntil - now + 999) / 1000)
        }
        if (lockedFor > 0) return@immediate Reservation(lockedFor, MAX_FAILED_ATTEMPTS)
        for ((key, failures, _) in states) {
            val next = failures + 1
            val lockedUntil = if (next >= MAX_FAILED_ATTEMPTS) now + LOCKOUT_DURATION_MS else 0L
            conn.update(
                "INSERT INTO admin_lockouts(key, failures, locked_until, updated_at) VALUES(?,?,?,?) " +
                    "ON CONFLICT(key) DO UPDATE SET failures = excluded.failures, locked_until = excluded.locked_until, updated_at = excluded.updated_at",
                key, next, lockedUntil, now,
            )
            maxFailures = maxOf(maxFailures, next)
        }
        Reservation(0, maxFailures)
    }

    fun resetLockout(identifier: String) {
        db.withConnection {
            it.update("DELETE FROM admin_lockouts WHERE key IN (?, ?)", "user:${identifier.trim().lowercase()}", "ip:$identifier")
        }
    }

    fun isLockedOut(username: String): LockoutStatus {
        val now = clock()
        val until = db.withConnection { it.queryOne("SELECT locked_until FROM admin_lockouts WHERE key = ?", "user:${username.trim().lowercase()}") { r -> r.getLong(1) } } ?: 0L
        return if (until > now) LockoutStatus(true, (until - now) / 1000) else LockoutStatus(false)
    }

    // ------------------------------------------------------------------------------------------------ sessions

    fun createSession(username: String, role: String, clientIp: String = ""): AdminSession {
        val token = ByteArray(32).also(secureRandom::nextBytes).toHex()
        val csrf = ByteArray(24).also(secureRandom::nextBytes).toHex()
        val now = clock()
        val session = AdminSession(
            token = token,
            username = username,
            role = role,
            createdAtMs = now,
            expiresAtMs = now + SESSION_TTL_MS,
            csrfToken = csrf,
            lastSeenMs = now,
            sessionId = sha256Hex(token).take(12),
        )
        db.immediate { conn ->
            conn.update("DELETE FROM admin_sessions WHERE expires_at <= ? OR last_seen <= ?", now, now - SESSION_IDLE_MS)
            // Cap per administrator: the oldest sessions go first.
            val keep = MAX_SESSIONS_PER_ADMIN - 1
            conn.update(
                "DELETE FROM admin_sessions WHERE username = ? AND token_hash NOT IN " +
                    "(SELECT token_hash FROM admin_sessions WHERE username = ? ORDER BY created_at DESC LIMIT ?)",
                username, username, keep,
            )
            conn.update(
                "INSERT INTO admin_sessions(token_hash, username, role, created_at, expires_at, last_seen, csrf_token, ip) VALUES(?,?,?,?,?,?,?,?)",
                sha256Hex(token), username, role, now, session.expiresAtMs, now, csrf, clientIp.take(64),
            )
        }
        return session
    }

    fun validateSession(token: String?): AdminSession? {
        if (token.isNullOrBlank() || token.length > 128) return null
        val now = clock()
        val hash = sha256Hex(token)
        val session = db.withConnection { conn ->
            conn.queryOne("SELECT username, role, created_at, expires_at, last_seen, csrf_token FROM admin_sessions WHERE token_hash = ?", hash) {
                AdminSession(token, it.getString(1), it.getString(2), it.getLong(3), it.getLong(4), it.getString(6), it.getLong(5), hash.take(12))
            }
        } ?: return null
        val stillAdmin = session.username.let { u -> loadAdmin(u)?.takeUnless { it.disabled } != null } || isTestPrincipal(session.username)
        if (session.isExpired(now) || now - session.lastSeenMs > SESSION_IDLE_MS || !stillAdmin) {
            invalidateSession(token)
            return null
        }
        db.withConnection { it.update("UPDATE admin_sessions SET last_seen = ? WHERE token_hash = ?", now, hash) }
        return session.copy(lastSeenMs = now)
    }

    /** Sessions created directly for a name that has no admin row (unit tests of the routes) stay valid. */
    private fun isTestPrincipal(username: String): Boolean = loadAdmin(username) == null && !hasAdmins()

    fun invalidateSession(token: String) {
        db.withConnection { it.update("DELETE FROM admin_sessions WHERE token_hash = ?", sha256Hex(token)) }
    }

    fun sweepSessions() {
        val now = clock()
        db.withConnection { it.update("DELETE FROM admin_sessions WHERE expires_at <= ? OR last_seen <= ?", now, now - SESSION_IDLE_MS) }
    }

    fun listSessions(username: String? = null): List<AdminSession> = db.withConnection { conn ->
        val sql = "SELECT token_hash, username, role, created_at, expires_at, last_seen FROM admin_sessions" +
            (if (username != null) " WHERE username = ?" else "") + " ORDER BY created_at DESC"
        val params = if (username != null) arrayOf<Any?>(username) else emptyArray()
        conn.query(sql, *params) { AdminSession("", it.getString(2), it.getString(3), it.getLong(4), it.getLong(5), "", it.getLong(6), it.getString(1).take(12)) }
    }

    /** Revokes one session by its public id; returns whether one existed. */
    fun revokeSession(sessionId: String, username: String? = null): Boolean = db.withConnection { conn ->
        val rows = if (username != null) {
            conn.update("DELETE FROM admin_sessions WHERE substr(token_hash, 1, 12) = ? AND username = ?", sessionId, username)
        } else {
            conn.update("DELETE FROM admin_sessions WHERE substr(token_hash, 1, 12) = ?", sessionId)
        }
        rows > 0
    }

    fun revokeAllSessions(username: String): Int = db.withConnection { it.update("DELETE FROM admin_sessions WHERE username = ?", username) }

    // ------------------------------------------------------------------------------------------------ admin management

    fun getAdmin(username: String): AdminUser? = loadAdmin(username)

    fun listAdmins(): List<AdminUser> = db.withConnection { conn ->
        conn.query("SELECT * FROM admin_users ORDER BY created_at, username", map = ::adminRow)
    }

    /** Creates another administrator; the server generates the TOTP secret and the recovery codes (returned once). */
    fun createAdmin(username: String, password: String, createdBy: String, role: String = "ORG_ADMIN"): AdminCredentials {
        require(USERNAME.matches(username.trim())) { "Username must be 3-64 letters, digits or . _ @ -" }
        require(password.length >= MIN_PASSWORD_CHARS) { "Password must be at least $MIN_PASSWORD_CHARS characters" }
        val secret = generateTotpSecret()
        val admin = AdminUser(username.trim(), hashPassword(password), secret, role, null, false, clock(), createdBy)
        db.immediate { conn ->
            check(conn.scalarLong("SELECT COUNT(*) FROM admin_users WHERE username = ?", admin.username) == 0L) { "Administrator already exists" }
            insertAdmin(conn, admin)
        }
        return AdminCredentials(admin.username, null, secret, getOtpAuthUri(admin.username, secret), generateRecoveryCodes(admin.username))
    }

    /** Sets a new password and signs the administrator out everywhere. */
    fun setPassword(username: String, newPassword: String) {
        require(newPassword.length >= MIN_PASSWORD_CHARS) { "Password must be at least $MIN_PASSWORD_CHARS characters" }
        val changed = db.withConnection { it.update("UPDATE admin_users SET password_hash = ? WHERE username = ?", hashPassword(newPassword), username) }
        check(changed == 1) { "Administrator not found" }
        revokeAllSessions(username)
    }

    /** Changes the caller's own password after re-checking the old one. */
    fun changeOwnPassword(username: String, oldPassword: String, newPassword: String): Boolean {
        val admin = loadAdmin(username) ?: return false
        if (!verifyPassword(oldPassword, admin.passwordHash)) return false
        setPassword(username, newPassword)
        return true
    }

    sealed interface PasswordChange {
        data object Changed : PasswordChange
        data object Wrong : PasswordChange
        data class Locked(val remainingSeconds: Long) : PasswordChange
    }

    /**
     * [changeOwnPassword] behind a failure counter (REV-10): a stolen session cookie must not be a free oracle for the current
     * password. Each attempt is reserved BEFORE the old password is checked; after [MAX_FAILED_ATTEMPTS] wrong ones the account's
     * password change is locked for [LOCKOUT_DURATION_MS]. The counter is its own key (`pw:`), so it never locks the sign-in
     * of the real administrator, and a correct password clears it.
     */
    fun changeOwnPasswordLimited(username: String, oldPassword: String, newPassword: String): PasswordChange {
        val key = "pw:${username.trim().lowercase()}"
        val reservation = reserve(listOf(key))
        if (reservation.lockedSeconds > 0) return PasswordChange.Locked(reservation.lockedSeconds)
        if (!changeOwnPassword(username, oldPassword, newPassword)) return PasswordChange.Wrong
        db.withConnection { it.update("DELETE FROM admin_lockouts WHERE key = ?", key) }
        return PasswordChange.Changed
    }

    /** Disables or re-enables an administrator. The last enabled administrator can not be disabled. */
    fun setDisabled(username: String, disabled: Boolean) {
        db.immediate { conn ->
            if (disabled) {
                val others = conn.scalarLong("SELECT COUNT(*) FROM admin_users WHERE disabled = 0 AND username <> ?", username)
                if (others == 0L) throw IllegalStateException("The last enabled administrator can not be disabled")
            }
            val changed = conn.update("UPDATE admin_users SET disabled = ? WHERE username = ?", if (disabled) 1 else 0, username)
            if (changed != 1) throw IllegalStateException("Administrator not found")
        }
        if (disabled) revokeAllSessions(username)
    }

    /** Binds the enrolled device the administrator signs step-up challenges with. */
    fun bindDevice(username: String, deviceId: String?) {
        val changed = db.withConnection { it.update("UPDATE admin_users SET enrolled_device_id = ? WHERE username = ?", deviceId?.trim()?.ifBlank { null }, username) }
        check(changed == 1) { "Administrator not found" }
    }

    /** Binds the enrolled admin phone that must also approve the most dangerous actions (ADR-131). */
    fun bindApprover(username: String, deviceId: String) {
        val changed = db.withConnection { it.update("UPDATE admin_users SET approver_device_id = ? WHERE username = ?", deviceId.trim(), username) }
        check(changed == 1) { "Administrator not found" }
    }

    /** Unbinds the approver phone (the loss path: it needs a recovery code, see the route). */
    fun clearApprover(username: String) {
        db.withConnection { it.update("UPDATE admin_users SET approver_device_id = NULL WHERE username = ?", username) }
    }

    /** The device id of [username]'s approver phone, or null. */
    fun approverOf(username: String): String? = loadAdmin(username)?.approverDeviceId

    /**
     * Registers the public key of a browser the administrator confirms dangerous actions with (a WebCrypto P-256 key that never
     * leaves that browser, ADR-125) and binds the administrator to it. One key per administrator: an earlier one is dropped.
     * The caller has already verified the proof of possession. Returns the key id (`console-` + 16 hex digits of SHA-256(spki)).
     */
    fun registerConsoleKey(username: String, spkiBase64: String, label: String?): String {
        val keyId = CONSOLE_KEY_PREFIX + sha256Hex(spkiBase64).take(16)
        db.immediate { conn ->
            check(conn.scalarLong("SELECT COUNT(*) FROM admin_users WHERE username = ?", username) == 1L) { "Administrator not found" }
            conn.update("DELETE FROM admin_console_keys WHERE username = ?", username)
            conn.update(
                "INSERT INTO admin_console_keys(key_id, username, spki, label, created_at) VALUES(?,?,?,?,?)",
                keyId, username, spkiBase64, label?.filter { it.code >= 0x20 && it.code != 0x7F }?.trim()?.take(64), clock(),
            )
            conn.update("UPDATE admin_users SET enrolled_device_id = ? WHERE username = ?", keyId, username)
        }
        return keyId
    }

    /** The public key (base64 SPKI) of console key [keyId] when it belongs to [username], else null. */
    fun consoleKey(keyId: String, username: String): String? = db.withConnection { conn ->
        conn.queryOne("SELECT spki FROM admin_console_keys WHERE key_id = ? AND username = ?", keyId, username) { it.getString(1) }
    }

    /**
     * Replaces the authenticator secret and the recovery codes of [username] (a lost or compromised authenticator), ends all of
     * its sessions and clears its lockout. The password and the console key stay. Secrets are returned once.
     */
    fun resetTotp(username: String): AdminCredentials {
        val secret = generateTotpSecret()
        val changed = db.immediate { conn ->
            val rows = conn.update("UPDATE admin_users SET totp_secret = ?, last_totp_step = -1 WHERE username = ?", sealTotp(username, secret), username)
            if (rows == 1) {
                conn.update("DELETE FROM admin_sessions WHERE username = ?", username)
                conn.update("DELETE FROM admin_lockouts WHERE key = ?", "user:${username.lowercase()}")
            }
            rows
        }
        check(changed == 1) { "Administrator not found" }
        return AdminCredentials(username, null, secret, getOtpAuthUri(username, secret), generateRecoveryCodes(username))
    }

    /** Replaces the administrator's recovery codes with ten new ones; the plain codes are returned once. */
    fun generateRecoveryCodes(username: String): List<String> {
        val now = clock()
        val codes = List(RECOVERY_CODE_COUNT) { newRecoveryCode() }
        db.immediate { conn ->
            conn.update("DELETE FROM admin_recovery WHERE username = ?", username)
            for (c in codes) {
                conn.update("INSERT INTO admin_recovery(code_hash, username, created_at) VALUES(?,?,?)", sha256Hex(c.replace("-", "")), username, now)
            }
        }
        return codes
    }

    fun recoveryCodesRemaining(username: String): Int = db.withConnection {
        it.scalarLong("SELECT COUNT(*) FROM admin_recovery WHERE username = ? AND used_at IS NULL", username).toInt()
    }

    private fun newRecoveryCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val raw = (1..10).map { alphabet[secureRandom.nextInt(alphabet.length)] }.joinToString("")
        return raw.substring(0, 5) + "-" + raw.substring(5)
    }

    /**
     * Break-glass recovery for an operator at the console (`admin-recovery --username X`): resets the password and the
     * authenticator, clears the lockout, revokes every session and issues new recovery codes. Creates the administrator
     * when the name is new. The credentials are returned for the operator to read once.
     */
    fun breakGlassReset(username: String): AdminCredentials {
        require(USERNAME.matches(username.trim())) { "Username must be 3-64 letters, digits or . _ @ -" }
        val name = username.trim()
        val password = ByteArray(15).also(secureRandom::nextBytes).let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val secret = generateTotpSecret()
        db.immediate { conn ->
            val exists = conn.scalarLong("SELECT COUNT(*) FROM admin_users WHERE username = ?", name) > 0
            if (exists) {
                conn.update(
                    "UPDATE admin_users SET password_hash = ?, totp_secret = ?, disabled = 0, last_totp_step = -1 WHERE username = ?",
                    hashPassword(password), sealTotp(name, secret), name,
                )
            } else {
                insertAdmin(conn, AdminUser(name, hashPassword(password), secret, "ORG_ADMIN", null, false, clock(), "break-glass"))
            }
            conn.update("DELETE FROM admin_sessions WHERE username = ?", name)
            conn.update("DELETE FROM admin_lockouts WHERE key = ?", "user:${name.lowercase()}")
        }
        return AdminCredentials(name, password, secret, getOtpAuthUri(name, secret), generateRecoveryCodes(name))
    }

    // ------------------------------------------------------------------------------------------------ settings

    fun getSetting(key: String): String? = db.withConnection { it.queryOne("SELECT value FROM admin_settings WHERE key = ?", key) { r -> r.getString(1) } }

    fun putSetting(key: String, value: String, by: String) {
        db.withConnection {
            it.update(
                "INSERT INTO admin_settings(key, value, updated_at, updated_by) VALUES(?,?,?,?) " +
                    "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at, updated_by = excluded.updated_by",
                key, value, clock(), by,
            )
        }
    }

    fun allSettings(): Map<String, String> = db.withConnection { conn ->
        conn.query("SELECT key, value FROM admin_settings ORDER BY key") { it.getString(1) to it.getString(2) }.toMap()
    }

    // ------------------------------------------------------------------------------------------------ password hashing

    fun hashPassword(password: String, salt: ByteArray = generateSalt()): String =
        hashPassword(password, salt, passwordIterations)

    private fun hashPassword(password: String, salt: ByteArray, iterations: Int): String {
        val hash = pbkdf2(password, salt, iterations)
        return "$PASSWORD_SCHEME\$$iterations\$${salt.toHex()}\$${hash.toHex()}"
    }

    /** Verifies [password] against the versioned form or the legacy `saltHex:hashHex` (100 000 iterations). */
    fun verifyPassword(password: String, stored: String): Boolean {
        return try {
            if (stored.startsWith("$PASSWORD_SCHEME\$")) {
                val parts = stored.split('$')
                if (parts.size != 4) return false
                val iterations = parts[1].toIntOrNull() ?: return false
                if (iterations < 1 || iterations > 10_000_000) return false
                MessageDigest.isEqual(parts[3].hexToBytes(), pbkdf2(password, parts[2].hexToBytes(), iterations))
            } else {
                val parts = stored.split(":")
                if (parts.size != 2) return false
                MessageDigest.isEqual(parts[1].hexToBytes(), pbkdf2(password, parts[0].hexToBytes(), LEGACY_PBKDF2_ITERATIONS))
            }
        } catch (_: Exception) {
            false
        }
    }

    /** True for a legacy hash or one made with fewer iterations than this service uses now. */
    fun passwordNeedsUpgrade(stored: String): Boolean {
        if (!stored.startsWith("$PASSWORD_SCHEME\$")) return true
        val iterations = stored.split('$').getOrNull(1)?.toIntOrNull() ?: return true
        return iterations < passwordIterations
    }

    private fun pbkdf2(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun generateSalt(): ByteArray = ByteArray(SALT_LENGTH_BYTES).also(secureRandom::nextBytes)

    // ------------------------------------------------------------------------------------------------ RFC 6238 TOTP

    /** A new TOTP secret; remembered as issued so the setup and admin creation can only use server-made secrets. */
    fun generateTotpSecret(): String {
        val secret = encodeBase32(ByteArray(20).also(secureRandom::nextBytes))
        synchronized(issuedSecrets) {
            val now = clock()
            issuedSecrets.entries.removeIf { now - it.value > SETUP_TTL_MS }
            while (issuedSecrets.size >= 64) issuedSecrets.remove(issuedSecrets.keys.first())
            issuedSecrets[secret] = now
        }
        return secret
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
        return (binary % modulo).toString().padStart(digits, '0')
    }

    /** The 30 s step number whose code equals [code] within +-[window] steps of [timeSeconds], or null. */
    fun matchTotpStep(secretBase32: String, code: String, timeSeconds: Long, window: Int = 1): Long? {
        val sanitized = code.trim()
        if (sanitized.length != TOTP_DIGITS || !sanitized.all { it.isDigit() }) return null
        var found: Long? = null
        for (offset in -window..window) {
            val stepTime = timeSeconds + (offset * TOTP_STEP_SECONDS)
            val expected = generateTotp(secretBase32, stepTime)
            // No early exit: every window position is compared in constant time.
            if (constantTimeEquals(sanitized.toByteArray(), expected.toByteArray())) found = stepTime / TOTP_STEP_SECONDS
        }
        return found
    }

    fun validateTotp(
        secretBase32: String,
        code: String,
        timeSeconds: Long = System.currentTimeMillis() / 1000L,
        window: Int = 1
    ): Boolean = matchTotpStep(secretBase32, code, timeSeconds, window) != null

    fun getOtpAuthUri(username: String, secretBase32: String, issuer: String = "Flash"): String {
        val label = java.net.URLEncoder.encode("$issuer:$username", Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/$label?secret=$secretBase32&issuer=${java.net.URLEncoder.encode(issuer, Charsets.UTF_8)}"
    }

    // ------------------------------------------------------------------------------------------------ storage helpers

    private fun totpContext(username: String) = "admin_users.totp_secret:$username"

    private fun sealTotp(username: String, secret: String): String = secrets.seal(secret, totpContext(username))

    /**
     * Seals every authenticator seed still stored in plain text (a data directory from before REV-03). Idempotent; runs at startup
     * when a data key is configured. Returns how many rows were sealed.
     */
    fun sealLegacySecrets(): Int {
        if (!secrets.isActive) return 0
        return db.immediate { conn ->
            val rows = conn.query("SELECT username, totp_secret FROM admin_users") { it.getString(1) to it.getString(2) }
            var sealed = 0
            for ((username, stored) in rows) {
                if (secrets.isSealed(stored)) continue
                conn.update("UPDATE admin_users SET totp_secret = ? WHERE username = ?", sealTotp(username, stored), username)
                sealed++
            }
            sealed
        }
    }

    private fun loadAdmin(username: String): AdminUser? = db.withConnection { conn ->
        conn.queryOne("SELECT * FROM admin_users WHERE username = ?", username.trim(), map = ::adminRow)
    }

    private fun adminRow(rs: java.sql.ResultSet) = AdminUser(
        username = rs.getString("username"),
        passwordHash = rs.getString("password_hash"),
        totpSecret = secrets.open(rs.getString("totp_secret"), totpContext(rs.getString("username"))),
        role = rs.getString("role"),
        enrolledDeviceId = rs.getString("enrolled_device_id"),
        disabled = rs.getInt("disabled") != 0,
        createdAtMs = rs.getLong("created_at"),
        createdBy = rs.getString("created_by"),
        approverDeviceId = rs.getString("approver_device_id"),
        displayName = runCatching { rs.getString("display_name") }.getOrNull().orEmpty(),
        linkedDeviceId = runCatching { rs.getString("linked_device_id") }.getOrNull(),
    )

    private fun insertAdmin(conn: java.sql.Connection, a: AdminUser) {
        conn.update(
            "INSERT INTO admin_users(username, password_hash, totp_secret, role, enrolled_device_id, disabled, last_totp_step, created_at, created_by, approver_device_id, display_name, linked_device_id) " +
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            a.username, a.passwordHash, sealTotp(a.username, a.totpSecret), a.role, a.enrolledDeviceId, if (a.disabled) 1 else 0, -1L, a.createdAtMs, a.createdBy,
            a.approverDeviceId, a.displayName, a.linkedDeviceId,
        )
    }

    fun addAdminDirectlyForTesting(admin: AdminUser) {
        db.withConnection { conn ->
            conn.update("DELETE FROM admin_users WHERE username = ?", admin.username)
            insertAdmin(conn, admin)
        }
    }

    // ------------------------------------------------------------------------------------------------ department heads (Plan A1)

    /** The departments a head login is bound to (empty for org admins and for heads with no departments). */
    fun headDepartments(username: String): Set<String> = db.withConnection { conn ->
        conn.query("SELECT department_id FROM admin_user_departments WHERE username = ? ORDER BY department_id", username) { it.getString(1) }.toSet()
    }

    /**
     * Replaces the departments of [username] (org admin only, enforced by the route).
     * Removing the last one leaves a login with empty scope ("No departments").
     */
    fun setHeadDepartments(username: String, departmentIds: Collection<String>, addedBy: String) {
        val clean = departmentIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        require(clean.size <= 200) { "Too many departments" }
        db.immediate { conn ->
            check(conn.scalarLong("SELECT COUNT(*) FROM admin_users WHERE username = ?", username) == 1L) { "Administrator not found" }
            conn.update("DELETE FROM admin_user_departments WHERE username = ?", username)
            val now = clock()
            for (id in clean) {
                conn.update("INSERT INTO admin_user_departments(username, department_id, added_by, added_at) VALUES(?,?,?,?)", username, id, addedBy, now)
            }
        }
    }

    /** Deletes every scope row that names [departmentId] (department deletion, same transaction as the caller). */
    fun removeDepartmentScopes(departmentId: String) {
        db.withConnection { it.update("DELETE FROM admin_user_departments WHERE department_id = ?", departmentId) }
    }

    /**
     * Creates a department-head login (role DEPT_HEAD). The server generates the TOTP
     * secret and recovery codes (returned once), exactly like [createAdmin].
     */
    fun createHead(
        username: String,
        password: String,
        createdBy: String,
        displayName: String,
        departments: Collection<String>,
        linkedDeviceId: String?,
    ): AdminCredentials {
        require(USERNAME.matches(username.trim())) { "Username must be 3-64 letters, digits or . _ @ -" }
        require(password.length >= MIN_PASSWORD_CHARS) { "Password must be at least $MIN_PASSWORD_CHARS characters" }
        require(displayName.trim().length <= 60) { "Display name is too long" }
        val cleanName = displayName.trim().filter { it.code >= 0x20 && it.code != 0x7F }
        val secret = generateTotpSecret()
        val admin = AdminUser(
            username.trim(), hashPassword(password), secret, AdminUser.ROLE_DEPT_HEAD, null, false, clock(), createdBy,
            displayName = cleanName, linkedDeviceId = linkedDeviceId?.trim()?.ifBlank { null },
        )
        db.immediate { conn ->
            check(conn.scalarLong("SELECT COUNT(*) FROM admin_users WHERE username = ?", admin.username) == 0L) { "Administrator already exists" }
            insertAdmin(conn, admin)
        }
        setHeadDepartments(admin.username, departments, createdBy)
        return AdminCredentials(admin.username, null, secret, getOtpAuthUri(admin.username, secret), generateRecoveryCodes(admin.username))
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

/** Strict RFC 4648 base32: only A-Z and 2-7 (padding and case tolerated by the caller), no skipped characters. */
fun isStrictBase32(text: String): Boolean = text.isNotEmpty() && text.all { it in BASE32_ALPHABET }

fun decodeBase32(base32: String): ByteArray {
    val clean = base32.trim().uppercase().replace("=", "")
    require(isStrictBase32(clean)) { "Invalid base32 text" }
    val out = ArrayList<Byte>()
    var buffer = 0
    var bitsLeft = 0
    for (char in clean) {
        val index = BASE32_ALPHABET.indexOf(char)
        buffer = (buffer shl 5) or index
        bitsLeft += 5
        if (bitsLeft >= 8) {
            bitsLeft -= 8
            out.add(((buffer shr bitsLeft) and 0xff).toByte())
        }
    }
    return out.toByteArray()
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

internal fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

internal fun sha256Hex(text: String): String = sha256(text).toHex()

/** Constant-time comparison (length is not secret here: both sides are fixed-size digests or codes). */
internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
