package com.transfer.flash.server.core

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.rest.HealthLite
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.server.admin.AdminAuthService
import com.transfer.flash.server.admin.AuditLogger
import com.transfer.flash.server.admin.AdminConsoleHeaders
import com.transfer.flash.server.admin.AdminWeb
import com.transfer.flash.server.admin.adminConsoleRoutes
import com.transfer.flash.server.auth.BearerPrincipalResolver
import com.transfer.flash.server.auth.HubApiGate
import com.transfer.flash.server.auth.installHubApiGate
import com.transfer.flash.server.config.TlsMaterial
import com.transfer.flash.server.config.TlsSupport
import io.ktor.server.application.pluginOrNull
import io.ktor.server.routing.Route
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.ChallengeResponseAuth
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.RevocationService
import com.transfer.flash.server.auth.authRoutes
import com.transfer.flash.server.auth.respondError
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.directory.directoryRoutes
import com.transfer.flash.server.mailbox.MailboxStore
import com.transfer.flash.server.mailbox.TtlSweeper
import com.transfer.flash.server.observability.DefaultManagementGuard
import com.transfer.flash.server.observability.LogSanitizer
import com.transfer.flash.server.observability.MailboxStats
import com.transfer.flash.server.observability.ManagementGuard
import com.transfer.flash.server.observability.RelayMetrics
import com.transfer.flash.server.relay.CertChainRelayAuthenticator
import com.transfer.flash.server.relay.DirectoryGroupMembership
import com.transfer.flash.server.relay.GroupMembershipView
import com.transfer.flash.server.relay.RelayAuthenticator
import com.transfer.flash.server.relay.RelayConnectionPool
import com.transfer.flash.server.relay.RelayFrameRouter
import com.transfer.flash.server.relay.RelayPolicy
import com.transfer.flash.server.storage.ServerDatabase
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Embedded Ktor Netty application engine managing the Flash Enterprise Hub.
 *
 * Configures the HTTP and WebSocket endpoints, StatusPages error handling, the public liveness probe, the guarded
 * operational pages (`/health/detail`, `/metrics`), the mailbox sweeper and an idempotent graceful shutdown.
 *
 * @property config Server operational configuration.
 * @property connectionPool Registry of connected peer sessions.
 * @property mailboxQueue Offline store-and-forward persistence ([MailboxStore.None] disables it).
 */
