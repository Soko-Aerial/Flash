package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.DelegationCert
import com.transfer.flash.core.hubprotocol.certs.HubRoles
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.server.auth.BearerTokenStore
import com.transfer.flash.server.auth.DelegationLoad
import com.transfer.flash.server.auth.DelegationManager
import com.transfer.flash.server.auth.DelegationStatus
import com.transfer.flash.server.auth.EnrollmentService
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.InviteScope
import com.transfer.flash.server.auth.SqliteRevocationService
import com.transfer.flash.server.auth.receiveBoundedText
import com.transfer.flash.server.auth.respondError
import com.transfer.flash.server.auth.respondJson
import com.transfer.flash.server.config.ServerConfig
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.DirectoryConflictException
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.identity.DeviceRecord
import com.transfer.flash.server.identity.DeviceStatus
import com.transfer.flash.server.identity.IdentityStore
import com.transfer.flash.server.identity.InviteRecord
import com.transfer.flash.server.identity.InviteState
import com.transfer.flash.server.observability.MailboxStats
import com.transfer.flash.server.observability.RelayMetrics
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.Base64

/**
 * Everything the console API needs. Constructed once by the hub wiring; tests build it from in-memory parts.
 *
 * [revocations] and [enrollment] may be null on a hub that is still awaiting its delegation: the routes then answer
 * with a fixed "not available yet" sentence instead of failing.
 */
class AdminApiDeps(
    val web: AdminWeb,
    val admins: AdminAuthService,
    val audit: AuditLogger,
    val directory: OrganizationDirectory,
    val identities: IdentityStore,
    val stepUp: StepUpAuth,
    val config: ServerConfig,
    val enrollment: EnrollmentService? = null,
    val revocations: SqliteRevocationService? = null,
    val bearerTokens: BearerTokenStore? = null,
    val delegations: DelegationManager? = null,
    val mailboxStats: MailboxStats = MailboxStats.Empty,
    val limits: HubRateLimits = HubRateLimits(),
    val clock: () -> Long = System::currentTimeMillis,
    /** Extra work after a device is revoked (the relay already kicks through `RevocationService.onRevoked`). */
    val onDeviceRevoked: (String) -> Unit = {},
    /** Relay counters for `GET /metrics`; null on a hub without them (the page then shows what it has). */
    val metrics: RelayMetrics? = null,
    /** The hub's own public key (base64 SPKI) so the console can print the `keytool issue-delegation` command while awaiting delegation. */
    val hubPublicKey: String = "",
    val startedAtMs: Long = System.currentTimeMillis(),
    val announcements: com.transfer.flash.server.announcements.AnnouncementStore? = null,
    /** The sealed file store (ADR-215); null on a hub built without one (tests that do not need it). */
    val files: com.transfer.flash.server.files.FileStore? = null,
    val router: com.transfer.flash.server.relay.RelayFrameRouter? = null,
)

internal const val ERR_STEP_UP = "ERR_STEP_UP"
internal const val ERR_CSRF = "ERR_CSRF"
internal const val MSG_STEP_UP = "Confirm this action on your admin device first."
internal const val MSG_CSRF = "The request did not come from the console."
internal const val MSG_NOT_AVAILABLE = "This is not available until the hub has its delegation."
internal const val MSG_BAD_REQUEST = "The request is not valid."
internal const val MSG_NOT_FOUND = "Not found."
internal const val MSG_CONFLICT = "That conflicts with the current state."
internal const val MSG_FORBIDDEN = "You are not allowed to do that."

private val DAY_MS = 24L * 60 * 60 * 1000

/**
 * The administration JSON API, `/admin/api/v1/...`. Every route except setup status/begin/complete and login needs a
 * valid session cookie; every state-changing route also needs the session's `X-CSRF-Token` and a same-origin
 * `Origin` / `Sec-Fetch-Site` (SEC-13); the dangerous ones also need a step-up proof (`X-Step-Up`, SEC-12).
 */
fun Route.adminApiRoutes(d: AdminApiDeps) {
    route("/admin/api/v1") {
        authRoutes(d)
        overviewRoutes(d)
        deviceRoutes(d)
        inviteRoutes(d)
        revocationRoutes(d)
        delegationRoutes(d)
        settingsRoutes(d)
        stepUpRoutes(d)
        metricsRoutes(d)
        adminDirectoryRoutes(d)
        adminAccountRoutes(d)
        auditRoutes(d)
        announcementAdminRoutes(d)
        fileAdminRoutes(d)
    }
}

// ---------------------------------------------------------------------------------------------------------- guard

/** Result of the common admission checks. */
internal suspend fun ApplicationCall.adminSession(d: AdminApiDeps, requireSession: Boolean = true): AdminSession? {
    if (!d.web.listenerAllowed(this)) {
        respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, MSG_NOT_FOUND)
        return null
    }
    val session = if (requireSession) d.web.session(this) else null
    if (requireSession && session == null) {
        respondError(HttpStatusCode.Unauthorized, HubErrorCodes.ERR_AUTH, "Sign in to continue.")
        return null
    }
    val verdict = d.web.csrfVerdict(this, session, requireToken = requireSession)
    if (verdict != CsrfGuard.Verdict.OK) {
        respondError(HttpStatusCode.Forbidden, ERR_CSRF, MSG_CSRF)
        return null
    }
    if (session != null && !d.limits.principal.tryAcquire("admin:${session.username}")) {
        response.header("Retry-After", "5")
        respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many requests. Try again later.")
        return null
    }
    return session
}

