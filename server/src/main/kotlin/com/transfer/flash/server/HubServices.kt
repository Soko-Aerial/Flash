package com.transfer.flash.server

import com.transfer.flash.core.hubprotocol.certs.HubRoles
import com.transfer.flash.core.hubprotocol.certs.OrgMemberStatement
import com.transfer.flash.server.admin.AdminApiDeps
import com.transfer.flash.server.admin.AdminAuthService
import com.transfer.flash.server.admin.AdminListenerPolicy
import com.transfer.flash.server.admin.AdminWeb
import com.transfer.flash.server.admin.AuditLogger
import com.transfer.flash.server.admin.AuditSigner
import com.transfer.flash.server.admin.StepUpAuth
import com.transfer.flash.server.admin.adminApiRoutes
import com.transfer.flash.server.admin.approvalRoutes
import com.transfer.flash.server.admin.adminConsoleRoutes
import com.transfer.flash.server.auth.BearerPrincipalResolver
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.ChallengeResponseAuth
import com.transfer.flash.server.auth.ClientAddressResolver
import com.transfer.flash.server.auth.DelegationLoad
import com.transfer.flash.server.auth.DelegationManager
import com.transfer.flash.server.auth.EnrollmentEvent
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.HubApiGate
import com.transfer.flash.server.auth.HubKeyStore
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.RevocationSigner
import com.transfer.flash.server.auth.SqliteRevocationService
import com.transfer.flash.server.auth.TrustedProxies
import com.transfer.flash.server.auth.authRoutes
import com.transfer.flash.server.auth.issue
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.config.TlsMaterial
import com.transfer.flash.server.config.TlsSupport
import com.transfer.flash.server.core.FlashServerEngine
import com.transfer.flash.server.directory.DepartmentMember
import com.transfer.flash.server.directory.MemberRole
import com.transfer.flash.server.directory.OrgMemberStatementSigner
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.directory.PublishedSealingKey
import com.transfer.flash.server.directory.SealingKeyLookup
import com.transfer.flash.server.directory.directoryRoutes
import com.transfer.flash.server.announcements.AnnouncementStore
import com.transfer.flash.server.announcements.announcementRoutes
import com.transfer.flash.server.relay.RelayFrameRouter
import com.transfer.flash.server.identity.DeviceStatus
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.identity.sealingKeyRoutes
import com.transfer.flash.server.mailbox.DatabaseDeviceRegistry
import com.transfer.flash.server.mailbox.MailboxLimits
import com.transfer.flash.server.mailbox.MailboxQueue
import com.transfer.flash.server.observability.RelayMetrics
import com.transfer.flash.server.relay.RelayConnectionPool
import com.transfer.flash.server.storage.ServerDatabase
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** A condition that makes the hub refuse to start (fail closed). The message is shown to the operator as is. */
class HubStartupException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * The address the first-run banner tells the operator to open. The console lives on the management listener (default
 * `127.0.0.1:8444`, plain http unless the hub terminates TLS itself), not on the public listener the invites point at, so the
 * banner must not reuse [ServerConfig.externalUrl] (it opened a 404 on the public port, or an https URL nothing answered).
 */
internal fun setupUrlFor(config: ServerConfig): String {
    if (config.adminOnPublicPort) return "${config.externalUrl.trimEnd('/')}/admin/setup"
    val scheme = if (config.directTls) "https" else "http"
    val bind = config.adminHost.removePrefix("[").removeSuffix("]")
    val host = when {
        bind == "0.0.0.0" || bind == "::" -> "localhost"
        bind.contains(':') -> "[$bind]"
        else -> bind
    }
    return "$scheme://$host:${config.adminPort}/admin/setup"
}