public class FlashServerEngine(
    public val config: ServerConfig = ServerConfig(),
    public val connectionPool: RelayConnectionPool = RelayConnectionPool(),
    public val mailboxQueue: MailboxStore = MailboxStore.None,
    public val directory: OrganizationDirectory = OrganizationDirectory(),
    public val auditLogger: AuditLogger = AuditLogger(),
    public val adminAuthService: AdminAuthService = AdminAuthService(printSetupTokenToConsole = true),
    public val challengeAuth: ChallengeResponseAuth? = null,
    public val enrollmentService: EnrollmentService? = null,
    public val revocationListProvider: (() -> RevocationList?)? = null,
    /** Overrides the relay handshake verifier; tests inject a fake, production derives it from [challengeAuth]. */
    public val relayAuthenticator: RelayAuthenticator? = null,
    /** Bearer tokens issued by `/api/v1/auth/verify` and accepted by the directory routes. */
    public val bearerTokens: BearerTokenStore = BearerTokenStore(),
    /** How long a relay connection may take to answer the challenge (ADR-109: 10 s). Tests shorten it. */
    public val relayHandshakeTimeoutMs: Long = HubContract.HANDSHAKE_TIMEOUT_MS,
    /** Relay limits (connection caps, rate limit, queue sizes, timeouts). */
    public val relayPolicy: RelayPolicy = RelayPolicy(handshakeTimeoutMs = relayHandshakeTimeoutMs),
    /** Hub-side revocation state; revoked devices are refused at the handshake, checked per frame and kicked live. */
    public val revocationService: RevocationService = RevocationService.None,
    /** Group membership for group META and group relay; defaults to the directory's group charters. */
    public val groupMembership: GroupMembershipView = DirectoryGroupMembership(directory),
    public val metrics: RelayMetrics = RelayMetrics(),
    /** Queue depth and age numbers for `/health/detail` and `/metrics`; defaults to the mailbox when it reports them. */
    public val mailboxStats: MailboxStats = (mailboxQueue as? MailboxStats) ?: MailboxStats.Empty,
    /** When set the engine probes it for `/health`, vacuums it after sweeps, and checkpoints and closes it on [stop]. */
    public val database: ServerDatabase? = null,
    /** Who may read `/health/detail` and `/metrics` (loopback without a proxy, or a bearer token). */
    public val managementGuard: ManagementGuard = DefaultManagementGuard(),
    /** Days until the delegation certificate expires (SEC-09 alarm), or null when unknown. */
    public val delegationDaysRemaining: (() -> Long?)? = null,
    /** Sweep period for expired mailbox rows. */
    public val sweepIntervalMs: Long = TtlSweeper.DEFAULT_SWEEP_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** TLS material for `tlsMode=self` (null: plain listeners, the proxy or the test host terminates TLS). */
    public val tls: TlsMaterial? = null,
    /** The `/api/v1` gate: bearer required on every route except enrol, renew, auth and hub info (SEC-03). */
    public val apiGate: HubApiGate? = null,
    /**
     * The hub's REST and admin routes, wired by the launcher (`HubServices`). When null the engine mounts the legacy
     * defaults below, which tests use.
     */
    public val hubRoutes: (Route.() -> Unit)? = null,
    /** Resolves the client address behind the configured trusted proxies; the relay's per-IP cap counts against it (REV-01). */
    public val clientAddresses: com.transfer.flash.server.auth.ClientAddressResolver = com.transfer.flash.server.auth.ClientAddressResolver(),
) {

    private val logger = LoggerFactory.getLogger(FlashServerEngine::class.java)
    private val serverStartTime: Long = clock()
    private val stopped = AtomicBoolean(false)
    private val sweeperScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    public val relayFrameRouter: RelayFrameRouter = RelayFrameRouter(
        connectionPool = connectionPool,
        mailboxQueue = mailboxQueue,
        directory = directory,
        revocationListProvider = revocationListProvider,
        authenticator = relayAuthenticator ?: challengeAuth?.let { CertChainRelayAuthenticator(it) },
        serverId = challengeAuth?.serverId ?: enrollmentService?.serverId ?: "",
        policy = relayPolicy,
        handshakeTimeoutMs = relayHandshakeTimeoutMs,
        groups = groupMembership,
        revocation = revocationService,
        metrics = metrics,
        clock = clock,
        clientAddressOf = clientAddresses::clientIp,
    )

    public val ttlSweeper: TtlSweeper? =
        if (mailboxQueue === MailboxStore.None) {
            null
        } else {
            TtlSweeper(
                store = mailboxQueue,
                sweepIntervalMs = sweepIntervalMs,
                coroutineScope = sweeperScope,
                onExpired = { rows -> rows.forEach { relayFrameRouter.notifyExpired(it.origin, it.target, it.msgId) } },
                onMaintenance = { database?.incrementalVacuum() },
            )
        }

    // The listeners come from the config: the public one and, unless the console is served on it, the management one (SEC-06).
    private val engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> = embeddedServer(
        factory = Netty,
        configure = {
            connectors.addAll(TlsSupport.connectors(config, tls))
        },
        module = {
            configureApplication(this)
        },
    )

    /**
     * Installs plugins and routes onto the Ktor [Application].
     * Can be invoked directly by tests via `testApplication`.
     */
    public fun configureApplication(app: Application) {
        // One canonical path for every security decision below (REV-02); before the gate.
        app.installRequestPathGuard()
        // Security headers on every response (the console's strict set, the baseline elsewhere), errors and the gate's own refusals
        // included (ADR-124, REV-02): installed before the gate so a 401/429 it sends carries them.
        app.install(AdminConsoleHeaders)
        apiGate?.let { app.installHubApiGate(it) }

        // 1. WebSockets Plugin configuration
        app.install(WebSockets) {
            pingPeriod = relayPolicy.pingPeriodMs.milliseconds
            timeout = relayPolicy.pongTimeoutMs.milliseconds
            maxFrameSize = HubContract.MAX_FRAME_BYTES.toLong()
            masking = false
        }

        // 2. StatusPages: a fixed sentence and a correlation id to the client, the real cause only in the log (SEC-16).
        app.install(StatusPages) {
            exception<Throwable> { call, cause ->
                val correlation = call.request.headers[HubContract.HEADER_CORRELATION_ID]?.let { LogSanitizer.clean(it) } ?: "-"
                logger.error(
                    "Unhandled server exception path={} correlationId={}",
                    LogSanitizer.clean(call.request.local.uri, 120),
                    correlation,
                    cause,
                )
                call.respondError(HttpStatusCode.InternalServerError, HubErrorCodes.ERR_INTERNAL, INTERNAL_ERROR_MESSAGE)
            }
        }

        // 3. Application Routing
        app.routing {
            // Root overview
            get("/") {
                call.respondText(
                    text = "Flash Enterprise Hub is running.",
                    contentType = ContentType.Text.Plain,
                )
            }

            // Public liveness: status only (SEC-16). 503 when the database does not answer so a load balancer stops sending traffic.
            get(HubContract.PATH_HEALTH) {
                val level = healthLevel()
                val status = if (level == HealthLevel.DOWN) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
                call.respondText(HubRestCodec.encode(HealthLite(status = level.wire)), ContentType.Application.Json, status)
            }

            // Operational detail: guarded, never public.
            get("${HubContract.PATH_HEALTH}/detail") {
                if (!managementGuard.allows(call)) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, FORBIDDEN_MESSAGE)
                    return@get
                }
                call.respondText(healthDetailJson(), ContentType.Application.Json)
            }

            // Prometheus metrics: guarded.
            get("/metrics") {
                if (!managementGuard.allows(call)) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, FORBIDDEN_MESSAGE)
                    return@get
                }
                call.respondText(
                    metrics.renderPrometheus(gauges()),
                    ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
                )
            }

            // WebSocket Relay route
            relayFrameRouter.registerRoute(this)

            val mount = hubRoutes
            if (mount != null) {
                mount()
                return@routing
            }

            // Legacy defaults (tests): admin dashboard, directory and auth routes with default limits.
            // There is no fabricated fingerprint any more: an invite carries the enrolment service's real server id.

            adminConsoleRoutes(AdminWeb(adminAuthService, externalUrl = config.externalUrl))

            // Organization Directory REST routes (/api/v1/directory/*)
            directoryRoutes(
                directory = directory,
                principalResolver = BearerPrincipalResolver(bearerTokens),
                isOnline = connectionPool::isConnected,
            )

            // Authentication & Device Enrolment routes (/api/v1/auth/*, /api/v1/enroll)
            val auth = challengeAuth
            val enroll = enrollmentService
            if (auth != null && enroll != null) {
                authRoutes(
                    challengeAuth = auth,
                    enrollmentService = enroll,
                    serverExternalUrl = config.externalUrl,
                    bearerTokens = bearerTokens,
                )
            }
        }
    }

    private enum class HealthLevel(val wire: String) { OK("ok"), DEGRADED("degraded"), DOWN("down") }

    private fun healthLevel(): HealthLevel {
        val probe = database?.probe() ?: return HealthLevel.OK
        return when {
            !probe.ok -> HealthLevel.DOWN
            probe.latencyMs > SLOW_DB_MS -> HealthLevel.DEGRADED
            else -> HealthLevel.OK
        }
    }

    private fun oldestAgeSeconds(): Long = (mailboxStats.oldestAgesMs(1).values.maxOrNull() ?: 0L) / 1000

    private fun gauges(): List<RelayMetrics.Gauge> {
        val probe = database?.probe()
        val used = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        val list = mutableListOf(
            RelayMetrics.Gauge("flash_hub_uptime_seconds", "Seconds since the engine was created.", (clock() - serverStartTime) / 1000),
            RelayMetrics.Gauge("flash_relay_sessions", "Authenticated relay sessions.", connectionPool.activeCount),
            RelayMetrics.Gauge("flash_relay_open_connections", "Open WebSockets (handshaking or established).", relayFrameRouter.openConnections),
            RelayMetrics.Gauge("flash_mailbox_queued_rows", "Messages waiting in the mailbox.", mailboxStats.queuedCount()),
            RelayMetrics.Gauge("flash_mailbox_queued_bytes", "Payload bytes waiting in the mailbox.", mailboxStats.queuedBytes()),
            RelayMetrics.Gauge("flash_mailbox_oldest_age_seconds", "Age of the oldest queued message.", oldestAgeSeconds()),
            RelayMetrics.Gauge("flash_mailbox_delivered_total", "Mailbox rows removed because the recipient acknowledged them.", mailboxStats.deliveredTotal()),
            RelayMetrics.Gauge("flash_mailbox_expired_total", "Mailbox rows removed by the TTL sweeper.", mailboxStats.expiredTotal()),
            RelayMetrics.Gauge("flash_mailbox_dropped_total", "Messages the mailbox refused or lost.", mailboxStats.droppedTotal()),
            RelayMetrics.Gauge("flash_jvm_memory_used_bytes", "JVM heap in use.", used),
        )
        if (probe != null) {
            list += RelayMetrics.Gauge("flash_db_up", "1 when the database answers.", if (probe.ok) 1 else 0)
            list += RelayMetrics.Gauge("flash_db_probe_latency_ms", "Database probe latency.", probe.latencyMs)
            probe.walPages?.let { list += RelayMetrics.Gauge("flash_db_wal_pages", "Pages in the WAL file.", it) }
        }
        ttlSweeper?.let { list += RelayMetrics.Gauge("flash_mailbox_sweep_failures_total", "Failed TTL sweeps.", it.failureCount) }
        delegationDaysRemaining?.invoke()?.let {
            list += RelayMetrics.Gauge("flash_delegation_days_remaining", "Days until the delegation certificate expires.", it)
        }
        return list
    }

    private fun healthDetailJson(): String {
        val probe = database?.probe()
        val rt = Runtime.getRuntime()
        val b = StringBuilder(512)
        b.append('{')
        b.append("\"status\":\"").append(healthLevel().wire).append('"')
        b.append(",\"uptimeSeconds\":").append((clock() - serverStartTime) / 1000)
        b.append(",\"sessions\":").append(connectionPool.activeCount)
        b.append(",\"openConnections\":").append(relayFrameRouter.openConnections)
        b.append(",\"mailbox\":{\"queuedRows\":").append(mailboxStats.queuedCount())
        b.append(",\"queuedBytes\":").append(mailboxStats.queuedBytes())
        b.append(",\"oldestAgeSeconds\":").append(oldestAgeSeconds())
        b.append(",\"deliveredTotal\":").append(mailboxStats.deliveredTotal())
        b.append(",\"expiredTotal\":").append(mailboxStats.expiredTotal())
        b.append(",\"droppedTotal\":").append(mailboxStats.droppedTotal())
        b.append('}')
        if (probe != null) {
            b.append(",\"database\":{\"ok\":").append(probe.ok)
            b.append(",\"latencyMs\":").append(probe.latencyMs)
            probe.walPages?.let { b.append(",\"walPages\":").append(it) }
            b.append('}')
        }
        delegationDaysRemaining?.invoke()?.let { b.append(",\"delegationDaysRemaining\":").append(it) }
        b.append(",\"memory\":{\"usedBytes\":").append(rt.totalMemory() - rt.freeMemory())
        b.append(",\"maxBytes\":").append(rt.maxMemory()).append('}')
        b.append('}')
        return b.toString()
    }

    /**
     * Every route the running server answers, as `METHOD /path` lines, sorted. Used by the contract test that keeps `docs/protocol-routes.txt`
     * (the checked-in list of the REST surface) in step with the code. Only valid after [start]: routes are installed when the engine starts.
     */
    internal fun routeTable(): List<String> {
        val root = engine.application.pluginOrNull(io.ktor.server.routing.RoutingRoot) ?: return emptyList()
        val out = sortedSetOf<String>()
        fun walk(route: io.ktor.server.routing.RoutingNode, path: String) {
            val selector = route.selector
            val here = when (selector) {
                is io.ktor.server.routing.HttpMethodRouteSelector -> path
                else -> path + selector.toString().let { if (it.isEmpty() || it == "/" || it == "<slash>" || it == "/<slash>" || it.startsWith("(")) "" else if (it.startsWith("/")) it else "/$it" }
            }
            if (selector is io.ktor.server.routing.HttpMethodRouteSelector) out += "${selector.method.value} ${path.ifEmpty { "/" }}"
            route.children.forEach { walk(it, here) }
        }
        walk(root, "")
        return out.toList()
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
        ttlSweeper?.start()
        try {
            engine.start(wait = wait)
        } catch (t: Throwable) {
            // A failed bind (port in use) must not leave the sweeper and the database pool running (REL-13).
            logger.error("Flash Enterprise Hub failed to start: {}", LogSanitizer.clean(t.message ?: t.javaClass.simpleName, 200))
            stop(0, 0)
            throw t
        }
    }

    /**
     * Initiates a graceful, idempotent shutdown: refuse new sockets, tell every live session `FLASH_KICK
     * server_shutdown` and close it (in parallel, bounded), stop the sweeper, stop Netty, checkpoint and close the database.
     */
    public fun stop(gracePeriodMillis: Long = 1000, timeoutMillis: Long = 5000) {
        if (!stopped.compareAndSet(false, true)) return
        logger.info("Initiating graceful shutdown for Flash Enterprise Hub...")
        ttlSweeper?.stop()
        try {
            runBlocking { relayFrameRouter.shutdown(relayPolicy.shutdownTimeoutMs) }
        } catch (t: Throwable) {
            logger.warn("Relay shutdown did not complete cleanly: {}", t.toString())
        }
        sweeperScope.cancel()
        try {
            engine.stop(gracePeriodMillis, timeoutMillis)
        } catch (t: Throwable) {
            logger.warn("Netty stop failed: {}", t.toString())
        }
        database?.close()
        logger.info("Flash Enterprise Hub server stopped successfully.")
    }

    private companion object {
        const val INTERNAL_ERROR_MESSAGE: String = "The hub could not complete the request."
        const val FORBIDDEN_MESSAGE: String = "Not allowed."
        const val SLOW_DB_MS: Long = 1_000L
    }
}
