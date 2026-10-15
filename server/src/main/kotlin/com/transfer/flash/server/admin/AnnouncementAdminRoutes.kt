package com.transfer.flash.server.admin

import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementPreviewRequestDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementPreviewResponseDto
import com.transfer.flash.core.hubprotocol.rest.AnnouncementSearchResponseDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.PostAnnouncementRequestDto
import com.transfer.flash.core.hubprotocol.rest.UpdateAnnouncementRequestDto
import com.transfer.flash.server.announcements.AnnouncementConflictException
import com.transfer.flash.server.announcements.AnnouncementPinLimitException
import com.transfer.flash.server.announcements.AnnouncementStore
import com.transfer.flash.server.announcements.AnnouncementText
import com.transfer.flash.server.auth.receiveBoundedText
import com.transfer.flash.server.auth.respondError
import com.transfer.flash.server.auth.respondJson
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

/**
 * Console admin routes for organization announcements (Plan A3 §3.4 and §5).
 * Plaintext broadcast messages stored unencrypted on the hub (ADR-138).
 *
 * Routes:
 * - `GET  /admin/api/v1/announcements`
 * - `GET  /admin/api/v1/announcements/{id}`
 * - `POST /admin/api/v1/announcements`
 * - `PUT  /admin/api/v1/announcements/{id}`
 * - `DELETE /admin/api/v1/announcements/{id}`
 * - `POST /admin/api/v1/announcements/{id}/pin`
 * - `POST /admin/api/v1/announcements/{id}/unpin`
 * - `POST /admin/api/v1/announcements/preview`
 */
