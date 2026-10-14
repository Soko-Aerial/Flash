package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.HubRoles
import com.transfer.flash.server.auth.receiveBoundedText
import com.transfer.flash.server.auth.respondError
import com.transfer.flash.server.auth.respondJson
import com.transfer.flash.server.directory.Department
import com.transfer.flash.server.directory.DirectoryActor
import com.transfer.flash.server.directory.DirectoryConflictException
import com.transfer.flash.server.directory.GroupCharter
import com.transfer.flash.server.directory.GroupVisibility
import com.transfer.flash.server.directory.JoinMode
import com.transfer.flash.server.directory.JoinRequest
import com.transfer.flash.server.identity.DeviceStatus
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/*
 * The second half of the administration JSON API: departments / groups / join requests (the directory, acting as
 * `DirectoryActor.CONSOLE`), administrator accounts and sessions, and the audit log. The first half is in AdminApi.kt.
 */

private val CONSOLE = DirectoryActor.CONSOLE

// ---------------------------------------------------------------------------------------------------------- directory

private fun Department.toAdminDto(d: AdminApiDeps) =
    AdminDepartmentDto(id, name, adminDeviceIds, d.directory.memberCount(id), d.directory.groupCount(id), hidden)

private fun GroupCharter.toAdminDto(d: AdminApiDeps) = AdminGroupDto(
    id, name, departmentId, visibility.name, joinMode.name, adminDeviceIds, memberDeviceIds,
    d.directory.onlineCount(this), d.directory.pendingCount(id),
)

private fun JoinRequest.toAdminDto(d: AdminApiDeps) = AdminJoinRequestDto(
    requestId = id,
    groupId = groupId,
    deviceId = deviceId,
    displayName = d.directory.getDepartmentMember(deviceId)?.displayName ?: d.identities.device(deviceId)?.name.orEmpty(),
    status = status.name,
    requestedAt = timestampMs,
    message = message,
)

private inline fun <reified E : Enum<E>> parseEnum(name: String?): E? =
    name?.let { n -> enumValues<E>().firstOrNull { it.name.equals(n.trim(), ignoreCase = true) } }