/**
 * Wires one running hub: persistence, keys, delegation, revocation, enrolment, directory, admin console and relay.
 *
 * The organisation ROOT key is never loaded here (SEC-04, ADR-111). The hub holds its own delegate key and a
 * `delegation.cert` signed offline by the root. Without a valid delegation it runs in AWAITING DELEGATION mode: the
 * admin console, `/health` and the operator hints work, but nothing can enrol, authenticate or relay. A delegation
 * that appears while the hub runs is validated and reported, but activating a first delegation needs a restart
 * (the enrolment and auth services are built from the root key the delegation carries).
 */
class HubServices private constructor(
    val config: ServerConfig,
    val engine: FlashServerEngine,
    val database: ServerDatabase,
    val delegations: DelegationManager,
    val identities: IdentityStore,
    val directory: OrganizationDirectory,
    val announcements: AnnouncementStore? = null,
    val admins: AdminAuthService,
    val audit: AuditLogger,
    val revocations: SqliteRevocationService?,
    val enrollment: EnrollmentService?,
    val challengeAuth: ChallengeResponseAuth?,
    val bearerTokens: BearerTokenStore,
    val limits: HubRateLimits,
    val tls: TlsMaterial?,
    private val scheduler: ScheduledExecutorService,
    private val delegationFile: File,
) {
    private val log = LoggerFactory.getLogger(HubServices::class.java)

    /** True while no valid delegation is in force at startup: nothing can enrol or authenticate. */
    val awaitingDelegation: Boolean get() = enrollment == null

    @Volatile
    private var lastDelegationMtime: Long = delegationFile.lastModified()

    fun start(wait: Boolean = true) {
        scheduler.scheduleWithFixedDelay({ housekeeping() }, HOUSEKEEPING_PERIOD_S, HOUSEKEEPING_PERIOD_S, TimeUnit.SECONDS)
        engine.start(wait)
    }

    fun stop() {
        scheduler.shutdownNow()
        engine.stop()
    }

    /** One maintenance tick. Public for tests; the scheduler calls it every minute. */
    fun housekeeping() {
        runCatching { limits.sweepAll() }.onFailure { log.warn("Rate limiter sweep failed: {}", it.toString()) }
        runCatching { admins.sweepSessions() }.onFailure { log.warn("Session sweep failed: {}", it.toString()) }
        runCatching { delegations.checkExpiryWarnings() }.onFailure { log.warn("Delegation expiry check failed: {}", it.toString()) }
        runCatching { watchDelegationFile() }.onFailure { log.warn("Delegation reload failed: {}", it.toString()) }
        runCatching {
            announcements?.let { store ->
                val retentionDays = admins.getSetting("announcementRetentionDays")?.toIntOrNull() ?: 0
                val purged = store.purgeExpiredAndOldTombstones(retentionDays = retentionDays)
                if (purged > 0) {
                    audit.logUnauthenticated("system", "announcement.purge", "purged $purged announcements/tombstones")
                }
            }
        }.onFailure { log.warn("Announcement purge failed: {}", it.toString()) }
    }

    private fun watchDelegationFile() {
        val mtime = delegationFile.lastModified()
        if (mtime == lastDelegationMtime) return
        lastDelegationMtime = mtime
        when (val result = delegations.reload()) {
            is DelegationLoad.Loaded -> {
                log.info("Delegation reloaded (serial {}, valid until {}).", result.cert.serial, result.cert.validTo)
                if (awaitingDelegation) log.warn("A first delegation was installed: restart the hub to activate enrolment and relay.")
            }
            is DelegationLoad.Rejected -> log.error("Delegation file changed but was REJECTED, keeping the previous one: {}", result.reason)
            DelegationLoad.Missing -> Unit
        }
    }

    companion object {
        private const val HOUSEKEEPING_PERIOD_S = 60L
        private const val ROOT_FINGERPRINT_META = "root.fingerprint"
        private const val HIGHEST_DELEGATION_SERIAL_META = "delegation.highest-serial"
        private val log = LoggerFactory.getLogger(HubServices::class.java)

        /**
         * Builds the hub for [config]. Throws [HubStartupException] when the layout is unsafe or a key file is corrupt.
         *
         * @param database inject a database for tests; production opens `<dataDir>/flash-hub.db`.
         */
        fun build(
            config: ServerConfig,
            clock: () -> Long = System::currentTimeMillis,
            database: ServerDatabase? = null,
            /** Test hook (ADR-124): the abuse limits; production always uses the defaults. */
            limits: HubRateLimits = HubRateLimits(),
            scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "flash-hub-housekeeping").apply { isDaemon = true }
            },
        ): HubServices {
            val dataDir = File(config.dataDir)
            Files.createDirectories(dataDir.toPath())
            val keyStore = HubKeyStore(dataDir.toPath())

            // 1. The root key must not live here (SEC-04). The old layout kept root-identity.pk8 in the data dir.
            keyStore.misplacedRootKey()?.let { misplaced ->
                if (!config.devEmbeddedRoot) {
                    throw HubStartupException(
                        "Refusing to start: the organisation ROOT private key is on this server ($misplaced). " +
                            "Move it OFFLINE (a copy in a vault, then delete it here), issue this hub a delegation with " +
                            "`keytool issue-delegation`, and start again. Local development only: --dev-embedded-root.",
                    )
                }
            }

            // 2. The hub's own delegate key. A corrupt or mismatched key file is fatal: never regenerate silently.
            val delegate = try {
                keyStore.loadOrCreateDelegateKey()
            } catch (e: Exception) {
                throw HubStartupException("Refusing to start: the hub key files in $dataDir are unusable (${e.message}). Restore them from backup; they are never regenerated over an existing file.", e)
            }

            val db = database ?: ServerDatabase("jdbc:sqlite:${File(dataDir, "flash-hub.db").absolutePath}")
            val identities = IdentityStore.open(db)

            // 3. The delegation, pinned to the root fingerprint (config first, then the first delegation ever accepted).
            val delegationFile = if (config.devEmbeddedRoot) keyStore.delegationFile.toFile() else config.delegationPath
            val delegations = DelegationManager(
                file = delegationFile.toPath(),
                delegatePublicKey = delegate.publicKey,
                rootPin = { config.rootFingerprint ?: identities.meta(ROOT_FINGERPRINT_META) },
                onRootAdopted = { identities.putMetaIfAbsent(ROOT_FINGERPRINT_META, it) },
                clock = clock,
                requireRootPin = config.requireRootPin,
                highestAcceptedSerial = { identities.meta(HIGHEST_DELEGATION_SERIAL_META)?.toLongOrNull() },
                onSerialAccepted = { serial ->
                    if (serial > (identities.meta(HIGHEST_DELEGATION_SERIAL_META)?.toLongOrNull() ?: 0L)) {
                        identities.putMeta(HIGHEST_DELEGATION_SERIAL_META, serial.toString())
                    }
                },
            )
            if (config.devEmbeddedRoot) keyStore.devEmbeddedRootDelegation(delegate, clock())
            val loaded = delegations.load()

            val pool = RelayConnectionPool()
            val metrics = RelayMetrics()
            val addresses = ClientAddressResolver(TrustedProxies(config.trustedProxies))
            val directory = OrganizationDirectory.open(db, pool, clock)
            val announcements = AnnouncementStore.open(db, clock)
            var routerRef: RelayFrameRouter? = null
            val audit = AuditLogger(db, AuditSigner(delegate.pair.private, delegate.publicKey), clock)
            val dataSecrets = com.transfer.flash.server.auth.SecretBox.loadOrCreate(File(dataDir, "data.key").toPath())
            val admins = AdminAuthService(
                printSetupTokenToConsole = true,
                setupUrl = setupUrlFor(config),
                db = db,
                clock = clock,
                setupTokenFile = config.setupTokenPath.toPath(),
                explicitSetupToken = config.setupToken,
                devPrintSetupToken = config.devEmbeddedRoot,
                secrets = dataSecrets,
            )
            admins.sealLegacySecrets().let { if (it > 0) log.info("Sealed {} administrator authenticator seed(s) that were stored in plain text", it) }
            val stepUp = StepUpAuth(admins, identities, clock = clock)
            val tls = TlsSupport.loadOrCreate(config)
            tls?.let { log.info("TLS certificate fingerprint (SHA-256, give it to clients that pin): {}", it.fingerprint) }
            val web = AdminWeb(
                admins = admins,
                addresses = addresses,
                directTls = config.directTls,
                externalUrl = config.externalUrl,
                policy = AdminListenerPolicy(config.port, config.adminPort, config.adminOnPublicPort),
            )
            val mailbox = MailboxQueue(
                database = db,
                limits = MailboxLimits.forTtlDays(config.mailboxTtlDays),
                clock = clock,
                registry = DatabaseDeviceRegistry(db),
                metrics = metrics,
                secrets = dataSecrets,
            )

            var revocations: SqliteRevocationService? = null
            var challengeAuth: ChallengeResponseAuth? = null
            var enrollment: EnrollmentService? = null
            val bearerTokens: BearerTokenStore

            val cert = (loaded as? DelegationLoad.Loaded)?.cert
            if (cert == null) {
                bearerTokens = BearerTokenStore()
                logAwaiting(loaded, delegate.publicKey, keyStore, delegations)
            } else {
                val rootPub = cert.rootPublicKey
                val revocationService = SqliteRevocationService(
                    db = db,
                    rootPublicKey = rootPub,
                    signer = RevocationSigner(delegate.pair.private, delegate.publicKey) { delegations.current },
                    clock = clock,
                )
                revocations = revocationService
                bearerTokens = BearerTokenStore(revocations = revocationService)
                challengeAuth = ChallengeResponseAuth(
                    rootPublicKey = rootPub,
                    revocationListProvider = { revocationService.current() },
                    revocations = revocationService,
                )
                enrollment = EnrollmentService(
                    rootPublicKey = rootPub,
                    serverPrivateKey = delegate.pair.private,
                    delegationCert = cert,
                    initialQuota = config.deviceQuota,
                    store = identities,
                    delegations = delegations,
                    revocations = revocationService,
                    requireHttpsInvites = !config.allowInsecure,
                    onEnrolled = { event -> applyToDirectory(directory, event) },
                )
                installDroppedRevocationLists(File(dataDir, "revocations"), revocationService)
                if (revocationService.isDelegationRevoked(cert.serial)) {
                    log.error("This hub's delegation (serial {}) was REVOKED by the organisation root: it will issue no certificate until a new delegation is installed.", cert.serial)
                }
                revocationService.onRevoked { deviceId, _, _ ->
                    bearerTokens.revokeDevice(deviceId)
                    directory.removeDevice(deviceId)
                }
                reconcileDirectory(identities, directory)
                directory.statementSigner = OrgMemberStatementSigner { deviceId, subjectPublicKey, departmentId, role, nowMs, serial ->
                    val d = delegations.current ?: return@OrgMemberStatementSigner null
                    val statement = OrgMemberStatement.issue(
                        issuerPrivateKey = delegate.pair.private,
                        deviceId = deviceId,
                        subjectPublicKey = subjectPublicKey,
                        departmentId = departmentId,
                        role = role,
                        issuedAtMs = nowMs,
                        statementSerial = serial,
                    )
                    statement to d.serial
                }
                directory.ensureStatementsSigned()
                log.info("Delegation active: serial {}, {} day(s) left, root fingerprint {}", cert.serial, delegations.daysLeft(), DelegationManager.rootFingerprint(cert))
            }

            val resolver = BearerPrincipalResolver(bearerTokens)
            val apiGate = HubApiGate(resolver, limits, addresses)
            val authService = challengeAuth
            val enrollService = enrollment
            val revService = revocations
            val engine = FlashServerEngine(
                config = config,
                connectionPool = pool,
                mailboxQueue = mailbox,
                directory = directory,
                auditLogger = audit,
                adminAuthService = admins,
                challengeAuth = authService,
                enrollmentService = enrollService,
                revocationListProvider = revService?.let { { it.current() } },
                bearerTokens = bearerTokens,
                revocationService = revService ?: com.transfer.flash.server.auth.RevocationService.None,
                metrics = metrics,
                mailboxStats = mailbox,
                database = db,
                delegationDaysRemaining = { delegations.daysLeft() },
                managementGuard = com.transfer.flash.server.observability.DefaultManagementGuard(token = config.managementToken),
                clock = clock,
                tls = tls,
                apiGate = apiGate,
                clientAddresses = addresses,
                hubRoutes = {
                    adminConsoleRoutes(web)
                    adminApiRoutes(
                        AdminApiDeps(
                            web = web,
                            admins = admins,
                            audit = audit,
                            directory = directory,
                            identities = identities,
                            stepUp = stepUp,
                            config = config,
                            enrollment = enrollService,
                            revocations = revService,
                            bearerTokens = bearerTokens,
                            delegations = delegations,
                            mailboxStats = mailbox,
                            limits = limits,
                            clock = clock,
                            metrics = metrics,
                            hubPublicKey = delegate.publicKey,
                            announcements = announcements,
                            router = routerRef,
                        ),
                    )
                    announcementRoutes(
                        store = announcements,
                        principalResolver = resolver,
                        router = routerRef,
                        audit = audit,
                        limits = limits,
                        clock = clock,
                    )
                    directoryRoutes(
                        directory = directory,
                        principalResolver = resolver,
                        isOnline = pool::isOnline,
                        audit = audit,
                        sealingKeys = SealingKeyLookup { ids ->
                            identities.sealingKeys(ids).mapValues { PublishedSealingKey(it.value.sealingPublicKey, it.value.sealingSignature) }
                        },
                        limits = limits,
                        delegationChain = { delegations.current?.let { listOf(it) } ?: emptyList() },
                        offlineGraceDays = { admins.getSetting("offlineGraceDays")?.toIntOrNull() ?: 30 },
                    )
                    sealingKeyRoutes(
                        identities = identities,
                        principalResolver = resolver,
                        limits = limits,
                        audit = audit,
                        clock = clock,
                    )
                    approvalRoutes(stepUp = stepUp, principalResolver = resolver, limits = limits, audit = audit)
                    if (authService != null && enrollService != null) {
                        authRoutes(
                            challengeAuth = authService,
                            enrollmentService = enrollService,
                            serverExternalUrl = config.externalUrl,
                            bearerTokens = bearerTokens,
                            revocations = revService ?: com.transfer.flash.server.auth.RevocationService.None,
                            directory = directory,
                            audit = audit,
                            limits = limits,
                            addresses = addresses,
                            hubName = config.hubName,
                            clock = clock,
                            principalResolver = resolver,
                        )
                    }
                },
            )
            routerRef = engine.relayFrameRouter

            return HubServices(
                config, engine, db, delegations, identities, directory, announcements, admins, audit, revocations, enrollment,
                challengeAuth, bearerTokens, limits, tls, scheduler, delegationFile,
            )
        }

        private fun logAwaiting(loaded: DelegationLoad, delegatePublicKey: String, keyStore: HubKeyStore, delegations: DelegationManager) {
            val status = delegations.status
            log.warn("##############################################################################")
            log.warn("# AWAITING DELEGATION: this hub has no valid delegation, so nothing can enrol,")
            log.warn("# authenticate or relay. The admin console and /health still work.")
            if (loaded is DelegationLoad.Rejected) log.error("# The delegation file was REJECTED: {} (status {})", loaded.reason, status)
            log.warn("# Hub public key (give it to the machine that holds the offline root key):")
            log.warn("#   {}", delegatePublicKey)
            log.warn("# On that machine: {}", keyStore.issueDelegationHint(delegatePublicKey))
            log.warn("# Then restart the hub.")
            log.warn("##############################################################################")
        }

        /** The directory learns a member from an enrolment event (Plan A1 §3.8: a head device joins its department's admin list). */
        internal fun applyToDirectory(directory: OrganizationDirectory, event: EnrollmentEvent) {
            val device = event.device
            directory.applyEnrollment(
                DepartmentMember(
                    deviceId = device.deviceId,
                    departmentId = device.department.orEmpty(),
                    displayName = device.name,
                    role = roleOf(device.role),
                    subjectPublicKey = device.subjectPublicKey,
                ),
                autoJoinGroups = event.invite.autoJoinGroups,
                allowedGroups = event.invite.allowedGroups,
            )
            if (device.role == HubRoles.DEPT_ADMIN) {
                directory.addDepartmentAdmin(device.department.orEmpty(), device.deviceId)
            }
        }

        private fun roleOf(role: String): MemberRole = when (role) {
            HubRoles.ADMIN -> MemberRole.ORG_ADMIN
            HubRoles.DEPT_ADMIN -> MemberRole.DEPT_ADMIN
            else -> MemberRole.MEMBER
        }

        /**
         * Installs the root-signed revocation lists an operator dropped in [dir] (`keytool revoke` output), oldest name first. An
         * accepted file becomes `<name>.installed`, a refused one `<name>.rejected`; an unreadable file is left alone and logged.
         * Returns how many were installed (REV-04).
         */
        internal fun installDroppedRevocationLists(dir: File, revocations: SqliteRevocationService): Int {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".signed") }?.sortedBy { it.name } ?: return 0
            var installed = 0
            for (file in files) {
                val list = try {
                    com.transfer.flash.core.hubprotocol.rest.HubRestCodec.decodeOrNull<com.transfer.flash.core.hubprotocol.certs.RevocationList>(file.readText(), 16 * 1024 * 1024)
                } catch (e: java.io.IOException) {
                    log.warn("Could not read the revocation list {}: {}", file.name, e.message)
                    continue
                }
                val ok = list != null && revocations.installRootSigned(list)
                file.renameTo(File(file.path.removeSuffix(".signed") + if (ok) ".installed" else ".rejected"))
                if (ok) {
                    installed++
                    log.warn("Installed the root-signed revocation list {} (version {}, delegations revoked: {})", file.name, list!!.version, list.revokedDelegationSerials.sorted())
                } else {
                    log.warn("Refused the revocation list {}: not signed by the organisation root, not higher than the current version, or not a list", file.name)
                }
            }
            return installed
        }

        /** After a restart the directory must contain every ACTIVE device (a crash between the two writes must not lose one). */
        internal fun reconcileDirectory(identities: IdentityStore, directory: OrganizationDirectory) {
            var offset = 0
            while (true) {
                val page = identities.listDevices(status = DeviceStatus.ACTIVE, limit = 500, offset = offset)
                if (page.isEmpty()) break
                for (device in page) {
                    if (directory.getDepartmentMember(device.deviceId) != null) continue
                    directory.applyEnrollment(
                        DepartmentMember(
                            deviceId = device.deviceId,
                            departmentId = device.department.orEmpty(),
                            displayName = device.name,
                            role = roleOf(device.role),
                            subjectPublicKey = device.subjectPublicKey,
                        ),
                        autoJoinGroups = emptySet(),
                        allowedGroups = emptySet(),
                    )
                    if (device.role == HubRoles.DEPT_ADMIN) {
                        directory.addDepartmentAdmin(device.department.orEmpty(), device.deviceId)
                    }
                }
                if (page.size < 500) break
                offset += page.size
            }
        }
    }
}
