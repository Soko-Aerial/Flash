package com.transfer.flash.server.config

import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Configuration for the Flash Enterprise Hub standalone server.
 *
 * All settings have production-ready defaults and can be configured through
 * environment variables, command-line arguments, or code.
 *
 * @property port Main communication port for TLS/WSS connections and endpoints (default: 8443 or FLASH_PORT).
 * @property adminPort Dedicated port for internal administration and metrics (default: 8444 or FLASH_ADMIN_PORT).
 * @property host Listening network interface binding (default: "0.0.0.0" or FLASH_HOST).
 * @property dataDir Directory for durable persistence, mailboxes, and tokens (default: "./data" or FLASH_DATA_DIR).
 * @property tlsMode TLS termination mode: "proxy" for reverse proxy (nginx/caddy) or "self" for built-in TLS (default: "proxy" or FLASH_TLS_MODE).
 * @property setupToken Secret one-time token for initial administrator onboarding (generated if not provided).
 * @property challengeTimeoutMs Nonce challenge validity timeout in milliseconds (default: 60000 or FLASH_CHALLENGE_TIMEOUT_MS).
 * @property mailboxTtlDays Store-and-forward mailbox message retention period in days (default: 7 or FLASH_MAILBOX_TTL_DAYS).
 */
public data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val adminPort: Int = DEFAULT_ADMIN_PORT,
    val host: String = DEFAULT_HOST,
    val dataDir: String = DEFAULT_DATA_DIR,
    val tlsMode: String = DEFAULT_TLS_MODE,
    val setupToken: String = generateOrLoadSetupToken(DEFAULT_DATA_DIR),
    val challengeTimeoutMs: Long = DEFAULT_CHALLENGE_TIMEOUT_MS,
    val mailboxTtlDays: Int = DEFAULT_MAILBOX_TTL_DAYS,
) {

    init {
        require(port in 1..65535) { "port must be between 1 and 65535, was: $port" }
        require(adminPort in 1..65535) { "adminPort must be between 1 and 65535, was: $adminPort" }
        require(host.isNotBlank()) { "host must not be blank" }
        require(dataDir.isNotBlank()) { "dataDir must not be blank" }
        require(tlsMode in listOf("proxy", "self")) { "tlsMode must be 'proxy' or 'self', was: '$tlsMode'" }
        require(setupToken.isNotBlank()) { "setupToken must not be blank" }
        require(challengeTimeoutMs > 0) { "challengeTimeoutMs must be positive, was: $challengeTimeoutMs" }
        require(mailboxTtlDays > 0) { "mailboxTtlDays must be positive, was: $mailboxTtlDays" }
    }

    public companion object {
        public const val DEFAULT_PORT: Int = 8443
        public const val DEFAULT_ADMIN_PORT: Int = 8444
        public const val DEFAULT_HOST: String = "0.0.0.0"
        public const val DEFAULT_DATA_DIR: String = "./data"
        public const val DEFAULT_TLS_MODE: String = "proxy"
        public const val DEFAULT_CHALLENGE_TIMEOUT_MS: Long = 60_000L
        public const val DEFAULT_MAILBOX_TTL_DAYS: Int = 7

        private const val SETUP_TOKEN_FILENAME: String = "setup-token.txt"

        /**
         * Parses [ServerConfig] by resolving CLI arguments, environment variables, and fallback defaults.
         *
         * Precedence: CLI arguments > Environment variables > Defaults.
         */
        public fun fromEnvironment(
            args: Array<String> = emptyArray(),
            env: Map<String, String> = System.getenv(),
        ): ServerConfig {
            val cliMap = parseCliArgs(args)

            val dataDir = cliMap["data-dir"]
                ?: cliMap["dataDir"]
                ?: env["FLASH_DATA_DIR"]
                ?: DEFAULT_DATA_DIR

            val port = (cliMap["port"] ?: env["FLASH_PORT"])?.toIntOrNull() ?: DEFAULT_PORT
            val adminPort = (cliMap["admin-port"] ?: cliMap["adminPort"] ?: env["FLASH_ADMIN_PORT"])?.toIntOrNull()
                ?: DEFAULT_ADMIN_PORT
            val host = cliMap["host"] ?: env["FLASH_HOST"] ?: DEFAULT_HOST
            val tlsMode = cliMap["tls-mode"] ?: cliMap["tlsMode"] ?: env["FLASH_TLS_MODE"] ?: DEFAULT_TLS_MODE
            val setupToken = cliMap["setup-token"]
                ?: cliMap["setupToken"]
                ?: env["FLASH_SETUP_TOKEN"]
                ?: generateOrLoadSetupToken(dataDir)
            val challengeTimeoutMs = (cliMap["challenge-timeout-ms"]
                ?: cliMap["challengeTimeoutMs"]
                ?: env["FLASH_CHALLENGE_TIMEOUT_MS"])?.toLongOrNull() ?: DEFAULT_CHALLENGE_TIMEOUT_MS
            val mailboxTtlDays = (cliMap["mailbox-ttl-days"]
                ?: cliMap["mailboxTtlDays"]
                ?: env["FLASH_MAILBOX_TTL_DAYS"])?.toIntOrNull() ?: DEFAULT_MAILBOX_TTL_DAYS

            return ServerConfig(
                port = port,
                adminPort = adminPort,
                host = host,
                dataDir = dataDir,
                tlsMode = tlsMode,
                setupToken = setupToken,
                challengeTimeoutMs = challengeTimeoutMs,
                mailboxTtlDays = mailboxTtlDays,
            )
        }

        /**
         * Loads existing setup token from [dataDir]/setup-token.txt or generates and persists a new one.
         */
        public fun generateOrLoadSetupToken(dataDir: String): String {
            val dir = File(dataDir)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val tokenFile = File(dir, SETUP_TOKEN_FILENAME)
            if (tokenFile.exists()) {
                val existing = tokenFile.readText().trim()
                if (existing.isNotEmpty()) {
                    return existing
                }
            }

            // Generate cryptographically secure random token (24 bytes = 32 base64 chars)
            val random = SecureRandom()
            val bytes = ByteArray(24)
            random.nextBytes(bytes)
            val generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

            try {
                tokenFile.writeText(generated)
            } catch (_: Throwable) {
                // Ignore filesystem write failure in read-only / test environments
            }
            return generated
        }

        private fun parseCliArgs(args: Array<String>): Map<String, String> {
            val map = mutableMapOf<String, String>()
            var i = 0
            while (i < args.size) {
                val arg = args[i]
                if (arg.startsWith("--")) {
                    val eqIndex = arg.indexOf('=')
                    if (eqIndex != -1) {
                        val key = arg.substring(2, eqIndex)
                        val value = arg.substring(eqIndex + 1)
                        map[key] = value
                    } else if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                        val key = arg.substring(2)
                        val value = args[i + 1]
                        map[key] = value
                        i++
                    }
                }
                i++
            }
            return map
        }
    }
}
