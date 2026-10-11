package com.transfer.flash.server.core

import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.relay.RelayConnectionPool
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlashServerEngineTest {

    @Test
    fun testHealthEndpoint() = testApplication {
        val pool = RelayConnectionPool()
        val config = ServerConfig(
            port = 8443,
            adminPort = 8444,
            host = "0.0.0.0",
            dataDir = "./test-data",
            tlsMode = "proxy",
            setupToken = "test-token-123",
        )
        val engine = FlashServerEngine(config = config, connectionPool = pool)

        application {
            engine.configureApplication(this)
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\": \"UP\""))
        assertTrue(body.contains("\"server\": \"Flash Enterprise Hub\""))
        assertTrue(body.contains("\"port\": 8443"))
        assertTrue(body.contains("\"tlsMode\": \"proxy\""))
        assertTrue(body.contains("\"activeSessions\": 0"))
    }

    @Test
    fun testRootEndpoint() = testApplication {
        val engine = FlashServerEngine()
        application {
            engine.configureApplication(this)
        }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Flash Enterprise Hub is running"))
    }
}
