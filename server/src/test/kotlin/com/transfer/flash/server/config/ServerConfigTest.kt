package com.transfer.flash.server.config

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SEC-06 / SEC-16: a hub that is unsafe or ambiguous is refused at configuration time, never half-started. */
class ServerConfigTest {

    private val longToken = "0123456789abcdef-token"

    @Test
    fun testDefaults() {
        val config = ServerConfig()
        assertEquals(8443, config.port)
        assertEquals(8444, config.adminPort)
        assertEquals("0.0.0.0", config.host)
        assertEquals("127.0.0.1", config.adminHost, "the management listener is loopback by default")
        assertEquals("./data", config.dataDir)
        assertEquals("proxy", config.tlsMode)
        assertNull(config.setupToken, "no baked-in setup token: it is generated into a 0600 file")
        assertEquals(60000L, config.challengeTimeoutMs)
        assertEquals(7, config.mailboxTtlDays)
        assertTrue(config.externalUrl.startsWith("https://"), "invites carry https by default")
        assertFalse(config.allowInsecure)
        assertFalse(config.devEmbeddedRoot)
        assertFalse(config.adminOnPublicPort)
    }

    @Test
    fun testCliArgOverrides() {
        val args = arrayOf(
            "--port=9000",
            "--admin-port=9001",
            "--host=127.0.0.1",
            "--data-dir=/tmp/flash-test",
            "--tls-mode=self",
            "--setup-token=$longToken",
            "--challenge-timeout-ms=30000",
            "--mailbox-ttl-days=14",
            "--external-url=https://hub.example.org:9000",
        )
        val config = ServerConfig.fromEnvironment(args = args, env = emptyMap())
        assertEquals(9000, config.port)
        assertEquals(9001, config.adminPort)
        assertEquals("127.0.0.1", config.host)
        assertEquals("/tmp/flash-test", config.dataDir)
        assertEquals("self", config.tlsMode)
        assertTrue(config.directTls)
        assertEquals(longToken, config.setupToken)
        assertEquals(30000L, config.challengeTimeoutMs)
        assertEquals(14, config.mailboxTtlDays)
        assertEquals("https://hub.example.org:9000", config.externalUrl)
    }

    @Test
    fun testEnvOverrides() {
        val env = mapOf(
            "FLASH_PORT" to "8080",
            "FLASH_ADMIN_PORT" to "8081",
            "FLASH_HOST" to "0.0.0.0",
            "FLASH_DATA_DIR" to "./custom-data",
            "FLASH_TLS_MODE" to "proxy",
            "FLASH_SETUP_TOKEN" to longToken,
            "FLASH_CHALLENGE_TIMEOUT_MS" to "45000",
            "FLASH_MAILBOX_TTL_DAYS" to "3",
            "FLASH_EXTERNAL_URL" to "https://hub.example.org",
            "FLASH_TRUSTED_PROXIES" to "10.0.0.0/8, 192.168.1.5",
            "FLASH_ROOT_FINGERPRINT" to "ab".repeat(32),
        )
        val config = ServerConfig.fromEnvironment(args = emptyArray(), env = env)
        assertEquals(8080, config.port)
        assertEquals(8081, config.adminPort)
        assertEquals("./custom-data", config.dataDir)
        assertEquals(longToken, config.setupToken)
        assertEquals(45000L, config.challengeTimeoutMs)
        assertEquals(3, config.mailboxTtlDays)
        assertEquals("https://hub.example.org", config.externalUrl)
        assertEquals(listOf("10.0.0.0/8", "192.168.1.5"), config.trustedProxies)
        assertEquals("ab".repeat(32), config.rootFingerprint)
    }

    @Test
    fun testLanIpResolutionFallback() {
        assertEquals("https://192.168.1.99:8443", ServerConfig.resolveDefaultExternalUrl("192.168.1.99", 8443))
        assertEquals("http://192.168.1.99:8443", ServerConfig.resolveDefaultExternalUrl("192.168.1.99", 8443, "http"))
    }

    // ------------------------------------------------------------------------------------------ strictness

