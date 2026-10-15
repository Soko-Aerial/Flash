package com.transfer.flash.server

import com.transfer.flash.server.admin.AuditLogger
import com.transfer.flash.server.admin.AuditSigner
import com.transfer.flash.server.admin.AdminAuthService
import com.transfer.flash.server.auth.HubKeyStore
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.storage.ServerDatabase
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("FlashEnterpriseHub")

/** Environment variable that supplies the root key pass-phrase to `keytool` non-interactively (CI, air-gapped scripts). */
internal const val ROOT_PASSPHRASE_ENV = "FLASH_ROOT_PASSPHRASE"

/**
 * Entry point. Subcommands:
 * - `serve` (default): run the hub.
 * - `keytool init-root | issue-delegation | rotate`: the OFFLINE root key tool (run it on another machine).
 * - `healthcheck`: probe this hub's own `/health` on loopback (the Docker HEALTHCHECK); exit 0 when it answers 200.
 * - `admin-recovery --username <name>`: break-glass reset of an admin (prints a new password and authenticator secret once).
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()?.takeUnless { it.startsWith("--") } ?: "serve"
    val rest = if (args.firstOrNull()?.startsWith("--") == false) args.drop(1).toTypedArray() else args
    val code = try {
        when (command) {
            "serve" -> serve(rest)
            "keytool" -> runKeyTool(rest.toList())
            "healthcheck" -> healthcheck(rest)
            "admin-recovery" -> adminRecovery(rest)
            "help", "--help", "-h" -> usage(0)
            else -> usage(2)
        }
    } catch (e: HubStartupException) {
        System.err.println("FLASH HUB: ${e.message}")
        1
    } catch (e: IllegalArgumentException) {
        // A bad setting names itself ("FLASH_PORT must be a whole number ...").
        System.err.println("FLASH HUB: invalid configuration: ${e.message}")
        2
    }
    if (code != 0) exitProcess(code)
}

private fun usage(code: Int): Int {
    val text = """
        Flash Enterprise Hub
          serve                          run the hub (default)
          keytool init-root --out DIR [--no-passphrase]
          keytool issue-delegation --root-key FILE --server-pub FILE_OR_BASE64 --scopes enrol:member,enrol:dept_admin,revoke:member
                  [--max-devices N] [--valid-days N] [--serial N] [--out FILE]
          keytool rotate
          healthcheck                    probe /health on loopback (exit 0 = healthy)
          admin-recovery --username NAME break-glass admin reset (console only)
    """.trimIndent()
    (if (code == 0) System.out else System.err).println(text)
    return code
}

private fun serve(args: Array<String>): Int {
    val config = ServerConfig.fromEnvironment(args)
    println("Flash Enterprise Hub")
    logger.info("Listening: {}:{} (management {}:{}), TLS mode {}", config.host, config.port, config.adminHost, config.adminPort, config.tlsMode)
    logger.info("External URL: {}", config.externalUrl)
    logger.info("Data directory: {}", File(config.dataDir).absolutePath)
    if (config.devEmbeddedRoot) {
        logger.error("DEVELOPMENT MODE: --dev-embedded-root keeps an organisation root key on this server. Never use it in production.")
    }
    config.proxyMisconfigurationWarning()?.let { logger.warn("CONFIGURATION: {}", it) }
    if (config.allowInsecure) {
        logger.warn("INSECURE MODE: --allow-insecure permits plain http invites and links. Never use it in production.")
    }

    val hub = HubServices.build(config)
    Runtime.getRuntime().addShutdownHook(
        Thread(
            {
                logger.info("Shutdown signal received. Stopping Flash Enterprise Hub...")
                hub.stop()
            },
            "flash-server-shutdown-hook",
        ),
    )
    return try {
        hub.start(wait = true)
        0
    } catch (e: Throwable) {
        logger.error("Flash Enterprise Hub terminated: {}", e.message ?: e.javaClass.simpleName)
        1
    }
}

private fun runKeyTool(args: List<String>): Int {
    val passphrases = PassphraseSource { prompt, confirm ->
        System.getenv(ROOT_PASSPHRASE_ENV)?.takeIf { it.isNotEmpty() }?.toCharArray()
            ?: System.console()?.let { console ->
                val first = console.readPassword("%s", prompt)
                if (first == null || first.isEmpty()) {
                    null
                } else {
                    if (confirm) {
                        val again = console.readPassword("%s", "Repeat: ")
                        if (again == null || !again.contentEquals(first)) throw KeyToolException("The pass-phrases do not match.")
                    }
                    first
                }
            }
    }
    return KeyTool.run(args, KeyTool(passphrases = passphrases))
}

/**
 * The Docker HEALTHCHECK. It talks to the hub's OWN listener over loopback, so in `self` mode it accepts the hub's
 * self-signed certificate: the trust is limited to 127.0.0.1 / the configured bind address and only reads `/health`.
 */
internal fun healthcheck(args: Array<String>): Int {
    val config = ServerConfig.fromEnvironment(args)
    val host = config.host.takeUnless { it == "0.0.0.0" || it == "::" || it.isBlank() } ?: "127.0.0.1"
    val scheme = if (config.directTls) "https" else "http"
    val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
    if (config.directTls) {
        val trustLoopback = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        builder.sslContext(SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustLoopback), SecureRandom()) })
    }
    return try {
        val request = HttpRequest.newBuilder(URI.create("$scheme://$host:${config.port}/health")).timeout(Duration.ofSeconds(5)).GET().build()
        val response = builder.build().send(request, HttpResponse.BodyHandlers.discarding())
        if (response.statusCode() == 200) 0 else 1
    } catch (e: Exception) {
        1
    }
}

internal fun adminRecovery(args: Array<String>): Int {
    val config = ServerConfig.fromEnvironment(args)
    val index = args.indexOf("--username")
    val username = args.getOrNull(index + 1)?.takeIf { index >= 0 }
    if (username == null) {
        System.err.println("admin-recovery needs --username <name>")
        return 2
    }
    val dataDir = File(config.dataDir)
    val delegate = HubKeyStore(dataDir.toPath()).loadOrCreateDelegateKey()
    ServerDatabase("jdbc:sqlite:${File(dataDir, "flash-hub.db").absolutePath}").use { db ->
        val admins = AdminAuthService(printSetupTokenToConsole = false, db = db)
        val creds = admins.breakGlassReset(username)
        AuditLogger(db, AuditSigner(delegate.pair.private, delegate.publicKey))
            .log(actor = "console", action = "ADMIN_BREAK_GLASS", details = "Break-glass reset of admin ${creds.username} from the server console")
        println("Admin: ${creds.username}")
        println("One-time password: ${creds.password}")
        println("Authenticator secret (Base32): ${creds.totpSecret}")
        println("Authenticator URI: ${creds.otpAuthUri}")
        println("Recovery codes (shown once): ${creds.recoveryCodes.joinToString(" ")}")
        println("All sessions of this admin were ended. Change the password after signing in.")
    }
    return 0
}