fun Route.announcementAdminRoutes(d: AdminApiDeps) {
    route("/announcements") {

        // POST /admin/api/v1/announcements/preview
        post("/preview") {
            val session = call.adminSession(d) ?: return@post
            val rawBody = call.receiveBoundedText(MAX_ADMIN_ANNOUNCEMENT_BODY_BYTES) ?: return@post
            val req = HubRestCodec.decodeOrNull<AnnouncementPreviewRequestDto>(rawBody)
            if (req == null) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Malformed preview request")
                return@post
            }

            val cleaned = try {
                AnnouncementText.clean(req.title, req.body)
            } catch (e: AnnouncementText.ValidationException) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, e.message ?: "Invalid title or body")
                return@post
            }

            val scope = d.scopeOf(session)
            val isOrgAdmin = scope is ConsoleScope.Org
            if (req.scope == "org" && !isOrgAdmin) {
                call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "Department heads cannot preview org-wide announcements")
                return@post
            }
            if (req.scope == "department") {
                if (req.departmentId.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "departmentId is required for department scope")
                    return@post
                }
                if (!isOrgAdmin && !scope.allows(req.departmentId)) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "Department heads may only preview for their assigned department")
                    return@post
                }
            }

            val deptName = req.departmentId?.let { d.directory.getDepartment(it)?.name ?: it }
            val audienceScope = if (req.scope == "org") "Entire organisation" else "Department: ${deptName ?: req.departmentId}"
            val recipientCount = if (req.scope == "org") {
                d.identities.activeDeviceCount().toInt()
            } else {
                req.departmentId?.let { deptId -> d.identities.listDevices(department = deptId).size } ?: 0
            }

            call.respondJson(
                AnnouncementPreviewResponseDto(
                    title = cleaned.title,
                    body = cleaned.body,
                    titleCodePoints = cleaned.titleCodePoints,
                    bodyCodePoints = cleaned.bodyCodePoints,
                    links = AnnouncementText.extractLinks(cleaned.body),
                    audienceScope = audienceScope,
                    departmentName = deptName,
                    estimatedRecipients = recipientCount,
                    honestyNotice = "Announcements are stored as plain text on your company's server. Administrators can read them.",
                ),
            )
        }

        // GET /admin/api/v1/announcements
        get {
            val session = call.adminSession(d) ?: return@get
            val store = d.announcements ?: run {
                call.respondJson(AnnouncementSearchResponseDto())
                return@get
            }
            val scope = d.scopeOf(session)
            val isOrgAdmin = scope is ConsoleScope.Org

            val q = call.request.queryParameters["q"]
            val reqScope = call.request.queryParameters["scope"]
            val departmentId = call.request.queryParameters["departmentId"]
            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull()
            val limit = (rawLimit ?: 50).coerceIn(1, 100)

            // Scoping: Department head cannot query 'org' scope or another department
            if (!isOrgAdmin) {
                val headScope = scope as ConsoleScope.Departments
                if (reqScope == "org") {
                    call.respondJson(AnnouncementSearchResponseDto())
                    return@get
                }
                if (departmentId != null && !headScope.allows(departmentId)) {
                    call.respondJson(AnnouncementSearchResponseDto())
                    return@get
                }
            }

            val effectiveDeptId = when {
                isOrgAdmin -> departmentId
                scope is ConsoleScope.Departments -> departmentId ?: if (scope.ids.size == 1) scope.ids.single() else null
                else -> null
            }

            val result = store.searchAnnouncements(
                query = q,
                scope = if (!isOrgAdmin) "department" else reqScope,
                departmentId = effectiveDeptId,
                before = before,
                limit = limit,
                callerDeptId = if (scope is ConsoleScope.Departments && scope.ids.size == 1) scope.ids.single() else null,
                isOrgAdmin = isOrgAdmin,
                nowMs = d.clock(),
            )

            // If head scope has multiple departments, filter in-memory to ensure only allowed departments
            val finalItems = if (!isOrgAdmin && scope is ConsoleScope.Departments) {
                result.items.filter { it.scope == "department" && scope.allows(it.departmentId) }
            } else {
                result.items
            }

            d.audit.record(session.username, "admin.announcement.list", "rows=${finalItems.size}")
            call.respondJson(result.copy(items = finalItems))
        }

        // GET /admin/api/v1/announcements/{id}
        get("/{id}") {
            val session = call.adminSession(d) ?: return@get
            val store = d.announcements ?: return@get call.notFound()
            val id = call.parameters["id"] ?: return@get call.badRequest()
            val scope = d.scopeOf(session)
            val isOrgAdmin = scope is ConsoleScope.Org

            val item = store.getAnnouncement(id, callerDeptId = null, isOrgAdmin = true, nowMs = d.clock())
            if (item == null) return@get call.notFound()

            if (!isOrgAdmin) {
                val headScope = scope as ConsoleScope.Departments
                if (item.scope == "org" || !headScope.allows(item.departmentId)) {
                    return@get call.notFound()
                }
            }

            call.respondJson(item)
        }

        // POST /admin/api/v1/announcements
        post {
            val session = call.adminSession(d) ?: return@post
            val store = d.announcements ?: return@post call.notFound()
            val scope = d.scopeOf(session)
            val isOrgAdmin = scope is ConsoleScope.Org

            val rawBody = call.receiveBoundedText(MAX_ADMIN_ANNOUNCEMENT_BODY_BYTES) ?: return@post
            val req = HubRestCodec.decodeOrNull<PostAnnouncementRequestDto>(rawBody)
            if (req == null) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Malformed announcement request")
                return@post
            }

            if (req.scope == "org" && !isOrgAdmin) {
                call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "Department heads cannot post organization-wide announcements")
                return@post
            }

            if (req.scope == "department") {
                if (req.departmentId.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "departmentId is required for department scope")
                    return@post
                }
                if (!isOrgAdmin && !scope.allows(req.departmentId)) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "Department heads may only post to their assigned department")
                    return@post
                }
            } else if (req.scope != "org") {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "scope must be 'org' or 'department'")
                return@post
            }

            val cleaned = try {
                AnnouncementText.clean(req.title, req.body)
            } catch (e: AnnouncementText.ValidationException) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, e.message ?: "Invalid title or body")
                return@post
            }

            val created = try {
                store.createAnnouncement(
                    clientRequestId = req.clientRequestId.ifBlank { UUID.randomUUID().toString() },
                    scope = req.scope,
                    departmentId = req.departmentId,
                    title = cleaned.title,
                    body = cleaned.body,
                    authorKind = "console",
                    authorRef = session.username,
                    authorName = session.username,
                    authorRole = if (isOrgAdmin) "ORG_ADMIN" else "DEPT_HEAD",
                    pinned = req.pinned,
                    expiresAtMs = req.expiresAtMs,
                )
            } catch (e: AnnouncementPinLimitException) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_PIN_LIMIT, e.message ?: "Pin limit reached (max 3)")
                return@post
            } catch (e: IllegalStateException) {
                val msg = e.message.orEmpty()
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, msg)
                return@post
            }

            d.router?.broadcastAnnouncement(
                RelayFrame.AnnouncementEvent(
                    id = created.id,
                    seq = created.seq,
                    scope = created.scope,
                    departmentId = created.departmentId,
                    change = "created",
                    epoch = store.epoch,
                ),
            )

            d.audit.record(session.username, "admin.announcement.create", "id=${created.id},scope=${created.scope},len=${cleaned.body.length}")
            call.respondJson(created, HttpStatusCode.Created)
        }

        // PUT /admin/api/v1/announcements/{id}
        put("/{id}") {
            val session = call.adminSession(d) ?: return@put
            val store = d.announcements ?: return@put call.notFound()
            val id = call.parameters["id"] ?: return@put call.badRequest()
            val scope = d.scopeOf(session)
            val isOrgAdmin = scope is ConsoleScope.Org

            val existing = store.getAnnouncement(id, callerDeptId = null, isOrgAdmin = true, nowMs = d.clock())
            if (existing == null) return@put call.notFound()

            if (!isOrgAdmin) {
                val headScope = scope as ConsoleScope.Departments
                if (existing.scope == "org" || !headScope.allows(existing.departmentId)) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to edit this announcement")
                    return@put
                }
            }

            val rawBody = call.receiveBoundedText(MAX_ADMIN_ANNOUNCEMENT_BODY_BYTES) ?: return@put
            val req = HubRestCodec.decodeOrNull<UpdateAnnouncementRequestDto>(rawBody)
            if (req == null) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Malformed update request")
                return@put
            }

            if (req.expectedSeq != existing.seq) {
                call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, "Concurrent edit conflict")
                return@put
            }

            val cleanTitle = if (!req.title.isNullOrBlank()) {
                try {
                    AnnouncementText.cleanTitle(req.title)
                } catch (e: AnnouncementText.ValidationException) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, e.message ?: "Invalid title")
                    return@put
                }
            } else null

            val cleanBody = if (!req.body.isNullOrBlank()) {
                try {
                    AnnouncementText.cleanBody(req.body)
                } catch (e: AnnouncementText.ValidationException) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, e.message ?: "Invalid body")
                    return@put
                }
            } else null

            val updated = try {
                store.updateAnnouncement(
                    id = id,
                    expectedSeq = req.expectedSeq,
                    title = cleanTitle,
                    body = cleanBody,
                    pinned = req.pinned,
                    expiresAtMs = req.expiresAtMs,
                    actorRole = if (isOrgAdmin) "ORG_ADMIN" else "DEPT_HEAD",
                    actorDeptId = if (isOrgAdmin) null else (scope as? ConsoleScope.Departments)?.ids?.singleOrNull(),
                    actorRef = session.username,
                    clientRequestId = null,
                )
            } catch (e: AnnouncementPinLimitException) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_PIN_LIMIT, e.message ?: "Pin limit reached (max 3)")
                return@put
            } catch (_: AnnouncementConflictException) {
                call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, "Concurrent edit conflict")
                return@put
            } catch (e: IllegalStateException) {
                val msg = e.message.orEmpty()
                if (msg.contains("Concurrent edit conflict", ignoreCase = true)) {
                    call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_CONFLICT, "Concurrent edit conflict")
                } else {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, msg)
                }
                return@put
            }

            d.router?.broadcastAnnouncement(
                RelayFrame.AnnouncementEvent(
                    id = updated.id,
                    seq = updated.seq,
                    scope = updated.scope,
                    departmentId = updated.departmentId,
                    change = "updated",
                    epoch = store.epoch,
                ),
            )

            d.audit.record(session.username, "admin.announcement.update", "id=$id,seq=${updated.seq},len=${updated.body.length}")
            call.respondJson(updated)
        }

        // DELETE /admin/api/v1/announcements/{id}
        delete("/{id}") {
            val session = call.adminSession(d) ?: return@delete
            val store = d.announcements ?: return@delete call.notFound()
            val id = call.parameters["id"] ?: return@delete call.badRequest()
            val scope = d.scopeOf(session)
            val isOrgAdmin = scope is ConsoleScope.Org

            val existing = store.getAnnouncement(id, callerDeptId = null, isOrgAdmin = true, nowMs = d.clock())
            if (existing == null) return@delete call.notFound()

            if (!isOrgAdmin) {
                val headScope = scope as ConsoleScope.Departments
                if (existing.scope == "org" || !headScope.allows(existing.departmentId)) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to delete this announcement")
                    return@delete
                }
            }

            val deleted = store.deleteAnnouncement(id, actorRole = if (isOrgAdmin) "ORG_ADMIN" else "DEPT_HEAD")
            d.router?.broadcastAnnouncement(
                RelayFrame.AnnouncementEvent(
                    id = deleted.id,
                    seq = deleted.seq,
                    scope = deleted.scope,
                    departmentId = deleted.departmentId,
                    change = "deleted",
                    epoch = store.epoch,
                ),
            )

            d.audit.record(session.username, "admin.announcement.delete", "id=$id")
            call.respondJson(deleted)
        }

        // POST /admin/api/v1/announcements/{id}/pin
        post("/{id}/pin") {
            val session = call.adminSession(d) ?: return@post
            val store = d.announcements ?: return@post call.notFound()
            val id = call.parameters["id"] ?: return@post call.badRequest()
            val scope = d.scopeOf(session)
            val isOrgAdmin = scope is ConsoleScope.Org

            val existing = store.getAnnouncement(id, callerDeptId = null, isOrgAdmin = true, nowMs = d.clock())
            if (existing == null) return@post call.notFound()

            if (!isOrgAdmin) {
                val headScope = scope as ConsoleScope.Departments
                if (existing.scope == "org" || !headScope.allows(existing.departmentId)) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to pin this announcement")
                    return@post
                }
            }

            val pinned = try {
                store.pinAnnouncement(id, actorRole = if (isOrgAdmin) "ORG_ADMIN" else "DEPT_HEAD")
            } catch (e: AnnouncementPinLimitException) {
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_PIN_LIMIT, e.message ?: "Pin limit reached (max 3)")
                return@post
            } catch (e: IllegalStateException) {
                val msg = e.message.orEmpty()
                call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, msg)
                return@post
            }

            d.router?.broadcastAnnouncement(
                RelayFrame.AnnouncementEvent(
                    id = pinned.id,
                    seq = pinned.seq,
                    scope = pinned.scope,
                    departmentId = pinned.departmentId,
                    change = "pinned",
                    epoch = store.epoch,
                ),
            )

            d.audit.record(session.username, "admin.announcement.pin", "id=$id")
            call.respondJson(pinned)
        }

        // POST /admin/api/v1/announcements/{id}/unpin
        post("/{id}/unpin") {
            val session = call.adminSession(d) ?: return@post
            val store = d.announcements ?: return@post call.notFound()
            val id = call.parameters["id"] ?: return@post call.badRequest()
            val scope = d.scopeOf(session)
            val isOrgAdmin = scope is ConsoleScope.Org

            val existing = store.getAnnouncement(id, callerDeptId = null, isOrgAdmin = true, nowMs = d.clock())
            if (existing == null) return@post call.notFound()

            if (!isOrgAdmin) {
                val headScope = scope as ConsoleScope.Departments
                if (existing.scope == "org" || !headScope.allows(existing.departmentId)) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to unpin this announcement")
                    return@post
                }
            }

            val unpinned = store.unpinAnnouncement(id, actorRole = if (isOrgAdmin) "ORG_ADMIN" else "DEPT_HEAD")
            d.router?.broadcastAnnouncement(
                RelayFrame.AnnouncementEvent(
                    id = unpinned.id,
                    seq = unpinned.seq,
                    scope = unpinned.scope,
                    departmentId = unpinned.departmentId,
                    change = "unpinned",
                    epoch = store.epoch,
                ),
            )

            d.audit.record(session.username, "admin.announcement.unpin", "id=$id")
            call.respondJson(unpinned)
        }
    }
}

private const val MAX_ADMIN_ANNOUNCEMENT_BODY_BYTES = 16 * 1024
