package com.transfer.flash.server.admin

import com.transfer.flash.server.storage.ServerDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SEC-09, SEC-10, SEC-13, SEC-17: the administrator authentication backend. */
class AdminAuthServiceTest {

    private var now = 1_800_000_000_000L
    private val clock: () -> Long = { now }

    private fun service(
        db: ServerDatabase = ServerDatabase.inMemory(),
        iterations: Int = 1000,
        tokenFile: Path? = null,
        explicitToken: String? = null,
    ) = AdminAuthService(
        printSetupTokenToConsole = false,
        db = db,
        clock = clock,
        passwordIterations = iterations,
        setupTokenFile = tokenFile,
        explicitSetupToken = explicitToken,
    )

    /** Creates the first administrator through the real setup path and returns (service, totp secret). */
    private fun withAdmin(svc: AdminAuthService, name: String = "admin", password: String = "CorrectPassword123!"): String {
        val begin = svc.beginSetup(svc.getActiveSetupTokenForTesting()!!)
        svc.completeSetup(begin.setupId, svc.getActiveSetupTokenForTesting()!!, name, password, svc.generateTotp(begin.totpSecret, now / 1000L))
        return begin.totpSecret
    }

    /** A code for the NEXT 30 s step, so repeated logins in one test never hit the replay rule by accident. */
    private fun nextCode(svc: AdminAuthService, secret: String): String {
        now += AdminAuthService.TOTP_STEP_SECONDS * 1000L
        return svc.generateTotp(secret, now / 1000L)
    }

    // ------------------------------------------------------------------------------------------ password hashing

    @Test
    fun passwordHashIsVersionedPbkdf2With600kIterationsByDefault() {
        val svc = AdminAuthService(printSetupTokenToConsole = false)
        val stored = svc.hashPassword("SuperSecretPassword123!")
        val parts = stored.split('$')
        assertEquals(listOf("pbkdf2-sha256", "600000"), parts.take(2))
        assertEquals(32, parts[2].length, "16-byte salt")
        assertEquals(64, parts[3].length, "256-bit hash")
        assertTrue(svc.verifyPassword("SuperSecretPassword123!", stored))
        assertFalse(svc.verifyPassword("WrongPassword!", stored))
        assertFalse(svc.verifyPassword("", stored))
        assertNotEquals(stored, svc.hashPassword("SuperSecretPassword123!"), "fresh salt each time")
    }

    @Test
    fun legacyHashStillVerifiesAndIsUpgradedOnTheNextSuccessfulLogin() {
        val db = ServerDatabase.inMemory()
        val svc = service(db)
        val secret = withAdmin(svc)
        // Re-store the password in the legacy `salt:hash` form (100 000 iterations).
        val salt = ByteArray(16) { it.toByte() }
        val legacy = salt.toHex() + ":" + javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec("CorrectPassword123!".toCharArray(), salt, 100_000, 256)).encoded.toHex()
        val admin = svc.getAdmin("admin")!!
        svc.addAdminDirectlyForTesting(admin.copy(passwordHash = legacy))
        assertTrue(svc.verifyPassword("CorrectPassword123!", legacy))
        assertTrue(svc.passwordNeedsUpgrade(legacy))