/**
 * Admission for the routes that run BEFORE a session exists (setup, login): the listener policy and the Origin /
 * Fetch-Metadata check (a cross-site page must not be able to post a login or a setup). Responds and returns false when
 * refused.
 */
internal suspend fun ApplicationCall.preAuth(d: AdminApiDeps): Boolean {
    if (!d.web.listenerAllowed(this)) {
        notFound()
        return false
    }
    if (d.web.csrfVerdict(this, null, requireToken = false) != CsrfGuard.Verdict.OK) {
        respondError(HttpStatusCode.Forbidden, ERR_CSRF, MSG_CSRF)
        return false
    }
    return true
}

/** Verifies the step-up header for [action] on [target]; responds 403 [ERR_STEP_UP] and returns false when missing. */
internal suspend fun ApplicationCall.stepUpOk(d: AdminApiDeps, session: AdminSession, action: String, target: String): Boolean {
    val id = request.headers["X-Step-Up"]
    if (id.isNullOrBlank() || !d.stepUp.authorize(id, session.username, action, target)) {
        d.audit.logUnauthenticated(session.username, "stepup.refused", "action=$action target=$target")
        respondError(HttpStatusCode.Forbidden, ERR_STEP_UP, MSG_STEP_UP)
        return false
    }
    d.audit.log(session.username, "stepup.accepted", "action=$action target=$target")
    return true
}

internal suspend inline fun <reified T> ApplicationCall.body(): T? {
    val text = receiveBoundedText() ?: return null
    val value = HubRestCodec.decodeOrNull<T>(text)
    if (value == null) respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MSG_BAD_REQUEST)
    return value
}

internal suspend fun ApplicationCall.badRequest() =
    respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, MSG_BAD_REQUEST)

internal suspend fun ApplicationCall.notFound() =
    respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, MSG_NOT_FOUND)

internal suspend fun ApplicationCall.notAvailable() =
    respondError(HttpStatusCode.ServiceUnavailable, HubErrorCodes.ERR_INTERNAL, MSG_NOT_AVAILABLE)

/** Maps the directory's and the services' failures to fixed sentences (SEC-16: never exception text). */
internal suspend fun ApplicationCall.guarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: com.transfer.flash.server.directory.DirectoryNotFoundException) {
        notFound()
    } catch (_: com.transfer.flash.server.directory.DirectoryConflictException) {
        respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, MSG_CONFLICT)
    } catch (_: SecurityException) {
        respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, MSG_FORBIDDEN)
    } catch (_: IllegalStateException) {
        respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, MSG_CONFLICT)
    } catch (_: IllegalArgumentException) {
        badRequest()
    }
}

private fun String?.toIntOr(default: Int, range: IntRange): Int = (this?.toIntOrNull() ?: default).coerceIn(range)

// ---------------------------------------------------------------------------------------------------------- auth

