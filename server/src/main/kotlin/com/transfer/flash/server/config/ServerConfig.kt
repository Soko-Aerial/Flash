package com.transfer.flash.server.config

import com.transfer.flash.server.auth.SecureFiles
import com.transfer.flash.server.auth.TrustedProxies
import java.io.File
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

/**
 * Configuration for the Flash Enterprise Hub standalone server (SEC-06, SEC-16).
 *
 * Precedence: command-line arguments > environment variables > defaults. A malformed number or an unknown value is a
 * startup error: the hub never silently falls back to a default for something the operator tried to set.
 *
 * Transport security is explicit, three modes ([tlsMode]):
 * - `proxy` (default): TLS ends at a reverse proxy in front of the hub. `X-Forwarded-Proto/For` are believed ONLY from
 *   addresses listed in [trustedProxies] (`FLASH_TRUSTED_PROXIES`).
 * - `self`: the hub terminates TLS itself (a self-signed certificate it generates in [dataDir], or the keystore you
 *   supply); its SHA-256 fingerprint is printed at start-up so a client can pin it.
 * - `none`: plain HTTP, for local development only. Refused unless [allowInsecure] (`FLASH_ALLOW_INSECURE=true`), and
 *   the hub logs a warning on every start.
 *
 * @property port Public listener (relay WebSocket, REST, enrolment landing).
 * @property adminPort Management listener for `/admin` (see [adminHost], [adminOnPublicPort]).
 * @property host Bind address of the public listener.
 * @property adminHost Bind address of the management listener (default loopback: reach it with an SSH tunnel or a proxy).
 * @property adminOnPublicPort Also serve `/admin` on [port]. Off by default: the admin console is then reachable only on the management listener.
 * @property allowInsecure Permit `tlsMode=none`, an `http://` [externalUrl] and `--dev-embedded-root`.
 * @property trustedProxies CIDR blocks / addresses of the reverse proxies whose forwarded headers are believed.
 * @property delegationFile Path of the root-signed delegation certificate (default `<dataDir>/delegation.cert`).
 * @property devEmbeddedRoot DEVELOPMENT ONLY: the hub mints its own root key and delegation (the very thing SEC-04 removes).
 * @property setupToken Explicit one-time admin setup token; null makes the hub create one in `<dataDir>/setup-token.txt`.
 * @property managementToken Optional bearer token for `/health/detail` and `/metrics` (SERVER-B's guard). Without it only a direct loopback caller (no proxy headers) is allowed.
 * @property requireRootPin Refuse the first delegation unless [rootFingerprint] is set (`FLASH_REQUIRE_ROOT_PIN=true`).
 * @property rootFingerprint Optional pin (SHA-256 hex of the organisation root key): a delegation under another root is refused.
 */
