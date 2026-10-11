package com.transfer.flash.server.core

import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.relay.MailboxQueueSeam
import com.transfer.flash.server.relay.NoOpMailboxQueueSeam
import com.transfer.flash.server.relay.RelayConnectionPool
import com.transfer.flash.server.relay.RelayFrameRouter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.time.Duration.Companion.seconds

import com.transfer.flash.server.admin.AdminAuthService
import com.transfer.flash.server.admin.AuditLogger
import com.transfer.flash.server.admin.adminDashboardRoutes
import com.transfer.flash.server.auth.ChallengeResponseAuth
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.authRoutes
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.directory.directoryRoutes

/**
 * Embedded Ktor Netty application engine managing the Flash Enterprise Hub.
 *
 * Configures the HTTP and WebSocket endpoints, StatusPages error handling,
 * liveness health metrics, and graceful shutdown orchestration.
 *
 * @property config Server operational configuration.
 * @property connectionPool Registry of connected peer sessions.
 * @property mailboxQueue Offline store-and-forward persistence seam.
 */
public class FlashServerEngine(
    public val config: ServerConfig = ServerConfig(),
    public val connectionPool: RelayConnectionPool = RelayConnectionPool(),
    public val mailboxQueue: MailboxQueueSeam = NoOpMailboxQueueSeam,
    public val directory: OrganizationDirectory = OrganizationDirectory(),
    public val auditLogger: AuditLogger = AuditLogger(),
    public val adminAuthService: AdminAuthService = AdminAuthService(printSetupTokenToConsole = true),
    public val challengeAuth: ChallengeResponseAuth? = null,
    public val enrollmentService: EnrollmentService? = null,
) {

    private val logger = LoggerFactory.getLogger(FlashServerEngine::class.java)
    private val serverStartTime: Long = System.currentTimeMillis()

    public val relayFrameRouter: RelayFrameRouter = RelayFrameRouter(
        connectionPool = connectionPool,
        mailboxQueue = mailboxQueue,
    )

    private val engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> = embeddedServer(
        factory = Netty,
        port = config.port,
        host = config.host,
        module = {
            configureApplication(this)
        },
    )

    /**
     * Installs plugins and routes onto the Ktor [Application].
     * Can be invoked directly by tests via `testApplication`.
     */
    public fun configureApplication(app: Application) {
        // 1. WebSockets Plugin configuration
        app.install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 30.seconds
            maxFrameSize = Long.MAX_VALUE
            masking = false
        }

        // 2. StatusPages Plugin configuration for error recovery
        app.install(StatusPages) {
            exception<Throwable> { call, cause ->
                logger.error("Unhandled server exception on '${call.request.local.uri}': {}", cause.message, cause)
                call.respondText(
                    text = "{\"error\":\"Internal server error\",\"message\":\"${cause.message?.replace("\"", "\\\"") ?: "unknown"}\"}",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.InternalServerError,
                )
            }
        }

        // 3. Application Routing
        app.routing {
            // Root overview
            get("/") {
                call.respondText(
                    text = "Flash Enterprise Hub is running. Query /health for diagnostic telemetry.",
                    contentType = ContentType.Text.Plain,
                )
            }

            // Health and Telemetry endpoint
            get("/health") {
                val uptimeSeconds = (System.currentTimeMillis() - serverStartTime) / 1000
                val rt = Runtime.getRuntime()
                val totalMemory = rt.totalMemory()
                val freeMemory = rt.freeMemory()
                val usedMemory = totalMemory - freeMemory

                val healthJson = """
                    {
                      "status": "UP",
                      "server": "Flash Enterprise Hub",
                      "uptimeSeconds": $uptimeSeconds,
                      "activeSessions": ${connectionPool.activeCount},
                      "memory": {
                        "totalBytes": $totalMemory,
                        "freeBytes": $freeMemory,
                        "usedBytes": $usedMemory
                      },
                      "port": ${config.port},
                      "adminPort": ${config.adminPort},
                      "tlsMode": "${config.tlsMode}"
                    }
                """.trimIndent()
                call.respondText(healthJson, ContentType.Application.Json)
            }

            // WebSocket Relay route
            relayFrameRouter.registerRoute(this)

            // Admin Dashboard routes (/admin/login, /admin/dashboard, etc.)
            adminDashboardRoutes(
                adminAuthService = adminAuthService,
                directory = directory,
                auditLogger = auditLogger,
                serverExternalUrl = "http://${config.host}:${config.port}",
            )

            // Organization Directory REST routes (/api/v1/directory/*)
            directoryRoutes(directory = directory)

            // Authentication & Device Enrolment routes (/api/v1/auth/*, /api/v1/enroll)
            val auth = challengeAuth
            val enroll = enrollmentService
            if (auth != null && enroll != null) {
                authRoutes(challengeAuth = auth, enrollmentService = enroll)
            }
        }
    }

    /**
     * Starts the embedded Netty server engine.
     *
     * @param wait When true, suspends until the server stops.
     */
    public fun start(wait: Boolean = true) {
        logger.info(
            "Starting Flash Enterprise Hub on {}:{} (adminPort: {}, tlsMode: {})",
            config.host,
            config.port,
            config.adminPort,
            config.tlsMode,
        )
        engine.start(wait = wait)
    }

    /**
     * Initiates a graceful server shutdown.
     */
    public fun stop(gracePeriodMillis: Long = 1000, timeoutMillis: Long = 5000) {
        logger.info("Initiating graceful shutdown for Flash Enterprise Hub...")
        runBlocking {
            connectionPool.closeAll("Server shutting down")
        }
        engine.stop(gracePeriodMillis, timeoutMillis)
        logger.info("Flash Enterprise Hub server stopped successfully.")
    }
}