private fun Route.authRoutes(d: AdminApiDeps) {
    get("/setup/status") {
        if (!d.web.listenerAllowed(call)) return@get call.notFound()
        // REV-07: nothing but "can the setup still be done" before sign-in. Whether administrators exist and the delegation state
        // are not for an anonymous caller (hasAdmins stays in the DTO, always false, for older consoles).
        call.respondJson(SetupStatusDto(setupOpen = d.admins.isSetupOpen(), hasAdmins = false))
    }

    post("/setup/begin") {
        if (!call.preAuth(d)) return@post
        if (!d.limits.adminLogin.tryAcquire(d.web.clientKey(call))) return@post call.tooMany()
        if (!d.admins.isSetupOpen()) return@post respondConflict(call)
        val b = call.body<SetupBeginBody>() ?: return@post
        try {
            val begin = d.admins.beginSetup(b.token)
            call.respondJson(SetupBeginDto(begin.setupId, begin.totpSecret, begin.otpAuthUri, QrCodeGenerator.generateSvg(begin.otpAuthUri)))
        } catch (_: SecurityException) {
            d.audit.logUnauthenticated("setup", "admin.setup.refused", "invalid or locked setup token (begin) from ${d.web.clientIp(call)}")
            call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "The setup token is not valid, or setup is locked for a while.")
        } catch (_: IllegalStateException) {
            call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, "Setup is busy or already complete. Try again in a few minutes.")
        }
    }

    post("/setup/complete") {
        if (!call.preAuth(d)) return@post
        if (!d.limits.adminLogin.tryAcquire(d.web.clientKey(call))) return@post call.tooMany()
        val b = call.body<SetupCompleteBody>() ?: return@post
        // The proof of the browser key is checked BEFORE the one-shot setup token is consumed: a bad key must not burn the setup.
        val key = b.consoleKey
        if (key != null && !validConsoleKeyProof(b.username.trim(), key)) return@post call.badRequest()
        try {
            val admin = d.admins.completeSetup(b.setupId, b.token, b.username, b.password, b.confirmCode)
            val codes = d.admins.generateRecoveryCodes(admin.username)
            val keyId = key?.let { d.admins.registerConsoleKey(admin.username, it.publicKey, it.label) }
            d.audit.log(admin.username, "admin.setup", "first administrator created through setup from ${d.web.clientIp(call)}")
            if (keyId != null) d.audit.log(admin.username, "admin.consolekey.enrol", "key=$keyId (during setup)")
            call.respondJson(SetupCompleteDto(admin.username, codes), HttpStatusCode.Created)
        } catch (_: SecurityException) {
            d.audit.logUnauthenticated("setup", "admin.setup.refused", "invalid or locked setup token from ${d.web.clientIp(call)}")
            call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "The setup token is not valid, or setup is locked for a while.")
        } catch (_: IllegalStateException) {
            call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, "Setup is already complete or has expired. Start again.")
        } catch (_: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Check the username, the password (10 characters or more) and the 6-digit code.")
        }
    }

    post("/login") {
        if (!call.preAuth(d)) return@post
        val ip = d.web.clientIp(call)
        val ipKey = d.web.clientKey(call)
        if (!d.limits.adminLogin.tryAcquire(ipKey)) return@post call.tooMany()
        val b = call.body<LoginBody>() ?: return@post
        when (val r = d.admins.authenticate(b.username, b.password, b.code, ipKey)) {
            is AuthResult.Success -> {
                d.web.setSessionCookie(call, r.session)
                d.audit.log(r.session.username, "admin.login", "from $ip")
                if (r.recoveryCodeUsed) d.audit.log(r.session.username, "admin.login.recovery", "a recovery code was used; ${d.admins.recoveryCodesRemaining(r.session.username)} left")
                call.respondJson(
                    LoginDto(r.session.username, r.session.role, r.session.csrfToken, r.session.expiresAtMs, r.recoveryCodeUsed, d.admins.recoveryCodesRemaining(r.session.username)),
                )
            }
            is AuthResult.Locked -> {
                d.audit.logUnauthenticated(b.username.take(64), "admin.login.locked", "locked for ${r.remainingSeconds}s from $ip")
                call.response.header("Retry-After", r.remainingSeconds.coerceAtLeast(1).toString())
                call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many failed attempts. Try again later.")
            }
            is AuthResult.InvalidCredentials -> {
                d.audit.logUnauthenticated(b.username.take(64), "admin.login.failed", "from $ip")
                call.respondError(HttpStatusCode.Unauthorized, HubErrorCodes.ERR_AUTH, "The username, password or code is not right.")
            }
            is AuthResult.SetupRequired -> call.respondError(HttpStatusCode.Conflict, "ERR_SETUP_REQUIRED", "Finish the first-run setup first.")
        }
    }

    post("/logout") {
        val session = call.adminSession(d) ?: return@post
        d.admins.invalidateSession(session.token)
        d.web.clearSessionCookie(call)
        d.audit.log(session.username, "admin.logout", "")
        call.respondJson(OkDto())
    }

    get("/session") {
        val session = call.adminSession(d) ?: return@get
        val admin = d.admins.getAdmin(session.username)
        val scope = d.scopeOf(session)
        call.respondJson(
            SessionDto(
                session.username, session.role, session.csrfToken, session.expiresAtMs, d.stepUp.hasUsableDevice(session.username),
                admin?.enrolledDeviceId, session.sessionId, d.clock(), admin?.approverDeviceId,
                admin?.displayName.orEmpty(),
                when (scope) {
                    ConsoleScope.Org -> emptyList()
                    is ConsoleScope.Departments -> scope.ids.sorted().map { id -> DepartmentRefDto(id, d.directory.getDepartment(id)?.name.orEmpty()) }
                },
            ),
        )
    }
}

/** True when [key] is a P-256 public key and [ConsoleKeys.proofMessage] for [username] is signed by it. */
internal fun validConsoleKeyProof(username: String, key: ConsoleKeyProof): Boolean =
    ConsoleKeys.isValidKey(key.publicKey) &&
        ConsoleKeys.verifyBase64(key.publicKey, ConsoleKeys.proofMessage(username, key.publicKey), key.proof)

private suspend fun ApplicationCall.tooMany() {
    response.header("Retry-After", "30")
    respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many requests. Try again later.")
}

private suspend fun respondConflict(call: ApplicationCall) =
    call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, "Setup is already complete.")

// ---------------------------------------------------------------------------------------------------------- overview

private fun delegationStatusName(d: AdminApiDeps): String =
    d.delegations?.status?.name ?: if (d.enrollment != null) DelegationStatus.ACTIVE.name else DelegationStatus.AWAITING.name

internal fun delegationDto(d: AdminApiDeps): DelegationDto {
    val now = d.clock()
    val manager = d.delegations
    val cert: DelegationCert? = manager?.current ?: d.enrollment?.delegationCert
    val status = delegationStatusName(d)
    val days = manager?.daysLeft() ?: cert?.let { Math.floorDiv(it.validTo - now, DAY_MS) }
    val warning = when {
        days == null -> null
        days < 0 -> "The delegation expired ${-days} day(s) ago. Enrolment and renewal are refused."
        days < 3 -> "The delegation expires in $days day(s)."
        days < 14 -> "The delegation expires in $days day(s)."
        else -> null
    }
    return DelegationDto(
        status = status,
        serial = cert?.serial,
        validFrom = cert?.validFrom,
        validTo = cert?.validTo,
        daysLeft = days,
        maxDevices = cert?.maxDevices,
        scopes = cert?.scopes?.sorted().orEmpty(),
        rootFingerprint = cert?.let { DelegationManager.rootFingerprint(it) },
        delegatePublicKey = cert?.delegatePublicKey,
        error = manager?.lastError,
        warning = warning,
        hubPublicKey = d.hubPublicKey.ifBlank { null },
    )
}