internal fun Route.adminDirectoryRoutes(d: AdminApiDeps) {
    route("/departments") {
        get {
            val session = call.adminSession(d) ?: return@get
            val scope = d.scopeOf(session)
            val all = d.directory.listDepartments()
            call.respondJson(all.filter { scope.allows(it.id) }.map { it.toAdminDto(d) })
        }

        post {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val b = call.body<DepartmentBody>() ?: return@post
            call.guarded {
                val created = if (b.hidden == null) {
                    d.directory.createDepartment(b.id.orEmpty(), b.name.orEmpty(), b.adminDeviceIds.orEmpty(), CONSOLE)
                } else {
                    d.directory.createDepartment(b.id.orEmpty(), b.name.orEmpty(), b.adminDeviceIds.orEmpty(), b.hidden, CONSOLE)
                }
                d.audit.log(session.username, "department.create", "department=${created.id}")
                if (created.hidden) d.audit.log(session.username, "directory.department.hidden", "department=${created.id} hidden=true")
                call.respondJson(created.toAdminDto(d), HttpStatusCode.Created)
            }
        }

        put("/{id}") {
            val session = call.adminSession(d) ?: return@put
            if (!call.requireOrg(d.scopeOf(session))) return@put
            val id = call.parameters["id"].orEmpty()
            val b = call.body<DepartmentBody>() ?: return@put
            call.guarded {
                val before = d.directory.getDepartment(id)?.hidden
                val updated = d.directory.updateDepartment(id, b.name, b.adminDeviceIds, b.hidden, CONSOLE)
                d.audit.log(session.username, "department.update", "department=$id")
                if (b.hidden != null && b.hidden != before) {
                    d.audit.log(session.username, "directory.department.hidden", "department=$id hidden=${updated.hidden}")
                }
                call.respondJson(updated.toAdminDto(d))
            }
        }

        delete("/{id}") {
            val session = call.adminSession(d) ?: return@delete
            if (!call.requireOrg(d.scopeOf(session))) return@delete
            val id = call.parameters["id"].orEmpty()
            call.guarded {
                d.directory.deleteDepartment(id, CONSOLE)
                d.admins.removeDepartmentScopes(id)
                d.audit.log(session.username, "department.delete", "department=$id")
                call.respondJson(OkDto())
            }
        }
    }

    route("/groups") {
        get {
            val session = call.adminSession(d) ?: return@get
            val scope = d.scopeOf(session)
            call.respondJson(d.directory.listGroups().filter { scope.allowsGroup(it) }.map { it.toAdminDto(d) })
        }

        post {
            val session = call.adminSession(d) ?: return@post
            val scope = d.scopeOf(session)
            val b = call.body<GroupBody>() ?: return@post
            val visibility = if (b.visibility == null) GroupVisibility.LISTED else parseEnum<GroupVisibility>(b.visibility) ?: return@post call.badRequest()
            val joinMode = if (b.joinMode == null) JoinMode.REQUEST_APPROVAL else parseEnum<JoinMode>(b.joinMode) ?: return@post call.badRequest()
            val dept = b.departmentId.orEmpty().trim()
            if (dept.isBlank()) {
                // Organisation-wide groups are org admin only.
                if (!call.requireOrg(scope)) return@post
            } else if (!scope.allows(dept)) {
                return@post call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "That department is not yours.")
            }
            call.guarded {
                val created = d.directory.createGroup(
                    b.id.orEmpty(), b.name.orEmpty(), b.departmentId.orEmpty(), visibility, joinMode, b.adminDeviceIds.orEmpty(), CONSOLE,
                )
                d.audit.log(session.username, "group.create", "group=${created.id} department=${created.departmentId}")
                call.respondJson(created.toAdminDto(d), HttpStatusCode.Created)
            }
        }

        put("/{id}") {
            val session = call.adminSession(d) ?: return@put
            val scope = d.scopeOf(session)
            val id = call.parameters["id"].orEmpty()
            val b = call.body<GroupBody>() ?: return@put
            val visibility = if (b.visibility == null) null else parseEnum<GroupVisibility>(b.visibility) ?: return@put call.badRequest()
            val joinMode = if (b.joinMode == null) null else parseEnum<JoinMode>(b.joinMode) ?: return@put call.badRequest()
            call.guarded {
                val existing = d.directory.getGroup(id) ?: throw com.transfer.flash.server.directory.DirectoryNotFoundException("group")
                if (!scope.allowsGroup(existing)) return@guarded call.notFound()
                var updated = d.directory.updateGroup(id, b.name, visibility, joinMode, CONSOLE)
                if (b.adminDeviceIds != null) updated = d.directory.setGroupAdmins(id, b.adminDeviceIds, CONSOLE)
                d.audit.log(session.username, "group.update", "group=$id")
                call.respondJson(updated.toAdminDto(d))
            }
        }

        delete("/{id}") {
            val session = call.adminSession(d) ?: return@delete
            val scope = d.scopeOf(session)
            val id = call.parameters["id"].orEmpty()
            call.guarded {
                val existing = d.directory.getGroup(id) ?: throw com.transfer.flash.server.directory.DirectoryNotFoundException("group")
                if (!scope.allowsGroup(existing)) return@guarded call.notFound()
                d.directory.deleteGroup(id, CONSOLE)
                d.audit.log(session.username, "group.delete", "group=$id")
                call.respondJson(OkDto())
            }
        }

        post("/{id}/members") {
            val session = call.adminSession(d) ?: return@post
            val scope = d.scopeOf(session)
            val id = call.parameters["id"].orEmpty()
            val b = call.body<GroupMemberAddBody>() ?: return@post
            call.guarded {
                val existing = d.directory.getGroup(id) ?: throw com.transfer.flash.server.directory.DirectoryNotFoundException("group")
                if (!scope.allowsGroup(existing)) return@guarded call.notFound()
                if (b.deviceId.isNotBlank() && scope !is ConsoleScope.Org) {
                    val memberDept = d.directory.getDepartmentMember(b.deviceId)?.departmentId
                    if (!scope.allows(memberDept)) {
                        return@guarded call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "That device is not in your department.")
                    }
                }
                val updated = d.directory.addMemberToGroup(id, b.deviceId, CONSOLE)
                d.audit.log(session.username, "group.member.add", "group=$id device=${b.deviceId}")
                call.respondJson(updated.toAdminDto(d))
            }
        }

        delete("/{id}/members/{deviceId}") {
            val session = call.adminSession(d) ?: return@delete
            val scope = d.scopeOf(session)
            val id = call.parameters["id"].orEmpty()
            val device = call.parameters["deviceId"].orEmpty()
            call.guarded {
                val existing = d.directory.getGroup(id) ?: throw com.transfer.flash.server.directory.DirectoryNotFoundException("group")
                if (!scope.allowsGroup(existing)) return@guarded call.notFound()
                val updated = d.directory.removeMemberFromGroup(id, device, CONSOLE)
                d.audit.log(session.username, "group.member.remove", "group=$id device=$device")
                call.respondJson(updated.toAdminDto(d))
            }
        }
    }

    route("/join-requests") {
        get {
            val session = call.adminSession(d) ?: return@get
            val scope = d.scopeOf(session)
            val all = d.directory.listPendingFor(CONSOLE)
            call.respondJson(
                all.filter { r -> d.directory.getGroup(r.groupId)?.let { scope.allowsGroup(it) } == true }.map { it.toAdminDto(d) },
            )
        }

        post("/{id}/approve") {
            val session = call.adminSession(d) ?: return@post
            val scope = d.scopeOf(session)
            val id = call.parameters["id"].orEmpty()
            call.guarded {
                val req = d.directory.getJoinRequest(id) ?: throw com.transfer.flash.server.directory.DirectoryNotFoundException("request")
                val group = d.directory.getGroup(req.groupId) ?: throw com.transfer.flash.server.directory.DirectoryNotFoundException("group")
                if (!scope.allowsGroup(group)) return@guarded call.notFound()
                val updated = d.directory.approveJoinRequest(id, CONSOLE)
                d.audit.log(session.username, "join.approve", "request=$id group=${updated.id}")
                call.respondJson(updated.toAdminDto(d))
            }
        }

        post("/{id}/reject") {
            val session = call.adminSession(d) ?: return@post
            val scope = d.scopeOf(session)
            val id = call.parameters["id"].orEmpty()
            // The body is optional: `{"reason": "..."}` is recorded in the audit log (the requester is not told).
            val reason = com.transfer.flash.core.hubprotocol.rest.HubRestCodec
                .decodeOrNull<RejectBody>(call.receiveBoundedText().orEmpty())?.reason
                ?.filter { it.code >= 0x20 && it.code != 0x7F }?.trim()?.take(200).orEmpty()
            call.guarded {
                val req = d.directory.getJoinRequest(id) ?: throw com.transfer.flash.server.directory.DirectoryNotFoundException("request")
                val group = d.directory.getGroup(req.groupId) ?: throw com.transfer.flash.server.directory.DirectoryNotFoundException("group")
                if (!scope.allowsGroup(group)) return@guarded call.notFound()
                d.directory.rejectJoinRequest(id, CONSOLE)
                d.audit.log(session.username, "join.reject", "request=$id" + if (reason.isNotEmpty()) " reason=$reason" else "")
                call.respondJson(OkDto())
            }
        }
    }
}

