package com.transfer.flash.server.config

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerConfigTest {

    @Test
    fun testDefaults() {
        val config = ServerConfig()
        assertEquals(8443, config.port)
        assertEquals(8444, config.adminPort)
        assertEquals("0.0.0.0", config.host)
        assertEquals("./data", config.dataDir)
        assertEquals("proxy", config.tlsMode)
        assertNotNull(config.setupToken)
        assertTrue(config.setupToken.isNotEmpty())
        assertEquals(60000L, config.challengeTimeoutMs)
        assertEquals(7, config.mailboxTtlDays)
        assertTrue(config.externalUrl.startsWith("http://"))
    }

    @Test
    fun testCliArgOverrides() {
        val args = arrayOf(
            "--port=9000",
            "--admin-port=9001",
            "--host=127.0.0.1",
            "--data-dir=/tmp/flash-test",
            "--tls-mode=self",
            "--setup-token=secret-token",
            "--challenge-timeout-ms=30000",
            "--mailbox-ttl-days=14",
            "--external-url=http://192.168.1.150:9000",
        )
        val config = ServerConfig.fromEnvironment(args = args, env = emptyMap())
        assertEquals(9000, config.port)
        assertEquals(9001, config.adminPort)
        assertEquals("127.0.0.1", config.host)
        assertEquals("/tmp/flash-test", config.dataDir)
        assertEquals("self", config.tlsMode)
        assertEquals("secret-token", config.setupToken)
        assertEquals(30000L, config.challengeTimeoutMs)
        assertEquals(14, config.mailboxTtlDays)
        assertEquals("http://192.168.1.150:9000", config.externalUrl)
    }

    @Test
    fun testEnvOverrides() {
        val env = mapOf(
            "FLASH_PORT" to "8080",
            "FLASH_ADMIN_PORT" to "8081",
            "FLASH_HOST" to "0.0.0.0",
            "FLASH_DATA_DIR" to "./custom-data",
            "FLASH_TLS_MODE" to "proxy",
            "FLASH_SETUP_TOKEN" to "env-token",
            "FLASH_CHALLENGE_TIMEOUT_MS" to "45000",
            "FLASH_MAILBOX_TTL_DAYS" to "3",
            "FLASH_EXTERNAL_URL" to "http://10.0.0.5:8080",
        )
        val config = ServerConfig.fromEnvironment(args = emptyArray(), env = env)
        assertEquals(8080, config.port)
        assertEquals(8081, config.adminPort)
        assertEquals("./custom-data", config.dataDir)
        assertEquals("env-token", config.setupToken)
        assertEquals(45000L, config.challengeTimeoutMs)
        assertEquals(3, config.mailboxTtlDays)
        assertEquals("http://10.0.0.5:8080", config.externalUrl)
    }

    @Test
    fun testLanIpResolutionFallback() {
        val resolved = ServerConfig.resolveDefaultExternalUrl("192.168.1.99", 8443)
        assertEquals("http://192.168.1.99:8443", resolved)
    }
}