private fun Route.overviewRoutes(d: AdminApiDeps) {
    get("/overview") {
        val session = call.adminSession(d) ?: return@get
        val scope = d.scopeOf(session)
        val now = d.clock()
        val inScopeDept: (String?) -> Boolean = { dept -> scope.allows(dept) || (scope is ConsoleScope.Org) }
        val invites = d.identities.listInvites(1000).filter { inScopeDept(it.department) }.groupingBy { it.state(now) }.eachCount()
        val groups = d.directory.listGroups().filter { g ->
            when (scope) {
                ConsoleScope.Org -> true
                is ConsoleScope.Departments -> g.departmentId.isNotBlank() && g.departmentId in scope.ids
            }
        }
        val rev = d.revocations?.status()
        val head = d.audit.head()
        val scopedDepartments = when (scope) {
            ConsoleScope.Org -> d.directory.listDepartments().size
            is ConsoleScope.Departments -> scope.ids.size
        }
        // Counted without a row cap (review F5): the department filter runs in SQL, not over the first 500 rows.
        val activeIdsInScope: List<String> = when (scope) {
            ConsoleScope.Org -> d.identities.activeDeviceIds()
            is ConsoleScope.Departments -> scope.ids.flatMap { d.identities.activeDeviceIds(it) }.distinct()
        }
        call.respondJson(
            OverviewDto(
                hubName = d.config.hubName,
                serverId = d.enrollment?.serverId.orEmpty(),
                devicesByStatus = if (scope is ConsoleScope.Org) d.identities.countByStatus().mapKeys { it.key.name } else mapOf(DeviceStatus.ACTIVE.name to activeIdsInScope.size.toLong()),
                onlineDevices = activeIdsInScope.count { d.directory.isOnline(it) },
                invites = InviteCountsDto(
                    invites[InviteState.PENDING] ?: 0, invites[InviteState.USED] ?: 0,
                    invites[InviteState.EXPIRED] ?: 0, invites[InviteState.REVOKED] ?: 0,
                ),
                departments = scopedDepartments,
                groups = groups.size,
                pendingJoinRequests = groups.sumOf { d.directory.pendingCount(it.id) },
                queueDepth = if (scope is ConsoleScope.Org) d.mailboxStats.queuedCount() else 0L,
                queueBytes = if (scope is ConsoleScope.Org) d.mailboxStats.queuedBytes() else 0L,
                delegation = delegationDto(d),
                revocations = if (scope is ConsoleScope.Org) rev?.let { RevocationStatusDto(it.listVersion, it.revokedSerials, it.revokedDeviceIds, it.unpublishedChanges, it.canSign, it.revokedDelegations.sorted()) } else null,
                audit = if (scope is ConsoleScope.Org) AuditHeadDto(head?.first, head?.second, d.audit.size()) else AuditHeadDto(),
                deviceQuota = if (scope is ConsoleScope.Org) d.config.deviceQuota else 0,
            ),
        )
    }
}

// ---------------------------------------------------------------------------------------------------------- devices

internal fun DeviceRecord.toDto(online: Boolean) = DeviceDto(
    deviceId, name, department, role, status.name, serial, enrolledAt, certExpiry, lastSeenAt, online, subjectPublicKey, inviteId,
)

