package com.transfer.flash.server

import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.server.admin.AdminAuthService
import com.transfer.flash.server.admin.AuditLogger
import com.transfer.flash.server.auth.ChallengeResponseAuth
import com.transfer.flash.server.auth.DelegationCert
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.generateEcKeyPair
import com.transfer.flash.server.auth.toBase64Spki
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.core.FlashServerEngine
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.storage.ServerDatabase
import java.io.File
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("FlashEnterpriseHub")

public fun main(args: Array<String>) {
    printBanner()

    val config = ServerConfig.fromEnvironment(args)

    logger.info("==================================================================")
    logger.info("Flash Enterprise Hub - Hybrid Directory & Relay Engine")
    logger.info("Listening Address      : http://{}:{}", config.host, config.port)
    logger.info("Admin Port             : {}", config.adminPort)
    logger.info("TLS Termination Mode   : {}", config.tlsMode)
    logger.info("Data Storage Directory : {}", config.dataDir)
    logger.info("Mailbox Retention Days : {}", config.mailboxTtlDays)
    logger.info("Challenge Timeout (ms) : {}", config.challengeTimeoutMs)
    logger.info("Initial Setup Token    : [PROTECTED: {} characters]", config.setupToken.length)
    logger.info("==================================================================")

    val dataDirFile = File(config.dataDir)
    if (!dataDirFile.exists()) {
        dataDirFile.mkdirs()
    }

    val db = ServerDatabase("jdbc:sqlite:${config.dataDir}/flash-hub.db")
    val mailboxQueue = MailboxQueue(database = db)
    val directory = OrganizationDirectory()
    val auditLogger = AuditLogger()
    val adminAuthService = AdminAuthService(printSetupTokenToConsole = true)

    val rootKeyPair = FlashCrypto.generateEcKeyPair()
    val serverKeyPair = FlashCrypto.generateEcKeyPair()
    val rootPubKey = rootKeyPair.toBase64Spki()
    val serverPubKey = serverKeyPair.toBase64Spki()

    val now = System.currentTimeMillis()
    val delegationCert = DelegationCert.issue(
        rootPrivateKey = rootKeyPair.private,
        rootPublicKey = rootPubKey,
        delegatePublicKey = serverPubKey,
        scopes = setOf(DelegationCert.SCOPE_ENROL_MEMBER, DelegationCert.SCOPE_REVOKE_MEMBER),
        validFrom = now - 10_000,
        validTo = now + 30L * 24 * 3600 * 1000,
        maxDevices = 1000,
        serial = 1L,
    )

    val challengeAuth = ChallengeResponseAuth(rootPublicKey = rootPubKey)
    val enrollmentService = EnrollmentService(
        rootPublicKey = rootPubKey,
        serverPrivateKey = serverKeyPair.private,
        delegationCert = delegationCert,
    )

    val engine = FlashServerEngine(
        config = config,
        mailboxQueue = mailboxQueue,
        directory = directory,
        auditLogger = auditLogger,
        adminAuthService = adminAuthService,
        challengeAuth = challengeAuth,
        enrollmentService = enrollmentService,
    )

    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                logger.info("JVM shutdown signal received. Stopping Flash Enterprise Hub...")
                engine.stop()
            },
            "flash-server-shutdown-hook",
        ),
    )

    try {
        engine.start(wait = true)
    } catch (e: Throwable) {
        logger.error("Flash Enterprise Hub terminated unexpectedly: {}", e.message, e)
    }
}

private fun printBanner() {
    val banner = """
        
        ███████╗██╗      █████╗ ███████╗██╗  ██╗
        ██╔════╝██║     ██╔══██╗██╔════╝██║  ██║
        █████╗  ██║     ███████║███████╗███████║
        ██╔══╝  ██║     ██╔══██║╚════██║██╔══██║
        ██║     ███████╗██║  ██║███████║██║  ██║
        ╚═╝     ╚══════╝╚═╝  ╚═╝╚══════╝╚═╝  ╚═╝
               E N T E R P R I S E   H U B
         Local-First Secure Relay & Mailbox Daemon
    """.trimIndent()
    println(banner)
}