    @Test
    fun aMalformedNumberIsAnErrorNotADefault() {
        for ((name, value) in listOf("FLASH_PORT" to "80x", "FLASH_ADMIN_PORT" to "abc", "FLASH_MAILBOX_TTL_DAYS" to "7.5", "FLASH_CHALLENGE_TIMEOUT_MS" to "1e3")) {
            val e = assertFailsWith<IllegalArgumentException>(name) {
                ServerConfig.fromEnvironment(emptyArray(), mapOf(name to value))
            }
            assertTrue(e.message!!.contains(name), "the message names the setting: ${e.message}")
        }
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnvironment(emptyArray(), mapOf("FLASH_ALLOW_INSECURE" to "maybe")) }
    }

    @Test
    fun outOfRangeAndInconsistentValuesAreRefused() {
        assertFailsWith<IllegalArgumentException> { ServerConfig(port = 0) }
        assertFailsWith<IllegalArgumentException> { ServerConfig(port = 70000) }
        assertFailsWith<IllegalArgumentException> { ServerConfig(mailboxTtlDays = 0) }
        assertFailsWith<IllegalArgumentException> { ServerConfig(tlsMode = "magic") }
        assertFailsWith<IllegalArgumentException> { ServerConfig(setupToken = "short") }
        assertFailsWith<IllegalArgumentException> { ServerConfig(port = 9000, adminPort = 9000) }
        ServerConfig(port = 9000, adminPort = 9000, adminOnPublicPort = true)
        assertFailsWith<IllegalArgumentException> { ServerConfig(rootFingerprint = "not-hex") }
        assertFailsWith<IllegalArgumentException> { ServerConfig(trustedProxies = listOf("300.1.1.1/8")) }
    }

    @Test
    fun plainHttpAndTheEmbeddedRootNeedTheExplicitInsecureOptIn() {
        assertFailsWith<IllegalArgumentException> { ServerConfig(externalUrl = "http://hub.local:8443") }
        assertFailsWith<IllegalArgumentException> { ServerConfig(tlsMode = "none") }
        assertFailsWith<IllegalArgumentException> { ServerConfig(devEmbeddedRoot = true) }
        val dev = ServerConfig(externalUrl = "http://hub.local:8443", allowInsecure = true, tlsMode = "none", devEmbeddedRoot = true)
        assertTrue(dev.devEmbeddedRoot)
    }

    @Test
    fun theDevFlagComesFromTheCommandLineToo() {
        val config = ServerConfig.fromEnvironment(
            arrayOf("--dev-embedded-root", "--allow-insecure", "--external-url=http://localhost:8443", "--tls-mode=none"),
            emptyMap(),
        )
        assertTrue(config.devEmbeddedRoot)
        assertTrue(config.allowInsecure)
    }

    @Test
    fun aProxyModeHubThatDoesNotKnowItsProxyWarnsLoudly() {
        assertTrue(ServerConfig(tlsMode = "proxy", externalUrl = "https://hub.test").proxyMisconfigurationWarning()!!.contains("FLASH_TRUSTED_PROXIES"))
        assertNull(ServerConfig(tlsMode = "proxy", externalUrl = "https://hub.test", trustedProxies = listOf("10.0.0.0/8")).proxyMisconfigurationWarning())
        assertNull(ServerConfig(tlsMode = "self", externalUrl = "https://hub.test").proxyMisconfigurationWarning())
    }

    @Test
    fun theSetupTokenIsGeneratedIntoAFileAndReusedNotPrinted() {
        val dir = Files.createTempDirectory("flash-setup-token").toFile()
        try {
            val first = ServerConfig.generateOrLoadSetupToken(dir.path)
            val file = dir.resolve(ServerConfig.SETUP_TOKEN_FILENAME)
            assertTrue(file.isFile)
            assertTrue(com.transfer.flash.server.auth.SecureFiles.isOwnerOnly(file.toPath()), "owner-only")
            assertEquals(first, ServerConfig.generateOrLoadSetupToken(dir.path), "the same token until it is used")
            assertTrue(first.length >= ServerConfig.MIN_SETUP_TOKEN_CHARS)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theManagementTokenIsOptionalButNotShort() {
        assertNull(ServerConfig().managementToken)
        val config = ServerConfig.fromEnvironment(emptyArray(), mapOf("FLASH_MANAGEMENT_TOKEN" to longToken))
        assertEquals(longToken, config.managementToken)
        assertFailsWith<IllegalArgumentException> { ServerConfig(managementToken = "short") }
    }

    @Test
    fun theDelegationFileDefaultsToTheDataDirectory() {
        assertEquals("delegation.cert", ServerConfig(dataDir = "/var/flash").delegationPath.name)
        assertEquals("/etc/flash/d.cert".replace('/', java.io.File.separatorChar), ServerConfig(delegationFile = "/etc/flash/d.cert").delegationPath.path)
    }
}