private fun Route.deviceRoutes(d: AdminApiDeps) {
    route("/devices") {
        get {
            val session = call.adminSession(d) ?: return@get
            val scope = d.scopeOf(session)
            val q = call.request.queryParameters
            val status = q["status"]?.let { s -> DeviceStatus.entries.firstOrNull { it.name.equals(s, true) } ?: return@get call.badRequest() }
            // A list endpoint filters; it never returns forbidden for a partly allowed list.
            val askedDept = q["department"]
            if (askedDept != null && scope !is ConsoleScope.Org && !scope.allows(askedDept)) {
                return@get call.respondJson(emptyList<DeviceDto>())
            }
            val effectiveDept = when (scope) {
                ConsoleScope.Org -> askedDept
                is ConsoleScope.Departments -> askedDept ?: if (scope.ids.size == 1) scope.ids.single() else null
            }
            val list = d.identities.listDevices(status, effectiveDept, q["search"], q["limit"].toIntOr(100, 1..500), q["offset"].toIntOr(0, 0..1_000_000))
            val scoped = when (scope) {
                ConsoleScope.Org -> list
                is ConsoleScope.Departments -> list.filter { scope.allows(it.department) }
            }
            call.respondJson(scoped.map { it.toDto(d.directory.isOnline(it.deviceId)) })
        }

        get("/{id}") {
            val session = call.adminSession(d) ?: return@get
            val scope = d.scopeOf(session)
            val device = d.identities.device(call.parameters["id"].orEmpty())
            // Cross-scope object ids answer exactly like a non-existent id (no existence leak).
            if (device == null || (scope !is ConsoleScope.Org && !scope.allows(device.department))) return@get call.notFound()
            call.respondJson(device.toDto(d.directory.isOnline(device.deviceId)))
        }

        put("/{id}") {
            val session = call.adminSession(d) ?: return@put
            val scope = d.scopeOf(session)
            val id = call.parameters["id"].orEmpty()
            val b = call.body<DeviceUpdateBody>() ?: return@put
            call.guarded {
                val device = d.identities.device(id) ?: return@guarded call.notFound()
                if (scope !is ConsoleScope.Org && !scope.allows(device.department)) return@guarded call.notFound()
                val changes = ArrayList<String>()
                if (b.name != null) {
                    require(b.name.isNotBlank() && b.name.length <= IdentityStore.MAX_NAME)
                    d.identities.rename(id, b.name.trim())
                    changes += "name"
                }
                if (b.moveToDepartment) {
                    // Cross-department moves are org-admin work; a head may only rename.
                    if (scope !is ConsoleScope.Org) {
                        return@guarded call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "Only an organisation administrator can move a device.")
                    }
                    val target = b.department?.trim()?.ifEmpty { null }
                    if (target != null && d.directory.getDepartment(target) == null) throw com.transfer.flash.server.directory.DirectoryNotFoundException("department")
                    val previous = d.directory.getDepartmentMember(id)?.departmentId
                    val registryBefore = device.department.orEmpty()
                    d.identities.setDepartment(id, target)
                    d.directory.getDepartmentMember(id)?.let { d.directory.registerMember(it.copy(departmentId = target.orEmpty())) }
                    if (previous != null && previous != target.orEmpty()) {
                        d.directory.removeFromAdmins(previous, id)
                    }
                    changes += "department=${target.orEmpty()}"
                    if (registryBefore != target.orEmpty()) {
                        // The principal now comes from the registry (DEV-01): drop the old scope everywhere it is cached
                        // so the device's next handshake carries the new department. The device reconnects by itself.
                        d.bearerTokens?.revokeDevice(id)
                        d.router?.kick(id, "department_changed")
                    }
                }
                d.audit.log(session.username, "device.update", "device=${device.deviceId} ${changes.joinToString(" ")}")
                call.respondJson(d.identities.device(id)!!.toDto(d.directory.isOnline(id)))
            }
        }

        post("/{id}/revoke") {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val id = call.parameters["id"].orEmpty()
            val b = call.body<RevokeBody>() ?: return@post
            val revocations = d.revocations ?: return@post call.notAvailable()
            if (d.identities.device(id) == null) return@post call.notFound()
            if (!call.stepUpOk(d, session, StepUpActions.REVOKE_DEVICE, id)) return@post
            val result = revocations.revokeDevice(id, b.reason.take(200), session.username)
            d.bearerTokens?.revokeDevice(id)
            d.directory.removeDevice(id)
            d.onDeviceRevoked(id)
            d.audit.log(session.username, "device.revoke", "device=$id changed=${result.changed} published=${result.publishedVersion} reason=${b.reason.take(200)}")
            call.respondJson(d.identities.device(id)!!.toDto(false))
        }

        post("/{id}/unrevoke") {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val id = call.parameters["id"].orEmpty()
            val revocations = d.revocations ?: return@post call.notAvailable()
            if (d.identities.device(id) == null) return@post call.notFound()
            if (!call.stepUpOk(d, session, StepUpActions.UNREVOKE_DEVICE, id)) return@post
            val result = revocations.unrevokeDevice(id, session.username)
            d.audit.log(session.username, "device.unrevoke", "device=$id changed=${result.changed} published=${result.publishedVersion}")
            call.respondJson(d.identities.device(id)!!.toDto(false))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- invites

private fun InviteRecord.toDto(now: Long) = InviteDto(
    inviteId, state(now).name, department, role, allowedGroups.sorted(), autoJoinGroups.sorted(),
    createdAt, expiresAt, maxUses, uses, note, createdBy,
)

private fun Route.inviteRoutes(d: AdminApiDeps) {
    route("/invites") {
        get {
            val session = call.adminSession(d) ?: return@get
            val scope = d.scopeOf(session)
            val now = d.clock()
            val state = call.request.queryParameters["state"]?.let { s -> InviteState.entries.firstOrNull { it.name.equals(s, true) } ?: return@get call.badRequest() }
            val all = d.identities.listInvites(call.request.queryParameters["limit"].toIntOr(200, 1..1000), call.request.queryParameters["offset"].toIntOr(0, 0..1_000_000))
            call.respondJson(
                all.filter { state == null || it.state(now) == state }
                    .filter { inv -> if (scope is ConsoleScope.Org) true else !inv.department.isNullOrBlank() && scope.allows(inv.department) }
                    .map { it.toDto(now) },
            )
        }

        post {
            val session = call.adminSession(d) ?: return@post
            val scope = d.scopeOf(session)
            val b = call.body<InviteCreateBody>() ?: return@post
            val enrollment = d.enrollment ?: return@post call.notAvailable()
            if (!HubRoles.isValid(b.role)) return@post call.badRequest()
            if (scope !is ConsoleScope.Org) {
                // Heads invite employees to their own department only (plan §3.5, exact sentences in en.js).
                if (b.role != HubRoles.MEMBER) {
                    return@post call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "Only an organisation administrator can invite a department head.")
                }
                val dept = b.department?.trim().orEmpty()
                if (dept.isBlank()) {
                    return@post call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Choose a department for this invite.")
                }
                if (!scope.allows(dept)) {
                    return@post call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "A head can only invite employees to their own department.")
                }
                for (groupId in (b.allowedGroups + b.autoJoinGroups)) {
                    val group = d.directory.getGroup(groupId)
                    if (group == null || group.departmentId != dept) {
                        return@post call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "That department no longer exists.")
                    }
                }
            }
            // An invite that can mint an administrator device is as dangerous as creating an administrator.
            if (b.role != HubRoles.MEMBER && !call.stepUpOk(d, session, StepUpActions.ADMIN_CREATE, "invite:${b.role}")) return@post
            // A department-head invite names its department (required) so enrolment can grant the device role.
            if (b.role == HubRoles.DEPT_ADMIN && b.department.isNullOrBlank()) {
                return@post call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Choose a department for a department head.")
            }
            call.guarded {
                val created = createInvite(d, enrollment, session.username, b)
                d.audit.log(session.username, "invite.create", "invite=${created.invite.inviteId} role=${b.role} department=${b.department.orEmpty()} uses=${created.invite.maxUses}")
                call.respondJson(created, HttpStatusCode.Created)
            }
        }

        post("/bulk") {
            val session = call.adminSession(d) ?: return@post
            val scope = d.scopeOf(session)
            val b = call.body<BulkInviteBody>() ?: return@post
            val enrollment = d.enrollment ?: return@post call.notAvailable()
            if (b.role != HubRoles.MEMBER) return@post call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Bulk invites are for members only.")
            val rows = parseCsv(b.csv)
            if (rows.isEmpty() || rows.size > MAX_BULK) return@post call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Send between 1 and $MAX_BULK rows.")
            val out = rows.map { (line, name, dept) ->
                try {
                    // Heads are bound to their scope row by row; out-of-scope rows fail with the row sentence.
                    if (scope !is ConsoleScope.Org) {
                        val rowDept = dept?.trim().orEmpty()
                        if (rowDept.isBlank() || !scope.allows(rowDept)) throw IllegalArgumentException("out of scope")
                    }
                    val created = createInvite(d, enrollment, session.username, InviteCreateBody(dept, b.role, ttlMinutes = b.ttlMinutes, note = name))
                    BulkInviteRowDto(line, name, created)
                } catch (_: IllegalArgumentException) {
                    BulkInviteRowDto(line, name, error = "This row is not valid.")
                } catch (_: IllegalStateException) {
                    BulkInviteRowDto(line, name, error = "An invite can not be made right now.")
                }
            }
            d.audit.log(session.username, "invite.bulk", "rows=${rows.size} created=${out.count { it.created != null }}")
            call.respondJson(BulkInviteDto(out), HttpStatusCode.Created)
        }

        post("/{id}/revoke") {
            val session = call.adminSession(d) ?: return@post
            val scope = d.scopeOf(session)
            val id = call.parameters["id"].orEmpty()
            val invite = d.identities.invite(id)
            if (invite == null) return@post call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "That invite does not exist or is already revoked.")
            if (scope !is ConsoleScope.Org && (invite.department.isNullOrBlank() || !scope.allows(invite.department))) {
                return@post call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "That invite does not exist or is already revoked.")
            }
            val ok = d.identities.revokeInvite(id, d.clock())
            if (!ok) return@post call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "That invite does not exist or is already revoked.")
            d.audit.log(session.username, "invite.revoke", "invite=$id")
            call.respondJson(d.identities.invite(id)!!.toDto(d.clock()))
        }
    }
}