public data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val adminPort: Int = DEFAULT_ADMIN_PORT,
    val host: String = DEFAULT_HOST,
    val dataDir: String = DEFAULT_DATA_DIR,
    val tlsMode: String = DEFAULT_TLS_MODE,
    val setupToken: String? = null,
    val challengeTimeoutMs: Long = DEFAULT_CHALLENGE_TIMEOUT_MS,
    val mailboxTtlDays: Int = DEFAULT_MAILBOX_TTL_DAYS,
    val externalUrl: String = resolveDefaultExternalUrl(host, port, if (tlsMode == TLS_NONE) "http" else "https"),
    val adminHost: String = DEFAULT_ADMIN_HOST,
    val adminOnPublicPort: Boolean = false,
    val allowInsecure: Boolean = false,
    val trustedProxies: List<String> = emptyList(),
    val delegationFile: String? = null,
    val devEmbeddedRoot: Boolean = false,
    val tlsKeystoreFile: String? = null,
    val tlsKeystorePassword: String? = null,
    val hubName: String = DEFAULT_HUB_NAME,
    val deviceQuota: Int = DEFAULT_DEVICE_QUOTA,
    val rootFingerprint: String? = null,
    /** Refuse the first delegation unless [rootFingerprint] is set: no trust on first use of whatever file appears (REV-16). */
    val requireRootPin: Boolean = false,
    val managementToken: String? = null,
) {

    init {
        require(port in 1..65535) { "port must be between 1 and 65535, was: $port" }
        require(adminPort in 1..65535) { "adminPort must be between 1 and 65535, was: $adminPort" }
        require(host.isNotBlank()) { "host must not be blank" }
        require(adminHost.isNotBlank()) { "adminHost must not be blank" }
        require(dataDir.isNotBlank()) { "dataDir must not be blank" }
        require(tlsMode in TLS_MODES) { "tlsMode must be one of $TLS_MODES, was: '$tlsMode'" }
        require(setupToken == null || setupToken.length >= MIN_SETUP_TOKEN_CHARS) {
            "setupToken must be at least $MIN_SETUP_TOKEN_CHARS characters"
        }
        require(managementToken == null || managementToken.length >= MIN_SETUP_TOKEN_CHARS) {
            "managementToken must be at least $MIN_SETUP_TOKEN_CHARS characters"
        }
        require(challengeTimeoutMs > 0) { "challengeTimeoutMs must be positive, was: $challengeTimeoutMs" }
        require(mailboxTtlDays > 0) { "mailboxTtlDays must be positive, was: $mailboxTtlDays" }
        require(deviceQuota > 0) { "deviceQuota must be positive, was: $deviceQuota" }
        require(externalUrl.isNotBlank()) { "externalUrl must not be blank" }
        val scheme = runCatching { URI(externalUrl).scheme?.lowercase() }.getOrNull()
        require(scheme == "https" || scheme == "http") { "externalUrl must be an http(s) URL, was: '$externalUrl'" }
        require(tlsMode != TLS_NONE || allowInsecure) {
            "tlsMode 'none' sends everything in clear text. Set FLASH_ALLOW_INSECURE=true to run it anyway (development only)."
        }
        require(scheme == "https" || allowInsecure) {
            "externalUrl must be https:// (invites and QR codes carry it). Set FLASH_EXTERNAL_URL to the public https address, " +
                "or FLASH_ALLOW_INSECURE=true for local development."
        }
        require(!devEmbeddedRoot || allowInsecure) {
            "--dev-embedded-root keeps the organisation root key ON the server: development only, needs FLASH_ALLOW_INSECURE=true."
        }
        require(adminOnPublicPort || adminPort != port) { "adminPort must differ from port unless adminOnPublicPort is set" }
        // Throws IllegalArgumentException for a malformed entry: a typo must not silently trust everything or nothing.
        TrustedProxies(trustedProxies)
        require(rootFingerprint == null || rootFingerprint.matches(Regex("^[0-9a-fA-F]{64}$"))) {
            "rootFingerprint must be 64 hex characters (SHA-256 of the root public key)"
        }
    }

    /**
     * A sentence to log loudly when the hub sits behind a proxy it does not know (REV-10): with `tlsMode=proxy` and no
     * `FLASH_TRUSTED_PROXIES` every client appears to come from the proxy's address, so every per-address limit (sign-in lockout,
     * connection cap, enrolment) is shared by the whole organisation: one noisy or hostile device locks everybody out.
     */
    fun proxyMisconfigurationWarning(): String? =
        if (tlsMode == TLS_PROXY && trustedProxies.isEmpty()) {
            "TLS mode 'proxy' without FLASH_TRUSTED_PROXIES: the hub cannot see client addresses. Every device and administrator " +
                "shares the proxy's address in the rate limits (a few failed sign-ins lock EVERYONE out, and the per-address connection " +
                "cap applies to the whole organisation). Set FLASH_TRUSTED_PROXIES to the proxy's address or CIDR block."
        } else {
            null
        }

    /** True when requests reach the hub over TLS terminated by the hub itself. */
    val directTls: Boolean get() = tlsMode == TLS_SELF

    /** The delegation certificate file this configuration reads. */
    val delegationPath: File get() = File(delegationFile ?: File(dataDir, "delegation.cert").path)

    /** The setup token file of this data directory. */
    val setupTokenPath: File get() = File(dataDir, SETUP_TOKEN_FILENAME)

    public companion object {
        public const val DEFAULT_PORT: Int = 8443
        public const val DEFAULT_ADMIN_PORT: Int = 8444
        public const val DEFAULT_HOST: String = "0.0.0.0"
        public const val DEFAULT_ADMIN_HOST: String = "127.0.0.1"
        public const val DEFAULT_DATA_DIR: String = "./data"
        public const val DEFAULT_TLS_MODE: String = "proxy"
        public const val DEFAULT_CHALLENGE_TIMEOUT_MS: Long = 60_000L
        public const val DEFAULT_MAILBOX_TTL_DAYS: Int = 7
        public const val DEFAULT_HUB_NAME: String = "Flash Hub"
        public const val DEFAULT_DEVICE_QUOTA: Int = 1000

        public const val TLS_PROXY: String = "proxy"
        public const val TLS_SELF: String = "self"
        public const val TLS_NONE: String = "none"
        public val TLS_MODES: List<String> = listOf(TLS_PROXY, TLS_SELF, TLS_NONE)

        public const val MIN_SETUP_TOKEN_CHARS: Int = 16
        public const val SETUP_TOKEN_FILENAME: String = "setup-token.txt"

        /**
         * Parses [ServerConfig] from CLI arguments, environment variables and defaults. Throws
         * [IllegalArgumentException] with a message naming the offending setting.
         */
        public fun fromEnvironment(
            args: Array<String> = emptyArray(),
            env: Map<String, String> = System.getenv(),
        ): ServerConfig {
            val cli = parseCliArgs(args)

            fun text(vararg keys: String, envName: String): String? =
                keys.firstNotNullOfOrNull { cli[it] } ?: env[envName]?.takeIf { it.isNotBlank() }

            fun int(vararg keys: String, envName: String, default: Int): Int {
                val raw = text(*keys, envName = envName) ?: return default
                return raw.trim().toIntOrNull() ?: throw IllegalArgumentException("$envName must be a whole number, was: '$raw'")
            }

            fun long(vararg keys: String, envName: String, default: Long): Long {
                val raw = text(*keys, envName = envName) ?: return default
                return raw.trim().toLongOrNull() ?: throw IllegalArgumentException("$envName must be a whole number, was: '$raw'")
            }

            fun bool(vararg keys: String, envName: String): Boolean {
                val raw = (keys.firstNotNullOfOrNull { cli[it] } ?: env[envName])?.trim()?.lowercase() ?: return false
                return when (raw) {
                    "true", "1", "yes" -> true
                    "false", "0", "no", "" -> false
                    else -> throw IllegalArgumentException("$envName must be true or false, was: '$raw'")
                }
            }

            val dataDir = text("data-dir", "dataDir", envName = "FLASH_DATA_DIR") ?: DEFAULT_DATA_DIR
            val port = int("port", envName = "FLASH_PORT", default = DEFAULT_PORT)
            val host = text("host", envName = "FLASH_HOST") ?: DEFAULT_HOST
            val tlsMode = (text("tls-mode", "tlsMode", envName = "FLASH_TLS_MODE") ?: DEFAULT_TLS_MODE).lowercase()
            val allowInsecure = bool("allow-insecure", "allowInsecure", envName = "FLASH_ALLOW_INSECURE")
            val externalUrl = text("external-url", "externalUrl", envName = "FLASH_EXTERNAL_URL")
                ?: resolveDefaultExternalUrl(host, port, if (tlsMode == TLS_NONE) "http" else "https")

            return ServerConfig(
                port = port,
                adminPort = int("admin-port", "adminPort", envName = "FLASH_ADMIN_PORT", default = DEFAULT_ADMIN_PORT),
                host = host,
                dataDir = dataDir,
                tlsMode = tlsMode,
                setupToken = text("setup-token", "setupToken", envName = "FLASH_SETUP_TOKEN"),
                managementToken = text("management-token", "managementToken", envName = "FLASH_MANAGEMENT_TOKEN"),
                challengeTimeoutMs = long("challenge-timeout-ms", "challengeTimeoutMs", envName = "FLASH_CHALLENGE_TIMEOUT_MS", default = DEFAULT_CHALLENGE_TIMEOUT_MS),
                mailboxTtlDays = int("mailbox-ttl-days", "mailboxTtlDays", envName = "FLASH_MAILBOX_TTL_DAYS", default = DEFAULT_MAILBOX_TTL_DAYS),
                externalUrl = externalUrl,
                adminHost = text("admin-host", "adminHost", envName = "FLASH_ADMIN_HOST") ?: DEFAULT_ADMIN_HOST,
                adminOnPublicPort = bool("admin-on-public-port", "adminOnPublicPort", envName = "FLASH_ADMIN_ON_PUBLIC_PORT"),
                allowInsecure = allowInsecure,
                trustedProxies = (text("trusted-proxies", "trustedProxies", envName = "FLASH_TRUSTED_PROXIES") ?: "")
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() },
                delegationFile = text("delegation-file", "delegationFile", envName = "FLASH_DELEGATION_FILE"),
                devEmbeddedRoot = bool("dev-embedded-root", "devEmbeddedRoot", envName = "FLASH_DEV_EMBEDDED_ROOT"),
                tlsKeystoreFile = text("tls-keystore", "tlsKeystore", envName = "FLASH_TLS_KEYSTORE"),
                tlsKeystorePassword = text("tls-keystore-password", "tlsKeystorePassword", envName = "FLASH_TLS_KEYSTORE_PASSWORD"),
                hubName = text("hub-name", "hubName", envName = "FLASH_HUB_NAME") ?: DEFAULT_HUB_NAME,
                deviceQuota = int("device-quota", "deviceQuota", envName = "FLASH_DEVICE_QUOTA", default = DEFAULT_DEVICE_QUOTA),
                rootFingerprint = text("root-fingerprint", "rootFingerprint", envName = "FLASH_ROOT_FINGERPRINT"),
                requireRootPin = text("require-root-pin", "requireRootPin", envName = "FLASH_REQUIRE_ROOT_PIN")?.equals("true", ignoreCase = true) == true,
            )
        }

        /**
         * Resolves the default external URL of this hub in [scheme]: the bind host when it is a real address, else the
         * detected LAN IPv4 (so phones on the same network can reach it), else `localhost`. This is only a guess:
         * production sets FLASH_EXTERNAL_URL to the public https address.
         */
        public fun resolveDefaultExternalUrl(host: String, port: Int, scheme: String = "https"): String {
            if (host != "0.0.0.0" && host != "127.0.0.1" && host != "localhost") {
                return "$scheme://$host:$port"
            }
            val lanIp = detectLanIpv4()
            return if (lanIp != null) "$scheme://$lanIp:$port" else "$scheme://localhost:$port"
        }

        /** Detects the non-loopback IPv4 address for local LAN testing. */
        public fun detectLanIpv4(): String? {
            return runCatching {
                val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
                val candidates = mutableListOf<String>()
                for (iface in interfaces) {
                    if (iface.isLoopback || !iface.isUp) continue
                    for (addr in iface.inetAddresses) {
                        if (addr is java.net.Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return ip
                            candidates.add(ip)
                        }
                    }
                }
                candidates.firstOrNull()
            }.getOrNull()
        }

        /**
         * The setup token of [dataDir]: read from `setup-token.txt` or created there (owner-only permissions). The
         * token is written to that FILE and never to the console or the log: the operator reads the file. A file that
         * can not be created is an error (the hub must not hand out a token nobody can read back).
         */
        public fun generateOrLoadSetupToken(dataDir: String): String {
            val dir = File(dataDir)
            dir.mkdirs()
            val tokenFile = File(dir, SETUP_TOKEN_FILENAME)
            if (tokenFile.isFile) {
                val existing = tokenFile.readText().trim()
                if (existing.length >= MIN_SETUP_TOKEN_CHARS) return existing
            }
            val bytes = ByteArray(24).also(SecureRandom()::nextBytes)
            val generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            SecureFiles.writeOwnerOnly(tokenFile.toPath(), generated.encodeToByteArray())
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
                        map[arg.substring(2, eqIndex)] = arg.substring(eqIndex + 1)
                    } else if (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                        map[arg.substring(2)] = args[i + 1]
                        i++
                    } else {
                        map[arg.substring(2)] = "true" // a bare flag such as --dev-embedded-root
                    }
                }
                i++
            }
            return map
        }
    }
}
