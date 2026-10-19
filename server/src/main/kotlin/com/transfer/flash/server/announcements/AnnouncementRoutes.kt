package com.transfer.flash.server.announcements

import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.relay.HubPrincipal
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.rest.AnnouncementItemDto
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import com.transfer.flash.core.hubprotocol.rest.PostAnnouncementRequestDto
import com.transfer.flash.core.hubprotocol.rest.UpdateAnnouncementRequestDto
import com.transfer.flash.server.auth.AuditSink
import com.transfer.flash.server.auth.HubRateLimits
import com.transfer.flash.server.auth.receiveBoundedText
import com.transfer.flash.server.auth.requirePrincipal
import com.transfer.flash.server.auth.respondError
import com.transfer.flash.server.auth.respondJson
import com.transfer.flash.server.relay.HubPrincipalResolver
import com.transfer.flash.server.relay.RelayFrameRouter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.util.UUID

/**
 * Device REST routes for organization announcements (Plan A3 §3.3, ADR-138).
 * Plaintext broadcast messages stored unencrypted on the hub.
 *
 * Routes:
 * - `GET  /api/v1/announcements`: Feed (`since`) or search (`q`) or recency list
 * - `GET  /api/v1/announcements/{id}`: Single item
 * - `POST /api/v1/announcements`: Create
 * - `PUT  /api/v1/announcements/{id}`: Edit with optimistic concurrency
 * - `DELETE /api/v1/announcements/{id}`: Soft delete
 * - `POST /api/v1/announcements/{id}/pin`: Pin (max 3)
 * - `POST /api/v1/announcements/{id}/unpin`: Unpin
 */