private const val MAX_BULK = 200

/** `name[,department]` per line; a first line `name,department` is a header. Returns (lineNumber, name, department). */
internal fun parseCsv(csv: String): List<Triple<Int, String, String?>> {
    val out = ArrayList<Triple<Int, String, String?>>()
    csv.lineSequence().forEachIndexed { i, raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@forEachIndexed
        val cells = line.split(',').map { it.trim().trim('"') }
        if (i == 0 && cells.firstOrNull().equals("name", ignoreCase = true)) return@forEachIndexed
        val name = cells.getOrNull(0).orEmpty().take(64)
        if (name.isEmpty()) return@forEachIndexed
        out += Triple(i + 1, name, cells.getOrNull(1)?.ifEmpty { null })
    }
    return out
}

private fun createInvite(d: AdminApiDeps, enrollment: EnrollmentService, admin: String, b: InviteCreateBody): InviteCreatedDto {
    val now = d.clock()
    val ttl = (b.ttlMinutes ?: (d.admins.getSetting(SETTING_TTL)?.toLongOrNull() ?: DEFAULT_TTL_MINUTES)).also { require(it in 5..MAX_TTL_MINUTES) }
    val uses = (b.maxUses ?: (d.admins.getSetting(SETTING_USES)?.toIntOrNull() ?: 1)).also { require(it in 1..EnrollmentService.MAX_INVITE_USES) }
    if (b.department != null) require(d.directory.getDepartment(b.department) != null) { "unknown department" }
    val scope = InviteScope(
        department = b.department?.ifBlank { null },
        allowedGroups = b.allowedGroups.toSet(),
        role = b.role,
        expiry = now + ttl * 60_000L,
        maxUses = uses,
        autoJoinGroups = b.autoJoinGroups.toSet(),
        note = b.note?.take(128),
        createdBy = admin,
    )
    val created = enrollment.createInvite(d.config.externalUrl, scope, now)
    return InviteCreatedDto(created.record.toDto(now), created.token, created.link, QrCodeGenerator.generateSvg(created.link))
}

internal const val SETTING_TTL = "invite.defaultTtlMinutes"
internal const val SETTING_USES = "invite.defaultMaxUses"
internal const val DEFAULT_TTL_MINUTES = 1440L
internal const val MAX_TTL_MINUTES = 43_200L

// ---------------------------------------------------------------------------------------------------------- revocations