/** Organisation-wide groups (blank department) are ORG; otherwise the group's department must be in scope. */
internal fun ConsoleScope.allowsGroup(group: GroupCharter): Boolean {
    if (this is ConsoleScope.Org) return true
    if (group.departmentId.isBlank()) return false
    return allows(group.departmentId)
}

// ---------------------------------------------------------------------------------------------------------- accounts

private fun AdminUser.toDto(d: AdminApiDeps) = AdminDto(
    username, role, disabled, enrolledDeviceId, createdAtMs, createdBy, d.admins.recoveryCodesRemaining(username), approverDeviceId,
    displayName, if (role == AdminUser.ROLE_DEPT_HEAD) d.admins.headDepartments(username).sorted() else emptyList(), linkedDeviceId,
)

private fun AdminSession.toRow() = SessionRowDto(sessionId, username, createdAtMs, lastSeenMs, expiresAtMs)

internal fun Route.adminAccountRoutes(d: AdminApiDeps) {
    route("/admins") {
        get {
            val session = call.adminSession(d) ?: return@get
            if (!call.requireOrg(d.scopeOf(session))) return@get
            call.respondJson(d.admins.listAdmins().map { it.toDto(d) })
        }

        // A new administrator can approve everything the console can do: it needs the same step-up as a delegation.
        post {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val b = call.body<AdminCreateBody>() ?: return@post
            if (!call.stepUpOk(d, session, StepUpActions.ADMIN_CREATE, b.username.trim())) return@post
            // Plan A1: creating a head = role DEPT_HEAD with departments, display name and an optional linked device.
            if (b.role == AdminUser.ROLE_DEPT_HEAD) {
                if (b.departments.isEmpty()) {
                    return@post call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Choose at least one department for a department head.")
                }
                for (deptId in b.departments) {
                    if (d.directory.getDepartment(deptId) == null) {
                        return@post call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "That department no longer exists.")
                    }
                }
                call.guarded {
                    val credentials = d.admins.createHead(b.username, b.password, session.username, b.displayName.orEmpty(), b.departments, b.linkedDeviceId)
                    d.audit.log(session.username, "admin.create", "admin=${credentials.username} role=DEPT_HEAD departments=${b.departments.sorted()}")
                    call.respondJson(
                        AdminCredentialsDto(credentials.username, null, credentials.totpSecret, credentials.otpAuthUri, credentials.recoveryCodes, QrCodeGenerator.generateSvg(credentials.otpAuthUri)),
                        HttpStatusCode.Created,
                    )
                }
                return@post
            }
            call.guarded {
                val credentials = d.admins.createAdmin(b.username, b.password, session.username)
                d.audit.log(session.username, "admin.create", "admin=${credentials.username}")
                call.respondJson(
                    AdminCredentialsDto(credentials.username, null, credentials.totpSecret, credentials.otpAuthUri, credentials.recoveryCodes, QrCodeGenerator.generateSvg(credentials.otpAuthUri)),
                    HttpStatusCode.Created,
                )
            }
        }

        // Plan A1: add or remove a head's departments (org admin only). Removing the last one
        // leaves a login with empty scope ("No departments").
        get("/{username}/departments") {
            val session = call.adminSession(d) ?: return@get
            if (!call.requireOrg(d.scopeOf(session))) return@get
            val name = call.parameters["username"].orEmpty()
            val admin = d.admins.getAdmin(name) ?: return@get call.notFound()
            if (admin.role != AdminUser.ROLE_DEPT_HEAD) return@get call.respondJson(HeadDepartmentsDto(name, emptyList()))
            call.respondJson(HeadDepartmentsDto(name, d.admins.headDepartments(name).sorted()))
        }

        put("/{username}/departments") {
            val session = call.adminSession(d) ?: return@put
            if (!call.requireOrg(d.scopeOf(session))) return@put
            val name = call.parameters["username"].orEmpty()
            val b = call.body<HeadDepartmentsBody>() ?: return@put
            val admin = d.admins.getAdmin(name) ?: return@put call.notFound()
            if (admin.role != AdminUser.ROLE_DEPT_HEAD) {
                return@put call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Only a department head has departments.")
            }
            for (deptId in b.departments) {
                if (d.directory.getDepartment(deptId) == null) {
                    return@put call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "That department no longer exists.")
                }
            }
            if (name.equals(session.username, ignoreCase = true)) {
                return@put call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You can not remove your own departments.")
            }
            call.guarded {
                d.admins.setHeadDepartments(name, b.departments, session.username)
                d.audit.log(session.username, "admin.departments.change", "admin=$name departments=${b.departments.sorted()}")
                call.respondJson(HeadDepartmentsDto(name, d.admins.headDepartments(name).sorted()))
            }
        }

        post("/{username}/password") {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val name = call.parameters["username"].orEmpty()
            val b = call.body<PasswordResetBody>() ?: return@post
            if (d.admins.getAdmin(name) == null) return@post call.notFound()
            if (!call.stepUpOk(d, session, StepUpActions.ADMIN_CREATE, "reset:$name")) return@post
            call.guarded {
                d.admins.setPassword(name, b.newPassword)
                d.admins.revokeAllSessions(name)
                d.audit.log(session.username, "admin.password.reset", "admin=$name")
                call.respondJson(OkDto())
            }
        }

        // Lost or compromised authenticator: new TOTP secret and recovery codes, the target's sessions end. For the signed-in
        // administrator this ends their own session too; the console says so before it asks.
        post("/{username}/totp-reset") {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val name = call.parameters["username"].orEmpty()
            if (d.admins.getAdmin(name) == null) return@post call.notFound()
            if (!call.stepUpOk(d, session, StepUpActions.ADMIN_CREATE, "totp:$name")) return@post
            call.guarded {
                val credentials = d.admins.resetTotp(name)
                d.audit.log(session.username, "admin.totp.reset", "admin=$name")
                if (name.equals(session.username, ignoreCase = true)) d.web.clearSessionCookie(call)
                call.respondJson(AdminCredentialsDto(credentials.username, null, credentials.totpSecret, credentials.otpAuthUri, credentials.recoveryCodes, QrCodeGenerator.generateSvg(credentials.otpAuthUri)))
            }
        }

        post("/{username}/disable") {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val name = call.parameters["username"].orEmpty()
            if (d.admins.getAdmin(name) == null) return@post call.notFound()
            if (name.equals(session.username, ignoreCase = true)) {
                return@post call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You can not disable your own login.")
            }
            if (!call.stepUpOk(d, session, StepUpActions.ADMIN_DISABLE, name)) return@post
            call.guarded {
                d.admins.setDisabled(name, true)
                d.admins.revokeAllSessions(name)
                d.audit.log(session.username, "admin.disable", "admin=$name")
                call.respondJson(OkDto())
            }
        }

        post("/{username}/enable") {
            val session = call.adminSession(d) ?: return@post
            if (!call.requireOrg(d.scopeOf(session))) return@post
            val name = call.parameters["username"].orEmpty()
            if (d.admins.getAdmin(name) == null) return@post call.notFound()
            if (!call.stepUpOk(d, session, StepUpActions.ADMIN_DISABLE, name)) return@post
            call.guarded {
                d.admins.setDisabled(name, false)
                d.audit.log(session.username, "admin.enable", "admin=$name")
                call.respondJson(OkDto())
            }
        }

        post("/me/password") {
            val session = call.adminSession(d) ?: return@post
            val b = call.body<PasswordChangeBody>() ?: return@post
            call.guarded {
                when (val r = d.admins.changeOwnPasswordLimited(session.username, b.oldPassword, b.newPassword)) {
                    is AdminAuthService.PasswordChange.Locked -> {
                        d.audit.logUnauthenticated(session.username, "admin.password.change.locked", "locked for ${r.remainingSeconds}s")
                        call.response.header("Retry-After", r.remainingSeconds.coerceAtLeast(1).toString())
                        return@guarded call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many wrong passwords. Try again later.")
                    }
                    AdminAuthService.PasswordChange.Wrong -> {
                        d.audit.logUnauthenticated(session.username, "admin.password.change.refused", "wrong current password")
                        return@guarded call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "The current password is not right.")
                    }
                    AdminAuthService.PasswordChange.Changed -> Unit
                }
                // Every session (this one too) ends: the new password is used from the next sign-in.
                d.admins.revokeAllSessions(session.username)
                d.web.clearSessionCookie(call)
                d.audit.log(session.username, "admin.password.change", "")
                call.respondJson(OkDto())
            }
        }

        post("/me/recovery-codes") {
            val session = call.adminSession(d) ?: return@post
            val b = call.body<FreshCodeBody>() ?: return@post
            // REV-11: new recovery codes are what replaces a lost signer, so once a signer exists they need ITS confirmation too.
            if (d.admins.hasStepUpSigner(session.username) && !call.stepUpOk(d, session, StepUpActions.RECOVERY_REGENERATE, "codes")) return@post
            if (!d.admins.confirmFreshTotp(session.username, b.code)) {
                d.audit.logUnauthenticated(session.username, "admin.recovery.refused", "wrong code")
                return@post call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "The code is not right. Wait for the next code.")
            }
            val codes = d.admins.generateRecoveryCodes(session.username)
            d.audit.log(session.username, "admin.recovery.regenerate", "codes=${codes.size}")
            call.respondJson(RecoveryCodesDto(codes))
        }

        // Registers THIS browser's WebCrypto key as the signer of step-up confirmations (ADR-125). Needs a fresh authenticator code
        // (so a stolen session cookie alone can not install an attacker's key) and the proof that the browser holds the private half.
        post("/me/console-key") {
            val session = call.adminSession(d) ?: return@post
            val b = call.body<ConsoleKeyEnrolBody>() ?: return@post
            if (!d.limits.adminLogin.tryAcquire(d.web.clientKey(call))) {
                call.response.header("Retry-After", "30")
                return@post call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many requests. Try again later.")
            }
            if (!validConsoleKeyProof(session.username, ConsoleKeyProof(b.publicKey, b.proof, b.label))) return@post call.badRequest()
            if (!call.phoneApprovesSignerChange(d, session, "console-key")) return@post
            if (!call.signerChangeAllowed(d, session.username, b.code, "console-key", "admin.consolekey.refused")) return@post
            call.guarded {
                val keyId = d.admins.registerConsoleKey(session.username, b.publicKey, b.label)
                d.audit.log(session.username, "admin.consolekey.enrol", "key=$keyId")
                call.respondJson(ConsoleKeyDto(keyId))
            }
        }

        // Binds the administrator to the enrolled admin-role device that confirms dangerous actions (SEC-12).
        post("/me/device") {
            val session = call.adminSession(d) ?: return@post
            val b = call.body<BindDeviceBody>() ?: return@post
            val device = d.identities.device(b.deviceId)
            if (device == null || device.status != DeviceStatus.ACTIVE || device.role != HubRoles.ADMIN) {
                return@post call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "That device is not an active administrator device of this hub.")
            }
            if (!call.phoneApprovesSignerChange(d, session, "device")) return@post
            if (!call.signerChangeAllowed(d, session.username, b.code, "device", "admin.device.bind.refused")) return@post
            d.admins.bindDevice(session.username, device.deviceId)
            d.audit.log(session.username, "admin.device.bind", "device=${device.deviceId}")
            call.respondJson(OkDto())
        }
    }

    // ADR-131 (FO-14): the phone that must also approve delegation install, admin creation and signer replacement.
    route("/admins/me/approver") {
        post {
            val session = call.adminSession(d) ?: return@post
            val b = call.body<ApproverBindBody>() ?: return@post
            val device = d.identities.device(b.deviceId)
            if (device == null || device.status != DeviceStatus.ACTIVE || device.role != HubRoles.ADMIN) {
                return@post call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "That device is not an active administrator device of this hub.")
            }
            val firstPhone = d.admins.approverOf(session.username) == null
            // The browser key's confirmation comes FIRST (and the old phone's, when replacing one), so a missing one does not spend the
            // one-time authenticator code and the same code can be used on the retry.
            if ((!firstPhone || d.admins.hasStepUpSigner(session.username)) &&
                !call.stepUpOk(d, session, StepUpActions.APPROVER_CHANGE, "approver:${device.deviceId}")
            ) {
                return@post
            }
            if (firstPhone) {
                // A fresh authenticator code always: a stolen session alone must not be able to name the attacker's phone.
                val clean = b.code.replace(Regex("\\s+"), "")
                if (!d.admins.confirmFreshTotp(session.username, clean)) {
                    d.audit.logUnauthenticated(session.username, "admin.approver.refused", "wrong code")
                    return@post call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "The code is not right. Wait for the next code.")
                }
            }
            d.admins.bindApprover(session.username, device.deviceId)
            d.audit.log(session.username, "admin.approver.bind", "device=${device.deviceId}")
            call.respondJson(OkDto())
        }

        // The loss path: a lost phone is unbound with one of the administrator's one-time recovery codes. Nothing else can remove it.
        post("/remove") {
            val session = call.adminSession(d) ?: return@post
            val b = call.body<ApproverRemoveBody>() ?: return@post
            if (!d.limits.adminLogin.tryAcquire(d.web.clientKey(call))) {
                call.response.header("Retry-After", "30")
                return@post call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many requests. Try again later.")
            }
            if (d.admins.approverOf(session.username) == null) {
                return@post call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "No approver phone is bound.")
            }
            if (!d.admins.confirmRecoveryCode(session.username, b.code.replace(Regex("\\s+"), ""))) {
                d.audit.logUnauthenticated(session.username, "admin.approver.remove.refused", "wrong recovery code")
                return@post call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "That recovery code is not right.")
            }
            d.admins.clearApprover(session.username)
            d.audit.log(session.username, "admin.approver.remove", "removed with a recovery code")
            call.respondJson(OkDto())
        }
    }

    route("/sessions") {
        get {
            val session = call.adminSession(d) ?: return@get
            val scope = d.scopeOf(session)
            // A head sees only their own sessions; the org admin sees all.
            val rows = if (scope is ConsoleScope.Org) d.admins.listSessions() else d.admins.listSessions(session.username)
            call.respondJson(rows.map { it.toRow() })
        }

        post("/{id}/revoke") {
            val session = call.adminSession(d) ?: return@post
            val scope = d.scopeOf(session)
            val id = call.parameters["id"].orEmpty()
            if (scope !is ConsoleScope.Org) {
                // A head may revoke only their own sessions.
                val own = d.admins.listSessions(session.username).any { it.sessionId == id }
                if (!own) return@post call.notFound()
            }
            if (!d.admins.revokeSession(id)) return@post call.notFound()
            d.audit.log(session.username, "admin.session.revoke", "session=$id")
            call.respondJson(OkDto())
        }
    }
}

