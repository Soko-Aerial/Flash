package com.transfer.flash.server

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.transfer.flash.server.admin.AdminAuthService
import com.transfer.flash.server.storage.ServerDatabase
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** REV-13: the secrets of the administrator flows never reach a log line, even at DEBUG. */
class LogHygieneTest {

    @Test
    fun noSetupTokenPasswordSeedSessionOrRecoveryCodeIsEverLogged() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val before = root.level
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        root.level = Level.ALL
        root.addAppender(appender)
        val secrets = LinkedHashMap<String, String>()
        try {
            var now = 1_800_000_000_000L
            val svc = AdminAuthService(printSetupTokenToConsole = false, db = ServerDatabase.inMemory(), clock = { now }, passwordIterations = 1000)
            val token = svc.getActiveSetupTokenForTesting()!!
            secrets["setup token"] = token
            val begin = svc.beginSetup(token)
            secrets["totp seed"] = begin.totpSecret
            val password = "Very-Secret-Password-9876!"
            secrets["password"] = password
            // a failed setup, a successful one, failed and successful sign-ins, a password change, recovery codes, a reset
            runCatching { svc.beginSetup("a-wrong-setup-token-value") }
            svc.completeSetup(begin.setupId, token, "owner", password, svc.generateTotp(begin.totpSecret, now / 1000L))
            now += 31_000
            svc.authenticate("owner", "Wrong-Password-1234!", "000000", "10.1.1.1")
            now += 31_000
            val ok = svc.authenticate("owner", password, svc.generateTotp(begin.totpSecret, now / 1000L), "10.1.1.1")
            val session = (ok as com.transfer.flash.server.admin.AuthResult.Success).session
            secrets["session token"] = session.token
            secrets["csrf token"] = session.csrfToken
            val codes = svc.generateRecoveryCodes("owner")
            codes.forEachIndexed { i, c -> secrets["recovery code $i"] = c }
            val newPassword = "Another-Secret-Password-5555!"
            secrets["new password"] = newPassword
            svc.changeOwnPasswordLimited("owner", password, newPassword)
            val reset = svc.breakGlassReset("owner")
            secrets["reset totp seed"] = reset.totpSecret
            reset.password?.let { secrets["reset password"] = it }
        } finally {
            root.detachAppender(appender)
            root.level = before
        }
        val lines = appender.list.map { it.formattedMessage + " " + (it.throwableProxy?.message ?: "") }
        for ((name, value) in secrets) {
            val leaked = lines.firstOrNull { it.contains(value) }
            assertEquals(null, leaked, "the $name was written to the log: $leaked")
        }
    }
}