fun Route.announcementRoutes(
    store: AnnouncementStore,
    principalResolver: HubPrincipalResolver,
    router: RelayFrameRouter? = null,
    audit: AuditSink = AuditSink.None,
    limits: HubRateLimits = HubRateLimits.unlimited(),
    clock: () -> Long = System::currentTimeMillis,
) {
    route(HubContract.PATH_API) {
        route("/announcements") {

            // GET /api/v1/announcements
            get {
                val principal = call.requirePrincipal(principalResolver) ?: return@get
                if (!limits.announcementRead.tryAcquire(principal.deviceId)) {
                    val retryAfter = limits.announcementRead.retryAfterSeconds(principal.deviceId)
                    call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many announcement read requests")
                    return@get
                }

                val isOrgAdmin = isOrgAdminRole(principal.role)
                val since = call.request.queryParameters["since"]?.toLongOrNull()
                val q = call.request.queryParameters["q"]
                val scope = call.request.queryParameters["scope"]
                val departmentId = call.request.queryParameters["departmentId"]
                val before = call.request.queryParameters["before"]?.toLongOrNull()
                val rawLimit = call.request.queryParameters["limit"]?.toIntOrNull()
                val limit = (rawLimit ?: 50).coerceIn(1, 100)

                if (since != null) {
                    val feed = store.getChangesFeed(
                        sinceSeq = since,
                        limit = limit,
                        callerDeptId = principal.departmentId,
                        isOrgAdmin = isOrgAdmin,
                        nowMs = clock(),
                    )
                    audit.record(principal.deviceId, "announcement.feed", "since=$since,rows=${feed.items.size}")
                    call.respondJson(feed)
                    return@get
                }

                if (q != null && q.length > 64) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Search query too long (max 64 chars)")
                    return@get
                }

                val searchResult = store.searchAnnouncements(
                    query = q,
                    scope = scope,
                    departmentId = departmentId,
                    before = before,
                    limit = limit,
                    callerDeptId = principal.departmentId,
                    isOrgAdmin = isOrgAdmin,
                    nowMs = clock(),
                )
                audit.record(principal.deviceId, "announcement.search", "rows=${searchResult.items.size}")
                call.respondJson(searchResult)
            }

            // GET /api/v1/announcements/{id}
            get("/{id}") {
                val principal = call.requirePrincipal(principalResolver) ?: return@get
                if (!limits.announcementRead.tryAcquire(principal.deviceId)) {
                    val retryAfter = limits.announcementRead.retryAfterSeconds(principal.deviceId)
                    call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many announcement read requests")
                    return@get
                }
                val id = call.parameters["id"]
                if (id.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Missing announcement id")
                    return@get
                }

                val isOrgAdmin = isOrgAdminRole(principal.role)
                val item = store.getAnnouncement(
                    id = id,
                    callerDeptId = principal.departmentId,
                    isOrgAdmin = isOrgAdmin,
                    nowMs = clock(),
                )

                if (item == null) {
                    call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "Announcement not found")
                    return@get
                }

                call.respondJson(item)
            }

            // POST /api/v1/announcements
            post {
                val principal = call.requirePrincipal(principalResolver) ?: return@post
                if (!limits.announcementPost.tryAcquire(principal.deviceId)) {
                    val retryAfter = limits.announcementPost.retryAfterSeconds(principal.deviceId)
                    call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many announcement post requests")
                    return@post
                }

                val role = principal.role
                val isOrgAdmin = isOrgAdminRole(role)
                val isDeptHead = isDeptHeadRole(role)

                if (!isOrgAdmin && !isDeptHead) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "Only administrators and department heads may post announcements")
                    return@post
                }

                val rawBody = call.receiveBoundedText(MAX_ANNOUNCEMENT_BODY_BYTES) ?: return@post
                val req = HubRestCodec.decodeOrNull<PostAnnouncementRequestDto>(rawBody)
                if (req == null) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Malformed post announcement request")
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
                    if (!isOrgAdmin && principal.departmentId != req.departmentId) {
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
                        authorKind = "device",
                        authorRef = principal.deviceId,
                        authorName = principal.deviceId.take(8),
                        authorRole = principal.role,
                        pinned = req.pinned,
                        expiresAtMs = req.expiresAtMs,
                    )
                } catch (e: AnnouncementPinLimitException) {
                    call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_PIN_LIMIT, e.message ?: "Pin limit reached (max 3)")
                    return@post
                } catch (e: IllegalStateException) {
                    val msg = e.message.orEmpty()
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, msg)
                    return@post
                }

                router?.broadcastAnnouncement(
                    RelayFrame.AnnouncementEvent(
                        id = created.id,
                        seq = created.seq,
                        scope = created.scope,
                        departmentId = created.departmentId,
                        change = "created",
                        epoch = store.epoch,
                    ),
                )

                audit.record(principal.deviceId, "announcement.create", "id=${created.id},scope=${created.scope},len=${cleaned.body.length}")
                call.respondJson(created, HttpStatusCode.Created)
            }

            // PUT /api/v1/announcements/{id}
            put("/{id}") {
                val principal = call.requirePrincipal(principalResolver) ?: return@put
                if (!limits.announcementPost.tryAcquire(principal.deviceId)) {
                    val retryAfter = limits.announcementPost.retryAfterSeconds(principal.deviceId)
                    call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many announcement post requests")
                    return@put
                }

                val id = call.parameters["id"]
                if (id.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Missing announcement id")
                    return@put
                }

                val isOrgAdmin = isOrgAdminRole(principal.role)
                val existing = store.getAnnouncement(id, callerDeptId = principal.departmentId, isOrgAdmin = isOrgAdmin, nowMs = clock())
                if (existing == null) {
                    call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "Announcement not found")
                    return@put
                }

                val isAuthor = existing.authorRef == principal.deviceId
                val isDeptHeadForAnn = isDeptHeadRole(principal.role) && existing.scope == "department" && existing.departmentId == principal.departmentId
                if (!isOrgAdmin && !isAuthor && !isDeptHeadForAnn) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to edit this announcement")
                    return@put
                }

                val rawBody = call.receiveBoundedText(MAX_ANNOUNCEMENT_BODY_BYTES) ?: return@put
                val req = HubRestCodec.decodeOrNull<UpdateAnnouncementRequestDto>(rawBody)
                if (req == null) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Malformed update announcement request")
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
                        actorRole = principal.role,
                        actorDeptId = principal.departmentId,
                        actorRef = principal.deviceId,
                        clientRequestId = null,
                    )
                } catch (e: AnnouncementPinLimitException) {
                    call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_PIN_LIMIT, e.message ?: "Pin limit reached (max 3)")
                    return@put
                } catch (e: AnnouncementForbiddenException) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to change this announcement")
                    return@put
                } catch (e: AnnouncementNotFoundException) {
                    call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "Announcement not found")
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

                router?.broadcastAnnouncement(
                    RelayFrame.AnnouncementEvent(
                        id = updated.id,
                        seq = updated.seq,
                        scope = updated.scope,
                        departmentId = updated.departmentId,
                        change = "updated",
                        epoch = store.epoch,
                    ),
                )

                audit.record(principal.deviceId, "announcement.update", "id=$id,seq=${updated.seq},len=${updated.body.length}")
                call.respondJson(updated)
            }

            // DELETE /api/v1/announcements/{id}
            delete("/{id}") {
                val principal = call.requirePrincipal(principalResolver) ?: return@delete
                if (!limits.announcementPost.tryAcquire(principal.deviceId)) {
                    val retryAfter = limits.announcementPost.retryAfterSeconds(principal.deviceId)
                    call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many announcement delete requests")
                    return@delete
                }

                val id = call.parameters["id"]
                if (id.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Missing announcement id")
                    return@delete
                }

                val isOrgAdmin = isOrgAdminRole(principal.role)
                val existing = store.getAnnouncement(id, callerDeptId = principal.departmentId, isOrgAdmin = isOrgAdmin, nowMs = clock())
                if (existing == null) {
                    call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "Announcement not found")
                    return@delete
                }

                val isAuthor = existing.authorRef == principal.deviceId
                val isDeptHeadForAnn = isDeptHeadRole(principal.role) && existing.scope == "department" && existing.departmentId == principal.departmentId
                if (!isOrgAdmin && !isAuthor && !isDeptHeadForAnn) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to delete this announcement")
                    return@delete
                }

                val deleted = try {
                    store.deleteAnnouncement(
                        id = id,
                        actorRole = principal.role,
                        actorDeptId = principal.departmentId,
                        actorRef = principal.deviceId,
                    )
                } catch (e: AnnouncementForbiddenException) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to change this announcement")
                    return@delete
                } catch (e: AnnouncementNotFoundException) {
                    call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "Announcement not found")
                    return@delete
                }
                router?.broadcastAnnouncement(
                    RelayFrame.AnnouncementEvent(
                        id = deleted.id,
                        seq = deleted.seq,
                        scope = deleted.scope,
                        departmentId = deleted.departmentId,
                        change = "deleted",
                        epoch = store.epoch,
                    ),
                )

                audit.record(principal.deviceId, "announcement.delete", "id=$id")
                call.respondJson(deleted)
            }

            // POST /api/v1/announcements/{id}/pin
            post("/{id}/pin") {
                val principal = call.requirePrincipal(principalResolver) ?: return@post
                if (!limits.announcementPost.tryAcquire(principal.deviceId)) {
                    val retryAfter = limits.announcementPost.retryAfterSeconds(principal.deviceId)
                    call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many announcement post requests")
                    return@post
                }
                val id = call.parameters["id"]
                if (id.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Missing announcement id")
                    return@post
                }

                val isOrgAdmin = isOrgAdminRole(principal.role)
                val existing = store.getAnnouncement(id, callerDeptId = principal.departmentId, isOrgAdmin = isOrgAdmin, nowMs = clock())
                if (existing == null) {
                    call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "Announcement not found")
                    return@post
                }

                val isDeptHeadForAnn = isDeptHeadRole(principal.role) && existing.scope == "department" && existing.departmentId == principal.departmentId
                if (!isOrgAdmin && !isDeptHeadForAnn) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to pin this announcement")
                    return@post
                }

                val pinned = try {
                    store.pinAnnouncement(
                        id = id,
                        actorRole = principal.role,
                        actorDeptId = principal.departmentId,
                        actorRef = principal.deviceId,
                    )
                } catch (e: AnnouncementPinLimitException) {
                    call.respondError(HttpStatusCode.Conflict, HubErrorCodes.ERR_PIN_LIMIT, e.message ?: "Pin limit reached (max 3)")
                    return@post
                } catch (e: AnnouncementForbiddenException) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to change this announcement")
                    return@post
                } catch (e: AnnouncementNotFoundException) {
                    call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "Announcement not found")
                    return@post
                } catch (e: IllegalStateException) {
                    val msg = e.message.orEmpty()
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, msg)
                    return@post
                }

                router?.broadcastAnnouncement(
                    RelayFrame.AnnouncementEvent(
                        id = pinned.id,
                        seq = pinned.seq,
                        scope = pinned.scope,
                        departmentId = pinned.departmentId,
                        change = "pinned",
                        epoch = store.epoch,
                    ),
                )

                audit.record(principal.deviceId, "announcement.pin", "id=$id")
                call.respondJson(pinned)
            }

            // POST /api/v1/announcements/{id}/unpin
            post("/{id}/unpin") {
                val principal = call.requirePrincipal(principalResolver) ?: return@post
                if (!limits.announcementPost.tryAcquire(principal.deviceId)) {
                    val retryAfter = limits.announcementPost.retryAfterSeconds(principal.deviceId)
                    call.response.header(HttpHeaders.RetryAfter, retryAfter.toString())
                    call.respondError(HttpStatusCode.TooManyRequests, HubErrorCodes.ERR_RATE_LIMIT, "Too many announcement post requests")
                    return@post
                }
                val id = call.parameters["id"]
                if (id.isNullOrBlank()) {
                    call.respondError(HttpStatusCode.BadRequest, HubErrorCodes.ERR_BAD_REQUEST, "Missing announcement id")
                    return@post
                }

                val isOrgAdmin = isOrgAdminRole(principal.role)
                val existing = store.getAnnouncement(id, callerDeptId = principal.departmentId, isOrgAdmin = isOrgAdmin, nowMs = clock())
                if (existing == null) {
                    call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "Announcement not found")
                    return@post
                }

                val isDeptHeadForAnn = isDeptHeadRole(principal.role) && existing.scope == "department" && existing.departmentId == principal.departmentId
                if (!isOrgAdmin && !isDeptHeadForAnn) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to unpin this announcement")
                    return@post
                }

                val unpinned = try {
                    store.unpinAnnouncement(
                        id = id,
                        actorRole = principal.role,
                        actorDeptId = principal.departmentId,
                        actorRef = principal.deviceId,
                    )
                } catch (e: AnnouncementForbiddenException) {
                    call.respondError(HttpStatusCode.Forbidden, HubErrorCodes.ERR_FORBIDDEN, "You do not have permission to change this announcement")
                    return@post
                } catch (e: AnnouncementNotFoundException) {
                    call.respondError(HttpStatusCode.NotFound, HubErrorCodes.ERR_NOT_FOUND, "Announcement not found")
                    return@post
                }
                router?.broadcastAnnouncement(
                    RelayFrame.AnnouncementEvent(
                        id = unpinned.id,
                        seq = unpinned.seq,
                        scope = unpinned.scope,
                        departmentId = unpinned.departmentId,
                        change = "unpinned",
                        epoch = store.epoch,
                    ),
                )

                audit.record(principal.deviceId, "announcement.unpin", "id=$id")
                call.respondJson(unpinned)
            }
        }
    }
}

private const val MAX_ANNOUNCEMENT_BODY_BYTES = 16 * 1024

private fun isOrgAdminRole(role: String): Boolean =
    role.equals("admin", ignoreCase = true) || role.equals("ORG_ADMIN", ignoreCase = true)

private fun isDeptHeadRole(role: String): Boolean =
    role.equals("dept_admin", ignoreCase = true) || role.equals("DEPT_HEAD", ignoreCase = true)
