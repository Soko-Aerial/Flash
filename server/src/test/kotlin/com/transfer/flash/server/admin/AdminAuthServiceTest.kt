package com.transfer.flash.server.admin

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AdminAuthServiceTest {

    @Test
    fun testPasswordHashingAndVerification() {
        val authService = AdminAuthService(printSetupTokenToConsole = false)

        val password = "SuperSecretPassword123!"
        val storedHash = authService.hashPassword(password)

        // Stored format is saltHex:hashHex
        val parts = storedHash.split(":")
        assertEquals(2, parts.size)
        // 16-byte salt = 32 hex chars
        assertEquals(32, parts[0].length)
        // 256-bit hash = 32 bytes = 64 hex chars
        assertEquals(64, parts[1].length)

        // Valid password matches
        assertTrue(authService.verifyPassword(password, storedHash))

        // Wrong password fails
        assertFalse(authService.verifyPassword("WrongPassword!", storedHash))
        assertFalse(authService.verifyPassword("", storedHash))
    }

    @Test
    fun testTotpGenerationAndRfc6238Validation() {
        val authService = AdminAuthService(printSetupTokenToConsole = false)

        val secret = authService.generateTotpSecret()
        assertTrue(secret.isNotBlank())

        val nowSeconds = System.currentTimeMillis() / 1000L
        val code = authService.generateTotp(secret, timeSeconds = nowSeconds)

        assertEquals(6, code.length)
        assertTrue(code.all { it.isDigit() })

        // Validation at current time
        assertTrue(authService.validateTotp(secret, code, timeSeconds = nowSeconds))

        // Clock skew tolerance: accepted at +25s (within tolerance window)
        assertTrue(authService.validateTotp(secret, code, timeSeconds = nowSeconds + 25L))

        // Accepted at -25s
        assertTrue(authService.validateTotp(secret, code, timeSeconds = nowSeconds - 25L))

        // Far in the future (+120s / 4 steps) must be rejected
        assertFalse(authService.validateTotp(secret, code, timeSeconds = nowSeconds + 120L))

        // Invalid code must be rejected
        assertFalse(authService.validateTotp(secret, "000000", timeSeconds = nowSeconds))
        assertFalse(authService.validateTotp(secret, "999999", timeSeconds = nowSeconds))
    }

    @Test
    fun testFirstRunSetupTokenLifecycle() {
        val authService = AdminAuthService(printSetupTokenToConsole = false)
        assertFalse(authService.hasAdmins())

        val setupToken = authService.getActiveSetupTokenForTesting()
        assertNotNull(setupToken)
        assertEquals(48, setupToken.length) // 24 bytes = 48 hex chars

        val totpSecret = authService.generateTotpSecret()

        // Wrong token fails
        assertFailsWith<SecurityException> {
            authService.consumeSetupToken("wrong-token", "admin", "adminPass123", totpSecret)
        }

        // Valid token succeeds
        val admin = authService.consumeSetupToken(setupToken, "gh_admin", "AdminPass1234!", totpSecret)
        assertEquals("gh_admin", admin.username)
        assertEquals("ORG_ADMIN", admin.role)
        assertTrue(authService.hasAdmins())

        // Setup token is single-use: subsequent attempt must fail
        assertFailsWith<IllegalStateException> {
            authService.consumeSetupToken(setupToken, "admin2", "adminPass1234!", totpSecret)
        }
    }

    @Test
    fun testSessionLifecycleAndExpiry() {
        val authService = AdminAuthService(printSetupTokenToConsole = false)

        val session = authService.createSession("admin_user", "ORG_ADMIN")
        assertNotNull(session)
        assertEquals(64, session.token.length) // 32 bytes = 64 hex chars
        assertEquals("admin_user", session.username)

        // Session validation succeeds
        val valid = authService.validateSession(session.token)
        assertNotNull(valid)
        assertEquals("admin_user", valid.username)

        // Invalidate session
        authService.invalidateSession(session.token)
        assertNull(authService.validateSession(session.token))

        // Expired session validation fails
        val now = System.currentTimeMillis()
        val expiredSession = AdminSession("expired_tok", "user", "MEMBER", now - 10000, now - 1000)
        assertTrue(expiredSession.isExpired(now))
    }

    @Test
    fun testBruteForceRateLimitingAndLockout() {
        val authService = AdminAuthService(printSetupTokenToConsole = false)
        val setupToken = authService.getActiveSetupTokenForTesting()!!
        val totpSecret = authService.generateTotpSecret()

        authService.consumeSetupToken(setupToken, "admin", "CorrectPassword123!", totpSecret)

        val clientIp = "192.168.1.105"

        // 4 failed attempts: account is not locked yet
        for (i in 1..4) {
            val result = authService.authenticate("admin", "WrongPass", "123456", clientIp)
            assertIs<AuthResult.InvalidCredentials>(result)
            assertEquals(5 - i, result.remainingAttempts)
        }

        // 5th failed attempt: triggers 15-minute lockout
        val fifthResult = authService.authenticate("admin", "WrongPass", "123456", clientIp)
        assertIs<AuthResult.InvalidCredentials>(fifthResult)
        assertEquals(0, fifthResult.remainingAttempts)

        // 6th attempt: locked!
        val lockedResult = authService.authenticate("admin", "CorrectPassword123!", "123456", clientIp)
        assertIs<AuthResult.Locked>(lockedResult)
        assertTrue(lockedResult.remainingSeconds > 0)
        assertTrue(lockedResult.remainingSeconds <= 900)

        // Reset lockout and verify valid login succeeds
        authService.resetLockout("admin")
        authService.resetLockout(clientIp)

        val validTotp = authService.generateTotp(totpSecret)
        val successResult = authService.authenticate("admin", "CorrectPassword123!", validTotp, clientIp)
        assertIs<AuthResult.Success>(successResult)
        assertEquals("admin", successResult.session.username)
    }

    @Test
    fun testAuditLoggerCryptographicHashChainIntegrity() {
        val logger = AuditLogger()

        val e1 = logger.log("admin1", "CREATE_DEPARTMENT", "dept-infantry")
        assertEquals(0L, e1.index)
        assertEquals(AuditLogger.GENESIS_HASH, e1.prevHash)

        val e2 = logger.log("admin1", "CREATE_GROUP", "grp-recon")
        assertEquals(1L, e2.index)
        assertEquals(e1.hash, e2.prevHash)

        val e3 = logger.log("admin1", "INVITE_EMPLOYEE", "Captain Mensah")
        assertEquals(2L, e3.index)
        assertEquals(e2.hash, e3.prevHash)

        // Chain is intact
        assertTrue(logger.verifyIntegrity())
        assertEquals(3, logger.size())

        // Tampering with an entry must be immediately detected
        logger.tamperWithEntryForTesting(1, "TAMPERED_DETAILS_MALICIOUS")
        assertFalse(logger.verifyIntegrity())
    }

    @Test
    fun testStepUpAuthWithEcdsaSignature() {
        val stepUp = StepUpAuth()

        // 1. Generate standard ECDSA P-256 key pair representing enrolled device
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        val devicePublicKeyX509 = keyPair.public.encoded
        val devicePublicKeyHex = devicePublicKeyX509.toHex()

        // 2. Create step-up challenge for sensitive action
        val challenge = stepUp.createChallenge(
            adminUsername = "gh_admin",
            deviceId = "dev-admiral-phone",
            action = "REVOKE_ADMIN"
        )
        assertNotNull(challenge)
        assertFalse(challenge.verified)

        // 3. Sign challenge nonce using device private key
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(challenge.nonce.toByteArray(Charsets.UTF_8))
        val signatureBytes = sig.sign()
        val signatureHex = signatureBytes.toHex()

        // 4. Verify signature
        val verified = stepUp.verifySignatureHex(
            challengeId = challenge.challengeId,
            signatureDerHex = signatureHex,
            devicePublicKeyDerHex = devicePublicKeyHex
        )
        assertTrue(verified)

        val verifiedChallenge = stepUp.getChallenge(challenge.challengeId)
        assertNotNull(verifiedChallenge)
        assertTrue(verifiedChallenge.verified)

        // 5. Authorize action and consume challenge (single-use replay protection)
        val authorized = stepUp.isActionAuthorizedAndConsume(
            challengeId = challenge.challengeId,
            expectedAction = "REVOKE_ADMIN"
        )
        assertTrue(authorized)

        // Subsequent attempt with consumed challenge must fail
        assertFalse(
            stepUp.isActionAuthorizedAndConsume(
                challengeId = challenge.challengeId,
                expectedAction = "REVOKE_ADMIN"
            )
        )
    }
}