        val result = svc.authenticate("admin", "CorrectPassword123!", nextCode(svc, secret), "10.0.0.1")
        assertIs<AuthResult.Success>(result)
        val upgraded = svc.getAdmin("admin")!!.passwordHash
        assertTrue(upgraded.startsWith("pbkdf2-sha256\$1000\$"), upgraded)
        assertTrue(svc.verifyPassword("CorrectPassword123!", upgraded))
    }

    // ------------------------------------------------------------------------------------------ TOTP

    @Test
    fun totpWindowAcceptsClockSkewButNotFarCodes() {
        val svc = service()
        val secret = svc.generateTotpSecret()
        val t = now / 1000L
        val code = svc.generateTotp(secret, t)
        assertEquals(6, code.length)
        assertTrue(svc.validateTotp(secret, code, t))
        assertTrue(svc.validateTotp(secret, code, t + 25))
        assertTrue(svc.validateTotp(secret, code, t - 25))
        assertFalse(svc.validateTotp(secret, code, t + 120))
        assertFalse(svc.validateTotp(secret, "12345", t))
        assertFalse(svc.validateTotp(secret, "abcdef", t))
    }

    @Test
    fun rfc6238Sha1VectorMatches() {
        // RFC 6238 appendix B, SHA-1, secret "12345678901234567890", T=59 -> 94287082 (8 digits) => 287082 with 6 digits.
        val svc = service()
        val secret = encodeBase32("12345678901234567890".toByteArray())
        assertEquals("287082", svc.generateTotp(secret, 59L))
    }

    @Test
    fun aTotpCodeCanLogInOnlyOnce() {
        val svc = service()
        val secret = withAdmin(svc)
        val code = nextCode(svc, secret)
        assertIs<AuthResult.Success>(svc.authenticate("admin", "CorrectPassword123!", code, "10.0.0.1"))
        // The very same code a moment later, even from another address, is a replay and must be refused.
        assertIs<AuthResult.InvalidCredentials>(svc.authenticate("admin", "CorrectPassword123!", code, "10.0.0.2"))
        // An earlier step than the one already used is refused too.
        val older = svc.generateTotp(secret, now / 1000L - 30)
        assertIs<AuthResult.InvalidCredentials>(svc.authenticate("admin", "CorrectPassword123!", older, "10.0.0.3"))
        // A later step works again.
        assertIs<AuthResult.Success>(svc.authenticate("admin", "CorrectPassword123!", nextCode(svc, secret), "10.0.0.4"))
    }

    @Test
    fun base32DecodingIsStrict() {
        assertFailsWith<IllegalArgumentException> { decodeBase32("ABC!DEF") }
        assertFailsWith<IllegalArgumentException> { decodeBase32("ABCD1EFG") } // 1 is not in the alphabet
        assertEquals("foo", String(decodeBase32(encodeBase32("foo".toByteArray()))))
    }

    // ------------------------------------------------------------------------------------------ setup

    @Test
    fun setupTokenIsSingleUseAndWrongTokensLockSetup() {
        val svc = service()
        assertFalse(svc.hasAdmins())
        val token = svc.getActiveSetupTokenForTesting()!!
        assertEquals(48, token.length)
        val begin = svc.beginSetup(svc.getActiveSetupTokenForTesting()!!)

        repeat(AdminAuthService.MAX_FAILED_ATTEMPTS) {
            assertFailsWith<SecurityException> { svc.consumeSetupToken("wrong-token-wrong-token", "gh_admin", "AdminPass1234!", begin.totpSecret) }
        }
        // Brute force on the setup token locks setup, even for the right token.
        assertFailsWith<SecurityException> { svc.consumeSetupToken(token, "gh_admin", "AdminPass1234!", begin.totpSecret) }
        now += AdminAuthService.LOCKOUT_DURATION_MS + 1000
        val admin = svc.consumeSetupToken(token, "gh_admin", "AdminPass1234!", begin.totpSecret)
        assertEquals("gh_admin", admin.username)
        assertTrue(svc.hasAdmins())
        assertNull(svc.getActiveSetupTokenForTesting())
        assertFailsWith<IllegalStateException> { svc.consumeSetupToken(token, "admin2", "AdminPass1234!", begin.totpSecret) }
    }

    @Test
    fun setupRefusesSecretsTheServerDidNotIssueAndWeakInput() {
        val svc = service()
        val token = svc.getActiveSetupTokenForTesting()!!
        assertFailsWith<IllegalArgumentException> { svc.consumeSetupToken(token, "admin", "AdminPass1234!", "AAAAAAAAAAAAAAAAAAAA") }
        assertFailsWith<IllegalArgumentException> { svc.consumeSetupToken(token, "admin", "AdminPass1234!", "not base32 !!") }
        val secret = svc.generateTotpSecret()
        assertFailsWith<IllegalArgumentException> { svc.consumeSetupToken(token, "admin", "short", secret) }
        assertFailsWith<IllegalArgumentException> { svc.consumeSetupToken(token, "a b", "AdminPass1234!", secret) }
        assertFalse(svc.hasAdmins(), "a refused setup must not burn the token or create an admin")
        assertNotNull(svc.getActiveSetupTokenForTesting())
    }

    @Test
    fun completeSetupNeedsALiveCodeFromTheAuthenticator() {
        val svc = service()
        val begin = svc.beginSetup(svc.getActiveSetupTokenForTesting()!!)
        assertFailsWith<IllegalArgumentException> {
            svc.completeSetup(begin.setupId, svc.getActiveSetupTokenForTesting()!!, "admin", "AdminPass1234!", "000000")
        }
        assertFalse(svc.hasAdmins())
    }

    @Test
    fun setupNeverReopensAfterARestart() {
        val dir = Files.createTempDirectory("flash-admin-setup")
        val url = "jdbc:sqlite:${dir.resolve("admin.db")}"
        val tokenFile = dir.resolve("setup-token.txt")
        val first = service(ServerDatabase(url), tokenFile = tokenFile)
        assertTrue(Files.exists(tokenFile), "the token lives in a file, not on the console")
        assertEquals(first.getActiveSetupTokenForTesting(), Files.readString(tokenFile).trim())
        withAdmin(first)
        assertFalse(Files.exists(tokenFile), "the token file is removed once it is used")

        val restarted = service(ServerDatabase(url), tokenFile = tokenFile)
        assertTrue(restarted.hasAdmins())
        assertNull(restarted.getActiveSetupTokenForTesting())
        assertFalse(restarted.isSetupOpen())
        assertFalse(Files.exists(tokenFile))
        assertNotNull(restarted.getAdmin("admin"))
    }

    @Test
    fun setupTokenFileIsOwnerOnlyAndSurvivesARestartBeforeSetup() {
        val dir = Files.createTempDirectory("flash-admin-token")
        val tokenFile = dir.resolve("setup-token.txt")
        val url = "jdbc:sqlite:${dir.resolve("admin.db")}"
        val a = service(ServerDatabase(url), tokenFile = tokenFile)
        val b = service(ServerDatabase(url), tokenFile = tokenFile)
        assertEquals(a.getActiveSetupTokenForTesting(), b.getActiveSetupTokenForTesting())
        assertTrue(com.transfer.flash.server.auth.SecureFiles.isOwnerOnly(tokenFile))
    }

    // ------------------------------------------------------------------------------------------ login, lockout

    @Test
    fun fifthFailureReportsZeroAttemptsAndTheSixthIsLockedEvenWithTheRightPassword() {
        val svc = service()
        val secret = withAdmin(svc)
        for (i in 1..4) {
            val r = svc.authenticate("admin", "WrongPass", "123456", "192.168.1.105")
            assertIs<AuthResult.InvalidCredentials>(r)
            assertEquals(5 - i, r.remainingAttempts)
        }
        assertEquals(0, assertIs<AuthResult.InvalidCredentials>(svc.authenticate("admin", "WrongPass", "123456", "192.168.1.105")).remainingAttempts)
        val locked = svc.authenticate("admin", "CorrectPassword123!", nextCode(svc, secret), "192.168.1.105")
        assertIs<AuthResult.Locked>(locked)
        assertTrue(locked.remainingSeconds in 1..900)
        // After the lock lapses the right credentials work again.
        now += AdminAuthService.LOCKOUT_DURATION_MS + 1000
        assertIs<AuthResult.Success>(svc.authenticate("admin", "CorrectPassword123!", nextCode(svc, secret), "192.168.1.105"))
    }

    @Test
    fun theAttemptIsCountedBeforeTheSecretIsCheckedSoParallelGuessesCannotOutrunTheCounter() {
        val svc = service()
        withAdmin(svc)
        val threads = 40
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val start = java.util.concurrent.CountDownLatch(1)
        val results = java.util.concurrent.ConcurrentLinkedQueue<AuthResult>()
        val futures = (1..threads).map {
            pool.submit {
                start.await()
                results += svc.authenticate("admin", "guess-$it", "123456", "203.0.113.9")
            }
        }
        start.countDown()
        futures.forEach { it.get() }
        pool.shutdown()
        val evaluated = results.count { it is AuthResult.InvalidCredentials }
        assertEquals(AdminAuthService.MAX_FAILED_ATTEMPTS, evaluated, "only five guesses may ever be evaluated; the rest are locked out")
        assertEquals(threads - AdminAuthService.MAX_FAILED_ATTEMPTS, results.count { it is AuthResult.Locked })
    }

    @Test
    fun unknownAndKnownUsersGetTheSameAnswerAndTheSameLockout() {
        val svc = service()
        withAdmin(svc)
        val unknown = svc.authenticate("nobody", "whatever-password", "123456", "10.9.9.9")
        val known = svc.authenticate("admin", "wrong-password-x", "123456", "10.9.9.8")
        assertIs<AuthResult.InvalidCredentials>(unknown)
        assertIs<AuthResult.InvalidCredentials>(known)
        assertEquals(known.remainingAttempts, unknown.remainingAttempts)
        // Guessing usernames from one address locks that address.
        repeat(5) { svc.authenticate("ghost$it", "x", "000000", "10.7.7.7") }
        assertIs<AuthResult.Locked>(svc.authenticate("ghost-final", "x", "000000", "10.7.7.7"))
    }

    @Test
    fun aSuccessfulLoginClearsTheFailureCounter() {
        val svc = service()
        val secret = withAdmin(svc)
        repeat(3) { svc.authenticate("admin", "bad-password-1", "123456", "10.1.1.1") }
        assertIs<AuthResult.Success>(svc.authenticate("admin", "CorrectPassword123!", nextCode(svc, secret), "10.1.1.1"))
        val next = svc.authenticate("admin", "bad-password-1", "123456", "10.1.1.1")
        assertEquals(4, assertIs<AuthResult.InvalidCredentials>(next).remainingAttempts)
    }

    @Test
    fun lockoutsPersistAcrossARestart() {
        val dir = Files.createTempDirectory("flash-admin-lock")
        val url = "jdbc:sqlite:${dir.resolve("admin.db")}"
        val first = service(ServerDatabase(url))
        withAdmin(first)
        repeat(5) { first.authenticate("admin", "bad-password-1", "123456", "10.2.2.2") }
        val restarted = service(ServerDatabase(url))
        assertIs<AuthResult.Locked>(restarted.authenticate("admin", "CorrectPassword123!", "123456", "10.2.2.3"))
    }

    // ------------------------------------------------------------------------------------------ recovery codes

    @Test
    fun recoveryCodesAreSingleUseAndStoredHashed() {
        val db = ServerDatabase.inMemory()
        val svc = service(db)
        withAdmin(svc)
        val codes = svc.generateRecoveryCodes("admin")
        assertEquals(10, codes.size)
        assertEquals(10, codes.toSet().size)
        assertTrue(codes.all { Regex("^[A-Z2-9]{5}-[A-Z2-9]{5}$").matches(it) }, codes.toString())
        db.withConnection { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT code_hash FROM admin_recovery").use { rs ->
                    while (rs.next()) assertFalse(codes.any { code -> rs.getString(1).contains(code.replace("-", "")) })
                }
            }
        }
        val first = svc.authenticate("admin", "CorrectPassword123!", codes[0], "10.3.3.3")
        val ok = assertIs<AuthResult.Success>(first)
        assertTrue(ok.recoveryCodeUsed)
        assertEquals(9, svc.recoveryCodesRemaining("admin"))
        assertIs<AuthResult.InvalidCredentials>(svc.authenticate("admin", "CorrectPassword123!", codes[0], "10.3.3.4"))
        assertIs<AuthResult.Success>(svc.authenticate("admin", "CorrectPassword123!", codes[1].lowercase(), "10.3.3.5"))
    }

    @Test
    fun aRecoveryCodeOfAnotherAdministratorDoesNotWork() {
        val svc = service()
        withAdmin(svc)
        val other = svc.createAdmin("second_admin", "SecondAdmin123!", "admin")
        assertEquals(10, other.recoveryCodes.size)
        assertIs<AuthResult.InvalidCredentials>(svc.authenticate("admin", "CorrectPassword123!", other.recoveryCodes[0], "10.4.4.4"))
    }

    // ------------------------------------------------------------------------------------------ sessions

    @Test
    fun sessionsAreStoredHashedExpireAndAreCappedPerAdministrator() {
        val db = ServerDatabase.inMemory()
        val svc = service(db)
        withAdmin(svc)
        val s = svc.createSession("admin", "ORG_ADMIN")
        assertEquals(64, s.token.length)
        assertTrue(s.csrfToken.length >= 32)
        assertEquals("admin", svc.validateSession(s.token)!!.username)
        db.withConnection { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT token_hash FROM admin_sessions").use { rs ->
                    while (rs.next()) assertNotEquals(s.token, rs.getString(1))
                }
            }
        }
        svc.invalidateSession(s.token)
        assertNull(svc.validateSession(s.token))

        val many = (1..8).map { now += 1000; svc.createSession("admin", "ORG_ADMIN") }
        assertEquals(AdminAuthService.MAX_SESSIONS_PER_ADMIN, svc.listSessions("admin").size)
        assertNull(svc.validateSession(many.first().token), "the oldest sessions are dropped first")
        assertNotNull(svc.validateSession(many.last().token))
    }

    @Test
    fun sessionsExpireByLifetimeAndByIdleTime() {
        val svc = service()
        withAdmin(svc)
        val s = svc.createSession("admin", "ORG_ADMIN")
        now += AdminAuthService.SESSION_IDLE_MS + 1
        assertNull(svc.validateSession(s.token), "idle timeout")
        val t = svc.createSession("admin", "ORG_ADMIN")
        var elapsed = 0L
        while (elapsed < AdminAuthService.SESSION_TTL_MS) {
            now += 20 * 60 * 1000L
            elapsed += 20 * 60 * 1000L
            if (elapsed < AdminAuthService.SESSION_TTL_MS) assertNotNull(svc.validateSession(t.token))
        }
        assertNull(svc.validateSession(t.token), "absolute lifetime")
    }

    @Test
    fun disablingAnAdministratorKillsTheirSessionsAndTheLastOneCannotBeDisabled() {
        val svc = service()
        withAdmin(svc)
        svc.createAdmin("second_admin", "SecondAdmin123!", "admin")
        val s = svc.createSession("second_admin", "ORG_ADMIN")
        svc.setDisabled("second_admin", true)
        assertNull(svc.validateSession(s.token))
        assertFailsWith<IllegalStateException> { svc.setDisabled("admin", true) }
        svc.setDisabled("second_admin", false)
        assertFalse(svc.getAdmin("second_admin")!!.disabled)
    }

    @Test
    fun aDisabledAdministratorCannotLogInEvenWithAllFactors() {
        val svc = service()
        withAdmin(svc)
        val creds = svc.createAdmin("second_admin", "SecondAdmin123!", "admin")
        svc.setDisabled("second_admin", true)
        val r = svc.authenticate("second_admin", "SecondAdmin123!", svc.generateTotp(creds.totpSecret, now / 1000L), "10.5.5.5")
        assertIs<AuthResult.InvalidCredentials>(r)
    }

    @Test
    fun wrongCurrentPasswordsLockThePasswordChangeButNotTheSignIn() {
        val svc = service()
        val secret = withAdmin(svc)
        repeat(AdminAuthService.MAX_FAILED_ATTEMPTS) {
            assertEquals(AdminAuthService.PasswordChange.Wrong, svc.changeOwnPasswordLimited("admin", "guess-$it-guess-$it", "NewPassword123!"))
        }
        // The right password no longer helps while the lock holds: a stolen cookie gets no oracle.
        assertIs<AdminAuthService.PasswordChange.Locked>(svc.changeOwnPasswordLimited("admin", "CorrectPassword123!", "NewPassword123!"))
        assertIs<AuthResult.Success>(svc.authenticate("admin", "CorrectPassword123!", nextCode(svc, secret), "10.6.6.6"), "the real administrator can still sign in")
        now += AdminAuthService.LOCKOUT_DURATION_MS + 1000
        assertEquals(AdminAuthService.PasswordChange.Changed, svc.changeOwnPasswordLimited("admin", "CorrectPassword123!", "NewPassword123!"))
    }

    @Test
    fun changingAPasswordSignsTheAdministratorOutEverywhere() {
        val svc = service()
        val secret = withAdmin(svc)
        val s = svc.createSession("admin", "ORG_ADMIN")
        assertFalse(svc.changeOwnPassword("admin", "not-the-old-password", "NewPassword123!"))
        assertNotNull(svc.validateSession(s.token))
        assertTrue(svc.changeOwnPassword("admin", "CorrectPassword123!", "NewPassword123!"))
        assertNull(svc.validateSession(s.token))
        assertIs<AuthResult.Success>(svc.authenticate("admin", "NewPassword123!", nextCode(svc, secret), "10.6.6.6"))
    }

    @Test
    fun breakGlassResetReplacesEveryCredentialAndCreatesAMissingAdministrator() {
        val svc = service()
        val oldSecret = withAdmin(svc)
        val s = svc.createSession("admin", "ORG_ADMIN")
        repeat(5) { svc.authenticate("admin", "bad-password-1", "123456", "10.8.8.8") }
        val reset = svc.breakGlassReset("admin")
        assertNotNull(reset.password)
        assertNotEquals(oldSecret, reset.totpSecret)
        assertNull(svc.validateSession(s.token))
        assertIs<AuthResult.Success>(svc.authenticate("admin", reset.password!!, svc.generateTotp(reset.totpSecret, now / 1000L), "10.8.8.9"))
        assertIs<AuthResult.InvalidCredentials>(svc.authenticate("admin", "CorrectPassword123!", nextCode(svc, oldSecret), "10.8.8.10"))

        val fresh = svc.breakGlassReset("recovery_admin")
        assertNotNull(svc.getAdmin("recovery_admin"))
        assertEquals(10, fresh.recoveryCodes.size)
    }

    @Test
    fun enrolledDeviceBindingIsPersisted() {
        val svc = service()
        withAdmin(svc)
        svc.bindDevice("admin", "dev-admin-phone")
        assertEquals("dev-admin-phone", svc.getAdmin("admin")!!.enrolledDeviceId)
        assertFailsWith<IllegalStateException> { svc.bindDevice("nobody", "dev-x") }
    }

    @Test
    fun otpAuthUriNamesTheIssuer() {
        val svc = service()
        val uri = svc.getOtpAuthUri("gh admin", "JBSWY3DPEHPK3PXP")
        assertTrue(uri.startsWith("otpauth://totp/Flash%3Agh%20admin?secret=JBSWY3DPEHPK3PXP"), uri)
    }
}
