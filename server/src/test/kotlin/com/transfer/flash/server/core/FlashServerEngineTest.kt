@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.core

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.rest.ErrorBody
import com.transfer.flash.core.hubprotocol.rest.HealthLite
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.mailbox.MailboxLimits
import com.transfer.flash.server.mailbox.MailboxMessage
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.observability.DefaultManagementGuard
import com.transfer.flash.server.relay.RelayConnectionPool
import com.transfer.flash.server.storage.ServerDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FlashServerEngineTest {

    private fun config(port: Int = 8443) = ServerConfig(
        port = port,
        adminPort = port,
        adminOnPublicPort = true,
        host = "127.0.0.1",
        dataDir = "./test-data",
        tlsMode = "proxy",
    )

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun healthIsPublicAndSaysNothingButStatus() = testApplication {
        val engine = FlashServerEngine(config = config(), connectionPool = RelayConnectionPool())
        application { engine.configureApplication(this) }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertEquals(HealthLite(status = "ok"), HubRestCodec.decode<HealthLite>(body))
        for (leak in listOf("port", "tlsMode", "memory", "activeSessions", "uptime", "server", "8443")) {
            assertFalse(body.contains(leak), "the public probe must not reveal '$leak': $body")
        }
    }

    @Test
    fun healthReportsDownWith503WhenTheDatabaseDoesNotAnswer() = testApplication {
        val db = ServerDatabase.inMemory()
        val engine = FlashServerEngine(config = config(), database = db)
        application { engine.configureApplication(this) }

        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        db.close()
        val down = client.get("/health")
        assertEquals(HttpStatusCode.ServiceUnavailable, down.status)
        assertEquals("down", HubRestCodec.decode<HealthLite>(down.bodyAsText()).status)
    }

    @Test
    fun healthDetailAndMetricsRefuseAnonymousAndProxiedCallers() = testApplication {
        val engine = FlashServerEngine(config = config(), managementGuard = DefaultManagementGuard(token = "s3cret", allowLoopback = false))
        application { engine.configureApplication(this) }

        for (path in listOf("/health/detail", "/metrics")) {
            val anonymous = client.get(path)
            assertEquals(HttpStatusCode.Forbidden, anonymous.status, path)
            val error = HubRestCodec.decode<ErrorBody>(anonymous.bodyAsText())
            assertEquals(HubErrorCodes.ERR_FORBIDDEN, error.code)
            assertFalse(anonymous.bodyAsText().contains("mailbox"), "nothing operational leaks in the refusal")

            assertEquals(HttpStatusCode.Forbidden, client.get(path) { header("Authorization", "Bearer wrong") }.status, path)
            assertEquals(HttpStatusCode.OK, client.get(path) { header("Authorization", "Bearer s3cret") }.status, path)
        }
    }

    @Test
    fun loopbackIsNotTrustedWhenTheRequestWasForwardedByAProxy() = testApplication {
        val engine = FlashServerEngine(config = config(), managementGuard = DefaultManagementGuard(token = null, allowLoopback = true))
        application { engine.configureApplication(this) }

        // Behind nginx every request comes from 127.0.0.1; the proxy header is what tells it apart from a local admin.
        assertEquals(HttpStatusCode.Forbidden, client.get("/health/detail") { header("X-Forwarded-For", "203.0.113.9") }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/metrics") { header("X-Real-IP", "203.0.113.9") }.status)
    }

    @Test
    fun healthDetailReportsQueueDepthSessionsAndDatabase() = testApplication {
        val db = ServerDatabase.inMemory()
        val mailbox = MailboxQueue(db, MailboxLimits())
        runBlocking { mailbox.enqueue(MailboxMessage("alice", "bob", "m1", "MSG_TEXT", "12345", System.currentTimeMillis())) }
        val engine = FlashServerEngine(
            config = config(),
            mailboxQueue = mailbox,
            database = db,
            managementGuard = DefaultManagementGuard(token = "t"),
            delegationDaysRemaining = { 12L },
        )
        application { engine.configureApplication(this) }

        val body = client.get("/health/detail") { header("Authorization", "Bearer t") }.bodyAsText()
        assertTrue(body.contains("\"queuedRows\":1"), body)
        assertTrue(body.contains("\"queuedBytes\":5"), body)
        assertTrue(body.contains("\"sessions\":0"), body)
        assertTrue(body.contains("\"database\":{\"ok\":true"), body)
        assertTrue(body.contains("\"delegationDaysRemaining\":12"), body)
        assertTrue(body.contains("\"status\":\"ok\""), body)
    }

    @Test
    fun metricsAreServedInPrometheusTextFormat() = testApplication {
        val db = ServerDatabase.inMemory()
        val mailbox = MailboxQueue(db, MailboxLimits())
        val engine = FlashServerEngine(config = config(), mailboxQueue = mailbox, database = db, managementGuard = DefaultManagementGuard(token = "t"))
        application { engine.configureApplication(this) }

        val response = client.get("/metrics") { header("Authorization", "Bearer t") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Type"]!!.startsWith("text/plain"))
        val body = response.bodyAsText()
        for (name in listOf("flash_relay_sessions", "flash_mailbox_queued_rows", "flash_mailbox_oldest_age_seconds", "flash_db_up", "flash_hub_uptime_seconds")) {
            assertTrue(body.contains("# TYPE $name gauge"), "missing $name in:\n$body")
        }
        assertTrue(body.lines().any { it == "flash_db_up 1" })
    }

    @Test
    fun anUnhandledExceptionAnswersAFixedSentenceAndACorrelationIdNeverTheCause() = testApplication {
        val engine = FlashServerEngine(config = config())
        application {
            engine.configureApplication(this)
            boom(this)
        }

        val response = client.get("/boom") { header(HubContract.HEADER_CORRELATION_ID, "req-77") }
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val text = response.bodyAsText()
        assertFalse(text.contains("jdbc:sqlite"), "the exception text must not reach the client: $text")
        assertFalse(text.contains("/etc/passwd"), text)
        val error = HubRestCodec.decode<ErrorBody>(text)
        assertEquals(HubErrorCodes.ERR_INTERNAL, error.code)
        assertEquals("req-77", error.correlationId)
        assertEquals("req-77", response.headers[HubContract.HEADER_CORRELATION_ID])
        assertTrue(error.message.isNotBlank())

        val noHeader = client.get("/boom")
        assertNotNull(HubRestCodec.decode<ErrorBody>(noHeader.bodyAsText()).correlationId, "a correlation id is minted when the client sent none")
    }

    private fun boom(app: Application) {
        app.routing { get("/boom") { error("jdbc:sqlite:/etc/passwd refused: secret internal detail") } }
    }

    @Test
    fun testRootEndpoint() = testApplication {
        val engine = FlashServerEngine()
        application { engine.configureApplication(this) }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Flash Enterprise Hub is running"))
    }

    @Test
    fun stopIsIdempotentAndClosesTheDatabase() {
        val db = ServerDatabase.inMemory()
        val engine = FlashServerEngine(config = config(freePort()), mailboxQueue = MailboxQueue(db), database = db)
        engine.start(wait = false)
        assertTrue(db.probe().ok)

        engine.stop(0, 500)
        engine.stop(0, 500) // second call: no exception, nothing happens twice
        assertFalse(db.probe().ok, "the database pool is closed by the engine")
    }

    @Test
    fun aFailedBindCleansUpInsteadOfLeavingThreadsAndPoolsRunning() {
        val port = freePort()
        val first = FlashServerEngine(config = config(port))
        first.start(wait = false)
        try {
            val db = ServerDatabase.inMemory()
            val second = FlashServerEngine(config = config(port), mailboxQueue = MailboxQueue(db), database = db)
            assertFailsWith<Throwable> { second.start(wait = false) }
            assertFalse(db.probe().ok, "a hub that could not bind must release its database")
        } finally {
            first.stop(0, 500)
        }
    }
}