// ---------------------------------------------------------------------------------------------------------- audit

private fun com.transfer.flash.server.admin.AuditEntry.toDto() =
    AuditEntryDto(index, timestampMs, actor, action, details, hash, signature != null)

private fun io.ktor.server.application.ApplicationCall.auditFilter(): AuditFilter? {
    val q = request.queryParameters
    val from = q["from"]?.let { it.toLongOrNull() ?: return null }
    val to = q["to"]?.let { it.toLongOrNull() ?: return null }
    return AuditFilter(
        actor = q["actor"]?.takeIf { it.isNotBlank() }?.take(128),
        actionPrefix = q["action"]?.takeIf { it.isNotBlank() }?.take(128),
        fromMs = from,
        toMs = to,
        text = q["text"]?.takeIf { it.isNotBlank() }?.take(128),
    )
}

internal fun Route.auditRoutes(d: AdminApiDeps) {
    route("/audit") {
        get {
            val session = call.adminSession(d) ?: return@get
            if (!call.requireOrg(d.scopeOf(session))) return@get
            val filter = call.auditFilter() ?: return@get call.badRequest()
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)
            val offset = (call.request.queryParameters["offset"]?.toIntOrNull() ?: 0).coerceIn(0, 10_000_000)
            val newestFirst = call.request.queryParameters["order"].equals("desc", ignoreCase = true)
            call.respondJson(AuditPageDto(d.audit.count(filter), d.audit.query(filter, limit, offset, newestFirst).map { it.toDto() }))
        }

        get("/head") {
            val session = call.adminSession(d) ?: return@get
            if (!call.requireOrg(d.scopeOf(session))) return@get
            val head = d.audit.head()
            call.respondJson(AuditHeadDto(head?.first, head?.second, d.audit.size()))
        }

        get("/verify") {
            val session = call.adminSession(d) ?: return@get
            if (!call.requireOrg(d.scopeOf(session))) return@get
            val v = d.audit.verifyChain()
            call.respondJson(AuditVerifyDto(v.ok, v.entries, v.anchors, v.firstBadIndex, v.reason))
        }

        get("/export") {
            val session = call.adminSession(d) ?: return@get
            if (!call.requireOrg(d.scopeOf(session))) return@get
            val filter = call.auditFilter() ?: return@get call.badRequest()
            d.audit.log(session.username, "audit.export", "filter=${filter.actionPrefix.orEmpty()}")
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"flash-hub-audit.csv\"")
            call.response.header("X-Content-Type-Options", "nosniff")
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respondText(d.audit.exportCsv(filter), ContentType.Text.CSV)
        }
    }
}

