package com.transfer.flash.server.admin

import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import com.transfer.flash.server.directory.OrganizationDirectory
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Web Admin Dashboard HTTP routes.
 *
 * Implements owner decision D-E12:
 * - /admin/login: PBKDF2 + TOTP 2FA authentication, rate limiting.
 * - /admin/setup: First-run console token consumption.
 * - /admin/logout: Session termination.
 * - /admin/dashboard: High-level system overview.
 * - /admin/departments: Department hierarchy management (D-E13).
 * - /admin/groups: Group creation, visibility scoping, join modes (D-E10, D-E11).
 * - /admin/invites: Cryptographic single-use enrolment link generation (E2b, E2c).
 * - /admin/audit: Blockchain-style append-only SHA-256 audit log inspection.
 */
fun Route.adminDashboardRoutes(
    adminAuthService: AdminAuthService,
    directory: OrganizationDirectory,
    auditLogger: AuditLogger,
    serverExternalUrl: String = "http://localhost:8080",
    rootFingerprint: String = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
) {
    val invites = ConcurrentHashMap<String, EnrolmentInvite>()
    val secureRandom = SecureRandom()

    route("/admin") {
        // --- Authentication & Setup Routes ---

        get("/login") {
            val session = call.getAuthenticatedAdminSession(adminAuthService)
            if (session != null) {
                call.respondRedirect("/admin/dashboard")
                return@get
            }
            if (!adminAuthService.hasAdmins()) {
                call.respondRedirect("/admin/setup")
                return@get
            }
            val html = AdminUiHtml.loginPage()
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        }

        post("/login") {
            val params = call.receiveParameters()
            val username = params["username"] ?: ""
            val password = params["password"] ?: ""
            val totp = params["totp"] ?: ""
            val clientIp = call.request.headers["X-Forwarded-For"]
                ?: call.request.headers["X-Real-IP"]
                ?: "127.0.0.1"

            when (val result = adminAuthService.authenticate(username, password, totp, clientIp)) {
                is AuthResult.Success -> {
                    call.response.cookies.append(
                        Cookie(
                            name = "flash_admin_session",
                            value = result.session.token,
                            maxAge = (AdminAuthService.SESSION_TTL_MS / 1000L).toInt(),
                            path = "/admin",
                            httpOnly = true,
                            secure = false,
                            extensions = mapOf("SameSite" to "Strict")
                        )
                    )
                    auditLogger.log(
                        actor = username,
                        action = "LOGIN_SUCCESS",
                        details = "Admin session established from $clientIp"
                    )
                    call.respondRedirect("/admin/dashboard")
                }
                is AuthResult.Locked -> {
                    auditLogger.log(
                        actor = username.ifBlank { "unknown" },
                        action = "LOGIN_LOCKED",
                        details = "Rate limit lock active: ${result.remainingSeconds}s remaining from $clientIp"
                    )
                    val error = "Account locked due to 5 failed attempts. Please wait ${result.remainingSeconds} seconds."
                    call.respondText(AdminUiHtml.loginPage(error), ContentType.Text.Html, HttpStatusCode.Forbidden)
                }
                is AuthResult.InvalidCredentials -> {
                    auditLogger.log(
                        actor = username.ifBlank { "unknown" },
                        action = "LOGIN_FAILED",
                        details = "Authentication failed (${result.remainingAttempts} attempts remaining) from $clientIp"
                    )
                    val error = "Invalid username, password, or TOTP code. ${result.remainingAttempts} attempts remaining before 15-minute lockout."
                    call.respondText(AdminUiHtml.loginPage(error), ContentType.Text.Html, HttpStatusCode.Unauthorized)
                }
                is AuthResult.SetupRequired -> {
                    call.respondRedirect("/admin/setup")
                }
            }
        }

        get("/setup") {
            if (adminAuthService.hasAdmins()) {
                call.respondRedirect("/admin/login")
                return@get
            }
            val totpSecret = adminAuthService.generateTotpSecret()
            val uri = adminAuthService.getOtpAuthUri("admin", totpSecret)
            val token = call.request.queryParameters["token"]
            val html = AdminUiHtml.setupPage(
                token = token,
                generatedTotpSecret = totpSecret,
                otpAuthUri = uri
            )
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        }

        post("/setup") {
            if (adminAuthService.hasAdmins()) {
                call.respondRedirect("/admin/login")
                return@post
            }
            val params = call.receiveParameters()
            val token = params["token"] ?: ""
            val username = params["username"] ?: ""
            val password = params["password"] ?: ""
            val totpSecret = params["totpSecret"] ?: ""
            val confirmTotp = params["confirmTotp"] ?: ""

            try {
                if (confirmTotp.isNotBlank() && !adminAuthService.validateTotp(totpSecret, confirmTotp)) {
                    throw IllegalArgumentException("Invalid 6-digit code. Please check your authenticator app and enter the current code.")
                }
                val admin = adminAuthService.consumeSetupToken(token, username, password, totpSecret)
                auditLogger.log(
                    actor = admin.username,
                    action = "SETUP_INITIAL_ADMIN",
                    details = "Initialized first organization administrator via console token"
                )
                call.respondRedirect("/admin/login")
            } catch (e: Exception) {
                val secret = totpSecret.ifBlank { adminAuthService.generateTotpSecret() }
                val uri = adminAuthService.getOtpAuthUri(username.ifBlank { "admin" }, secret)
                val html = AdminUiHtml.setupPage(
                    token = token,
                    error = "Setup failed: ${e.message}",
                    generatedTotpSecret = secret,
                    otpAuthUri = uri
                )
                call.respondText(html, ContentType.Text.Html, HttpStatusCode.BadRequest)
            }
        }

        post("/logout") {
            val token = call.request.cookies["flash_admin_session"]
            if (!token.isNullOrBlank()) {
                adminAuthService.invalidateSession(token)
            }
            call.response.cookies.append(
                Cookie(
                    name = "flash_admin_session",
                    value = "",
                    maxAge = 0,
                    path = "/admin"
                )
            )
            call.respondRedirect("/admin/login")
        }

        // --- Protected Admin Dashboard Pages ---

        get("/dashboard") {
            val session = call.requireAdminSession(adminAuthService) ?: return@get
            val depts = directory.listDepartments()
            val groups = directory.listGroups()
            val isChainValid = auditLogger.verifyIntegrity()
            val recentAudit = auditLogger.getRecent(10)

            val totalMembers = groups.flatMap { it.memberDeviceIds }.distinct().size
            val activeInvites = invites.values.count { !it.used && System.currentTimeMillis() <= it.expiresAtMs }

            val stats = DashboardStats(
                departmentCount = depts.size,
                groupCount = groups.size,
                memberCount = totalMembers,
                activeInviteCount = activeInvites,
                auditEntryCount = auditLogger.size(),
                isAuditChainValid = isChainValid
            )

            val html = AdminUiHtml.dashboardPage(session.username, stats, recentAudit)
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        }

        get("/departments") {
            val session = call.requireAdminSession(adminAuthService) ?: return@get
            val depts = directory.listDepartments()
            val html = AdminUiHtml.departmentsPage(session.username, depts)
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        }

        post("/departments") {
            val session = call.requireAdminSession(adminAuthService) ?: return@post
            val params = call.receiveParameters()
            val deptId = params["deptId"] ?: ""
            val deptName = params["deptName"] ?: ""
            val adminDevicesRaw = params["adminDeviceIds"] ?: ""

            val adminDevices = adminDevicesRaw.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            try {
                directory.createDepartment(
                    id = deptId,
                    name = deptName,
                    adminDeviceIds = adminDevices,
                    callerDeviceId = session.username,
                    isOrgAdmin = true
                )
                auditLogger.log(
                    actor = session.username,
                    action = "CREATE_DEPARTMENT",
                    details = "Created department '$deptName' ($deptId) with admins: $adminDevices"
                )
                call.respondRedirect("/admin/departments")
            } catch (e: Exception) {
                val depts = directory.listDepartments()
                val html = AdminUiHtml.departmentsPage(session.username, depts, error = e.message)
                call.respondText(html, ContentType.Text.Html, HttpStatusCode.BadRequest)
            }
        }

        get("/groups") {
            val session = call.requireAdminSession(adminAuthService) ?: return@get
            val groups = directory.listGroups()
            val depts = directory.listDepartments()
            val html = AdminUiHtml.groupsPage(session.username, groups, depts)
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        }

        post("/groups") {
            val session = call.requireAdminSession(adminAuthService) ?: return@post
            val params = call.receiveParameters()
            val groupId = params["groupId"] ?: ""
            val groupName = params["groupName"] ?: ""
            val deptId = params["departmentId"] ?: ""
            val visibilityStr = params["visibility"] ?: "LISTED"
            val joinModeStr = params["joinMode"] ?: "OPEN_IN_SCOPE"
            val adminDevicesRaw = params["adminDeviceIds"] ?: ""

            val visibility = try {
                GroupVisibility.valueOf(visibilityStr)
            } catch (_: Exception) {
                GroupVisibility.LISTED
            }

            val joinMode = try {
                JoinMode.valueOf(joinModeStr)
            } catch (_: Exception) {
                JoinMode.OPEN_IN_SCOPE
            }

            val adminDevices = adminDevicesRaw.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            try {
                directory.createGroup(
                    id = groupId,
                    name = groupName,
                    departmentId = deptId,
                    visibility = visibility,
                    joinMode = joinMode,
                    adminDeviceIds = adminDevices,
                    callerDeviceId = session.username,
                    isOrgAdmin = true
                )
                auditLogger.log(
                    actor = session.username,
                    action = "CREATE_GROUP",
                    details = "Created group '$groupName' ($groupId) in dept '$deptId' [vis=$visibility, join=$joinMode]"
                )
                call.respondRedirect("/admin/groups")
            } catch (e: Exception) {
                val groups = directory.listGroups()
                val depts = directory.listDepartments()
                val html = AdminUiHtml.groupsPage(session.username, groups, depts, error = e.message)
                call.respondText(html, ContentType.Text.Html, HttpStatusCode.BadRequest)
            }
        }

        get("/invites") {
            val session = call.requireAdminSession(adminAuthService) ?: return@get
            val allInvites = invites.values.toList()
            val depts = directory.listDepartments()
            val groups = directory.listGroups()
            val html = AdminUiHtml.invitesPage(session.username, allInvites, depts, groups)
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        }

        post("/invites") {
            val session = call.requireAdminSession(adminAuthService) ?: return@post
            val params = call.receiveParameters()
            val employeeName = params["employeeName"] ?: ""
            val deptId = params["departmentId"] ?: ""
            val ttlMinutes = (params["ttlMinutes"]?.toLongOrNull() ?: 60L).coerceIn(5L, 10080L)
            val allowedGroupsRaw = params["allowedGroups"] ?: ""
            val autoJoinGroupsRaw = params["autoJoinGroups"] ?: ""

            val allowedGroups = allowedGroupsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            val autoJoinGroups = autoJoinGroupsRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }

            val tokenBytes = ByteArray(16)
            secureRandom.nextBytes(tokenBytes)
            val token = tokenBytes.toHex()

            val link = "flash://enroll?server=${serverExternalUrl}&token=${token}&root=${rootFingerprint}"
            val expiresAtMs = System.currentTimeMillis() + (ttlMinutes * 60 * 1000L)

            val invite = EnrolmentInvite(
                token = token,
                employeeName = employeeName,
                departmentId = deptId,
                allowedGroupIds = allowedGroups,
                autoJoinGroupIds = autoJoinGroups,
                expiresAtMs = expiresAtMs,
                link = link,
                used = false
            )
            invites[token] = invite

            auditLogger.log(
                actor = session.username,
                action = "INVITE_EMPLOYEE",
                details = "Generated single-use enrolment link for $employeeName (dept=$deptId, ttl=${ttlMinutes}m)"
            )

            val allInvites = invites.values.toList()
            val depts = directory.listDepartments()
            val groups = directory.listGroups()
            val html = AdminUiHtml.invitesPage(
                username = session.username,
                invites = allInvites,
                departments = depts,
                groups = groups,
                newInviteLink = link
            )
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        }

        get("/audit") {
            val session = call.requireAdminSession(adminAuthService) ?: return@get
            val entries = auditLogger.getEntries(limit = 200)
            val isChainValid = auditLogger.verifyIntegrity()
            val html = AdminUiHtml.auditPage(session.username, entries, isChainValid)
            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        }
    }
}

private fun ApplicationCall.getAuthenticatedAdminSession(adminAuthService: AdminAuthService): AdminSession? {
    val token = request.cookies["flash_admin_session"] ?: return null
    return adminAuthService.validateSession(token)
}

private suspend fun ApplicationCall.requireAdminSession(adminAuthService: AdminAuthService): AdminSession? {
    val session = getAuthenticatedAdminSession(adminAuthService)
    if (session == null) {
        respondRedirect("/admin/login")
        return null
    }
    return session
}