private fun Route.revocationRoutes(d: AdminApiDeps) {
    route("/revocations") {
        get {
            val session = call.adminSession(d) ?: return@get
            if (!call.requireOrg(d.scopeOf(session))) return@get
            val r = d.revocations ?: return@get call.notAvailable()
            val s = r.status()
            call.respondJson(
                RevocationsDto(
                    RevocationStatusDto(s.listVersion, s.revokedSerials, s.revokedDeviceIds, s.unpublishedChanges, s.canSign, s.revokedDelegations.sorted()),
                    r.entries().map { RevocationEntryDto(it.kind, it.subject, it.reason, it.revokedAt, it.revokedBy) },
                ),
            )
        }

        // REV-04: a list signed OFFLINE by the root (`keytool revoke`), the only way to revoke a delegation. Verified against the root key,
        // strictly monotonic, merged; every certificate under a revoked delegation dies at once.
        post("/install") {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val r = d.revocations ?: return@post call.notAvailable()
            val list = call.body<RevocationList>() ?: return@post
            if (!call.stepUpOk(d, session, StepUpActions.REVOCATION_INSTALL, "version:${list.version}")) return@post
            val installed = r.installRootSigned(list)
            d.audit.log(session.username, "revocation.install", "version=${list.version} delegations=${list.revokedDelegationSerials.sorted()} installed=$installed")
            if (!installed) {
                return@post call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, "The list was not accepted: it must be signed by the organisation root key and have a higher version than the current list.")
            }
            call.respondJson(OkDto(true))
        }

        post("/serial") {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val b = call.body<RevokeSerialBody>() ?: return@post
            val r = d.revocations ?: return@post call.notAvailable()
            if (!call.stepUpOk(d, session, StepUpActions.REVOKE_DEVICE, "serial:${b.serial}")) return@post
            val result = r.revokeSerial(b.serial, b.reason.take(200), session.username)
            d.identities.deviceForSerial(b.serial)?.let { id -> d.bearerTokens?.revokeDevice(id); d.onDeviceRevoked(id) }
            d.audit.log(session.username, "serial.revoke", "serial=${b.serial} changed=${result.changed} published=${result.publishedVersion}")
            call.respondJson(OkDto(result.changed))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- delegation

private fun Route.delegationRoutes(d: AdminApiDeps) {
    route("/delegation") {
        get {
            val session = call.adminSession(d) ?: return@get
            if (!call.requireOrg(d.scopeOf(session))) return@get
            call.respondJson(delegationDto(d))
        }

        post("/reload") {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val manager = d.delegations ?: return@post call.notAvailable()
            if (!call.stepUpOk(d, session, StepUpActions.DELEGATION_RELOAD, "delegation")) return@post
            val result = manager.reload()
            d.audit.log(session.username, "delegation.reload", result.javaClass.simpleName)
            if (result is DelegationLoad.Rejected) {
                return@post call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, "The delegation file was not accepted. See the hub log.")
            }
            call.respondJson(delegationDto(d))
        }

        post {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val manager = d.delegations ?: return@post call.notAvailable()
            val cert = call.body<DelegationCert>() ?: return@post
            if (!call.stepUpOk(d, session, StepUpActions.DELEGATION_INSTALL, "serial:${cert.serial}")) return@post
            val result = manager.install(cert)
            d.audit.log(session.username, "delegation.install", "serial=${cert.serial} result=${result.javaClass.simpleName}")
            if (result is DelegationLoad.Rejected) {
                return@post call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, "The delegation was not accepted. See the hub log.")
            }
            call.respondJson(delegationDto(d))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- settings

private fun Route.settingsRoutes(d: AdminApiDeps) {
    route("/settings") {
        get {
            val session = call.adminSession(d) ?: return@get
            if (!call.requireOrg(d.scopeOf(session))) return@get
            val c = d.config
            call.respondJson(
                SettingsDto(
                    hubName = c.hubName, externalUrl = c.externalUrl, tlsMode = c.tlsMode, port = c.port, adminPort = c.adminPort,
                    adminOnPublicPort = c.adminOnPublicPort, deviceQuota = c.deviceQuota, mailboxTtlDays = c.mailboxTtlDays,
                    challengeTimeoutMs = c.challengeTimeoutMs, trustedProxies = c.trustedProxies.size, allowInsecure = c.allowInsecure,
                    inviteDefaultTtlMinutes = d.admins.getSetting(SETTING_TTL)?.toLongOrNull() ?: DEFAULT_TTL_MINUTES,
                    inviteDefaultMaxUses = d.admins.getSetting(SETTING_USES)?.toIntOrNull() ?: 1,
                    delegationFile = c.delegationPath.name,
                    maxFileBytes = fileLimits(d).maxFileBytes,
                    fileRetentionDays = (fileLimits(d).retentionMs / DAY_MS).toInt(),
                    deviceStoredBytes = fileLimits(d).deviceStoredBytes,
                    globalStoredBytes = fileLimits(d).globalStoredBytes,
                ),
            )
        }

        put {
            val session = call.adminSession(d) ?: return@put
            if (!call.requireOrg(d.scopeOf(session))) return@put
            val b = call.body<SettingChangeBody>() ?: return@put
            val invalid = when (b.key) {
                SETTING_TTL -> if (b.value.toLongOrNull()?.let { it in 5..MAX_TTL_MINUTES } == true) {
                    null
                } else {
                    "$SETTING_TTL must be a whole number of minutes between 5 and $MAX_TTL_MINUTES"
                }
                SETTING_USES -> if (b.value.toIntOrNull()?.let { it in 1..EnrollmentService.MAX_INVITE_USES } == true) {
                    null
                } else {
                    "$SETTING_USES must be a whole number between 1 and ${EnrollmentService.MAX_INVITE_USES}"
                }
                else -> com.transfer.flash.server.files.FileSettings.validate(b.key, b.value)
            }
            if (invalid != null) {
                return@put call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, invalid)
            }
            if (!call.stepUpOk(d, session, StepUpActions.SETTINGS_CHANGE, "${b.key}=${b.value}")) return@put
            d.admins.putSetting(b.key, b.value, session.username)
            d.audit.log(session.username, "settings.change", "${b.key}=${b.value}")
            call.respondJson(OkDto())
        }
    }
}

/** The administrator's file policy (ADR-215), read from the settings on every call. */
internal fun fileLimits(d: AdminApiDeps): com.transfer.flash.server.files.FileLimits =
    com.transfer.flash.server.files.FileSettings.limits(d.admins::getSetting)

/**
 * `GET /admin/api/v1/files/stats`: what the hub is holding, for the operator. Counts and bytes only: the console never
 * sees a file name, a recipient list or any content (ADR-215).
 */
private fun Route.fileAdminRoutes(d: AdminApiDeps) {
    route("/files") {
        get("/stats") {
            val session = call.adminSession(d) ?: return@get
            if (!call.requireOrg(d.scopeOf(session))) return@get
            val store = d.files
            if (store == null) {
                return@get call.respondJson(com.transfer.flash.core.hubprotocol.rest.FileStatsDto())
            }
            call.respondJson(store.stats())
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- step-up

private fun Route.stepUpRoutes(d: AdminApiDeps) {
    route("/stepup") {
        post("/challenge") {
            val session = call.adminSession(d) ?: return@post
            val b = call.body<StepUpChallengeBody>() ?: return@post
            when (val r = d.stepUp.createChallenge(session.username, b.action, b.target)) {
                is StepUpAuth.Created.Ok -> {
                    val c = r.challenge
                    call.respondJson(
                        StepUpChallengeDto(
                            c.challengeId, c.action, c.target, c.deviceId, c.nonce, c.expiresAtMs, Base64.getEncoder().encodeToString(c.proofBytes()),
                            approvalRequired = c.approvalRequired,
                            approvalCode = if (c.approvalRequired) c.approvalCode else "",
                            browserKeyRequired = c.action !in StepUpActions.WITHOUT_BROWSER_KEY,
                        ),
                    )
                }
                is StepUpAuth.Created.Refused -> call.respondError(
                    if (r.code == "ERR_TOO_MANY") HttpStatusCode.TooManyRequests else HttpStatusCode.BadRequest,
                    r.code, r.message,
                )
            }
        }

        post("/verify") {
            val session = call.adminSession(d) ?: return@post
            val b = call.body<StepUpVerifyBody>() ?: return@post
            val challenge = d.stepUp.getChallenge(b.challengeId)
            if (challenge == null || challenge.adminUsername != session.username) {
                return@post call.respondError(HttpStatusCode.Forbidden, ERR_STEP_UP, MSG_STEP_UP)
            }
            if (!d.stepUp.verifyBase64(b.challengeId, b.signature)) {
                d.audit.logUnauthenticated(session.username, "stepup.bad-signature", "action=${challenge.action}")
                return@post call.respondError(HttpStatusCode.Forbidden, ERR_STEP_UP, MSG_STEP_UP)
            }
            call.respondJson(OkDto())
        }

        // The console polls this while the administrator answers on the phone (ADR-131).
        get("/{id}") {
            val session = call.adminSession(d) ?: return@get
            val challenge = d.stepUp.getChallenge(call.parameters["id"].orEmpty())
            if (challenge == null || challenge.adminUsername != session.username) {
                return@get call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "That confirmation has expired.")
            }
            call.respondJson(StepUpStatusDto(challenge.verified, challenge.approvalRequired, challenge.approved, challenge.expiresAtMs))
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- metrics

private fun Route.metricsRoutes(d: AdminApiDeps) {
    get("/metrics") {
        val session = call.adminSession(d) ?: return@get
        if (!call.requireOrg(d.scopeOf(session))) return@get
        val m = d.metrics
        val stats = d.mailboxStats
        val now = d.clock()
        call.respondJson(
            MetricsDto(
                serverTimeMs = now,
                uptimeSeconds = ((now - d.startedAtMs) / 1000L).coerceAtLeast(0),
                onlineDevices = d.identities.listDevices(DeviceStatus.ACTIVE, limit = 500).count { d.directory.isOnline(it.deviceId) },
                queueDepth = stats.queuedCount(),
                queueBytes = stats.queuedBytes(),
                oldestWaitingMs = stats.oldestAgesMs(1).values.firstOrNull(),
                messagesDelivered = m?.count(RelayMetrics.MESSAGES, "outcome" to "delivered_live") ?: 0,
                messagesQueued = m?.count(RelayMetrics.MESSAGES, "outcome" to "queued") ?: 0,
                messagesRejected = (m?.count(RelayMetrics.MESSAGES, "outcome" to "rejected") ?: 0) +
                    (m?.count(RelayMetrics.MESSAGES, "outcome" to "quota_exceeded") ?: 0),
                redeliveries = m?.total(RelayMetrics.REDELIVERIES) ?: 0,
                framesIn = m?.total(RelayMetrics.FRAMES_IN) ?: 0,
                framesOut = m?.total(RelayMetrics.FRAMES_OUT) ?: 0,
                bytesIn = m?.total(RelayMetrics.BYTES_IN) ?: 0,
                bytesOut = m?.total(RelayMetrics.BYTES_OUT) ?: 0,
                rateLimitHits = m?.total(RelayMetrics.RATE_LIMIT_HITS) ?: 0,
                handshakesOk = m?.count(RelayMetrics.HANDSHAKES, "result" to "ok") ?: 0,
                handshakesRefused = (m?.total(RelayMetrics.HANDSHAKES) ?: 0) - (m?.count(RelayMetrics.HANDSHAKES, "result" to "ok") ?: 0),
                mailboxDelivered = stats.deliveredTotal(),
                mailboxExpired = stats.expiredTotal(),
                mailboxDropped = stats.droppedTotal(),
            ),
        )
    }
}