/**
 * Who may install or replace the signer of step-up confirmations (REV-11). The first signer needs a fresh authenticator code. Once one
 * exists, a stolen session cookie plus a guessed or phished authenticator code must NOT be able to swap it for the attacker's own, so a
 * replacement needs one of the administrator's one-time recovery codes (in [code]) instead. Answers 403 `ERR_SIGNER_EXISTS` otherwise.
 */
internal suspend fun io.ktor.server.application.ApplicationCall.signerChangeAllowed(d: AdminApiDeps, username: String, code: String, what: String, refusedAction: String): Boolean {
    val clean = code.replace(Regex("\\s+"), "")
    if (!d.admins.hasStepUpSigner(username)) {
        if (d.admins.confirmFreshTotp(username, clean)) return true
        d.audit.logUnauthenticated(username, refusedAction, "wrong code")
        respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "The code is not right. Wait for the next code.")
        return false
    }
    if (d.admins.confirmRecoveryCode(username, clean)) {
        d.audit.log(username, "admin.signer.replace", "$what replaced with a recovery code")
        return true
    }
    d.audit.logUnauthenticated(username, refusedAction, "$what: replacement without a recovery code")
    respondError(HttpStatusCode.Forbidden, "ERR_SIGNER_EXISTS", "This administrator already confirms with a key. To replace it, enter one of your recovery codes.")
    return false
}

/**
 * REV-11 plus ADR-131: replacing the signer already needs a recovery code. With an approver phone bound (and a signer to replace) it
 * needs the phone's approval of a [StepUpActions.SIGNER_REPLACE] confirmation as well, checked FIRST so a missing approval does not
 * burn a recovery code. The browser key is not asked: its loss is the usual reason to be here.
 */
internal suspend fun io.ktor.server.application.ApplicationCall.phoneApprovesSignerChange(d: AdminApiDeps, session: AdminSession, what: String): Boolean {
    if (d.admins.approverOf(session.username) == null || !d.admins.hasStepUpSigner(session.username)) return true
    return stepUpOk(d, session, StepUpActions.SIGNER_REPLACE, what)
}
